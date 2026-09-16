# WoW Classic reference installation and Rust verification

Blizzard Agent 9775 installed progression Classic 5.5.4.69585 in the isolated host Wine prefix. The install used Windows, x86_64, enUS and the base game without high-resolution textures. No existing game directory was removed. The Agent reported `download_complete=true`, `installed=true`, `playable=true`, and zero remaining download bytes.

The Agent transfer total was 22,668,685,063 bytes. The selected download-manifest content total was 22,546,600,744 bytes. These are different measurements: the manifest selection excludes installation metadata and auxiliary work. An unfiltered optional-content selection incorrectly included 81.3 GB; explicit exclusions for HighRes, Alternate and Talebound produce the base-game selection.

The new read-only Rust verifier independently checked all 384,508 selected content objects and all 35 selected installed files. It validates BLTE header and chunk hashes in CASC archives, authenticated install/encoding manifests, and the decoded content hashes of extracted files. A successful download is not inferred from the Agent's percentage alone.

CASC indices can contain allocation markers, zero-sized entries and two references to the same content (with and without its 30-byte storage header). Install manifests can contain different platform variants at the same relative path. Selection precedes path-conflict checks. Windows filename casing is resolved without following filesystem symlinks; ambiguous names fail verification.

Validation: 43 Rust tests, including malformed manifests, corrupted data, symlink substitution, install-path traversal, platform selection, range responses, per-object concurrency and interrupted writes. Clippy passed with warnings denied. The ARM64 JNI library and PUBG debug build passed, along with 192 JVM tests. Test directories and downloaded files were retained.

## Independent Rust download and installation

The Rust transfer engine subsequently downloaded all 384,508 selected objects itself from the CDN and verified all 22,546,600,744 encoded bytes. It resolved 1,363 authenticated archive indices and coalesced content into 18,634 requests, including 727 loose objects. Range responses are checked before accepting bytes. Eight workers use per-object claims and a process-level cache lock.

Live pause, cancel and resume tests preserved the cache. A paused snapshot remained unchanged; cancellation while paused woke and stopped the workers; restarting reused complete files and appended to the matching partial file. No downloaded folder was removed.

The Rust writer packed the cache and four metadata objects into a separate CASC installation, extracted 35 files, generated local indices and build configuration, and then verified the full result. CascLib independently opened the Rust-written installation and fully decoded ENCODING (50,270,006 bytes), ROOT (23,118,892), INSTALL (24,114), and DOWNLOAD (20,174,549). All 35 extracted files matched the Agent reference byte-for-byte by MD5.

The writer has an append-only journal, checks existing content before reuse, preserves incomplete archive tails, validates index checksums, rejects linked destinations and conflicting files, and publishes build information after packing. Interrupted writes resume only when existing bytes match the intended content. The verifier now reads the installed build and authenticated manifests locally, so verification does not depend on the latest CDN build.

The host artifacts are retained under the task's `research` directory: `wow-rust-content`, `wow-rust-installed`, and the separate `wine-client/drive_c/WinNativeTest/World of Warcraft` reference. Evidence is in `wow-rust-mirror-parallel.log`, `wow-rust-pack.log`, `casc-rust-install-check.log`, and `wow-rust-reference-comparison.json`.

Remaining: Android foreground download/service controls and UI integration, official-client registration of a Rust installation, authenticated game launch, device rendering and end-to-end device installation. The writer currently supports resumable installation of the same build and selection; cross-build updates and repair of conflicting extracted files are not yet implemented. A validated CASC installation is not proof of authenticated gameplay.

The device's PUBG package disappeared during this run after successful session-persistence tests. Device installation and UI testing were paused pending clarification; the existing host installation remains available.

Format references: the locally inspected [CascLib](https://github.com/ladislav-zezula/CascLib) and [TACTLib](https://github.com/overtools/TACTLib) implementations, plus live Agent manifests and CASC files. Ghidra inspection identified the `paused` handler at `004320dd` as a backfill-operation handler; sending that field to the update endpoint did not pause the live transfer, so that behavior is not exposed as a working control.
