pub mod archive;
pub mod blte;
pub mod casc;
pub mod install;
mod jenkins;
pub mod manifest;
pub mod mirror;
pub mod packing;
pub mod planning;
mod safe_dir;
pub mod transfer;
use jni::{
    objects::{JClass, JString},
    sys::jstring,
    JNIEnv,
};
use serde::Serialize;
use std::{io::Read, time::Duration};

const MAX_METADATA: u64 = 1024 * 1024;

#[derive(Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Build {
    pub build_key: String,
    pub version: String,
    pub build_id: u64,
}

fn valid_product(product: &str) -> bool {
    !product.is_empty()
        && product.len() <= 64
        && product
            .bytes()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'_')
}

fn valid_region(region: &str) -> bool {
    matches!(region, "us" | "eu" | "kr" | "tw" | "cn")
}

pub fn parse_versions(input: &str, region: &str) -> Result<Build, String> {
    if input.len() as u64 > MAX_METADATA || !valid_region(region) {
        return Err("invalid_metadata".into());
    }
    let mut lines = input
        .lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'));
    let headers: Vec<&str> = lines
        .next()
        .ok_or("invalid_metadata")?
        .split('|')
        .map(|s| s.split('!').next().unwrap_or(""))
        .collect();
    let column = |name| {
        let indices: Vec<_> = headers
            .iter()
            .enumerate()
            .filter(|(_, h)| **h == name)
            .map(|(i, _)| i)
            .collect();
        if indices.len() != 1 {
            Err("invalid_metadata")
        } else {
            Ok(indices[0])
        }
    };
    let (r, key, name, id) = (
        column("Region")?,
        column("BuildConfig")?,
        column("VersionsName")?,
        column("BuildId")?,
    );
    let mut selected = None;
    for line in lines {
        let fields: Vec<_> = line.split('|').collect();
        if fields.len() != headers.len() {
            return Err("invalid_metadata".into());
        }
        if fields[r] != region {
            continue;
        }
        if selected.is_some()
            || fields[key].len() != 32
            || !fields[key].bytes().all(|b| b.is_ascii_hexdigit())
            || fields[name].is_empty()
            || fields[name].len() > 128
            || fields[name].chars().any(char::is_control)
        {
            return Err("invalid_metadata".into());
        }
        let build_id = fields[id].parse::<u64>().map_err(|_| "invalid_metadata")?;
        if build_id == 0 {
            return Err("invalid_metadata".into());
        }
        selected = Some(Build {
            build_key: fields[key].to_ascii_lowercase(),
            version: fields[name].into(),
            build_id,
        });
    }
    selected.ok_or_else(|| "region_unavailable".into())
}

