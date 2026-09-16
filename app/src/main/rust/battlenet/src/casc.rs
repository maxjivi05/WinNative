use crate::{
    manifest::Selection,
    transfer::{verify_reader, Control},
};
use std::{
    collections::HashMap,
    ffi::CString,
    fs::File,
    io::{Read, Seek, SeekFrom},
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::ffi::OsStrExt,
    },
    path::{Component, Path},
};

#[derive(Clone, Copy, Debug, PartialEq)]
struct Location {
    archive: u16,
    offset: u64,
    size: u64,
}

fn open_at(
    directory: &File,
    name: &std::ffi::OsStr,
    is_directory: bool,
) -> Result<File, &'static str> {
    let name = CString::new(name.as_bytes()).map_err(|_| "unsafe_or_unavailable_path")?;
    let fd = unsafe {
        libc::openat(
            directory.as_raw_fd(),
            name.as_ptr(),
            libc::O_RDONLY
                | libc::O_NOFOLLOW
                | libc::O_CLOEXEC
                | libc::O_NONBLOCK
                | if is_directory { libc::O_DIRECTORY } else { 0 },
        )
    };
    if fd < 0 {
        return Err("unsafe_or_unavailable_path");
    }
    let file = unsafe { File::from_raw_fd(fd) };
    let metadata = file.metadata().map_err(|_| "file_error")?;
    if !(if is_directory {
        metadata.is_dir()
    } else {
        metadata.is_file()
    }) {
        return Err("unsafe_or_unavailable_path");
    }
    Ok(file)
}
fn directory(path: &Path) -> Result<File, &'static str> {
    if !path.is_absolute() {
        return Err("unsafe_or_unavailable_path");
    }
    let mut file = File::open("/").map_err(|_| "file_error")?;
    for component in path.components() {
        match component {
            Component::RootDir => {}
            Component::Normal(name) => file = open_at(&file, name, true)?,
            _ => return Err("unsafe_or_unavailable_path"),
        }
    }
    Ok(file)
}
fn index(bytes: &[u8], bucket: u8) -> Result<Vec<([u8; 9], Location)>, &'static str> {
    if bytes.len() < 40
        || bytes[..4] != 16u32.to_le_bytes()
        || bytes[8..16] != [7, 0, bucket, 0, 4, 5, 9, 30]
    {
        return Err("unsupported_casc_index");
    }
    let length = u32::from_le_bytes(bytes[32..36].try_into().unwrap()) as usize;
    if !length.is_multiple_of(18) || length > bytes.len() - 40 {
        return Err("invalid_casc_index");
    }
    let mut entries = Vec::with_capacity(length / 18);
    for entry in bytes[40..40 + length].as_chunks::<18>().0 {
        let key: [u8; 9] = entry[..9].try_into().unwrap();
        let size = u32::from_le_bytes(entry[14..18].try_into().unwrap()) as u64;
        if size == 0 {
            continue;
        }
        if size < 9 {
            return Err("invalid_casc_index");
        }
        let number = key.iter().fold(0u8, |n, b| n ^ b);
        if (number & 15) ^ (number >> 4) != bucket {
            if size == 30 {
                continue;
            }
            return Err("invalid_casc_index");
        }
        let offset = entry[9..14].iter().fold(0u64, |n, b| (n << 8) | *b as u64);
        entries.push((
            key,
            Location {
                archive: (offset >> 30) as u16,
                offset: offset & ((1 << 30) - 1),
                size,
            },
        ));
    }
    Ok(entries)
}

