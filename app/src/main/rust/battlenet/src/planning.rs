use crate::{blte, latest_build, manifest::DownloadManifest, valid_product, valid_region, Build};
use reqwest::blocking::Client;
use std::{collections::BTreeMap, io::Read, time::Duration};

pub struct Plan {
    pub build: Build,
    pub manifest: DownloadManifest,
    pub cdns: Vec<String>,
    pub archives: Vec<String>,
}
fn fetch(client: &Client, url: &str, limit: usize) -> Result<Vec<u8>, String> {
    let response = client.get(url).send().map_err(|_| "network_error")?;
    if !response.status().is_success() {
        return Err("content_unavailable".into());
    }
    if response
        .content_length()
        .is_some_and(|size| size > limit as u64)
    {
        return Err("content_too_large".into());
    }
    let mut bytes = Vec::new();
    response
        .take(limit as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "network_error")?;
    if bytes.len() > limit {
        return Err("content_too_large".into());
    }
    Ok(bytes)
}
fn key(value: &str) -> bool {
    value.len() == 32 && value.bytes().all(|b| b.is_ascii_hexdigit())
}
fn config(bytes: &[u8]) -> Result<BTreeMap<&str, Vec<&str>>, String> {
    let text = std::str::from_utf8(bytes).map_err(|_| "invalid_config")?;
    let mut entries = BTreeMap::new();
    for line in text
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
    {
        let (name, value) = line.split_once('=').ok_or("invalid_config")?;
        if entries
            .insert(name.trim(), value.split_whitespace().collect())
            .is_some()
        {
            return Err("invalid_config".into());
        }
    }
    Ok(entries)
}
fn cdn_urls(text: &str, region: &str) -> Result<Vec<String>, String> {
    let mut lines = text
        .lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'));
    let header: Vec<_> = lines
        .next()
        .ok_or("invalid_cdn")?
        .split('|')
        .map(|s| s.split('!').next().unwrap_or(""))
        .collect();
    let index = |name: &str| -> Result<usize, String> {
        let positions: Vec<_> = header
            .iter()
            .enumerate()
            .filter(|(_, v)| **v == name)
            .map(|(i, _)| i)
            .collect();
        if positions.len() != 1 {
            return Err("invalid_cdn".into());
        }
        Ok(positions[0])
    };
    let (name, path, servers) = (index("Name")?, index("Path")?, index("Servers")?);
    let mut selected = None;
    for line in lines {
        let row: Vec<_> = line.split('|').collect();
        if row.len() != header.len() {
            return Err("invalid_cdn".into());
        }
        if row[name] != region {
            continue;
        }
        if selected.is_some()
            || row[path].split('/').any(|part| {
                part.is_empty()
                    || !part
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
            })
        {
            return Err("invalid_cdn".into());
        }
        let mut urls = Vec::new();
        for server in row[servers].split_whitespace() {
            let Ok(mut url) = reqwest::Url::parse(server) else {
                continue;
            };
            let host = url.host_str().unwrap_or("");
            if url.scheme() != "https"
                || !url.username().is_empty()
                || url.password().is_some()
                || url.port().is_some()
                || !(host.ends_with(".blizzard.com") || host.ends_with(".battle.net"))
            {
                continue;
            }
            url.set_query(None);
            url.set_fragment(None);
            url.set_path(row[path]);
            urls.push(url.to_string().trim_end_matches('/').to_owned());
        }
        selected = Some(urls);
    }
    selected
        .filter(|urls| !urls.is_empty())
        .ok_or("cdn_unavailable".into())
}
fn object(
    client: &Client,
    cdns: &[String],
    category: &str,
    hash: &str,
    limit: usize,
) -> Result<Vec<u8>, String> {
    if !key(hash) {
        return Err("invalid_content_key".into());
    }
    for base in cdns {
        if let Ok(bytes) = fetch(
            client,
            &format!("{base}/{category}/{}/{}/{hash}", &hash[..2], &hash[2..4]),
            limit,
        ) {
            return Ok(bytes);
        }
    }
    Err("content_unavailable".into())
}
pub fn download_plan(product: &str, region: &str) -> Result<Plan, String> {
    if !valid_product(product) || !valid_region(region) {
        return Err("invalid_product".into());
    }
    let build = latest_build(product, region)?;
    let client = Client::builder()
        .https_only(true)
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(60))
        .user_agent("WinNative-BattleNet/0.1")
        .build()
        .map_err(|_| "network_error")?;
    let metadata = fetch(
        &client,
        &format!("https://us.version.battle.net/{product}/cdns"),
        1024 * 1024,
    )?;
    let cdns = cdn_urls(
        std::str::from_utf8(&metadata).map_err(|_| "invalid_cdn")?,
        region,
    )?;
    let versions = fetch(
        &client,
        &format!("https://us.version.battle.net/{product}/versions"),
        1024 * 1024,
    )?;
    let text = std::str::from_utf8(&versions).map_err(|_| "invalid_metadata")?;
    let mut rows = text
        .lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'));
    let header: Vec<_> = rows
        .next()
        .ok_or("invalid_metadata")?
        .split('|')
        .map(|v| v.split('!').next().unwrap_or(""))
        .collect();
    let column = |name| {
        header
            .iter()
            .position(|h| *h == name)
            .ok_or("invalid_metadata")
    };
    let (r, b, c) = (
        column("Region")?,
        column("BuildConfig")?,
        column("CDNConfig")?,
    );
    let row = rows
        .map(|line| line.split('|').collect::<Vec<_>>())
        .find(|v| v.len() == header.len() && v[r] == region)
        .ok_or("region_unavailable")?;
    if row[b] != build.build_key {
        return Err("build_changed_retry".into());
    }
    let cdn_config = object(&client, &cdns, "config", row[c], 1024 * 1024)?;
    if format!("{:x}", md5::compute(&cdn_config)) != row[c] {
        return Err("checksum_mismatch".into());
    }
    let cdn_config = config(&cdn_config)?;
    let archives: Vec<String> = cdn_config
        .get("archives")
        .map(|v| v.iter().map(|s| s.to_string()).collect())
        .unwrap_or_default();
    if archives.len() > 8192 || archives.iter().any(|v| !key(v)) {
        return Err("invalid_config".into());
    }
    let bytes = object(&client, &cdns, "config", &build.build_key, 1024 * 1024)?;
    if format!("{:x}", md5::compute(&bytes)) != build.build_key {
        return Err("checksum_mismatch".into());
    }
    let config = config(&bytes)?;
    let hashes = config
        .get("download")
        .filter(|v| v.len() == 2 && v.iter().all(|h| key(h)))
        .ok_or("download_manifest_unavailable")?;
    let sizes = config
        .get("download-size")
        .filter(|v| v.len() == 2)
        .ok_or("download_manifest_unavailable")?;
    let decoded_size = sizes[0].parse::<usize>().map_err(|_| "invalid_config")?;
    let encoded_size = sizes[1].parse::<usize>().map_err(|_| "invalid_config")?;
    if encoded_size > 128 * 1024 * 1024 || decoded_size > 128 * 1024 * 1024 {
        return Err("manifest_too_large".into());
    }
    let bytes = object(&client, &cdns, "data", hashes[1], encoded_size)?;
    if bytes.len() != encoded_size || bytes.len() < 8 {
        return Err("invalid_manifest".into());
    }
    let header = u32::from_be_bytes(bytes[4..8].try_into().unwrap()) as usize;
    let hashed = if header == 0 {
        bytes.as_slice()
    } else {
        bytes.get(..header).ok_or("invalid_blte")?
    };
    if !format!("{:x}", md5::compute(hashed)).eq_ignore_ascii_case(hashes[1]) {
        return Err("checksum_mismatch".into());
    }
    let decoded = blte::decode(&bytes, decoded_size)?;
    if decoded.len() != decoded_size
        || !format!("{:x}", md5::compute(&decoded)).eq_ignore_ascii_case(hashes[0])
    {
        return Err("checksum_mismatch".into());
    }
    Ok(Plan {
        build,
        manifest: DownloadManifest::parse(&decoded)?,
        cdns,
        archives,
    })
}
impl Plan {
    pub fn locate_archive(&self, encoding_key: &str, size: u64) -> Result<(String, u64), String> {
        if !key(encoding_key) {
            return Err("invalid_content_key".into());
        }
        let client = Client::builder()
            .https_only(true)
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(30))
            .build()
            .map_err(|_| "network_error")?;
        for archive in &self.archives {
            let mut parsed = None;
            for base in &self.cdns {
                let url = format!(
                    "{base}/data/{}/{}/{archive}.index",
                    &archive[..2],
                    &archive[2..4]
                );
                if let Ok(bytes) = fetch(&client, &url, 16 * 1024 * 1024) {
                    if let Ok(entries) = crate::archive::parse(&bytes, archive) {
                        parsed = Some(entries);
                        break;
                    }
                }
            }
            let entries = parsed.ok_or("archive_index_unavailable")?;
            if let Some(entry) = entries.iter().find(|entry| entry.key == encoding_key) {
                if entry.bytes != size {
                    return Err("content_size_mismatch".into());
                }
                return Ok((archive.clone(), entry.offset));
            }
        }
        Err("content_unavailable".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cdn_restricts_hosts_paths_and_transport() {
        let header = "Name|Path|Servers\n";
        for server in [
            "http://us.cdn.blizzard.com",
            "https://blizzard.com.evil.test",
            "https://127.0.0.1",
            "https://a:secret@us.cdn.blizzard.com",
            "https://us.cdn.blizzard.com:444",
        ] {
            assert!(cdn_urls(&format!("{header}us|tpr/test|{server}"), "us").is_err());
        }
        assert!(cdn_urls(
            &format!("{header}us|../test|https://us.cdn.blizzard.com"),
            "us"
        )
        .is_err());
        assert_eq!(
            cdn_urls(
                &format!("{header}us|tpr/test|https://us.cdn.blizzard.com/?fallback=1"),
                "us"
            )
            .unwrap(),
            vec!["https://us.cdn.blizzard.com/tpr/test"]
        );
    }
    #[test]
    fn rejects_duplicate_config_values() {
        assert!(config(b"download = a b\ndownload = c d").is_err());
    }
    #[test]
    fn rejects_invalid_input_without_network() {
        assert!(download_plan("../x", "us").is_err());
    }
}
