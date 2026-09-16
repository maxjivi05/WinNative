fn main() {
    let args: Vec<_> = std::env::args().collect();
    if args.len() != 3 {
        eprintln!("Usage: battlenet-metadata PRODUCT REGION");
        std::process::exit(2);
    }
    match wn_battlenet::latest_build(&args[1], &args[2]) {
        Ok(build) => println!(
            "{}",
            serde_json::to_string(&build).expect("serializable build")
        ),
        Err(code) => {
            eprintln!("{code}");
            std::process::exit(1);
        }
    }
}