pub struct Storage {
    directory: File,
    entries: HashMap<[u8; 9], Location>,
}
impl Storage {
    pub fn open(data_directory: &Path) -> Result<Self, &'static str> {
        let directory = directory(data_directory)?;
        let mut latest: HashMap<u8, (u32, String)> = HashMap::new();
        for entry in std::fs::read_dir(format!("/proc/self/fd/{}", directory.as_raw_fd()))
            .map_err(|_| "file_error")?
        {
            let entry = entry.map_err(|_| "file_error")?;
            let name = entry.file_name();
            let Some(name) = name.to_str() else {
                continue;
            };
            if name.len() != 14
                || !name.ends_with(".idx")
                || !name.as_bytes()[..10].iter().all(|b| b.is_ascii_hexdigit())
            {
                continue;
            }
            let bucket = u8::from_str_radix(&name[..2], 16).map_err(|_| "invalid_casc_index")?;
            if bucket >= 16 {
                continue;
            }
            let generation =
                u32::from_str_radix(&name[2..10], 16).map_err(|_| "invalid_casc_index")?;
            if latest.get(&bucket).is_none_or(|v| generation > v.0) {
                latest.insert(bucket, (generation, name.into()));
            }
        }
        if latest.len() != 16 {
            return Err("casc_index_missing");
        }
        let mut entries = HashMap::new();
        for (bucket, (_, name)) in latest {
            let mut file = open_at(&directory, name.as_ref(), false)?;
            if file.metadata().map_err(|_| "file_error")?.len() > 64 * 1024 * 1024 {
                return Err("invalid_casc_index");
            }
            let mut bytes = Vec::new();
            (&mut file)
                .take(64 * 1024 * 1024 + 1)
                .read_to_end(&mut bytes)
                .map_err(|_| "file_error")?;
            if bytes.len() > 64 * 1024 * 1024 {
                return Err("invalid_casc_index");
            }
            for (key, location) in index(&bytes, bucket)? {
                let previous: &mut Location = entries.entry(key).or_insert(location);
                if *previous != location {
                    let (larger, smaller) = if previous.size > location.size {
                        (*previous, location)
                    } else {
                        (location, *previous)
                    };
                    if larger.archive != smaller.archive
                        || larger.offset.checked_add(30) != Some(smaller.offset)
                        || smaller.size.checked_add(30) != Some(larger.size)
                    {
                        return Err("conflicting_casc_entry");
                    }
                    *previous = larger;
                }
            }
        }
        Ok(Self { directory, entries })
    }
    pub fn manifest(&self, object: &crate::planning::ManifestObject) -> Result<Vec<u8>, String> {
        if object.encoded_bytes > 128 * 1024 * 1024 || object.decoded_bytes > 128 * 1024 * 1024 {
            return Err("manifest_too_large".into());
        }
        let key = &object.encoding_key;
        let mut prefix = [0u8; 9];
        for (n, byte) in prefix.iter_mut().enumerate() {
            *byte = u8::from_str_radix(key.get(n * 2..n * 2 + 2).ok_or("invalid_content_key")?, 16)
                .map_err(|_| "invalid_content_key")?;
        }
        let location = self.entries.get(&prefix).ok_or("casc_manifest_missing")?;
        let size = object.encoded_bytes as u64;
        let header = if location.size == size {
            0
        } else if location.size == size + 30 {
            30
        } else {
            return Err("content_size_mismatch".into());
        };
        let mut file = open_at(
            &self.directory,
            format!("data.{:03}", location.archive).as_ref(),
            false,
        )?;
        file.seek(SeekFrom::Start(location.offset + header))
            .map_err(|_| "file_error")?;
        let mut bytes = vec![0u8; object.encoded_bytes];
        file.read_exact(&mut bytes).map_err(|_| "file_error")?;
        verify_reader(&mut bytes.as_slice(), key, size, &Control::default())?;
        let decoded = crate::blte::decode(&bytes, object.decoded_bytes)?;
        if decoded.len() != object.decoded_bytes
            || format!("{:x}", md5::compute(&decoded)) != object.content_key
        {
            return Err("checksum_mismatch".into());
        }
        Ok(decoded)
    }
    pub fn verify(
        &self,
        selection: &Selection,
        control: &Control,
        mut progress: impl FnMut(usize, u64),
    ) -> Result<(), String> {
        self.verify_with_files(
            selection,
            control,
            &std::collections::HashSet::new(),
            &mut progress,
        )
    }
    pub fn verify_with_files(
        &self,
        selection: &Selection,
        control: &Control,
        external: &std::collections::HashSet<String>,
        mut progress: impl FnMut(usize, u64),
    ) -> Result<(), String> {
        let mut archives = HashMap::new();
        let mut verified = 0u64;
        for (number, entry) in selection.entries.iter().enumerate() {
            control.checkpoint()?;
            let mut prefix = [0u8; 9];
            for (n, byte) in prefix.iter_mut().enumerate() {
                *byte = u8::from_str_radix(
                    entry
                        .encoding_key
                        .get(n * 2..n * 2 + 2)
                        .ok_or("invalid_content_key")?,
                    16,
                )
                .map_err(|_| "invalid_content_key")?;
            }
            let Some(location) = self.entries.get(&prefix) else {
                if external.contains(&entry.encoding_key) {
                    verified += entry.encoded_bytes;
                    progress(number + 1, verified);
                    continue;
                }
                return Err(format!("casc_content_missing:{}", entry.encoding_key));
            };
            let header = if location.size == entry.encoded_bytes {
                0
            } else if location.size
                == entry
                    .encoded_bytes
                    .checked_add(30)
                    .ok_or("content_size_mismatch")?
            {
                30
            } else {
                return Err(format!("content_size_mismatch:{}", entry.encoding_key));
            };
            if let std::collections::hash_map::Entry::Vacant(slot) =
                archives.entry(location.archive)
            {
                slot.insert(open_at(
                    &self.directory,
                    format!("data.{:03}", location.archive).as_ref(),
                    false,
                )?);
            }
            let file = archives.get_mut(&location.archive).ok_or("file_error")?;
            let end = location
                .offset
                .checked_add(location.size)
                .ok_or("invalid_content_range")?;
            if end > file.metadata().map_err(|_| "file_error")?.len() {
                return Err("content_size_mismatch".into());
            }
            file.seek(SeekFrom::Start(location.offset + header))
                .map_err(|_| "file_error")?;
            verify_reader(
                &mut file.take(entry.encoded_bytes),
                &entry.encoding_key,
                entry.encoded_bytes,
                control,
            )
            .map_err(|error| format!("{error}:{}", entry.encoding_key))?;
            verified += entry.encoded_bytes;
            progress(number + 1, verified);
        }
        Ok(())
    }
}

