# Battle.net integration research

## Existing store paths

WinNative's unified shell hosts the Steam, Epic, GOG and itch.io tabs. Their account and download implementations are independent; adding a tab does not supply a desktop client's authentication protocol. Battle.net installation and game startup currently use Blizzard's own Windows client and Agent inside the selected Wine container. Installed games become library shortcuts with `game_source=BATTLENET`; launching a shortcut routes through the client so it can supply each game's session and launch options.

## Shared installation

The canonical directory is `<imagefs>/.shared/battlenet`:

| Directory | Container links |
| --- | --- |
| `client` | Both Program Files variants' `Battle.net` |
| `programdata/Battle.net` | `C:\ProgramData\Battle.net` (including Agent/product.db) |
| `programdata/Blizzard Entertainment` | Same ProgramData directory |
| `appdata/roaming/Battle.net` | Each real Wine user's roaming Battle.net directory |
| `appdata/local/Battle.net` | Each real Wine user's local Battle.net directory |
| `appdata/local/Blizzard Entertainment` | Each real Wine user's local Blizzard directory |
| `games/<game>` | Known default game paths under both Program Files variants |
| `auth`, `setup`, `artwork`, session helper | Accessed under `C:\WinNative\Battle.net` |

Bindings adopt an existing installation only when the shared destination is empty. Conflicting data and foreign links cause an actionable error; they are not overwritten. Container deletion must unlink these paths without traversing them. Windows runtime files and the entire registry must not be shared between architectures.

The drive-C path passed to Wine is lexical: resolving a host symlink before mapping it would turn the path into a different Wine drive and break the shared installation's paths.

## Proton 11.0-2 components

