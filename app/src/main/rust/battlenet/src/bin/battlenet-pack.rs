use std::{io::BufRead, path::Path, sync::Arc, time::Instant};
use wn_battlenet::{
    planning::download_plan,
    transfer::{Cache, Control},
};
fn run(args: &[String]) -> Result<(), String> {
    let control = Arc::new(Control::default());
    let commands = control.clone();
    std::thread::spawn(move || {
        for line in std::io::stdin().lock().lines().map_while(Result::ok) {
            match line.trim() {
                "pause" => commands.pause(),
                "resume" => commands.resume(),
                "cancel" => commands.cancel(),
                _ => {}
            }
        }
    });
    let plan = download_plan(&args[0], &args[1])?;
    if plan.build.build_key != args[5] {
        return Err("build_changed_retry".into());
    }
    let names: Vec<_> = args[6..]
        .iter()
        .filter(|s| !s.starts_with('!'))
        .cloned()
        .collect();
    let excluded: Vec<_> = args[6..]
        .iter()
        .filter_map(|s| s.strip_prefix('!').map(str::to_owned))
        .collect();
    let selection = plan.manifest.select_excluding(&names, &excluded)?;
    let cache = Cache::open(Path::new(&args[2]))?;
    let mut last = Instant::now();
    wn_battlenet::packing::pack(
        &plan,
        &selection,
        &cache,
        Path::new(&args[3]),
        &args[4],
        &control,
        |n, total| {
            if last.elapsed().as_secs() >= 1 || n == total {
                println!(
                    "{}",
                    serde_json::json!({"stage":"packing","objects":n,"total":total})
                );
                last = Instant::now();
            }
        },
    )?;
    let storage = wn_battlenet::casc::Storage::open(&Path::new(&args[3]).join("Data/data"))?;
    storage.verify(&selection, &control, |n, _| {
        if n % 10000 == 0 {
            println!(
                "{}",
                serde_json::json!({"stage":"verifying","objects":n,"total":selection.entries.len()})
            );
        }
    })?;
    let install = wn_battlenet::install::InstallManifest::parse(
        &storage.manifest(
            plan.metadata
                .get("install")
                .ok_or("install_manifest_unavailable")?,
        )?,
    )?;
    let files = install.select(&names, &excluded)?;
    wn_battlenet::casc::verify_install_files(
        &Path::new(&args[3]).join(&args[4]),
        &files,
        &control,
    )?;
    println!(
        "{}",
        serde_json::json!({"stage":"complete","verified":true,"contentObjects":selection.entries.len(),"installedFiles":files.len(),"build":plan.build})
    );
    Ok(())
}
fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.len() < 6 {
        eprintln!("Usage: battlenet-pack PRODUCT REGION CACHE_DIRECTORY TARGET_DIRECTORY GAME_SUBDIRECTORY BUILD_KEY [TAG ...] [!EXCLUDED_TAG ...]");
        std::process::exit(2);
    }
    if let Err(error) = run(&args) {
        eprintln!("{error}");
        std::process::exit(1);
    }
}
