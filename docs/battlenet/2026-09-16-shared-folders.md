# Shared download folders and native registration

Battle.net now resolves the shared folder from Settings → Stores, or its own folder setting when shared downloads are disabled. The per-store picker uses the existing folder picker and pane navigation. Native game files live outside the Wine image; the private Rust cache and official-client session remain separate.

For completed legacy native installs, Play first dispatches a cache-backed installation into the selected folder. The old directory is retained. The installed record changes only after packing and full verification complete. On the device, the selected root resolves to `/storage/emulated/0/Documents/Games/PC`.

Before launching a verified native install, the runtime stages the game's product record, flavor marker and shared Windows path, then registers it in the Agent database without changing other products. An existing conflicting registration is rejected. The shared client is not opened while a native job is active. Client recognition still requires device validation.

Android shared storage returned ENOSYS for flock. The installer now falls back only for unsupported flock operations to an open-file-description write lock. A device probe confirmed that a second descriptor is excluded with EAGAIN and succeeds after the owning descriptor closes. The locking test also checks that closing the rejected descriptor does not release the owner's lock.

Validation: 193 JVM tests, 46 Rust tests, Clippy with warnings denied, and PUBG APK build passed. The first shared-storage attempt preserved the cache and stopped before packing at the unsupported flock operation. The replacement build is being tested against the same retained destination.

The corrected build completed packing and verification in the configured shared folder: 384,508 content objects verified, and the installed record now points to the shared target. The shared WowClassic.exe MD5 matches the independently verified reference (3ed82fba3fd426b46197eaa440dd75b4). The original private installation remains intact.

Battle.net downloads now use DownloadItemDeck in the common queue, below Pause/Cancel/Clear, including catalog artwork, byte totals, phase, progress, selection and pane controls. Device screenshots show the native WoW row alongside Steam's Cyberpunk row. Native verification completed, Pause stopped at 71%, and selected Resume continued verification. Clear retained the installed record. A cancellation during hashing exposed a decorated cancellation error; the verifier now preserves the cancellation classification.

The Agent rejects symlinked Windows game and client directories during permission checks. The runtime now registers dedicated container drives for the shared game folder and shared client, preserving existing directories and links. Registration accepts Blizzard's slash-normalized Windows paths. Launch recognition is still under device validation.

Current checks: 195 JVM tests, 46 Rust tests, Clippy with warnings denied, and the PUBG APK build passed. Upgrades use adb install -r and preserve application data.

Final device check on pubg-battlenet-unified-downloads.apk: cancelling paused verification at 379,968/384,508 returned stage=cancelled and retained installed_wow_classic. Container Exit returned to the library with no Wine processes left. The shared client launched from H:/client, but requested sign-in; Agent also reported client permission and Agent install-handler errors. Authenticated game launch and recognition remain unresolved. No uninstall, data clear, or game/cache deletion was performed.
