fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.len() < 2 {
        eprintln!("Usage: battlenet-plan PRODUCT REGION [TAG ...]");
        std::process::exit(2);
    }
    let result = wn_battlenet::planning::download_plan(&args[0], &args[1]).and_then(|plan| {
        let selection = plan.manifest.select(&args[2..])?;
        Ok(
            serde_json::json!({"build": plan.build, "availableTags": plan.manifest.tags,
            "selectedTags": selection.selected_tags, "encodedContentBytes": selection.encoded_bytes,
            "contentObjectCount": selection.entries.len(),
            "smallestObject": selection.entries.iter().min_by_key(|entry| entry.encoded_bytes),
            "sampleObject": selection.entries.iter().filter(|entry| entry.encoded_bytes >= 65536).min_by_key(|entry| entry.encoded_bytes)}),
        )
    });
    match result {
        Ok(value) => println!("{value}"),
        Err(code) => {
            eprintln!("{code}");
            std::process::exit(1);
        }
    }
}
