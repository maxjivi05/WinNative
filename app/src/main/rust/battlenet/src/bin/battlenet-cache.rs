use std::{io::BufRead, path::Path, sync::Arc};
use wn_battlenet::{
    planning::download_plan,
    transfer::{Cache, Content, Control},
};
fn run(args: &[String]) -> Result<(), String> {
    let plan = download_plan(&args[0], &args[1])?;
    let entry = plan
        .manifest
        .entries
        .iter()
        .find(|entry| entry.encoding_key == args[3])
        .ok_or("unknown_content_key")?;
    let cache = Cache::open(Path::new(&args[2]))?;
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
    let mut last_error = "content_unavailable";
    for cdn in &plan.cdns {
        match cache.download(
            cdn,
            Content {
                key: &entry.encoding_key,
                size: entry.encoded_bytes,
                archive: None,
            },
            &control,
            |bytes| {
                println!(
                    "{}",
                    serde_json::json!({"downloadedBytes": bytes, "totalBytes": entry.encoded_bytes})
                );
            },
        ) {
            Ok(()) => {
                println!(
                    "{}",
                    serde_json::json!({"verified": true, "encodingKey": entry.encoding_key})
                );
                return Ok(());
            }
            Err("cancelled") => return Err("cancelled".into()),
            Err(error) => last_error = error,
        }
    }
    let (archive, offset) = plan.locate_archive(&entry.encoding_key, entry.encoded_bytes)?;
    for cdn in &plan.cdns {
        match cache.download(
            cdn,
            Content {
                key: &entry.encoding_key,
                size: entry.encoded_bytes,
                archive: Some((&archive, offset)),
            },
            &control,
            |bytes| {
                println!(
                    "{}",
                    serde_json::json!({"downloadedBytes": bytes, "totalBytes": entry.encoded_bytes})
                );
            },
        ) {
            Ok(()) => {
                println!(
                    "{}",
                    serde_json::json!({"verified": true, "encodingKey": entry.encoding_key})
                );
                return Ok(());
            }
            Err("cancelled") => return Err("cancelled".into()),
            Err(error) => last_error = error,
        }
    }
    Err(last_error.into())
}
fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.len() != 4 {
        eprintln!("Usage: battlenet-cache PRODUCT REGION EXISTING_CACHE_DIRECTORY ENCODING_KEY\nCommands on stdin: pause, resume, cancel");
        std::process::exit(2);
    }
    if let Err(code) = run(&args) {
        eprintln!("{code}");
        std::process::exit(1);
    }
}