pub fn latest_build(product: &str, region: &str) -> Result<Build, String> {
    if !valid_product(product) || !valid_region(region) {
        return Err("invalid_product".into());
    }
    let client = reqwest::blocking::Client::builder()
        .https_only(true)
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(30))
        .user_agent("WinNative-BattleNet/0.1")
        .build()
        .map_err(|_| "network_error")?;
    let response = client
        .get(format!("https://us.version.battle.net/{product}/versions"))
        .send()
        .map_err(|_| "network_error")?;
    if !response.status().is_success() {
        return Err("metadata_unavailable".into());
    }
    if response.content_length().is_some_and(|n| n > MAX_METADATA) {
        return Err("invalid_metadata".into());
    }
    let mut bytes = Vec::new();
    response
        .take(MAX_METADATA + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "network_error")?;
    if bytes.len() as u64 > MAX_METADATA {
        return Err("invalid_metadata".into());
    }
    parse_versions(
        std::str::from_utf8(&bytes).map_err(|_| "invalid_metadata")?,
        region,
    )
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_battlenet_BattleNetNative_latestBuild(
    mut env: JNIEnv,
    _class: JClass,
    product: JString,
    region: JString,
) -> jstring {
    let result =
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Build, String> {
            let product: String = env
                .get_string(&product)
                .map_err(|_| "invalid_product")?
                .into();
            let region: String = env
                .get_string(&region)
                .map_err(|_| "invalid_product")?
                .into();
            latest_build(&product, &region)
        }))
        .unwrap_or_else(|_| Err("native_error".into()));
    let json = match result {
        Ok(build) => serde_json::json!({"build": build}),
        Err(code) => serde_json::json!({"error": code}),
    };
    match env.new_string(json.to_string()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_feature_stores_battlenet_BattleNetNative_downloadPlan(
    mut env: JNIEnv,
    _class: JClass,
    product: JString,
    region: JString,
    tags: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<serde_json::Value, String> {
        let product: String = env.get_string(&product).map_err(|_| "invalid_product")?.into();
        let region: String = env.get_string(&region).map_err(|_| "invalid_product")?.into();
        let tags: String = env.get_string(&tags).map_err(|_| "invalid_tags")?.into();
        if tags.len() > 4096 { return Err("invalid_tags".into()); }
        let tags: Vec<String> = serde_json::from_str(&tags).map_err(|_| "invalid_tags")?;
        if tags.len() > 64 || tags.iter().any(|tag| tag.is_empty() || tag.len() > 256) { return Err("invalid_tags".into()); }
        let plan = planning::download_plan(&product, &region)?;
        let selection = plan.manifest.select(&tags)?;
        Ok(serde_json::json!({"build":plan.build,"availableTags":plan.manifest.tags,"selectedTags":tags,
            "encodedContentBytes":selection.encoded_bytes,"contentObjectCount":selection.entries.len()}))
    })).unwrap_or_else(|_| Err("native_error".into()));
    let value = match result {
        Ok(value) => value,
        Err(code) => serde_json::json!({"error":code}),
    };
    env.new_string(value.to_string())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;
    const HEADER: &str = "Region!STRING:0|BuildConfig!HEX:16|BuildId!DEC:4|VersionsName!STRING:0\n";
    const ROW: &str = "us|0123456789abcdef0123456789abcdef|7000000000|12.1.0\n";
    #[test]
    fn parses_large_ids_and_crlf() {
        let input = format!("{HEADER}## seqn = 1\n{ROW}").replace('\n', "\r\n");
        assert_eq!(parse_versions(&input, "us").unwrap().build_id, 7000000000);
    }
    #[test]
    fn rejects_duplicate_regions() {
        assert!(parse_versions(&format!("{HEADER}{ROW}{ROW}"), "us").is_err());
    }
    #[test]
    fn rejects_missing_region() {
        assert!(parse_versions(&format!("{HEADER}{ROW}"), "eu").is_err());
    }
    #[test]
    fn rejects_truncated_rows() {
        assert!(parse_versions(&format!("{HEADER}us|abc"), "us").is_err());
    }
    #[test]
    fn rejects_bad_hashes() {
        assert!(parse_versions(
            &format!("{HEADER}{ROW}").replace("0123456789abcdef", "xxxxxxxxxxxxxxxx"),
            "us"
        )
        .is_err());
    }
    #[test]
    fn rejects_duplicate_columns() {
        assert!(parse_versions(
            &format!("{HEADER}{ROW}").replace("BuildId!DEC:4", "Region!STRING:0"),
            "us"
        )
        .is_err());
    }
    #[test]
    fn rejects_oversized_input() {
        assert!(parse_versions(&"x".repeat(MAX_METADATA as usize + 1), "us").is_err());
    }
    #[test]
    fn rejects_url_injection_before_network() {
        for product in [
            "../agent",
            "wow?test=1",
            "wow/versions",
            "",
            "WoW",
            "wow\n",
            "é",
        ] {
            assert_eq!(latest_build(product, "us").unwrap_err(), "invalid_product");
        }
    }
    #[test]
    fn rejects_invalid_region_before_network() {
        assert_eq!(latest_build("wow", "../us").unwrap_err(), "invalid_product");
    }
}
