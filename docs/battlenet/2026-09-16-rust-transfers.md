# Library session and Rust transfer continuation

The device at `192.168.50.53:44609` returned HTTP 401 from `/api/games-and-subs` despite having cookies and a saved desktop credential. This was an authorization failure, not an empty owned-game list. The login flow now connects the account website session after saving the desktop credential. Reconnect can establish that website session without replacing the existing desktop token. A failed refresh no longer displays the empty-library message. The opt-in device diagnostic emits HTTP status, JSON field names and product title IDs; it does not emit credentials or account identifiers.

## Rust implementation

- BLTE raw/zlib decoding validates chunk checksums, declared lengths and output limits. Encrypted chunks fail explicitly when a key is needed.
- Download-manifest versions 1–3 support 40-bit sizes, tag masks, same-group unions, cross-group intersections, duplicate-content elimination and conflicting-size rejection.
- Public HTTPS version/CDN/build metadata is bounded and pinned to one build. Build configurations, encoded manifest headers, decoded manifests and archive-index footer/TOC/page hash chains are checked.
- Archive indexes resolve content keys to archive byte ranges. Downloads support loose objects and archived objects, strict HTTP range validation, progress callbacks, cooperative pause/resume, terminal cancellation and restart from preserved partial files.
- File operations walk existing absolute directories through descriptors with `O_NOFOLLOW`. A process lock and an in-process mutex exclude concurrent cache writers. Symlinks, non-regular files and hardlinks are rejected. Cancellation preserves bytes; this subsystem contains no unlink, directory-removal or truncation operation.
- Completed objects are checksum-verified again when reused. Framed BLTE verification checks every encoded chunk, including encrypted chunks, without claiming to decrypt them.
- JNI exposes the selected-content planner; the CLI exposes planning and single-object cache transfers.

These are content-transfer components, not a finished installable game client. The Android store still delegates installation and game launch to the official client. The encoded-content total excludes container/index overhead and additional installation metadata, and has not been reconciled against an authenticated Agent's selected installation. It is therefore not substituted for the final game-download size in the UI.

## Validation

28 Rust tests cover decoding, bounds, checksums, selection, archive indexes, exact HTTP resume, ignored/wrong ranges, cancellation during transfer, preserved partial bytes, symlinks, hardlinks and concurrent cache owners. Test fixtures are retained in temporary directories instead of being deleted.

A live StarCraft build `1.23.10.13515` plan selecting `Windows x86_64 enUS Release noigr` contained 14,784 unique objects and 5,747,611,241 encoded bytes. Live 9-byte and 65,541-byte content objects were downloaded and checksum-verified, including archive lookup. These small tests do not establish full-game installation or launch compatibility.

The PUBG variant builds and all 189 JVM tests pass. Four native instrumentation tests pass on the connected ARM64 device, including live version lookup and a live selected-content plan. The rebuilt JNI library retains 16 KiB ELF segment alignment. The APK was signed with the existing local WinNative key and installed as an update without clearing application data.

## Commands

```sh
cargo test --locked --manifest-path app/src/main/rust/battlenet/Cargo.toml
cargo clippy --locked --manifest-path app/src/main/rust/battlenet/Cargo.toml --all-targets -- -D warnings
cargo run --locked --manifest-path app/src/main/rust/battlenet/Cargo.toml --bin battlenet-plan -- s1 us Windows x86_64 enUS Release noigr
cargo run --locked --manifest-path app/src/main/rust/battlenet/Cargo.toml --bin battlenet-cache -- s1 us /absolute/existing/cache ENCODING_KEY
```

The cache command accepts `pause`, `resume` and `cancel` on stdin. Cancellation is observed between bounded network reads; an in-flight blocking request can take up to its timeout to return. Archive-index discovery currently precedes the archived transfer and does not expose progress/control callbacks. A failed checksum preserves the object and reports an error; automatic repair/replacement is not implemented.

## Research and remaining integration

Protocol layouts were checked against [CascLib](https://github.com/ladislav-zezula/CascLib) and [TACTLib](https://github.com/overtools/TACTLib); the implementation here is Rust. Ghidra analysis of Agent 9775 also identified `paused` in handler `0x004320dd`, with response references in `0x00439c15` and `0x0043161d`. This is evidence for further control-request validation, not proof of tested Agent pause behavior.

Still required: persistent Android foreground-service ownership and UI controls, complete CASC installation/index writing or an authenticated Agent bridge, install-manifest extraction, entitlement/encryption handling, update/repair transactions, reconciled download and disk requirements, and authenticated game-launch tests on both Proton architectures. No game launch or full-game transfer is marked validated.
