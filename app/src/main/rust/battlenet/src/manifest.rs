use serde::Serialize;
use std::collections::{BTreeMap, HashMap};

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    pub encoding_key: String,
    pub encoded_bytes: u64,
    pub priority: i16,
}
#[derive(Debug, Serialize)]
pub struct Tag {
    pub name: String,
    pub group: u16,
    #[serde(skip)]
    mask: Vec<u8>,
}
#[derive(Debug)]
pub struct DownloadManifest {
    pub entries: Vec<Entry>,
    pub tags: Vec<Tag>,
}
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Selection {
    pub encoded_bytes: u64,
    pub entries: Vec<Entry>,
    pub selected_tags: Vec<String>,
}
struct Reader<'a> {
    bytes: &'a [u8],
}
impl<'a> Reader<'a> {
    fn take(&mut self, length: usize) -> Result<&'a [u8], &'static str> {
        if length > self.bytes.len() {
            return Err("truncated_manifest");
        }
        let (out, rest) = self.bytes.split_at(length);
        self.bytes = rest;
        Ok(out)
    }
    fn number(&mut self, length: usize) -> Result<u64, &'static str> {
        Ok(self
            .take(length)?
            .iter()
            .fold(0, |n, b| (n << 8) | *b as u64))
    }
}
impl DownloadManifest {
    pub fn parse(bytes: &[u8]) -> Result<Self, &'static str> {
        if bytes.len() > 128 * 1024 * 1024 {
            return Err("manifest_too_large");
        }
        let mut reader = Reader { bytes };
        if reader.take(2)? != b"DL" {
            return Err("invalid_manifest");
        }
        let version = reader.number(1)?;
        if !(1..=3).contains(&version) || reader.number(1)? != 16 {
            return Err("unsupported_manifest");
        }
        let checksum = reader.number(1)?;
        if checksum > 1 {
            return Err("invalid_manifest");
        }
        let count = reader.number(4)? as usize;
        let tag_count = reader.number(2)? as usize;
        let flags = if version >= 2 {
            reader.number(1)? as usize
        } else {
            0
        };
        let base = if version >= 3 {
            let n = reader.number(1)? as i16;
            reader.take(3)?;
            n
        } else {
            0
        };
        if count > 4_000_000
            || flags > 4
            || tag_count > 4096
            || count > reader.bytes.len() / (22 + flags + checksum as usize * 4)
        {
            return Err("invalid_manifest");
        }
        let mut entries = Vec::with_capacity(count);
        for _ in 0..count {
            let encoding_key = reader
                .take(16)?
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect();
            let encoded_bytes = reader.number(5)?;
            let priority = reader.number(1)? as i16 - base;
            reader.take(flags + checksum as usize * 4)?;
            entries.push(Entry {
                encoding_key,
                encoded_bytes,
                priority,
            });
        }
        let mut tags = Vec::with_capacity(tag_count);
        for _ in 0..tag_count {
            let length = reader
                .bytes
                .iter()
                .take(257)
                .position(|b| *b == 0)
                .ok_or("invalid_tag")?;
            let name = std::str::from_utf8(reader.take(length)?)
                .map_err(|_| "invalid_tag")?
                .to_owned();
            if name.is_empty()
                || name.chars().any(char::is_control)
                || tags.iter().any(|t: &Tag| t.name == name)
            {
                return Err("invalid_tag");
            }
            reader.take(1)?;
            let group = reader.number(2)? as u16;
            let mask = reader.take(count.div_ceil(8))?.to_vec();
            tags.push(Tag { name, group, mask });
        }
        if !reader.bytes.is_empty() {
            return Err("invalid_manifest");
        }
        Ok(Self { entries, tags })
    }
    pub fn select(&self, names: &[String]) -> Result<Selection, &'static str> {
        let mut groups: BTreeMap<u16, Vec<&Tag>> = BTreeMap::new();
        for name in names {
            let tag = self
                .tags
                .iter()
                .find(|tag| tag.name == *name)
                .ok_or("unknown_tag")?;
            groups.entry(tag.group).or_default().push(tag);
        }
        let mut unique = HashMap::new();
        let mut entries = Vec::new();
        let mut encoded_bytes: u64 = 0;
        for (index, entry) in self.entries.iter().enumerate() {
            if !groups.values().all(|tags| {
                tags.iter()
                    .any(|tag| tag.mask[index / 8] & (0x80 >> (index % 8)) != 0)
            }) {
                continue;
            }
            if let Some(size) = unique.insert(&entry.encoding_key, entry.encoded_bytes) {
                if size != entry.encoded_bytes {
                    return Err("conflicting_entry");
                }
                continue;
            }
            encoded_bytes = encoded_bytes
                .checked_add(entry.encoded_bytes)
                .ok_or("invalid_manifest")?;
            entries.push(entry.clone());
        }
        entries.sort_by_key(|entry| entry.priority);
        Ok(Selection {
            encoded_bytes,
            entries,
            selected_tags: names.to_vec(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> Vec<u8> {
        let mut data = b"DL\x03\x10\0\0\0\0\x02\0\x03\0\x01\0\0\0".to_vec();
        for n in [1u8, 2] {
            data.extend([n; 16]);
            data.extend([1, 0, 0, 0, n]);
            data.push(n);
        }
        for (name, group, mask) in [("Windows", 1u16, 0x80), ("Mac", 1, 0x40), ("enUS", 2, 0xc0)] {
            data.extend(name.as_bytes());
            data.push(0);
            data.extend(group.to_be_bytes());
            data.push(mask);
        }
        data
    }
    #[test]
    fn selects_union_within_groups_intersection_between_groups() {
        let m = DownloadManifest::parse(&fixture()).unwrap();
        let s = m.select(&["Windows".into(), "enUS".into()]).unwrap();
        assert_eq!(s.encoded_bytes, 4294967297);
        assert_eq!(s.entries.len(), 1);
        assert_eq!(s.entries[0].priority, 0);
        assert_eq!(
            m.select(&["Windows".into(), "Mac".into()])
                .unwrap()
                .entries
                .len(),
            2
        );
        assert_eq!(m.select(&[]).unwrap().encoded_bytes, 8589934595);
    }
    #[test]
    fn rejects_unknown_tags_and_truncation() {
        let bytes = fixture();
        assert!(DownloadManifest::parse(&bytes)
            .unwrap()
            .select(&["frFR".into()])
            .is_err());
        for length in 0..bytes.len() {
            assert!(DownloadManifest::parse(&bytes[..length]).is_err());
        }
    }
    #[test]
    fn deduplicates_without_hiding_size_conflicts() {
        let mut m = DownloadManifest::parse(&fixture()).unwrap();
        m.entries[1] = m.entries[0].clone();
        assert_eq!(m.select(&[]).unwrap().entries.len(), 1);
        m.entries[1].encoded_bytes += 1;
        assert_eq!(m.select(&[]).unwrap_err(), "conflicting_entry");
    }
}
