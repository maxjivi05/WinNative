use wn_battlenet::{casc::Storage, planning::installed_plan, transfer::Control};
fn run(args: &[String]) -> Result<(), String> {
    let root = std::path::Path::new(&args[2])
        .parent()
        .and_then(|p| p.parent())
        .ok_or("invalid_install_path")?;
    let plan = installed_plan(root, &args[0])?;
    if plan.region != args[1] {
        return Err("region_unavailable".into());
    }
    let names: Vec<_> = args[4..]
        .iter()
        .filter(|s| !s.starts_with('!'))
        .cloned()
        .collect();
    let excluded: Vec<_> = args[4..]
        .iter()
        .filter_map(|s| s.strip_prefix('!').map(str::to_owned))
        .collect();
    let selection = plan.manifest.select_excluding(&names, &excluded)?;
    let storage = Storage::open(std::path::Path::new(&args[2]))?;
    let install = wn_battlenet::install::InstallManifest::parse(
        &storage.manifest(
            plan.metadata
                .get("install")
                .ok_or("install_manifest_unavailable")?,
        )?,
    )?;
    let files = install.select(&names, &excluded)?;
    let control = Control::default();
    wn_battlenet::casc::verify_install_files(std::path::Path::new(&args[3]), &files, &control)?;
    let encoding = storage.manifest(
        plan.metadata
            .get("encoding")
            .ok_or("encoding_manifest_unavailable")?,
    )?;
    let external = wn_battlenet::install::encodings(&encoding, &files)?
        .into_keys()
        .collect();
    storage.verify_with_files(&selection,&control,&external,|count,bytes| { if count % 1000 == 0 { println!("{}",serde_json::json!({"verifiedObjects":count,"verifiedBytes":bytes,"totalObjects":selection.entries.len()})); } })?;
    println!(
        "{}",
        serde_json::json!({"verified":true,"verifiedObjects":selection.entries.len(),"verifiedSelectedContentBytes":selection.encoded_bytes,"verifiedInstalledFiles":files.len(),"build":plan.build})
    );
    Ok(())
}
fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.len() < 4 {
        eprintln!("Usage: battlenet-verify PRODUCT REGION CASC_DATA_DIRECTORY INSTALL_FILES_DIRECTORY [TAG ...] [!EXCLUDED_TAG ...]");
        std::process::exit(2);
    }
    if let Err(error) = run(&args) {
        eprintln!("{error}");
        std::process::exit(1);
    }
}
