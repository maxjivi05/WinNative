use crate::{
    blte,
    install::{encodings, InstallManifest},
    jenkins,
    manifest::{Entry, Selection},
    planning::Plan,
    safe_dir::Directory,
    transfer::{verify_reader, Cache, Content, Control},
};
use std::{
    collections::{BTreeMap, HashMap},
    io::{Read, Seek, SeekFrom, Write},
    path::Path,
};
const SEGMENT: u64 = 1024 * 1024 * 1024;
#[derive(Clone, Copy)]
struct Record {
    key: [u8; 16],
    archive: u16,
    offset: u64,
    bytes: u64,
}
fn key(value: &str) -> Result<[u8; 16], &'static str> {
    if value.len() != 32 {
        return Err("invalid_content_key");
    }
    let mut bytes = [0; 16];
    for (n, b) in bytes.iter_mut().enumerate() {
        *b = u8::from_str_radix(
            value.get(n * 2..n * 2 + 2).ok_or("invalid_content_key")?,
            16,
        )
        .map_err(|_| "invalid_content_key")?;
    }
    Ok(bytes)
}
fn encode(record: Record) -> [u8; 64] {
    let mut b = [0; 64];
    b[..4].copy_from_slice(b"WNC1");
    b[4..20].copy_from_slice(&record.key);
    b[20..22].copy_from_slice(&record.archive.to_le_bytes());
    b[22..30].copy_from_slice(&record.offset.to_le_bytes());
    b[30..38].copy_from_slice(&record.bytes.to_le_bytes());
    let hash = md5::compute(&b[..48]);
    b[48..].copy_from_slice(&hash.0);
    b
}
fn decode(b: &[u8]) -> Option<Record> {
    if b.len() != 64 || b[..4] != *b"WNC1" || md5::compute(&b[..48]).0 != b[48..] {
        return None;
    }
    let r = Record {
        key: b[4..20].try_into().ok()?,
        archive: u16::from_le_bytes(b[20..22].try_into().ok()?),
        offset: u64::from_le_bytes(b[22..30].try_into().ok()?),
        bytes: u64::from_le_bytes(b[30..38].try_into().ok()?),
    };
    if r.archive >= 1024 || r.bytes < 9 || r.offset.checked_add(r.bytes)?.checked_add(30)? > SEGMENT
    {
        return None;
    }
    Some(r)
}
fn header(record: Record) -> [u8; 30] {
    let mut b = [0; 30];
    for (n, v) in record.key.iter().rev().enumerate() {
        b[n] = *v;
    }
    b[16..20].copy_from_slice(&((record.bytes + 30) as u32).to_le_bytes());
    let hash = jenkins::hash(&b[..22], 0x3d6be971, 0).0;
    b[22..26].copy_from_slice(&hash.to_le_bytes());
    let table = [
        0x049396b8u32,
        0x72a82a9b,
        0xee626cca,
        0x9917754f,
        0x15de40b1,
        0xf5a8a9b6,
        0x421eac7e,
        0xa9d55c9a,
        0x317fd40c,
        0x04faf80d,
        0x3d6be971,
        0x52933cfd,
        0x27f64b7d,
        0xc6f5c11b,
        0xd5757e3a,
        0x6c388745,
    ];
    let offset = (record.offset + 30) as u32;
    let encoded = (offset ^ table[(offset & 15) as usize]).to_le_bytes();
    let mut hash = [0u8; 4];
    for (n, v) in b[..26].iter().enumerate() {
        hash[n & 3] ^= *v;
    }
    for n in 0..4 {
        b[26 + n] = hash[(26 + n) & 3] ^ encoded[(26 + n) & 3];
    }
    b
}
fn index_bytes(bucket: u8, records: &[Record]) -> Result<Vec<u8>, &'static str> {
    let mut items = Vec::new();
    for record in records {
        let n = record.key[..9].iter().fold(0u8, |a, b| a ^ b);
        if (n & 15) ^ (n >> 4) == bucket {
            let mut b = [0; 18];
            b[..9].copy_from_slice(&record.key[..9]);
            let location = ((record.archive as u64) << 30) | record.offset;
            b[9..14].copy_from_slice(&location.to_be_bytes()[3..]);
            b[14..].copy_from_slice(&((record.bytes + 30) as u32).to_le_bytes());
            items.push(b);
        }
    }
    items.sort();
    for pair in items.windows(2) {
        if pair[0][..9] == pair[1][..9] {
            return Err("conflicting_casc_entry");
        }
    }
    let mut h = vec![7, 0, bucket, 0, 4, 5, 9, 30];
    h.extend(0xffc0000000u64.to_le_bytes());
    let mut bytes = 16u32.to_le_bytes().to_vec();
    bytes.extend(jenkins::hash(&h, 0, 0).0.to_le_bytes());
    bytes.extend(h);
    bytes.extend([0; 8]);
    bytes.extend((items.len() as u32 * 18).to_le_bytes());
    let mut high = 0;
    let mut low = 0;
    for entry in &items {
        (high, low) = jenkins::hash(entry, high, low);
    }
    bytes.extend(high.to_le_bytes());
    for item in items {
        bytes.extend(item);
    }
    bytes.resize((bytes.len().div_ceil(4096) * 4096).max(0x8000), 0);
    Ok(bytes)
}
fn copy_entry(
    data: &Directory,
    record: Record,
    entry: &Entry,
    cache: &Cache,
    control: &Control,
) -> Result<bool, &'static str> {
    let mut f = data.file(&format!("data.{:03}", record.archive), false, false)?;
    if record.bytes != entry.encoded_bytes || record.key != key(&entry.encoding_key)? {
        return Ok(false);
    }
    if record.offset + record.bytes + 30 > f.metadata().map_err(|_| "file_error")?.len() {
        return Ok(false);
    }
    f.seek(SeekFrom::Start(record.offset))
        .map_err(|_| "file_error")?;
    let mut stored = [0u8; 30];
    f.read_exact(&mut stored).map_err(|_| "file_error")?;
    if stored != header(record) {
        return Ok(false);
    }
    f.seek(SeekFrom::Start(record.offset + 30))
        .map_err(|_| "file_error")?;
    match verify_reader(
        &mut (&mut f).take(record.bytes),
        &entry.encoding_key,
        record.bytes,
        control,
    ) {
        Ok(()) => Ok(true),
        Err("cancelled") => Err("cancelled"),
        Err(_) => {
            cache.verify(&entry.encoding_key, entry.encoded_bytes, control)?;
            Ok(false)
        }
    }
}
fn store_config(root: &Directory, hash: &str, bytes: &[u8]) -> Result<(), &'static str> {
    if format!("{:x}", md5::compute(bytes)) != hash {
        return Err("checksum_mismatch");
    }
    let config = root
        .child("config".as_ref(), true)?
        .child(hash[..2].as_ref(), true)?
        .child(hash[2..4].as_ref(), true)?;
    config.write_once(hash, bytes)
}
fn build_info(plan: &Plan, names: &[String]) -> Result<Vec<u8>, &'static str> {
    let cdn =
        reqwest::Url::parse(plan.cdns.first().ok_or("invalid_cdn")?).map_err(|_| "invalid_cdn")?;
    let hosts: Result<Vec<_>, _> = plan
        .cdns
        .iter()
        .map(|s| reqwest::Url::parse(s).map(|u| u.host_str().unwrap_or("").to_owned()))
        .collect();
    let hosts = hosts.map_err(|_| "invalid_cdn")?.join(" ");
    let tags = names
        .iter()
        .filter(|t| t.as_str() != "speech" && t.as_str() != "text")
        .cloned()
        .collect::<Vec<_>>()
        .join(" ");
    Ok(format!("Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|CDN Path!STRING:0|CDN Hosts!STRING:0|Tags!STRING:0|Version!STRING:0|Product!STRING:0\n{}|1|{}|{}|{}|{}|{} speech?:{} text?|{}|{}\n",plan.region,plan.build.build_key,plan.cdn_key,cdn.path().trim_start_matches('/'),hosts,tags,tags,plan.build.version,plan.product).into_bytes())
}
pub fn pack(
    plan: &Plan,
    selection: &Selection,
    cache: &Cache,
    target: &Path,
    subdirectory: &str,
    control: &Control,
    mut progress: impl FnMut(usize, usize),
) -> Result<(), String> {
    if !crate::install::safe_path(subdirectory) || subdirectory.contains('/') {
        return Err("unsafe_install_path".into());
    }
    let root = Directory::open(target)?;
    let _lock = root.lock()?;
    let names = root.names()?;
    if names.iter().any(|n| n != ".winnative-install-lock")
        && !names.iter().any(|n| n == ".winnative-build")
    {
        return Err("destination_not_empty".into());
    }
    root.write_once(".winnative-build", plan.build.build_key.as_bytes())?;
    root.write_once(".winnative-selection",serde_json::json!({"product":plan.product,"region":plan.region,"tags":selection.selected_tags,"excluded":selection.excluded_tags,"subdirectory":subdirectory}).to_string().as_bytes())?;
    let data_root = root.child("Data".as_ref(), true)?;
    let data = data_root.child("data".as_ref(), true)?;
    let mut entries = selection.entries.clone();
    let mut sizes: HashMap<_, _> = entries
        .iter()
        .map(|e| (e.encoding_key.clone(), e.encoded_bytes))
        .collect();
    for metadata in plan.metadata.values() {
        control.checkpoint()?;
        if !cache.complete(
            &metadata.encoding_key,
            metadata.encoded_bytes as u64,
            control,
        )? {
            let mut result = Err("content_unavailable");
            for cdn in &plan.cdns {
                result = cache.download(
                    cdn,
                    Content {
                        key: &metadata.encoding_key,
                        size: metadata.encoded_bytes as u64,
                        archive: None,
                    },
                    control,
                    |_| {},
                );
                if result.is_ok() || result == Err("cancelled") {
                    break;
                }
            }
            result?;
        }
        if let Some(size) =
            sizes.insert(metadata.encoding_key.clone(), metadata.encoded_bytes as u64)
        {
            if size != metadata.encoded_bytes as u64 {
                return Err("content_size_mismatch".into());
            }
        } else {
            entries.push(Entry {
                encoding_key: metadata.encoding_key.clone(),
                encoded_bytes: metadata.encoded_bytes as u64,
                priority: 0,
            });
        }
    }
    let mut journal = data.file(".winnative-journal", true, false)?;
    if journal.metadata().map_err(|_| "file_error")?.len() > 256 * 1024 * 1024 {
        return Err("journal_too_large".into());
    }
    let mut contents = Vec::new();
    (&mut journal)
        .take(256 * 1024 * 1024 + 1)
        .read_to_end(&mut contents)
        .map_err(|_| "file_error")?;
    if contents.len() > 256 * 1024 * 1024 {
        return Err("journal_too_large".into());
    }
    let mut records = HashMap::new();
    for chunk in contents.as_chunks::<64>().0 {
        if let Some(r) = decode(chunk) {
            records.insert(r.key, r);
        }
    }
    journal.seek(SeekFrom::End(0)).map_err(|_| "file_error")?;
    let remainder = contents.len() % 64;
    if remainder != 0 {
        journal
            .write_all(&vec![0; 64 - remainder])
            .map_err(|_| "file_error")?;
    }
    let mut number = 0u16;
    for name in data.names()? {
        if let Some(n) = name
            .to_str()
            .and_then(|n| n.strip_prefix("data."))
            .and_then(|n| n.parse::<u16>().ok())
        {
            number = number.max(n);
        }
    }
    if number >= 1024 {
        return Err("casc_storage_full".into());
    }
    let mut archive = data.file(&format!("data.{number:03}"), true, false)?;
    let mut cursor = archive.seek(SeekFrom::End(0)).map_err(|_| "file_error")?;
    let result = (|| {
        for (n, entry) in entries.iter().enumerate() {
            control.checkpoint()?;
            let hash = key(&entry.encoding_key)?;
            if let Some(&record) = records.get(&hash) {
                if copy_entry(&data, record, entry, cache, control)? {
                    progress(n + 1, entries.len());
                    continue;
                }
            }
            if entry.encoded_bytes + 30 > SEGMENT {
                return Err("content_too_large".to_owned());
            }
            if cursor + entry.encoded_bytes + 30 > SEGMENT {
                archive.sync_data().map_err(|_| "file_error")?;
                number = number
                    .checked_add(1)
                    .filter(|n| *n < 1024)
                    .ok_or("casc_storage_full")?;
                archive = data.file(&format!("data.{number:03}"), true, true)?;
                cursor = 0;
            }
            let record = Record {
                key: hash,
                archive: number,
                offset: cursor,
                bytes: entry.encoded_bytes,
            };
            archive
                .write_all(&header(record))
                .map_err(|_| "file_error")?;
            cache.copy_verified(
                &entry.encoding_key,
                entry.encoded_bytes,
                &mut archive,
                control,
            )?;
            cursor += entry.encoded_bytes + 30;
            journal
                .write_all(&encode(record))
                .map_err(|_| "file_error")?;
            records.insert(hash, record);
            if n % 1000 == 0 {
                archive.sync_data().map_err(|_| "file_error")?;
                journal.sync_data().map_err(|_| "file_error")?;
            }
            progress(n + 1, entries.len());
        }
        Ok(())
    })();
    archive.sync_data().map_err(|_| "file_error")?;
    journal.sync_data().map_err(|_| "file_error")?;
    result?;
    let mut decoded = BTreeMap::new();
    for name in ["install", "encoding"] {
        let m = plan
            .metadata
            .get(name)
            .ok_or("install_manifest_unavailable")?;
        let raw = cache.read_verified(&m.encoding_key, m.encoded_bytes as u64, control)?;
        let bytes = blte::decode(&raw, m.decoded_bytes)?;
        if bytes.len() != m.decoded_bytes || format!("{:x}", md5::compute(&bytes)) != m.content_key
        {
            return Err("checksum_mismatch".into());
        }
        decoded.insert(name, bytes);
    }
    let install = InstallManifest::parse(&decoded["install"])?;
    let files = install.select(&selection.selected_tags, &selection.excluded_tags)?;
    let encodings = encodings(&decoded["encoding"], &files)?;
    let install_root = root.windows_child(subdirectory)?;
    for file in &files {
        control.checkpoint()?;
        let (encoded, _) = encodings
            .iter()
            .find(|(k, f)| f.content_key == file.content_key && sizes.contains_key(*k))
            .ok_or("install_encoding_missing")?;
        let raw = cache.read_verified(encoded, sizes[encoded], control)?;
        if file.bytes > 512 * 1024 * 1024 {
            return Err("content_too_large".into());
        }
        let bytes = blte::decode(&raw, file.bytes as usize)?;
        if bytes.len() as u64 != file.bytes
            || format!("{:x}", md5::compute(&bytes)) != file.content_key
        {
            return Err("checksum_mismatch".into());
        }
        let parts: Vec<_> = file.path.split('/').collect();
        let mut folder = Directory(install_root.0.try_clone().map_err(|_| "file_error")?);
        for part in &parts[..parts.len() - 1] {
            folder = folder.windows_child(part)?;
        }
        folder.write_once(parts[parts.len() - 1], &bytes)?;
    }
    store_config(&data_root, &plan.build.build_key, &plan.build_config)?;
    store_config(&data_root, &plan.cdn_key, &plan.cdn_config)?;
    let mut selected = Vec::new();
    for entry in &entries {
        selected.push(
            *records
                .get(&key(&entry.encoding_key)?)
                .ok_or("casc_content_missing")?,
        );
    }
    let names = data.names()?;
    for bucket in 0..16u8 {
        control.checkpoint()?;
        let mut generation = 0u32;
        for name in &names {
            if let Some(name) = name.to_str() {
                if name.len() == 14
                    && name.ends_with(".idx")
                    && name.as_bytes()[..10].iter().all(|b| b.is_ascii_hexdigit())
                    && u8::from_str_radix(&name[..2], 16).ok() == Some(bucket)
                {
                    generation = generation.max(
                        u32::from_str_radix(&name[2..10], 16).map_err(|_| "invalid_casc_index")?,
                    );
                }
            }
        }
        let next = generation.checked_add(1).ok_or("casc_storage_full")?;
        data.write_once(
            &format!("{bucket:02x}{next:08x}.idx"),
            &index_bytes(bucket, &selected)?,
        )?;
    }
    root.replace_preserving(
        ".build.info",
        &build_info(plan, &selection.selected_tags)?,
        "winnative-build-info-previous",
    )?;
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn resumes_packing_and_repairs_archives_without_overwriting_old_spans() {
        use crate::{manifest::DownloadManifest, planning::ManifestObject};
        use std::sync::atomic::{AtomicUsize, Ordering};
        static NEXT: AtomicUsize = AtomicUsize::new(0);
        let root = std::env::temp_dir().join(format!(
            "wn-pack-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&root).unwrap();
        let cache_path = root.join("cache");
        let target = root.join("game");
        std::fs::create_dir(&cache_path).unwrap();
        std::fs::create_dir(&target).unwrap();
        let cache = Cache::open(&cache_path).unwrap();
        let control = Control::default();
        let store = |decoded: &[u8]| {
            let mut encoded = b"BLTE\0\0\0\0N".to_vec();
            encoded.extend(decoded);
            let object = ManifestObject {
                content_key: format!("{:x}", md5::compute(decoded)),
                encoding_key: format!("{:x}", md5::compute(&encoded)),
                decoded_bytes: decoded.len(),
                encoded_bytes: encoded.len(),
            };
            cache
                .store(&object.encoding_key, &encoded, &control)
                .unwrap();
            object
        };
        let payload = b"verified game fixture";
        let game = store(payload);
        let mut install = b"IN\x01\x10\0\0\0\0\0\x01game.exe\0".to_vec();
        install.extend(key(&game.content_key).unwrap());
        install.extend((payload.len() as u32).to_be_bytes());
        let install = store(&install);
        let mut encoding = b"EN\x01\x10\x10\0\x01\0\x01\0\0\0\x01\0\0\0\0\0\0\0\0\0".to_vec();
        let mut page = vec![0u8; 1024];
        page[..2].copy_from_slice(&1u16.to_le_bytes());
        page[2..6].copy_from_slice(&(payload.len() as u32).to_be_bytes());
        page[6..22].copy_from_slice(&key(&game.content_key).unwrap());
        page[22..38].copy_from_slice(&key(&game.encoding_key).unwrap());
        encoding.extend(key(&game.content_key).unwrap());
        encoding.extend(md5::compute(&page).0);
        encoding.extend(page);
        let encoding = store(&encoding);
        let mut download = b"DL\x01\x10\0\0\0\0\x01\0\0".to_vec();
        download.extend(key(&game.encoding_key).unwrap());
        download.extend(&(game.encoded_bytes as u64).to_be_bytes()[3..]);
        download.push(0);
        let download = store(&download);
        let mut build_config = String::new();
        for (name, m) in [
            ("install", &install),
            ("encoding", &encoding),
            ("download", &download),
        ] {
            build_config.push_str(&format!(
                "{name} = {} {}\n{name}-size = {} {}\n",
                m.content_key, m.encoding_key, m.decoded_bytes, m.encoded_bytes
            ));
        }
        let build_config = build_config.into_bytes();
        let cdn_config = b"archives =\n".to_vec();
        let plan = Plan {
            build: crate::Build {
                build_key: format!("{:x}", md5::compute(&build_config)),
                version: "1.0.1".into(),
                build_id: 1,
            },
            product: "test".into(),
            region: "us".into(),
            cdn_key: format!("{:x}", md5::compute(&cdn_config)),
            build_config,
            cdn_config,
            manifest: DownloadManifest {
                entries: vec![],
                tags: vec![],
            },
            cdns: vec!["https://us.cdn.blizzard.com/tpr/test".into()],
            archives: vec![],
            metadata: BTreeMap::from([
                ("install".into(), install),
                ("encoding".into(), encoding),
                ("download".into(), download),
            ]),
        };
        let selection = Selection {
            encoded_bytes: game.encoded_bytes as u64,
            entries: vec![Entry {
                encoding_key: game.encoding_key,
                encoded_bytes: game.encoded_bytes as u64,
                priority: 0,
            }],
            selected_tags: vec![],
            excluded_tags: vec![],
        };
        let cancelled = Control::default();
        assert_eq!(
            pack(
                &plan,
                &selection,
                &cache,
                &target,
                "_classic_",
                &cancelled,
                |n, _| {
                    if n == 1 {
                        cancelled.cancel();
                    }
                }
            )
            .unwrap_err(),
            "cancelled"
        );
        assert!(!target.join(".build.info").exists());
        pack(
            &plan,
            &selection,
            &cache,
            &target,
            "_classic_",
            &control,
            |_, _| {},
        )
        .unwrap();
        let local = crate::planning::installed_plan(&target, "test").unwrap();
        assert_eq!(local.manifest.entries, selection.entries);
        let path = target.join("Data/data/data.000");
        let original = std::fs::read(&path).unwrap();
        crate::casc::Storage::open(&target.join("Data/data"))
            .unwrap()
            .verify(&selection, &control, |_, _| {})
            .unwrap();
        assert_eq!(
            std::fs::read(target.join("_classic_/game.exe")).unwrap(),
            payload
        );
        pack(
            &plan,
            &selection,
            &cache,
            &target,
            "_classic_",
            &control,
            |_, _| {},
        )
        .unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), original);
        let mut bad = original;
        bad[40] ^= 1;
        std::fs::write(&path, &bad).unwrap();
        pack(
            &plan,
            &selection,
            &cache,
            &target,
            "_classic_",
            &control,
            |_, _| {},
        )
        .unwrap();
        let repaired = std::fs::read(&path).unwrap();
        assert_eq!(&repaired[..bad.len()], bad.as_slice());
        assert!(repaired.len() > bad.len());
        crate::casc::Storage::open(&target.join("Data/data"))
            .unwrap()
            .verify(&selection, &control, |_, _| {})
            .unwrap();
    }
    #[test]
    fn journal_detects_partial_and_corrupt_records() {
        let r = Record {
            key: [1; 16],
            archive: 2,
            offset: 33,
            bytes: 100,
        };
        let mut bytes = encode(r);
        assert_eq!(decode(&bytes).unwrap().bytes, 100);
        for n in 0..64 {
            assert!(decode(&bytes[..n]).is_none());
        }
        bytes[24] ^= 1;
        assert!(decode(&bytes).is_none());
    }
}
