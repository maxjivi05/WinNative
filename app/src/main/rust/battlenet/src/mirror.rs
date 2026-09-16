use crate::{
    manifest::{Entry, Selection},
    planning::{fetch, Plan},
    transfer::{Cache, Content, Control},
};
use std::{
    collections::{BTreeMap, HashMap},
    io::Read,
    sync::{
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
        mpsc,
    },
    time::Duration,
};
const BATCH_LIMIT: u64 = 8 * 1024 * 1024;
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Location {
    pub archive: usize,
    pub offset: u64,
}
#[derive(Debug)]
pub struct Batch {
    pub archive: Option<usize>,
    pub start: u64,
    pub bytes: u64,
    pub entries: Vec<(usize, u64)>,
}
fn client() -> Result<reqwest::blocking::Client, String> {
    reqwest::blocking::Client::builder()
        .https_only(true)
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(60))
        .build()
        .map_err(|_| "network_error".into())
}
pub fn resolve(
    plan: &Plan,
    selection: &Selection,
    control: &Control,
    mut progress: impl FnMut(usize, usize),
) -> Result<Vec<Option<Location>>, String> {
    let wanted: HashMap<_, _> = selection
        .entries
        .iter()
        .enumerate()
        .map(|(n, e)| (e.encoding_key.as_str(), n))
        .collect();
    let client = client()?;
    let next = AtomicUsize::new(0);
    let stop = AtomicBool::new(false);
    let mut locations = vec![None; selection.entries.len()];
    std::thread::scope(|scope| {
        let (sender, receiver) = mpsc::sync_channel(8);
        for _ in 0..8 {
            let sender = sender.clone();
            let client = client.clone();
            let next = &next;
            let stop = &stop;
            let wanted = &wanted;
            scope.spawn(move || {
                while !stop.load(Ordering::Relaxed) {
                    let n = next.fetch_add(1, Ordering::Relaxed);
                    if n >= plan.archives.len() {
                        break;
                    }
                    let result = (|| {
                        control.checkpoint()?;
                        let archive = &plan.archives[n];
                        let mut found = None;
                        for cdn in &plan.cdns {
                            control.checkpoint()?;
                            let url = format!(
                                "{cdn}/data/{}/{}/{archive}.index",
                                &archive[..2],
                                &archive[2..4]
                            );
                            if let Ok(bytes) = fetch(&client, &url, 16 * 1024 * 1024) {
                                if let Ok(entries) = crate::archive::parse(&bytes, archive) {
                                    found = Some(entries);
                                    break;
                                }
                            }
                        }
                        let entries = found.ok_or("archive_index_unavailable")?;
                        let mut output = Vec::new();
                        for entry in entries {
                            if let Some(&index) = wanted.get(entry.key.as_str()) {
                                if selection.entries[index].encoded_bytes != entry.bytes {
                                    return Err("content_size_mismatch".to_owned());
                                }
                                output.push((index, entry.offset));
                            }
                        }
                        Ok(output)
                    })();
                    if result.is_err() {
                        stop.store(true, Ordering::Relaxed);
                    }
                    if sender.send((n, result)).is_err() {
                        break;
                    }
                }
            });
        }
        drop(sender);
        let mut failed = None;
        let mut finished = 0;
        for (archive, result) in receiver {
            match result {
                Ok(entries) => {
                    for (n, offset) in entries {
                        if locations[n].is_none_or(|l: Location| archive < l.archive) {
                            locations[n] = Some(Location { archive, offset });
                        }
                    }
                }
                Err(error) => {
                    if failed.is_none() {
                        failed = Some(error);
                        control.cancel();
                    }
                }
            }
            finished += 1;
            progress(finished, plan.archives.len());
        }
        if let Some(error) = failed {
            Err(error)
        } else {
            control.checkpoint()?;
            Ok(locations)
        }
    })
}
pub fn batches(
    entries: &[Entry],
    locations: &[Option<Location>],
) -> Result<Vec<Batch>, &'static str> {
    if entries.len() != locations.len() {
        return Err("invalid_transfer_plan");
    }
    let mut groups: BTreeMap<usize, Vec<(usize, u64)>> = BTreeMap::new();
    let mut output = Vec::new();
    for (n, location) in locations.iter().enumerate() {
        if entries[n].encoded_bytes < 9 {
            return Err("invalid_transfer_plan");
        }
        if let Some(location) = location {
            groups
                .entry(location.archive)
                .or_default()
                .push((n, location.offset));
        } else {
            output.push(Batch {
                archive: None,
                start: 0,
                bytes: entries[n].encoded_bytes,
                entries: vec![(n, 0)],
            });
        }
    }
    for (archive, mut items) in groups {
        items.sort_by_key(|item| item.1);
        let mut current: Option<Batch> = None;
        for (n, offset) in items {
            let end = offset
                .checked_add(entries[n].encoded_bytes)
                .ok_or("invalid_content_range")?;
            if let Some(batch) = &mut current {
                let previous_end = batch.start + batch.bytes;
                if offset < previous_end {
                    return Err("overlapping_archive_entries");
                }
                if offset - previous_end <= 16384 && end - batch.start <= BATCH_LIMIT {
                    batch.entries.push((n, offset - batch.start));
                    batch.bytes = end - batch.start;
                    continue;
                }
            }
            if let Some(batch) = current.take() {
                output.push(batch);
            }
            current = Some(Batch {
                archive: Some(archive),
                start: offset,
                bytes: end - offset,
                entries: vec![(n, 0)],
            });
        }
        if let Some(batch) = current {
            output.push(batch);
        }
    }
    Ok(output)
}
fn fetch_batch(
    client: &reqwest::blocking::Client,
    plan: &Plan,
    batch: &Batch,
    selection: &Selection,
    control: &Control,
) -> Result<Vec<u8>, String> {
    if batch.bytes > BATCH_LIMIT {
        return Err("batch_too_large".into());
    }
    let key = if let Some(n) = batch.archive {
        plan.archives.get(n).ok_or("invalid_transfer_plan")?
    } else {
        &selection.entries[batch.entries[0].0].encoding_key
    };
    let end = batch
        .start
        .checked_add(batch.bytes - 1)
        .ok_or("invalid_content_range")?;
    for cdn in &plan.cdns {
        control.checkpoint()?;
        let mut request = client
            .get(format!("{cdn}/data/{}/{}/{key}", &key[..2], &key[2..4]))
            .header(reqwest::header::ACCEPT_ENCODING, "identity");
        if batch.archive.is_some() {
            request = request.header(
                reqwest::header::RANGE,
                format!("bytes={}-{end}", batch.start),
            );
        }
        let Ok(mut response) = request.send() else {
            continue;
        };
        if batch.archive.is_some() {
            if response.status() != reqwest::StatusCode::PARTIAL_CONTENT {
                continue;
            }
            let range = response
                .headers()
                .get(reqwest::header::CONTENT_RANGE)
                .and_then(|v| v.to_str().ok())
                .unwrap_or("");
            let expected = format!("bytes {}-{end}/", batch.start);
            let Some(total) = range
                .strip_prefix(&expected)
                .and_then(|v| v.parse::<u64>().ok())
            else {
                continue;
            };
            if total <= end {
                continue;
            }
        } else if response.status() != reqwest::StatusCode::OK {
            continue;
        }
        if response.content_length().is_some_and(|n| n != batch.bytes) {
            continue;
        }
        let mut data = Vec::with_capacity(batch.bytes as usize);
        let mut buffer = [0u8; 65536];
        let mut failed = false;
        while data.len() < batch.bytes as usize {
            control.checkpoint()?;
            let n = buffer.len().min(batch.bytes as usize - data.len());
            match response.read(&mut buffer[..n]) {
                Ok(0) | Err(_) => {
                    failed = true;
                    break;
                }
                Ok(n) => data.extend_from_slice(&buffer[..n]),
            }
        }
        if failed {
            continue;
        }
        let mut excess = [0u8; 1];
        if !matches!(response.read(&mut excess), Ok(0)) {
            continue;
        }
        return Ok(data);
    }
    Err("content_unavailable".into())
}
pub fn download(
    plan: &Plan,
    selection: &Selection,
    locations: &[Option<Location>],
    cache: &Cache,
    control: &Control,
    progress: impl Fn(usize, u64) + Sync,
) -> Result<(), String> {
    if selection.entries.iter().any(|e| {
        e.encoding_key.len() != 32 || !e.encoding_key.bytes().all(|b| b.is_ascii_hexdigit())
    }) || locations
        .iter()
        .flatten()
        .any(|l| l.archive >= plan.archives.len())
    {
        return Err("invalid_transfer_plan".into());
    }
    let batches = batches(&selection.entries, locations)?;
    let client = client()?;
    let next = AtomicUsize::new(0);
    let completed = AtomicUsize::new(0);
    let bytes = AtomicU64::new(0);
    let stop = AtomicBool::new(false);
    let counters: Vec<_> = selection
        .entries
        .iter()
        .map(|_| AtomicU64::new(0))
        .collect();
    let advance = |n: usize, value: u64| {
        let previous = counters[n].fetch_max(value, Ordering::Relaxed);
        if value > previous {
            bytes.fetch_add(value - previous, Ordering::Relaxed);
        }
        progress(
            completed.load(Ordering::Relaxed),
            bytes.load(Ordering::Relaxed),
        );
    };
    std::thread::scope(|scope| {
        let (sender, receiver) = mpsc::channel();
        for _ in 0..8 {
            let sender = sender.clone();
            let batches = &batches;
            let client = client.clone();
            let next = &next;
            let stop = &stop;
            let completed = &completed;
            let advance = &advance;
            scope.spawn(move || {
                while !stop.load(Ordering::Relaxed) {
                    let b = next.fetch_add(1, Ordering::Relaxed);
                    if b >= batches.len() {
                        break;
                    }
                    let batch = &batches[b];
                    let result = (|| {
                        control.checkpoint()?;
                        let mut pending = Vec::new();
                        for &(n, offset) in &batch.entries {
                            let entry = &selection.entries[n];
                            if cache.complete(&entry.encoding_key, entry.encoded_bytes, control)? {
                                completed.fetch_add(1, Ordering::Relaxed);
                                advance(n, entry.encoded_bytes);
                            } else {
                                pending.push((n, offset));
                            }
                        }
                        if pending.is_empty() {
                            return Ok(());
                        }
                        if batch.bytes > BATCH_LIMIT {
                            if pending.len() != 1 {
                                return Err("invalid_transfer_plan".to_owned());
                            }
                            let n = pending[0].0;
                            let entry = &selection.entries[n];
                            let location = locations[n];
                            let mut result = Err("content_unavailable");
                            for cdn in &plan.cdns {
                                control.checkpoint()?;
                                result = cache.download(
                                    cdn,
                                    Content {
                                        key: &entry.encoding_key,
                                        size: entry.encoded_bytes,
                                        archive: location
                                            .map(|l| (plan.archives[l.archive].as_str(), l.offset)),
                                    },
                                    control,
                                    |value| advance(n, value),
                                );
                                if result.is_ok() || result == Err("cancelled") {
                                    break;
                                }
                            }
                            result?;
                            completed.fetch_add(1, Ordering::Relaxed);
                            advance(n, entry.encoded_bytes);
                            return Ok(());
                        }
                        let data = fetch_batch(&client, plan, batch, selection, control)?;
                        for (n, offset) in pending {
                            control.checkpoint()?;
                            let entry = &selection.entries[n];
                            let end = offset
                                .checked_add(entry.encoded_bytes)
                                .ok_or("invalid_transfer_plan")?;
                            cache.store(
                                &entry.encoding_key,
                                data.get(offset as usize..end as usize)
                                    .ok_or("invalid_transfer_plan")?,
                                control,
                            )?;
                            completed.fetch_add(1, Ordering::Relaxed);
                            advance(n, entry.encoded_bytes);
                        }
                        Ok(())
                    })();
                    if let Err(error) = result {
                        stop.store(true, Ordering::Relaxed);
                        let _ = sender.send(error);
                        control.cancel();
                        break;
                    }
                }
            });
        }
        drop(sender);
        let error = receiver.into_iter().next();
        if let Some(error) = error {
            Err(error)
        } else {
            control.checkpoint()?;
            Ok(())
        }
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_ignored_and_misdirected_ranges() {
        use std::io::{Read, Write};
        for (status, range, success) in [
            ("200 OK", "", false),
            (
                "206 Partial Content",
                "Content-Range: bytes 11-19/40\r\n",
                false,
            ),
            (
                "206 Partial Content",
                "Content-Range: bytes 10-18/40\r\n",
                true,
            ),
        ] {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            let server = std::thread::spawn(move || {
                let (mut stream, _) = listener.accept().unwrap();
                let mut buffer = [0; 2048];
                let _ = stream.read(&mut buffer).unwrap();
                write!(stream,"HTTP/1.1 {status}\r\n{range}Content-Length: 9\r\nConnection: close\r\n\r\n123456789").unwrap();
            });
            let plan = Plan {
                product: "test".into(),
                region: "us".into(),
                cdn_key: "00".repeat(16),
                build_config: vec![],
                cdn_config: vec![],
                build: crate::Build {
                    build_key: "00".repeat(16),
                    version: "test".into(),
                    build_id: 1,
                },
                manifest: crate::manifest::DownloadManifest {
                    entries: vec![],
                    tags: vec![],
                },
                cdns: vec![format!("http://{address}")],
                archives: vec!["01".repeat(16)],
                metadata: BTreeMap::new(),
            };
            let selection = Selection {
                encoded_bytes: 9,
                entries: vec![Entry {
                    encoding_key: "02".repeat(16),
                    encoded_bytes: 9,
                    priority: 0,
                }],
                selected_tags: vec![],
                excluded_tags: vec![],
            };
            let batch = Batch {
                archive: Some(0),
                start: 10,
                bytes: 9,
                entries: vec![(0, 0)],
            };
            let result = fetch_batch(
                &reqwest::blocking::Client::new(),
                &plan,
                &batch,
                &selection,
                &Control::default(),
            );
            assert_eq!(result.is_ok(), success);
            server.join().unwrap();
        }
    }
    #[test]
    fn combines_nearby_ranges_without_crossing_archives_or_size_limits() {
        let entries: Vec<_> = (0..4)
            .map(|n| Entry {
                encoding_key: format!("{n:032x}"),
                encoded_bytes: 50,
                priority: 0,
            })
            .collect();
        let locations = [
            Some(Location {
                archive: 0,
                offset: 100,
            }),
            Some(Location {
                archive: 0,
                offset: 160,
            }),
            Some(Location {
                archive: 1,
                offset: 100,
            }),
            None,
        ];
        let plan = batches(&entries, &locations).unwrap();
        assert_eq!(plan.len(), 3);
        assert_eq!(plan[1].bytes, 110);
        assert_eq!(plan[1].entries, vec![(0, 0), (1, 60)]);
        let mut invalid = locations;
        invalid[1] = Some(Location {
            archive: 0,
            offset: 120,
        });
        assert!(batches(&entries, &invalid).is_err());
    }
}
