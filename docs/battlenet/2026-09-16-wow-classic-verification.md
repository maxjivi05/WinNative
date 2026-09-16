# WoW Classic reference installation and Rust verification

Blizzard Agent 9775 installed progression Classic 5.5.4.69585 in the isolated host Wine prefix. The install used Windows, x86_64, enUS and the base game without high-resolution textures. No existing game directory was removed. The Agent reported `download_complete=true`, `installed=true`, `playable=true`, and zero remaining download bytes.

The Agent transfer total was 22,668,685,063 bytes. The selected download-manifest content total was 22,546,600,744 bytes. These are different measurements: the manifest selection excludes installation metadata and auxiliary work. An unfiltered optional-content selection incorrectly included 81.3 GB; explicit exclusions for HighRes, Alternate and Talebound produce the base-game selection.

The new read-only Rust verifier independently checked all 384,508 selected content objects and all 35 selected installed files. It validates BLTE header and chunk hashes in CASC archives, authenticated install/encoding manifests, and the decoded content hashes of extracted files. A successful download is not inferred from the Agent's percentage alone.

CASC indices can contain allocation markers, zero-sized entries and two references to the same content (with and without its 30-byte storage header). Install manifests can contain different platform variants at the same relative path. Selection precedes path-conflict checks. Windows filename casing is resolved without following filesystem symlinks; ambiguous names fail verification.

Validation: 35 Rust tests, including malformed manifests, corrupted data, symlink substitution, install-path traversal and platform selection. The live full-install verification passed. Test directories and downloaded files were retained.

Remaining: the full reference download was performed by Blizzard's Agent, not the Rust transfer engine. Android download/service controls, CASC installation writing, authenticated game launch, device rendering and end-to-end device installation still need validation. The Rust command currently verifies the current CDN build and requires the matching installed build; it is not an offline repair command.

The device's PUBG package disappeared during this run after successful session-persistence tests. Device installation and UI testing were paused pending clarification; the existing host installation remains available.

Format references: the locally inspected [CascLib](https://github.com/ladislav-zezula/CascLib) and [TACTLib](https://github.com/overtools/TACTLib) implementations, plus live Agent manifests and CASC files. Ghidra inspection identified the `paused` handler at `004320dd` as a backfill-operation handler; sending that field to the update endpoint did not pause the live transfer, so that behavior is not exposed as a working control.
