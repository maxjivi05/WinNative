use std::{
    io::BufRead,
    path::Path,
    sync::{Arc, Mutex},
    time::Instant,
};
use wn_battlenet::{
    mirror,
    planning::download_plan,
    transfer::{Cache, Control},
};
fn run(args: &[String]) -> Result<(), String> {
    let control = Arc::new(Control::default());
    let commands = control.clone();
    std::thread::spawn(move || {
        for line in std::io::stdin().lock().lines().map_while(Result::ok) {
            match line.trim() {
                "pause" => {
                    commands.pause();
                    println!("{}", serde_json::json!({"state":"pause_requested"}));
                }
                "resume" => {
                    commands.resume();
                    println!("{}", serde_json::json!({"state":"resume_requested"}));
                }
                "cancel" => {
                    commands.cancel();
                    println!("{}", serde_json::json!({"state":"cancel_requested"}));
                }
                _ => {}
            }
        }
    });
    let plan = download_plan(&args[0], &args[1])?;
    let names: Vec<_> = args[3..]
        .iter()
        .filter(|v| !v.starts_with('!') && *v != "--plan")
        .cloned()
        .collect();
    let excluded: Vec<_> = args[3..]
        .iter()
        .filter_map(|v| v.strip_prefix('!').map(str::to_owned))
        .collect();
    let selection = plan.manifest.select_excluding(&names, &excluded)?;
    println!(
        "{}",
        serde_json::json!({"stage":"resolving","build":plan.build,"totalBytes":selection.encoded_bytes,"totalObjects":selection.entries.len(),"archives":plan.archives.len()})
    );
    let locations = mirror::resolve(&plan, &selection, &control, |n, total| {
        if n % 20 == 0 || n == total {
            println!(
                "{}",
                serde_json::json!({"stage":"resolving","resolvedArchives":n,"totalArchives":total})
            );
        }
    })?;
    let batches = mirror::batches(&selection.entries, &locations)?;
    println!(
        "{}",
        serde_json::json!({"stage":"planned","requests":batches.len(),"networkBytes":batches.iter().map(|b|b.bytes).sum::<u64>(),"contentBytes":selection.encoded_bytes,"looseObjects":locations.iter().filter(|l|l.is_none()).count()})
    );
    if args.iter().any(|v| v == "--plan") {
        return Ok(());
    }
    let cache = Cache::open(Path::new(&args[2]))?;
    let required = selection
        .encoded_bytes
        .checked_add(selection.entries.len() as u64 * 4096)
        .and_then(|v| v.checked_add(1024 * 1024 * 1024))
        .ok_or("insufficient_storage")?;
    if cache.available_bytes()? < required {
        return Err("insufficient_storage".into());
    }
    let progress = Mutex::new((Instant::now(), 0usize, 0u64));
    mirror::download(
        &plan,
        &selection,
        &locations,
        &cache,
        &control,
        |n, bytes| {
            let mut last = progress.lock().unwrap_or_else(|e| e.into_inner());
            last.1 = last.1.max(n);
            last.2 = last.2.max(bytes);
            if last.0.elapsed().as_secs() >= 1 || last.1 == selection.entries.len() {
                println!(
                    "{}",
                    serde_json::json!({"stage":"downloading","verifiedObjects":last.1,"downloadedBytes":last.2,"totalBytes":selection.encoded_bytes})
                );
                last.0 = Instant::now();
            }
        },
    )?;
    println!(
        "{}",
        serde_json::json!({"stage":"complete","verifiedObjects":selection.entries.len(),"verifiedBytes":selection.encoded_bytes,"build":plan.build})
    );
    Ok(())
}
fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.len() < 3 {
        eprintln!("Usage: battlenet-mirror PRODUCT REGION EXISTING_CACHE_DIRECTORY [TAG ...] [!EXCLUDED_TAG ...] [--plan]\nCommands on stdin: pause, resume, cancel");
        std::process::exit(2);
    }
    if let Err(error) = run(&args) {
        eprintln!("{error}");
        std::process::exit(1);
    }
}