fn open_windows_at(folder: &File, name: &str, is_directory: bool) -> Result<File, &'static str> {
    let mut found = None;
    for item in std::fs::read_dir(format!("/proc/self/fd/{}", folder.as_raw_fd()))
        .map_err(|_| "file_error")?
    {
        let name_on_disk = item.map_err(|_| "file_error")?.file_name();
        if name_on_disk
            .to_str()
            .is_some_and(|value| value.eq_ignore_ascii_case(name))
        {
            if found.is_some() {
                return Err("ambiguous_install_path");
            }
            found = Some(name_on_disk);
        }
    }
    open_at(folder, &found.ok_or("install_file_missing")?, is_directory)
}
pub fn verify_install_files(
    root: &Path,
    files: &[crate::install::InstalledFile],
    control: &Control,
) -> Result<(), String> {
    let root = directory(root)?;
    for entry in files {
        control.checkpoint()?;
        if !crate::install::safe_path(&entry.path) {
            return Err("unsafe_install_path".into());
        }
        let parts: Vec<_> = entry.path.split('/').collect();
        let mut folder = root.try_clone().map_err(|_| "file_error")?;
        for part in &parts[..parts.len() - 1] {
            folder =
                open_windows_at(&folder, part, true).map_err(|e| format!("{e}:{}", entry.path))?;
        }
        let mut file = open_windows_at(&folder, parts[parts.len() - 1], false)
            .map_err(|e| format!("{e}:{}", entry.path))?;
        if file.metadata().map_err(|_| "file_error")?.len() != entry.bytes {
            return Err(format!("content_size_mismatch:{}", entry.path));
        }
        let mut hash = md5::Context::new();
        let mut buffer = [0u8; 65536];
        let mut remaining = entry.bytes;
        while remaining > 0 {
            control.checkpoint()?;
            let n = remaining.min(buffer.len() as u64) as usize;
            file.read_exact(&mut buffer[..n])
                .map_err(|_| "file_error")?;
            hash.consume(&buffer[..n]);
            remaining -= n as u64;
        }
        if format!("{:x}", hash.finalize()) != entry.content_key {
            return Err(format!("checksum_mismatch:{}", entry.path));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn verifies_installed_content_and_rejects_symlink_substitution() {
        use crate::manifest::Entry;
        static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let root = std::env::temp_dir().join(format!(
            "wn-casc-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        std::fs::create_dir(&root).unwrap();
        let content = b"BLTE\0\0\0\0Nverified payload";
        let hash = md5::compute(content);
        let key = format!("{hash:x}");
        let n = hash.0[..9].iter().fold(0u8, |n, b| n ^ b);
        let bucket = (n & 15) ^ (n >> 4);
        for b in 0..16u8 {
            let mut bytes = vec![0u8; if b == bucket { 58 } else { 40 }];
            bytes[..4].copy_from_slice(&16u32.to_le_bytes());
            bytes[8..16].copy_from_slice(&[7, 0, b, 0, 4, 5, 9, 30]);
            if b == bucket {
                bytes[32..36].copy_from_slice(&18u32.to_le_bytes());
                bytes[40..49].copy_from_slice(&hash.0[..9]);
                bytes[54..58].copy_from_slice(&(content.len() as u32 + 30).to_le_bytes());
            }
            std::fs::write(root.join(format!("{b:02x}00000001.idx")), bytes).unwrap();
        }
        let mut data = vec![0u8; 30];
        data.extend(content);
        std::fs::write(root.join("data.000"), &data).unwrap();
        let selection = Selection {
            encoded_bytes: content.len() as u64,
            entries: vec![Entry {
                encoding_key: key,
                encoded_bytes: content.len() as u64,
                priority: 0,
            }],
            selected_tags: vec![],
            excluded_tags: vec![],
        };
        let storage = Storage::open(&root).unwrap();
        storage
            .verify(&selection, &Control::default(), |_, _| {})
            .unwrap();
        data[40] ^= 1;
        std::fs::write(root.join("data.000"), &data).unwrap();
        assert!(storage
            .verify(&selection, &Control::default(), |_, _| {})
            .unwrap_err()
            .starts_with("checksum_mismatch"));
        std::os::unix::fs::symlink(root.join("0000000001.idx"), root.join("0000000002.idx"))
            .unwrap();
        assert!(matches!(
            Storage::open(&root),
            Err("unsafe_or_unavailable_path")
        ));
        let link = root.with_extension("link");
        std::os::unix::fs::symlink(&root, &link).unwrap();
        assert!(matches!(
            Storage::open(&link),
            Err("unsafe_or_unavailable_path")
        ));
    }
    #[test]
    fn checks_bucket_lengths_and_archive_location() {
        let mut bytes = vec![0u8; 58];
        bytes[..4].copy_from_slice(&16u32.to_le_bytes());
        bytes[8..16].copy_from_slice(&[7, 0, 1, 0, 4, 5, 9, 30]);
        bytes[32..36].copy_from_slice(&18u32.to_le_bytes());
        bytes[40] = 1;
        bytes[49..54].copy_from_slice(&[0, 128, 0, 0, 25]);
        bytes[54..58].copy_from_slice(&100u32.to_le_bytes());
        let entries = index(&bytes, 1).unwrap();
        assert_eq!(
            entries[0].1,
            Location {
                archive: 2,
                offset: 25,
                size: 100
            }
        );
        assert!(index(&bytes, 0).is_err());
        for n in 0..58 {
            assert!(index(&bytes[..n], 1).is_err());
        }
        bytes[40] = 2;
        assert!(index(&bytes, 1).is_err());
    }
}
