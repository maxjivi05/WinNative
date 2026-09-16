use std::{
    ffi::{CString, OsStr},
    fs::File,
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::{ffi::OsStrExt, fs::MetadataExt},
    },
    path::{Component, Path},
};
pub(crate) struct Directory(pub File);
fn name(value: &OsStr) -> Result<CString, &'static str> {
    let b = value.as_bytes();
    if b.is_empty() || b == b"." || b == b".." || b.contains(&b'/') {
        return Err("unsafe_install_path");
    }
    CString::new(b).map_err(|_| "unsafe_install_path")
}
fn descriptor(fd: i32) -> Result<File, &'static str> {
    if fd < 0 {
        Err("unsafe_or_unavailable_path")
    } else {
        Ok(unsafe { File::from_raw_fd(fd) })
    }
}
impl Directory {
    pub fn open(path: &Path) -> Result<Self, &'static str> {
        if !path.is_absolute() {
            return Err("unsafe_install_path");
        }
        let mut folder = Self(File::open("/").map_err(|_| "file_error")?);
        for c in path.components() {
            match c {
                Component::RootDir => {}
                Component::Normal(n) => folder = folder.child(n, false)?,
                _ => return Err("unsafe_install_path"),
            }
        }
        Ok(folder)
    }
    pub fn child(&self, value: &OsStr, create: bool) -> Result<Self, &'static str> {
        let n = name(value)?;
        if create
            && unsafe { libc::mkdirat(self.0.as_raw_fd(), n.as_ptr(), 0o700) } != 0
            && std::io::Error::last_os_error().raw_os_error() != Some(libc::EEXIST)
        {
            return Err("file_error");
        }
        Ok(Self(descriptor(unsafe {
            libc::openat(
                self.0.as_raw_fd(),
                n.as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
            )
        })?))
    }
    pub fn windows_child(&self, value: &str) -> Result<Self, &'static str> {
        let mut matched = None;
        for item in self.names()? {
            if item.to_str().is_some_and(|v| v.eq_ignore_ascii_case(value)) {
                if matched.is_some() {
                    return Err("ambiguous_install_path");
                }
                matched = Some(item);
            }
        }
        self.child(matched.as_deref().unwrap_or_else(|| value.as_ref()), true)
    }
    pub fn names(&self) -> Result<Vec<std::ffi::OsString>, &'static str> {
        std::fs::read_dir(format!("/proc/self/fd/{}", self.0.as_raw_fd()))
            .map_err(|_| "file_error")?
            .map(|e| e.map(|e| e.file_name()).map_err(|_| "file_error"))
            .collect()
    }
    pub fn file(&self, value: &str, create: bool, exclusive: bool) -> Result<File, &'static str> {
        let n = name(value.as_ref())?;
        let flags = libc::O_RDWR
            | libc::O_NOFOLLOW
            | libc::O_CLOEXEC
            | libc::O_NONBLOCK
            | if create { libc::O_CREAT } else { 0 }
            | if exclusive { libc::O_EXCL } else { 0 };
        let fd = unsafe { libc::openat(self.0.as_raw_fd(), n.as_ptr(), flags, 0o600) };
        if fd < 0 && std::io::Error::last_os_error().raw_os_error() == Some(libc::ENOENT) {
            return Err("file_missing");
        }
        let file = descriptor(fd)?;
        let m = file.metadata().map_err(|_| "file_error")?;
        if !m.is_file() || m.nlink() != 1 {
            return Err("unsafe_or_unavailable_path");
        }
        Ok(file)
    }
    pub fn read_limited(&self, value: &str, limit: usize) -> Result<Vec<u8>, &'static str> {
        use std::io::Read;
        let n = name(value.as_ref())?;
        let mut file = descriptor(unsafe {
            libc::openat(
                self.0.as_raw_fd(),
                n.as_ptr(),
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK,
            )
        })?;
        let meta = file.metadata().map_err(|_| "file_error")?;
        if !meta.is_file() || meta.len() > limit as u64 {
            return Err("unsafe_or_unavailable_path");
        }
        let mut bytes = Vec::new();
        (&mut file)
            .take(limit as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| "file_error")?;
        if bytes.len() > limit {
            return Err("content_too_large");
        }
        Ok(bytes)
    }
    pub fn lock(&self) -> Result<File, &'static str> {
        let f = self.file(".winnative-install-lock", true, false)?;
        if unsafe { libc::flock(f.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
            return Err("install_busy");
        }
        Ok(f)
    }
    pub fn write_once(&self, value: &str, bytes: &[u8]) -> Result<(), &'static str> {
        use std::io::{Read, Write};
        match self.file(value, false, false) {
            Ok(mut f) => {
                let length = f.metadata().map_err(|_| "file_error")?.len();
                if length > bytes.len() as u64 {
                    return Err("existing_file_conflict");
                }
                let mut old = vec![0; length as usize];
                f.read_exact(&mut old).map_err(|_| "file_error")?;
                if old != bytes[..length as usize] {
                    return Err("existing_file_conflict");
                }
                if length < bytes.len() as u64 {
                    f.write_all(&bytes[length as usize..])
                        .map_err(|_| "file_error")?;
                    f.sync_all().map_err(|_| "file_error")?;
                }
                return Ok(());
            }
            Err("file_missing") => {}
            Err(e) => return Err(e),
        }
        let mut f = self.file(value, true, true)?;
        f.write_all(bytes).map_err(|_| "file_error")?;
        f.sync_all().map_err(|_| "file_error")?;
        self.0.sync_all().map_err(|_| "file_error")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_links_and_preserves_conflicting_files() {
        let base = std::env::temp_dir().join(format!("wn-safe-dir-{}", std::process::id()));
        std::fs::create_dir(&base).unwrap();
        let root = Directory::open(&base).unwrap();
        root.write_once("existing", b"preserved").unwrap();
        assert_eq!(
            root.write_once("existing", b"different").unwrap_err(),
            "existing_file_conflict"
        );
        assert_eq!(std::fs::read(base.join("existing")).unwrap(), b"preserved");
        root.write_once("interrupted", b"prefix").unwrap();
        root.write_once("interrupted", b"prefix and completion")
            .unwrap();
        assert_eq!(
            std::fs::read(base.join("interrupted")).unwrap(),
            b"prefix and completion"
        );
        std::os::unix::fs::symlink(base.join("existing"), base.join("link")).unwrap();
        assert!(root.write_once("link", b"changed").is_err());
        std::os::unix::fs::symlink(&base, base.join("directory-link")).unwrap();
        assert!(root.child("directory-link".as_ref(), true).is_err());
    }
}
