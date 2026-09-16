# Shared download folders and native registration

Battle.net now resolves the shared folder from Settings → Stores, or its own folder setting when shared downloads are disabled. The per-store picker uses the existing folder picker and pane navigation. Native game files live outside the Wine image; the private Rust cache and official-client session remain separate.

For completed legacy native installs, Play first dispatches a cache-backed installation into the selected folder. The old directory is retained. The installed record changes only after packing and full verification complete. On the device, the selected root resolves to `/storage/emulated/0/Documents/Games/PC`.

Before launching a verified native install, the runtime stages the game's product record, flavor marker and shared Windows path, then registers it in the Agent database without changing other products. An existing conflicting registration is rejected. The shared client is not opened while a native job is active. Client recognition still requires device validation.

Android shared storage returned ENOSYS for flock. The installer now falls back only for unsupported flock operations to an open-file-description write lock. A device probe confirmed that a second descriptor is excluded with EAGAIN and succeeds after the owning descriptor closes. The locking test also checks that closing the rejected descriptor does not release the owner's lock.

Validation: 193 JVM tests, 46 Rust tests, Clippy with warnings denied, and PUBG APK build passed. The first shared-storage attempt preserved the cache and stopped before packing at the unsupported flock operation. The replacement build is being tested against the same retained destination.

The corrected build completed packing and verification in the configured shared folder: 384,508 content objects verified, and the installed record now points to the shared target. The shared WowClassic.exe MD5 matches the independently verified reference (3ed82fba3fd426b46197eaa440dd75b4). The original private installation remains intact.
