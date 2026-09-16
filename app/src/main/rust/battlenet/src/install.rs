use crate::manifest::{DownloadManifest, Entry, Tag};
use std::collections::HashMap;
#[derive(Clone)]
pub struct InstalledFile {
    pub path: String,
    pub content_key: String,
    pub bytes: u64,
}
pub struct InstallManifest {
    pub files: Vec<InstalledFile>,
    selection: DownloadManifest,
}
fn take<'a>(data: &mut &'a [u8], n: usize) -> Result<&'a [u8], &'static str> {
    if n > data.len() {
        return Err("invalid_install_manifest");
    }
    let (out, rest) = data.split_at(n);
    *data = rest;
    Ok(out)
}
fn name(data: &mut &[u8]) -> Result<String, &'static str> {
    let n = data
        .iter()
        .take(4097)
        .position(|b| *b == 0)
        .ok_or("invalid_install_manifest")?;
    let value = std::str::from_utf8(take(data, n)?)
        .map_err(|_| "invalid_install_manifest")?
        .to_owned();
    take(data, 1)?;
    if value.is_empty() || value.chars().any(char::is_control) {
        return Err("invalid_install_manifest");
    }
    Ok(value)
}
pub fn safe_path(path: &str) -> bool {
    !path.is_empty()
        && !path.contains(':')
        && !path.chars().any(char::is_control)
        && path
            .split('/')
            .all(|s| !s.is_empty() && s != "." && s != "..")
}
impl InstallManifest {
    pub fn parse(mut data: &[u8]) -> Result<Self, &'static str> {
        let header = take(&mut data, 10)?;
        if header[..4] != *b"IN\x01\x10" {
            return Err("unsupported_install_manifest");
        }
        let tags = u16::from_be_bytes(header[4..6].try_into().unwrap()) as usize;
        let count = u32::from_be_bytes(header[6..10].try_into().unwrap()) as usize;
        if tags > 4096 || count > 100000 || data.len() > 64 * 1024 * 1024 {
            return Err("invalid_install_manifest");
        }
        let mut masks = Vec::new();
        for _ in 0..tags {
            let tag = name(&mut data)?;
            let group = u16::from_be_bytes(take(&mut data, 2)?.try_into().unwrap());
            let mask = take(&mut data, count.div_ceil(8))?.to_vec();
            if masks.iter().any(|t: &Tag| t.name == tag) {
                return Err("invalid_install_manifest");
            }
            masks.push(Tag {
                name: tag,
                group,
                mask,
            });
        }
        let mut files = Vec::new();
        let mut entries = Vec::new();
        for n in 0..count {
            let path = name(&mut data)?.replace('\\', "/");
            if !safe_path(&path) {
                return Err("unsafe_install_path");
            }
            let content_key = take(&mut data, 16)?
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect();
            let bytes = u32::from_be_bytes(take(&mut data, 4)?.try_into().unwrap()) as u64;
            files.push(InstalledFile {
                path,
                content_key,
                bytes,
            });
            entries.push(Entry {
                encoding_key: n.to_string(),
                encoded_bytes: bytes,
                priority: 0,
            });
        }
        if !data.is_empty() {
            return Err("invalid_install_manifest");
        }
        Ok(Self {
            files,
            selection: DownloadManifest {
                entries,
                tags: masks,
            },
        })
    }
    pub fn select(
        &self,
        names: &[String],
        excluded: &[String],
    ) -> Result<Vec<InstalledFile>, &'static str> {
        let available = |names: &[String]| {
            names
                .iter()
                .filter(|n| self.selection.tags.iter().any(|t| t.name == **n))
                .cloned()
                .collect::<Vec<_>>()
        };
        let files: Vec<InstalledFile> = self
            .selection
            .select_excluding(&available(names), &available(excluded))?
            .entries
            .iter()
            .map(|e| {
                e.encoding_key
                    .parse::<usize>()
                    .ok()
                    .and_then(|n| self.files.get(n))
                    .cloned()
                    .ok_or("invalid_install_manifest")
            })
            .collect::<Result<_, _>>()?;
        let mut paths = HashMap::new();
        for file in files {
            if let Some(previous) = paths.insert(file.path.to_lowercase(), file.clone()) {
                let previous: InstalledFile = previous;
                if previous.content_key != file.content_key || previous.bytes != file.bytes {
                    return Err("conflicting_install_path");
                }
            }
        }
        Ok(paths.into_values().collect())
    }
}
pub fn encodings(
    data: &[u8],
    wanted: &[InstalledFile],
) -> Result<HashMap<String, InstalledFile>, &'static str> {
    if data.len() < 22 || data[..5] != *b"EN\x01\x10\x10" || data[17] != 0 {
        return Err("unsupported_encoding_manifest");
    }
    let page_size = u16::from_be_bytes(data[5..7].try_into().unwrap()) as usize * 1024;
    let pages = u32::from_be_bytes(data[9..13].try_into().unwrap()) as usize;
    let strings = u32::from_be_bytes(data[18..22].try_into().unwrap()) as usize;
    if page_size == 0 || pages > 100000 || strings > data.len() - 22 {
        return Err("invalid_encoding_manifest");
    }
    let header = 22 + strings;
    let start = header
        .checked_add(pages * 32)
        .ok_or("invalid_encoding_manifest")?;
    let end = start
        .checked_add(
            pages
                .checked_mul(page_size)
                .ok_or("invalid_encoding_manifest")?,
        )
        .ok_or("invalid_encoding_manifest")?;
    if end > data.len() {
        return Err("invalid_encoding_manifest");
    }
    let wanted: HashMap<_, _> = wanted.iter().map(|f| (f.content_key.as_str(), f)).collect();
    let mut result = HashMap::new();
    for n in 0..pages {
        let mut page = &data[start + n * page_size..start + (n + 1) * page_size];
        if md5::compute(page).0 != data[header + n * 32 + 16..header + (n + 1) * 32] {
            return Err("checksum_mismatch");
        }
        while page.len() >= 22 {
            let count = u16::from_le_bytes(page[..2].try_into().unwrap()) as usize;
            if count == 0 {
                break;
            }
            let size = u32::from_be_bytes(page[2..6].try_into().unwrap()) as u64;
            let key = page[6..22]
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect::<String>();
            page = &page[22..];
            let keys = take(
                &mut page,
                count.checked_mul(16).ok_or("invalid_encoding_manifest")?,
            )?;
            if let Some(file) = wanted.get(key.as_str()) {
                if file.bytes != size {
                    return Err("content_size_mismatch");
                }
                for encoded in keys.as_chunks::<16>().0 {
                    let encoded = encoded.iter().map(|b| format!("{b:02x}")).collect();
                    result.insert(encoded, (*file).clone());
                }
            }
        }
    }
    Ok(result)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn selects_platform_variants_before_checking_path_conflicts() {
        let mut bytes = b"IN\x01\x10\0\x02\0\0\0\x02".to_vec();
        bytes.extend(b"Windows\0\0\x01\x80");
        bytes.extend(b"OSX\0\0\x01\x40");
        for n in [1u8, 2] {
            bytes.extend(b"game.exe\0");
            bytes.extend([n; 16]);
            bytes.extend(20u32.to_be_bytes());
        }
        let manifest = InstallManifest::parse(&bytes).unwrap();
        assert_eq!(
            manifest.select(&["Windows".into()], &[]).unwrap()[0].content_key,
            "01".repeat(16)
        );
        assert!(manifest.select(&[], &[]).is_err());
        for n in 0..bytes.len() {
            assert!(InstallManifest::parse(&bytes[..n]).is_err());
        }
    }
    #[test]
    fn authenticates_encoding_pages_and_maps_install_files() {
        let mut header = b"EN\x01\x10\x10\0\x01\0\x01\0\0\0\x01\0\0\0\0\0\0\0\0\0".to_vec();
        let mut page = vec![0u8; 1024];
        page[..2].copy_from_slice(&1u16.to_le_bytes());
        page[2..6].copy_from_slice(&20u32.to_be_bytes());
        page[6..22].fill(1);
        page[22..38].fill(2);
        header.extend([1; 16]);
        header.extend(md5::compute(&page).0);
        header.extend(page);
        let files = [InstalledFile {
            path: "game.exe".into(),
            content_key: "01".repeat(16),
            bytes: 20,
        }];
        assert!(encodings(&header, &files)
            .unwrap()
            .contains_key(&"02".repeat(16)));
        header[80] ^= 1;
        assert!(matches!(
            encodings(&header, &files),
            Err("checksum_mismatch")
        ));
    }
    #[test]
    fn rejects_install_paths_that_escape_the_root() {
        for path in ["../file", "/file", "a//b", "a/./b", "C:/file", "a/../b", ""] {
            assert!(!safe_path(path));
        }
        assert!(safe_path("folder/game.exe"));
    }
    #[test]
    fn rejects_truncated_and_invalid_manifests() {
        for n in 0..22 {
            assert!(encodings(&vec![0; n], &[]).is_err());
        }
        for n in 0..10 {
            assert!(InstallManifest::parse(&vec![0; n]).is_err());
        }
    }
}
