# Native Android Battle.net downloads

The Battle.net detail screen now requests an install preview from Rust and starts an Android foreground service for Download. It no longer routes Download into `prepareLaunch` or Wine. The service runs the Rust archive resolver, downloader, CASC writer and verifier through JNI on an IO worker, with a bounded native job registry and explicit pause/resume/cancel controls. The existing detail screen supplies the game artwork, size, navigation and action styling.

The preview identifies the Windows/enUS base-game selection and includes installation metadata in its size. Cache and installation files remain in the shared Battle.net directory. New builds use separate build-key directories; the installed record changes only after successful verification, preserving the previous build. The cache is reused and never cleared by Cancel. A resumed request retains its original build key and destination.

The Downloads tab displays native progress and individual controls. Global pause/resume/cancel controls also include the native job. Interrupted jobs are restored as resumable entries after process restart. Native installations expose Verify Files and update checks through the detail menu. Full authenticated launch and official-client registration are separate work; successful download does not establish gameplay compatibility.

Native installation profiles currently cover the WoW family. Other products display a translated availability message and do not fall back to downloading inside the container. The three new strings exist in all 23 resource locales.

Device testing found that Android grants search permission without directory-list permission on `/data` ancestors. The Rust path walk now uses `O_PATH` for ancestors and opens only the final directory for reading, while retaining `O_NOFOLLOW` and descriptor-relative access. A regression test covers search-only parent permissions.

Validation so far: 45 Rust tests, Clippy with warnings denied, 192 JVM tests, the native JNI device test suite and four Compose detail-screen tests. On the device, the preview displayed 21.07 GiB and the native service downloaded 3,057,754,084 bytes without Wine, Box64 or FEX processes. Pause held the cache steady; cancel while paused completed and left the full cache listing identical (SHA-256 `1989e1496c590ffa621083d47b9e44016a6f40838686a60270356be1ec84860c`). After an in-place upgrade, Resume reused the retained cache and continued downloading. The global Pause All control paused the native job at 4,509,319,006 bytes; Resume All resumed it. Controller shoulder navigation also reached the Downloads pane. The full host download and installation verification are documented separately.

All device app changes used signed, streamed, in-place upgrades (`adb install --no-incremental -r`). No uninstall or app-data clear was used. Existing files and store preferences were retained across the upgrades.

## Completed device installation

The device subsequently completed all 22,546,600,744 selected content bytes, packed 384,512 content/metadata objects, and passed final verification of all 384,508 selected content objects plus the 35 extracted files. The foreground service reached `stage=complete`, `done=true`, and published the installed record. Downloading continued while the app was in the background and the screen later locked.

An independent Android `md5sum` comparison of all 35 extracted files matched the host reference. Windows directory casing varied (`UTILS` versus `Utils`); comparison resolved names case-insensitively, as the runtime verifier does. No container was needed for any download, packing or verification stage.

Evidence is retained in `research/device-native-complete.json` and `research/device-native-reference-comparison.json`. The signed in-place upgrade is `artifacts/pubg-battlenet-native-download-final.apk`. The extra Verify Files menu interaction remains to be driven after the device is unlocked; the automatic verification backend completed successfully.
