use std::{
    ffi::CString,
    fs::File,
    io::{Read, Seek, SeekFrom, Write},
    os::fd::{AsRawFd, FromRawFd},
    path::Path,
    sync::{Condvar, Mutex},
    time::Duration,
};

#[derive(Clone, Copy, PartialEq)]
enum Command {
    Running,
    Paused,
    Cancelled,
}
pub struct Control {
    command: Mutex<Command>,
    wake: Condvar,
}
impl Default for Control {
    fn default() -> Self {
        Self {
            command: Mutex::new(Command::Running),
            wake: Condvar::new(),
        }
    }
}
impl Control {
    pub fn pause(&self) {
        self.change(Command::Paused);
    }
    pub fn resume(&self) {
        self.change(Command::Running);
    }
    pub fn cancel(&self) {
        self.change(Command::Cancelled);
    }
    fn change(&self, next: Command) {
        let mut command = self.command.lock().unwrap_or_else(|e| e.into_inner());
        if *command != Command::Cancelled {
            *command = next;
        }
        self.wake.notify_all();
    }
    pub fn checkpoint(&self) -> Result<(), &'static str> {
        let mut command = self.command.lock().map_err(|_| "transfer_state_error")?;
        while *command == Command::Paused {
            command = self
                .wake
                .wait(command)
                .map_err(|_| "transfer_state_error")?;
        }
        if *command == Command::Cancelled {
            Err("cancelled")
        } else {
            Ok(())
        }
    }
}
#[derive(Clone, Copy)]
pub struct Content<'a> {
    pub key: &'a str,
    pub size: u64,
    pub archive: Option<(&'a str, u64)>,
}
pub struct Cache {
    directory: File,
    _lock: File,
    active: Mutex<std::collections::HashSet<String>>,
    client: reqwest::blocking::Client,
}
struct TransferGuard<'a> {
    cache: &'a Cache,
    key: String,
}
impl Drop for TransferGuard<'_> {
    fn drop(&mut self) {
        self.cache
            .active
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&self.key);
    }
}
fn descriptor(fd: i32) -> Result<File, &'static str> {
    if fd < 0 {
        Err("unsafe_or_unavailable_path")
    } else {
        Ok(unsafe { File::from_raw_fd(fd) })
    }
}
fn regular(file: &File) -> Result<(), &'static str> {
    use std::os::unix::fs::MetadataExt;
    let metadata = file.metadata().map_err(|_| "file_error")?;
    if !metadata.is_file() || metadata.nlink() != 1 {
        return Err("unsafe_or_unavailable_path");
    }
    Ok(())
}
impl Cache {
    fn claim(&self, key: &str) -> Result<TransferGuard<'_>, &'static str> {
        if !self
            .active
            .lock()
            .map_err(|_| "transfer_state_error")?
            .insert(key.to_owned())
        {
            return Err("cache_busy");
        }
        Ok(TransferGuard {
            cache: self,
            key: key.into(),
        })
    }
    pub fn copy_verified(
        &self,
        key: &str,
        size: u64,
        out: &mut impl Write,
        control: &Control,
    ) -> Result<(), &'static str> {
        let _guard = self.claim(key)?;
        let mut file = self.content(key, false)?;
        verify(&mut file, key, size, control)?;
        file.seek(SeekFrom::Start(0)).map_err(|_| "file_error")?;
        let mut buffer = [0u8; 65536];
        let mut remaining = size;
        while remaining > 0 {
            control.checkpoint()?;
            let n = remaining.min(buffer.len() as u64) as usize;
            file.read_exact(&mut buffer[..n])
                .map_err(|_| "file_error")?;
            out.write_all(&buffer[..n]).map_err(|_| "file_error")?;
            remaining -= n as u64;
        }
        Ok(())
    }
    pub fn read_verified(
        &self,
        key: &str,
        size: u64,
        control: &Control,
    ) -> Result<Vec<u8>, &'static str> {
        if size > 128 * 1024 * 1024 {
            return Err("content_too_large");
        }
        let mut bytes = Vec::with_capacity(size as usize);
        self.copy_verified(key, size, &mut bytes, control)?;
        Ok(bytes)
    }
    pub fn open(path: &Path) -> Result<Self, &'static str> {
        let directory = crate::safe_dir::Directory::open(path)?.0;
        let lock = Self::file(&directory, ".winnative-transfer-lock", true)?;
        if unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
            return Err("cache_busy");
        }
        Ok(Self {
            directory,
            _lock: lock,
            active: Mutex::new(std::collections::HashSet::new()),
            client: reqwest::blocking::Client::builder()
                .redirect(reqwest::redirect::Policy::none())
                .connect_timeout(Duration::from_secs(10))
                .timeout(Duration::from_secs(60))
                .build()
                .map_err(|_| "network_error")?,
        })
    }
    fn file(directory: &File, name: &str, write: bool) -> Result<File, &'static str> {
        let name = CString::new(name).map_err(|_| "invalid_content_key")?;
        let flags = if write {
            libc::O_RDWR | libc::O_CREAT
        } else {
            libc::O_RDONLY
        };
        let fd = unsafe {
            libc::openat(
                directory.as_raw_fd(),
                name.as_ptr(),
                flags | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK,
                0o600,
            )
        };
        if fd < 0 && std::io::Error::last_os_error().raw_os_error() == Some(libc::ENOENT) {
            return Err("content_missing");
        }
        let file = descriptor(fd)?;
        regular(&file)?;
        Ok(file)
    }
    fn content(&self, key: &str, write: bool) -> Result<File, &'static str> {
        if key.len() != 32
            || !key
                .bytes()
                .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
        {
            return Err("invalid_content_key");
        }
        Self::file(&self.directory, &format!("{key}.blte"), write)
    }
    pub fn verify(&self, key: &str, size: u64, control: &Control) -> Result<(), &'static str> {
        let _guard = self.claim(key)?;
        verify(&mut self.content(key, false)?, key, size, control)
    }
    #[allow(
        clippy::unnecessary_cast,
        reason = "statvfs widths differ across targets"
    )]
    pub fn available_bytes(&self) -> Result<u64, &'static str> {
        let mut status = std::mem::MaybeUninit::<libc::statvfs>::uninit();
        if unsafe { libc::fstatvfs(self.directory.as_raw_fd(), status.as_mut_ptr()) } != 0 {
            return Err("file_error");
        }
        let status = unsafe { status.assume_init() };
        (status.f_bavail as u64)
            .checked_mul(status.f_frsize as u64)
            .ok_or("file_error")
    }
    pub fn complete(&self, key: &str, size: u64, control: &Control) -> Result<bool, &'static str> {
        let _guard = self.claim(key)?;
        let mut file = match self.content(key, false) {
            Ok(file) => file,
            Err("content_missing") => return Ok(false),
            Err(error) => return Err(error),
        };
        let length = file.metadata().map_err(|_| "file_error")?.len();
        if length > size {
            return Err("content_size_mismatch");
        }
        if length < size {
            return Ok(false);
        }
        verify(&mut file, key, size, control)?;
        Ok(true)
    }
    pub fn store(&self, key: &str, bytes: &[u8], control: &Control) -> Result<(), &'static str> {
        let mut reader = bytes;
        verify_reader(&mut reader, key, bytes.len() as u64, control)?;
        let _guard = self.claim(key)?;
        control.checkpoint()?;
        let mut file = self.content(key, true)?;
        let length = file.metadata().map_err(|_| "file_error")?.len();
        if length > bytes.len() as u64 {
            return Err("content_size_mismatch");
        }
        let mut buffer = [0u8; 65536];
        let mut offset = 0;
        while offset < length as usize {
            control.checkpoint()?;
            let n = (length as usize - offset).min(buffer.len());
            file.read_exact(&mut buffer[..n])
                .map_err(|_| "file_error")?;
            if buffer[..n] != bytes[offset..offset + n] {
                return Err("checksum_mismatch");
            }
            offset += n;
        }
        let result = (|| {
            for chunk in bytes[offset..].chunks(65536) {
                control.checkpoint()?;
                file.write_all(chunk).map_err(|_| "file_error")?;
            }
            Ok(())
        })();
        file.sync_data().map_err(|_| "file_error")?;
        result
    }
    pub fn download(
        &self,
        base: &str,
        content: Content<'_>,
        control: &Control,
        progress: impl FnMut(u64),
    ) -> Result<(), &'static str> {
        let url = reqwest::Url::parse(base).map_err(|_| "invalid_cdn")?;
        let host = url.host_str().unwrap_or("");
        if url.scheme() != "https"
            || !(host.ends_with(".blizzard.com") || host.ends_with(".battle.net"))
            || !url.username().is_empty()
            || url.password().is_some()
            || url.port().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
        {
            return Err("invalid_cdn");
        }
        self.download_with(&self.client, base, content, control, progress)
    }
    fn download_with(
        &self,
        client: &reqwest::blocking::Client,
        base: &str,
        content: Content<'_>,
        control: &Control,
        mut progress: impl FnMut(u64),
    ) -> Result<(), &'static str> {
        let Content { key, size, archive } = content;
        let (object_key, start) = archive.unwrap_or((key, 0));
        if object_key.len() != 32 || !object_key.bytes().all(|b| b.is_ascii_hexdigit()) || size < 9
        {
            return Err("invalid_content_key");
        }
        let end = start.checked_add(size - 1).ok_or("invalid_content_range")?;
        let _guard = self.claim(key)?;
        control.checkpoint()?;
        let mut file = self.content(key, true)?;
        let mut offset = file.metadata().map_err(|_| "file_error")?.len();
        if offset > size {
            return Err("content_size_mismatch");
        }
        if offset == size {
            verify(&mut file, key, size, control)?;
            progress(size);
            return Ok(());
        }
        file.seek(SeekFrom::End(0)).map_err(|_| "file_error")?;
        let mut request = client
            .get(format!(
                "{}/data/{}/{}/{object_key}",
                base.trim_end_matches('/'),
                &object_key[..2],
                &object_key[2..4]
            ))
            .header(reqwest::header::ACCEPT_ENCODING, "identity");
        let ranged = offset > 0 || archive.is_some();
        let begin = start.checked_add(offset).ok_or("invalid_content_range")?;
        if ranged {
            request = request.header(reqwest::header::RANGE, format!("bytes={begin}-{end}"));
        }
        let mut response = request.send().map_err(|_| "network_error")?;
        let expected_status = if ranged { 206 } else { 200 };
        if response.status().as_u16() != expected_status {
            return Err("range_or_content_unavailable");
        }
        if ranged {
            let range = response
                .headers()
                .get(reqwest::header::CONTENT_RANGE)
                .and_then(|v| v.to_str().ok())
                .ok_or("invalid_content_range")?;
            let (part, total) = range.split_once('/').ok_or("invalid_content_range")?;
            let total: u64 = total.parse().map_err(|_| "invalid_content_range")?;
            if part != format!("bytes {begin}-{end}")
                || total <= end
                || (archive.is_none() && total != size)
            {
                return Err("invalid_content_range");
            }
        }
        if response
            .content_length()
            .is_some_and(|n| n != size - offset)
        {
            return Err("content_size_mismatch");
        }
        progress(offset);
        let result = (|| {
            let mut buffer = [0u8; 64 * 1024];
            loop {
                control.checkpoint()?;
                let n = response.read(&mut buffer).map_err(|_| "network_error")?;
                if n == 0 {
                    break;
                }
                control.checkpoint()?;
                if n as u64 > size - offset {
                    return Err("content_size_mismatch");
                }
                file.write_all(&buffer[..n]).map_err(|_| "file_error")?;
                offset += n as u64;
                progress(offset);
            }
            if offset != size {
                return Err("content_size_mismatch");
            }
            Ok(())
        })();
        file.sync_data().map_err(|_| "file_error")?;
        result?;
        verify(&mut file, key, size, control)
    }
}
fn hash_bytes(
    file: &mut impl Read,
    length: u64,
    control: &Control,
) -> Result<md5::Digest, &'static str> {
    let mut remaining = length;
    let mut hash = md5::Context::new();
    let mut buffer = [0u8; 64 * 1024];
    while remaining > 0 {
        control.checkpoint()?;
        let n = remaining.min(buffer.len() as u64) as usize;
        file.read_exact(&mut buffer[..n])
            .map_err(|_| "file_error")?;
        hash.consume(&buffer[..n]);
        remaining -= n as u64;
    }
    Ok(hash.finalize())
}
fn verify(file: &mut File, key: &str, size: u64, control: &Control) -> Result<(), &'static str> {
    if file.metadata().map_err(|_| "file_error")?.len() != size || size < 9 {
        return Err("content_size_mismatch");
    }
    file.seek(SeekFrom::Start(0)).map_err(|_| "file_error")?;
    verify_reader(file, key, size, control)
}
pub fn verify_reader(
    file: &mut impl Read,
    key: &str,
    size: u64,
    control: &Control,
) -> Result<(), &'static str> {
    if size < 9 {
        return Err("content_size_mismatch");
    }
    let mut prefix = [0u8; 8];
    file.read_exact(&mut prefix).map_err(|_| "file_error")?;
    if &prefix[..4] != b"BLTE" {
        return Err("invalid_blte");
    }
    let header_size = u32::from_be_bytes(prefix[4..8].try_into().unwrap()) as usize;
    if header_size == 0 {
        let mut hash = md5::Context::new();
        hash.consume(prefix);
        let mut remaining = size - 8;
        let mut buffer = [0u8; 64 * 1024];
        while remaining > 0 {
            control.checkpoint()?;
            let n = remaining.min(buffer.len() as u64) as usize;
            file.read_exact(&mut buffer[..n])
                .map_err(|_| "file_error")?;
            hash.consume(&buffer[..n]);
            remaining -= n as u64;
        }
        if format!("{:x}", hash.finalize()) != key {
            return Err("checksum_mismatch");
        }
        return Ok(());
    }
    if !(12..=4 * 1024 * 1024).contains(&header_size) || header_size as u64 > size {
        return Err("invalid_blte");
    }
    let mut header = vec![0u8; header_size];
    header[..8].copy_from_slice(&prefix);
    file.read_exact(&mut header[8..])
        .map_err(|_| "file_error")?;
    if format!("{:x}", md5::compute(&header)) != key {
        return Err("checksum_mismatch");
    }
    let count = u32::from_be_bytes([0, header[9], header[10], header[11]]) as usize;
    if header[8] != 0x0f || count == 0 || 12 + count * 24 != header_size {
        return Err("invalid_blte");
    }
    let mut remaining = size - header_size as u64;
    for chunk in header[12..].as_chunks::<24>().0 {
        let encoded = u32::from_be_bytes(chunk[..4].try_into().unwrap()) as u64;
        if encoded == 0 || encoded > remaining {
            return Err("invalid_blte");
        }
        if hash_bytes(file, encoded, control)?.as_ref() != &chunk[8..24] {
            return Err("checksum_mismatch");
        }
        remaining -= encoded;
    }
    if remaining != 0 {
        return Err("invalid_blte");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{net::TcpListener, sync::Arc, thread};
    fn directory() -> std::path::PathBuf {
        static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let path = std::env::temp_dir().join(format!(
            "wn-battlenet-transfer-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        std::fs::create_dir(&path).unwrap();
        path
    }
    #[test]
    fn locks_individual_objects_and_releases_claims() {
        let root = directory();
        let cache = Cache::open(&root).unwrap();
        let first = cache.claim("first").unwrap();
        let second = cache.claim("second").unwrap();
        assert!(matches!(cache.claim("first"), Err("cache_busy")));
        drop(first);
        assert!(cache.claim("first").is_ok());
        drop(second);
    }
    #[test]
    fn verified_batch_storage_resumes_without_overwriting_corrupt_partial_files() {
        let root = directory();
        let cache = Cache::open(&root).unwrap();
        let data = b"BLTE\0\0\0\0Nbatch content";
        let key = format!("{:x}", md5::compute(data));
        let control = Control::default();
        assert!(!cache.complete(&key, data.len() as u64, &control).unwrap());
        std::fs::write(root.join(format!("{key}.blte")), &data[..10]).unwrap();
        cache.store(&key, data, &control).unwrap();
        assert!(cache.complete(&key, data.len() as u64, &control).unwrap());
        std::fs::write(root.join(format!("{key}.blte")), b"preserved").unwrap();
        assert_eq!(
            cache.store(&key, data, &control).unwrap_err(),
            "checksum_mismatch"
        );
        assert_eq!(
            std::fs::read(root.join(format!("{key}.blte"))).unwrap(),
            b"preserved"
        );
        let badkey = "01".repeat(16);
        assert_eq!(
            cache.store(&badkey, data, &control).unwrap_err(),
            "checksum_mismatch"
        );
        assert!(!root.join(format!("{badkey}.blte")).exists());
    }
    #[test]
    fn refuses_symlink_parents_files_hardlinks_and_concurrent_writers() {
        let path = directory();
        let cache = Cache::open(&path).unwrap();
        assert!(matches!(Cache::open(&path), Err("cache_busy")));
        let key = "0123456789abcdef0123456789abcdef";
        let outside = directory().join("preserved");
        std::fs::write(&outside, b"preserved").unwrap();
        std::os::unix::fs::symlink(&outside, path.join(format!("{key}.blte"))).unwrap();
        assert!(cache.content(key, true).is_err());
        let link = directory().join("link");
        std::os::unix::fs::symlink(&path, &link).unwrap();
        assert!(Cache::open(&link).is_err());
        let other = "abcdef0123456789abcdef0123456789";
        std::fs::hard_link(&outside, path.join(format!("{other}.blte"))).unwrap();
        assert!(cache.content(other, true).is_err());
        assert_eq!(std::fs::read(outside).unwrap(), b"preserved");
    }
    #[test]
    fn pause_resume_and_cancel_are_terminal() {
        let control = Arc::new(Control::default());
        control.pause();
        let (tx, rx) = std::sync::mpsc::channel();
        let worker = control.clone();
        let handle = thread::spawn(move || {
            tx.send(worker.checkpoint()).unwrap();
        });
        assert!(rx.recv_timeout(Duration::from_millis(30)).is_err());
        control.resume();
        assert_eq!(rx.recv_timeout(Duration::from_secs(1)).unwrap(), Ok(()));
        handle.join().unwrap();
        control.pause();
        control.cancel();
        control.resume();
        assert_eq!(control.checkpoint(), Err("cancelled"));
    }
    #[test]
    fn resumes_exact_range_and_preserves_cancelled_bytes() {
        let path = directory();
        let cache = Cache::open(&path).unwrap();
        let data = b"BLTE\0\0\0\0Nhello";
        let key = format!("{:x}", md5::compute(data));
        std::fs::write(path.join(format!("{key}.blte")), &data[..9]).unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            let mut request = Vec::new();
            let mut byte = [0u8; 1];
            while !request.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).unwrap();
                request.push(byte[0]);
            }
            assert!(String::from_utf8(request)
                .unwrap()
                .to_lowercase()
                .contains("range: bytes=9-13"));
            stream.write_all(b"HTTP/1.1 206 Partial Content\r\nContent-Length: 5\r\nContent-Range: bytes 9-13/14\r\nConnection: close\r\n\r\nhello").unwrap();
        });
        let control = Control::default();
        let client = reqwest::blocking::Client::builder()
            .no_proxy()
            .build()
            .unwrap();
        cache
            .download_with(
                &client,
                &format!("http://{address}"),
                Content {
                    key: &key,
                    size: data.len() as u64,
                    archive: None,
                },
                &control,
                |_| {},
            )
            .unwrap();
        server.join().unwrap();
        cache.verify(&key, data.len() as u64, &control).unwrap();
        control.cancel();
        assert_eq!(
            cache.download_with(
                &client,
                "http://127.0.0.1:1",
                Content {
                    key: &key,
                    size: data.len() as u64,
                    archive: None
                },
                &control,
                |_| {}
            ),
            Err("cancelled")
        );
        assert_eq!(
            std::fs::read(path.join(format!("{key}.blte"))).unwrap(),
            data
        );
    }
    fn server(response: Vec<u8>) -> (String, thread::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let handle = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            let mut request = Vec::new();
            let mut byte = [0];
            while !request.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).unwrap();
                request.push(byte[0]);
            }
            let _ = stream.write_all(&response);
        });
        (format!("http://{address}"), handle)
    }
    #[test]
    fn rejects_ignored_or_wrong_ranges_without_appending() {
        for response in [
            b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello".to_vec(),
            b"HTTP/1.1 206 Partial Content\r\nContent-Length: 5\r\nContent-Range: bytes 8-12/14\r\nConnection: close\r\n\r\nhello".to_vec(),
        ] {
            let path = directory(); let cache = Cache::open(&path).unwrap();
            let data = b"BLTE\0\0\0\0Nhello"; let key = format!("{:x}",md5::compute(data));
            let file = path.join(format!("{key}.blte")); std::fs::write(&file,&data[..9]).unwrap();
            let (url,worker)=server(response);
            assert!(cache.download_with(&reqwest::blocking::Client::builder().no_proxy().build().unwrap(), &url,
                Content { key:&key,size:14,archive:None }, &Control::default(), |_| {}).is_err());
            worker.join().unwrap(); assert_eq!(std::fs::read(file).unwrap(),&data[..9]);
        }
    }
    #[test]
    fn cancellation_during_transfer_preserves_resumable_prefix() {
        let mut data = b"BLTE\0\0\0\0N".to_vec();
        data.extend(vec![42; 256 * 1024]);
        let key = format!("{:x}", md5::compute(&data));
        let path = directory();
        let cache = Cache::open(&path).unwrap();
        let mut response = format!(
            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            data.len()
        )
        .into_bytes();
        response.extend(&data);
        let (url, worker) = server(response);
        let control = Control::default();
        let result = cache.download_with(
            &reqwest::blocking::Client::builder()
                .no_proxy()
                .build()
                .unwrap(),
            &url,
            Content {
                key: &key,
                size: data.len() as u64,
                archive: None,
            },
            &control,
            |bytes| {
                if bytes > 0 {
                    control.cancel();
                }
            },
        );
        assert_eq!(result, Err("cancelled"));
        worker.join().unwrap();
        let retained = std::fs::read(path.join(format!("{key}.blte"))).unwrap();
        assert!(!retained.is_empty() && retained.len() < data.len());
        assert_eq!(retained, &data[..retained.len()]);
    }
    #[test]
    fn verifies_archive_range_and_detects_corrupt_content() {
        for corrupt in [false, true] {
            let data = b"BLTE\0\0\0\0Nhello";
            let key = format!("{:x}", md5::compute(data));
            let mut response=b"HTTP/1.1 206 Partial Content\r\nContent-Length: 14\r\nContent-Range: bytes 100-113/1000\r\nConnection: close\r\n\r\n".to_vec();
            response.extend(data);
            if corrupt {
                *response.last_mut().unwrap() ^= 1;
            }
            let (url, worker) = server(response);
            let cache = Cache::open(&directory()).unwrap();
            let result = cache.download_with(
                &reqwest::blocking::Client::builder()
                    .no_proxy()
                    .build()
                    .unwrap(),
                &url,
                Content {
                    key: &key,
                    size: 14,
                    archive: Some(("0123456789abcdef0123456789abcdef", 100)),
                },
                &Control::default(),
                |_| {},
            );
            worker.join().unwrap();
            assert_eq!(
                result,
                if corrupt {
                    Err("checksum_mismatch")
                } else {
                    Ok(())
                }
            );
        }
    }
}