Both packages were obtained from the [WinNative Components Proton release](https://github.com/nicholasx417/WinNative-Components/releases/tag/Proton).

| Package | SHA-256 |
| --- | --- |
| `Proton-11.0-2-x86-64-steam.wcp` | `5d5258675bf2282cf8c8ec36622b8a7af4909c8e75079692f0a0bd20f39e20e1` |
| `Proton-11.0-2-arm64ec-steam-unix.wcp` | `6de8fe5581c04d87b5ae4cef23d96de136090c696e3733201a92b1ce59e5163e` |

The profiles identify `11.0-2-x86_64-steam` and `11.0-2-arm64ec-steam-unixlib`, internal version code 3. These are Android/Bionic components, not ordinary desktop Linux Proton distributions: their ELF interpreter is `/system/bin/linker64`. The x86 package uses an x86-64 Wine host with Box64; the ARM package uses an AArch64 Wine host with ARM64EC and x86 translation through the configured FEXCore/WoWBox path. Both contain 32-bit Windows support. The supplied prefix uses the `xuser` user.

A successful desktop Wine test does not prove either Android translation path works. Both require device testing, including the launcher's 32-bit components, browser rendering, networking, and the selected game's graphics support.

## Protocol evidence

- [Lutris Battle.net documentation](https://github.com/lutris/docs/blob/master/Battle.Net.md) describes Wine dependencies and Linux troubleshooting.
- [Lutris service](https://github.com/lutris/lutris/blob/master/lutris/services/battlenet.py) supplies catalog metadata and native client install/launch commands; its catalog is not evidence of account ownership.
- [Battle.Net-Installer](https://github.com/barncastle/Battle.Net-Installer) demonstrates the native Agent's local HTTP install protocol. The port is discovered from the running process, and `/agent` supplies authorization. Installation uses `/install`, the returned product endpoint, and `/update`. Native account login is still required for account-bound content.
- [Galaxy integration](https://github.com/melcom-creations/galaxy-integration-battlenet) retrieves numeric or string `gameAccounts[].titleId` with website cookies from `https://account.battle.net/api/games-and-subs` and documents the `product.db` protobuf format. An unauthenticated request returned HTTP 401 during research. Public developer OAuth credentials do not by themselves establish the desktop client's session.
- [D2RLoader](https://github.com/sh4nks/d2rloader) demonstrates a game-specific browser SSO token and a DPAPI-protected `WEB_TOKEN`. Its Diablo II registry path is not evidence that the launcher uses the same path.
- [Wine's CryptProtectData implementation](https://github.com/wine-mirror/wine/blob/master/dlls/crypt32/protectdata.c) derives its encryption key using the Wine username, salt and optional entropy. Copying opaque credentials between arbitrary Wine users is not a portable authentication design.

The Agent database distinguishes installed, playable and update-complete states. Downloads can exceed 4 GiB. UI progress comes from the native database; throughput is measured from remaining-byte differences against monotonic time, resetting on a new product/total or discontinuity. A playable but incomplete installation remains an active download.

## Reverse engineering

Research binaries and Ghidra projects remain outside the repository. No account password or live token belongs in logs, commits, command-line diagnostics or the research report.

The official installer downloaded during research identifies itself as 1.19.3.3219. Running it in an isolated Wine prefix installed Agent 9775 and desktop client 2.52.11.17778. Ghidra 11.3.1 analyzed the installer and the desktop DLL's authentication functions. Addresses below belong to this specific desktop build and are evidence references, not runtime hooks.

| Function address | Observed behavior |
| --- | --- |
| `0x1103bf00` | Parses the `http://localhost:0` browser callback, including `ST`, `accountName`, `accountRegion`, and `rememberMe` |
| `0x103ee100`, `0x103e8930` | MurmurHash2 of the account name, seed zero, formatted as eight uppercase hexadecimal digits |
| `0x10248650`, `0x10272820` | Persists a DPAPI-protected token under `HKCU\Software\Blizzard Entertainment\Battle.net\UnifiedAuth` |
| `0x102488e0`, `0x10738550` | Reads and decrypts that token |
| `0x10248be0` | Deletes an invalidated token |
| `0x1049e680`, `0x1029b580` | Login-success persistence and cached login |

The token's DPAPI optional entropy is `c876f4ae4c952efef2fa0f5419c09c43`, with `CRYPTPROTECT_UI_FORBIDDEN`. The login page observed in the client log was `https://account.battle.net/login/en/login.app?app=app`. The Android WebView uses this flow and intercepts only its exact loopback callback from an HTTPS Battle.net page. It does not use a developer OAuth client, accept an arbitrary external intent, or pass credentials in process arguments.

A synthetic token was protected and inserted using this format. The unmodified desktop client consumed it, contacted Blizzard, reported `ERROR_TOKEN_NOT_FOUND` (49), and deleted it. This establishes local format interoperability; it is not evidence of a successful authenticated server session.

## Session lifecycle

1. The WebView completes Blizzard's login, including any account challenge Blizzard requires. The callback is parsed with bounded fields and duplicate/host checks.
2. Android saves the pending credential using encrypted preferences, then stages it in the app-private shared `auth` directory and updates the native client's saved-account configuration. The transient `pending.token` is owner-readable/writable and is removed once the native helper imports it. No account password is stored.
3. The 32-bit Windows helper protects the token with Wine DPAPI, imports it into the selected prefix's UnifiedAuth registry, and starts the real client. Subsequent launches import the shared protected `session.bin` instead.
4. The helper observes native token rotation and deletion while the client runs. A rotated token replaces the shared file atomically; invalidation removes it. Reauthentication is still required when Blizzard expires or revokes the session.
5. Client registry sections `Identity` and `EncryptionKey` travel in bounded private sidecars, allowing the shared client cache to be read from another prefix. Runtime DLLs and the rest of each prefix registry stay separate.
6. A per-Wine mutex and readiness event serialize native imports. Repeated launches forward commands to the running client. The Android session path prevents switching the shared installation between containers while a session runs.
7. Sign-out requires the Wine session to be closed, removes staged/protected credentials and per-container UnifiedAuth/game launch tokens, clears saved-account configuration, and expires Battle.net website cookies. Sign-in/sign-out mutations are serialized.

Cross-prefix DPAPI reuse was tested with separate Wine prefixes using the same Unix/Wine username. Both shipped Proton templates use `xuser`; arbitrary different usernames are not supported. Decryption failure produces a reconnect error instead of launching with an unreadable token. Actual ARM64EC/x86 Proton interoperability still requires Android runtime tests.

## Build and tests

Build the helper after changing its source:

```sh
app/src/main/cpp/wn-battlenet/build.sh
```

Run synthetic native integration tests with Wine, Wine32, MinGW i686, Python 3 and an X display (or Xvfb):

```sh
xvfb-run -a python3 app/src/main/cpp/wn-battlenet/tests/run.py
```

The runner creates and deletes its own temporary prefixes. It checks protected-token import, quoted/concurrent launches, rotation, cross-prefix session and cache-key reuse, invalidation, malformed credentials/state, and helper shutdown. It uses only an `.invalid` account and fake tokens.

Build the PUBG debug variant and run JVM tests:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testPubgDebugUnitTest :app:assemblePubgDebug
```

## Validation limits

All 182 JVM tests pass. Unit tests cover bounded product database parsing, truncated writes, 64-bit transfer sizes, throughput resets, shared-directory migration, conflicting data, foreign symlinks, deletion safety, and concurrent binding. The PUBG debug variant builds and installs on an Android emulator. The Battle.net tab and official Blizzard login page were visually checked. All 20 catalog entries have a verified artwork endpoint; banners are used where covers are unavailable. Completed native installs are imported while the shell is resumed, with background polling canceled when it pauses. Native synthetic session tests pass in desktop Wine. An Android WebView test dispatched a synthetic loopback callback from the official login page: the activity completed, account state updated, staged files had owner-only permissions, and sign-out removed them. This tested transport and storage, not server acceptance of a real credential. A real account has not been used: browser callback issuance, account-library enumeration, successful server authentication/renewal, authenticated downloads and game launch remain unverified. Both Android Proton architectures need device testing. Download start/pause/resume/cancel dialogs run in the native client; Android displays Agent database progress and estimated throughput. Only cataloged products are imported into the Android library. Anti-cheat and individual game compatibility are not established by launcher integration.
