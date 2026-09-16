#[derive(Debug, Clone, PartialEq)]
pub struct ArchiveEntry {
    pub key: String,
    pub bytes: u64,
    pub offset: u64,
}
pub fn parse(bytes: &[u8], archive_key: &str) -> Result<Vec<ArchiveEntry>, &'static str> {
    if !(28..=16 * 1024 * 1024).contains(&bytes.len()) {
        return Err("invalid_archive_index");
    }
    let footer = &bytes[bytes.len() - 28..];
    if format!("{:x}", md5::compute(footer)) != archive_key {
        return Err("checksum_mismatch");
    }
    if footer[8..11] != [1, 0, 0] || footer[12..16] != [4, 4, 16, 8] || footer[11] == 0 {
        return Err("unsupported_archive_index");
    }
    let mut footer_check = [0u8; 20];
    footer_check[..12].copy_from_slice(&footer[8..20]);
    if md5::compute(footer_check).0[..8] != footer[20..28] {
        return Err("checksum_mismatch");
    }
    let page_size = footer[11] as usize * 1024;
    let count = u32::from_le_bytes(footer[16..20].try_into().unwrap()) as usize;
    let body_size = bytes.len() - 28;
    if !body_size.is_multiple_of(page_size + 24) || count > body_size / 24 {
        return Err("invalid_archive_index");
    }
    let pages = body_size / (page_size + 24);
    let toc = &bytes[pages * page_size..body_size];
    if md5::compute(toc).0[..8] != footer[..8] {
        return Err("checksum_mismatch");
    }
    let mut entries = Vec::new();
    for index in 0..pages {
        let page = &bytes[index * page_size..(index + 1) * page_size];
        if md5::compute(page).0[..8] != toc[pages * 16 + index * 8..pages * 16 + (index + 1) * 8] {
            return Err("checksum_mismatch");
        }
        let mut offset = 0;
        while offset + 24 <= page.len() && page[offset..offset + 16].iter().any(|b| *b != 0) {
            let entry = &page[offset..offset + 24];
            entries.push(ArchiveEntry {
                key: entry[..16].iter().map(|b| format!("{b:02x}")).collect(),
                bytes: u32::from_be_bytes(entry[16..20].try_into().unwrap()) as u64,
                offset: u32::from_be_bytes(entry[20..24].try_into().unwrap()) as u64,
            });
            offset += 24;
        }
        if page[offset..].iter().any(|b| *b != 0) {
            return Err("invalid_archive_index");
        }
    }
    if entries.len() != count {
        return Err("invalid_archive_index");
    }
    Ok(entries)
}
#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> (Vec<u8>, String) {
        let mut page = vec![0u8; 1024];
        page[..16].fill(1);
        page[16..20].copy_from_slice(&50u32.to_be_bytes());
        page[20..24].copy_from_slice(&90u32.to_be_bytes());
        let mut toc = vec![1u8; 16];
        toc.extend_from_slice(&md5::compute(&page).0[..8]);
        let mut footer = md5::compute(&toc).0[..8].to_vec();
        footer.extend([1, 0, 0, 1, 4, 4, 16, 8]);
        footer.extend(1u32.to_le_bytes());
        let mut check = footer[8..].to_vec();
        check.extend([0; 8]);
        footer.extend_from_slice(&md5::compute(check).0[..8]);
        let key = format!("{:x}", md5::compute(&footer));
        page.extend(toc);
        page.extend(footer);
        (page, key)
    }
    #[test]
    fn validates_full_hash_chain_and_extracts_ranges() {
        let (mut bytes, key) = fixture();
        let entries = parse(&bytes, &key).unwrap();
        assert_eq!(entries[0].offset, 90);
        assert_eq!(entries[0].bytes, 50);
        bytes[22] ^= 1;
        assert_eq!(parse(&bytes, &key).unwrap_err(), "checksum_mismatch");
    }
    #[test]
    fn rejects_truncated_index() {
        let (bytes, key) = fixture();
        for n in 0..bytes.len() {
            assert!(parse(&bytes[..n], &key).is_err());
        }
    }
}
