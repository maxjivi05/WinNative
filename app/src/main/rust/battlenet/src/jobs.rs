use crate::{
    casc, install,
    manifest::Selection,
    mirror, packing, planning,
    transfer::{Cache, Control},
};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    path::Path,
    sync::{
        atomic::{AtomicBool, AtomicI64, Ordering},
        Arc, Mutex, OnceLock,
    },
};

struct Job {
    control: Control,
    started: AtomicBool,
    status: Mutex<Value>,
}
static JOBS: OnceLock<Mutex<HashMap<i64, Arc<Job>>>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);
fn jobs() -> &'static Mutex<HashMap<i64, Arc<Job>>> {
    JOBS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn job(id: i64) -> Result<Arc<Job>, String> {
    jobs()
        .lock()
        .map_err(|_| "job_state_error")?
        .get(&id)
        .cloned()
        .ok_or("job_missing".into())
}
pub fn create() -> i64 {
    let Ok(mut jobs) = jobs().lock() else {
        return 0;
    };
    if !jobs.is_empty() {
        return 0;
    }
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    jobs.insert(
        id,
        Arc::new(Job {
            control: Control::default(),
            started: AtomicBool::new(false),
            status: Mutex::new(json!({"stage":"preparing","done":false,"paused":false})),
        }),
    );
    id
}
pub fn status(id: i64) -> Value {
    job(id)
        .and_then(|j| {
            j.status
                .lock()
                .map(|s| s.clone())
                .map_err(|_| "job_state_error".into())
        })
        .unwrap_or_else(|e| json!({"error":e,"done":true}))
}
pub fn command(id: i64, command: &str) -> bool {
    let Ok(j) = job(id) else { return false };
    let Ok(mut s) = j.status.lock() else {
        return false;
    };
    if s["done"] == true {
        return false;
    }
    match command {
        "pause" => {
            j.control.pause();
            s["paused"] = json!(true);
        }
        "resume" => {
            j.control.resume();
            s["paused"] = json!(false);
        }
        "cancel" => {
            j.control.cancel();
            s["paused"] = json!(false);
            s["cancelling"] = json!(true);
        }
        _ => return false,
    }
    true
}
pub fn release(id: i64) {
    if let Ok(mut jobs) = jobs().lock() {
        if jobs.get(&id).is_some_and(|j| {
            !j.started.load(Ordering::Acquire) || j.status.lock().is_ok_and(|s| s["done"] == true)
        }) {
            jobs.remove(&id);
        }
    }
}
fn subdir(product: &str) -> Result<&'static str, String> {
    match product {
        "wow" => Ok("_retail_"),
        "wow_classic" => Ok("_classic_"),
        "wow_classic_era" => Ok("_classic_era_"),
        _ => Err("unsupported_native_product".into()),
    }
}
fn selection(plan: &planning::Plan) -> Result<Selection, String> {
    subdir(&plan.product)?;
    let has = |name: &str| plan.manifest.tags.iter().any(|t| t.name == name);
    if !has("Windows") || !has("enUS") {
        return Err("unsupported_native_selection".into());
    }
    let names = ["Windows", "x86_64", "enUS", "US", "speech", "text"]
        .into_iter()
        .filter(|n| has(n))
        .map(str::to_owned)
        .collect::<Vec<_>>();
    let excluded = ["HighRes", "Alternate", "Talebound"]
        .into_iter()
        .filter(|n| has(n))
        .map(str::to_owned)
        .collect::<Vec<_>>();
    plan.manifest
        .select_excluding(&names, &excluded)
        .map_err(str::to_owned)
}
pub fn preview(product: &str, region: &str) -> Result<Value, String> {
    subdir(product)?;
    let plan = planning::download_plan(product, region)?;
    let s = selection(&plan)?;
    let metadata = plan
        .metadata
        .values()
        .filter(|m| !s.entries.iter().any(|e| e.encoding_key == m.encoding_key))
        .map(|m| m.encoded_bytes as u64)
        .sum::<u64>();
    let bytes = s
        .encoded_bytes
        .checked_add(metadata)
        .ok_or("invalid_manifest")?;
    Ok(
        json!({"buildKey":plan.build.build_key,"version":plan.build.version,"downloadBytes":bytes,"requiredBytes":bytes.saturating_mul(2).saturating_add(s.entries.len() as u64*4096).saturating_add(1024*1024*1024),"locale":"enUS","highResolution":false}),
    )
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Request {
    product: String,
    region: String,
    cache: String,
    target: String,
    build_key: String,
    action: String,
}
impl Job {
    fn progress(&self, stage: &str, current: u64, total: u64) {
        if let Ok(mut s) = self.status.lock() {
            if s["stage"] != stage {
                s["current"] = json!(0);
            }
            s["stage"] = json!(stage);
            s["current"] = json!(s["current"].as_u64().unwrap_or(0).max(current));
            s["total"] = json!(total);
        }
    }
}
fn execute(j: &Job, r: Request) -> Result<(), String> {
    j.control.checkpoint()?;
    let subdirectory = subdir(&r.product)?;
    let root = Path::new(&r.target);
    let plan = match r.action.as_str() {
        "download" => planning::download_plan(&r.product, &r.region)?,
        "verify" => planning::installed_plan(root, &r.product)?,
        _ => return Err("invalid_job_action".into()),
    };
    if plan.build.build_key != r.build_key || plan.region != r.region {
        return Err("build_changed_retry".into());
    }
    let selected = selection(&plan)?;
    if r.action == "download" {
        let cache = Cache::open(Path::new(&r.cache))?;
        let required = selected
            .encoded_bytes
            .saturating_mul(2)
            .saturating_add(selected.entries.len() as u64 * 4096)
            .saturating_add(1024 * 1024 * 1024);
        if cache.available_bytes()? < required {
            return Err("insufficient_storage".into());
        }
        let locations = mirror::resolve(&plan, &selected, &j.control, |n, t| {
            j.progress("resolving", n as u64, t as u64)
        })?;
        mirror::download(&plan, &selected, &locations, &cache, &j.control, |_, b| {
            j.progress("downloading", b, selected.encoded_bytes)
        })?;
        packing::pack(
            &plan,
            &selected,
            &cache,
            root,
            subdirectory,
            &j.control,
            |n, t| j.progress("installing", n as u64, t as u64),
        )?;
    }
    j.control.checkpoint()?;
    let storage = casc::Storage::open(&root.join("Data/data"))?;
    storage.verify(&selected, &j.control, |n, _| {
        j.progress("verifying", n as u64, selected.entries.len() as u64)
    })?;
    let manifest = install::InstallManifest::parse(
        &storage.manifest(
            plan.metadata
                .get("install")
                .ok_or("install_manifest_unavailable")?,
        )?,
    )?;
    let files = manifest.select(&selected.selected_tags, &selected.excluded_tags)?;
    casc::verify_install_files(&root.join(subdirectory), &files, &j.control)?;
    Ok(())
}
pub fn run(id: i64, request: &str) -> Value {
    let Ok(j) = job(id) else {
        return json!({"error":"job_missing","done":true});
    };
    if j.started.swap(true, Ordering::AcqRel) {
        return json!({"error":"job_busy"});
    }
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if request.len() > 16384 {
            return Err("invalid_job".into());
        }
        let r = serde_json::from_str(request).map_err(|_| "invalid_job")?;
        execute(&j, r)
    }))
    .unwrap_or_else(|_| Err("native_error".into()));
    let mut s = j.status.lock().unwrap_or_else(|e| e.into_inner());
    s["done"] = json!(true);
    s["paused"] = json!(false);
    match result {
        Ok(()) => s["stage"] = json!("complete"),
        Err(e) => {
            s["stage"] = json!(if e == "cancelled" {
                "cancelled"
            } else {
                "failed"
            });
            s["error"] = json!(e);
        }
    }
    s.clone()
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn job_controls_and_release_preserve_exclusive_ownership() {
        let id = create();
        assert!(id > 0);
        assert_eq!(create(), 0);
        assert!(command(id, "pause"));
        assert_eq!(status(id)["paused"], true);
        assert!(command(id, "resume"));
        assert_eq!(status(id)["paused"], false);
        assert!(command(id, "cancel"));
        let result = run(id, "{}");
        assert_eq!(result["done"], true);
        assert!(!command(id, "resume"));
        release(id);
        let next = create();
        assert!(next > id);
        release(next);
    }
}
