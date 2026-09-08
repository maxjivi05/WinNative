# EA and Ubisoft support in WinNative — research, on-device reproduction, and plan

Branch: `feature/ea-sports-ubisoft` (off `origin/main` @ `8ed4ff80`). Device: OnePlus Pad 3 (OPD2403), Ludashi variant `com.ludashi.benchmark`, Android 16. Nothing committed.

## TL;DR

Two independent problems, and they are not the same size.

1. **Launching a Ubisoft/EA game bought on Steam or Epic** is mostly a Wine/launcher problem, and it is close. The third-party client (Ubisoft Connect / EA app) is downloaded and run by the game's own `installscript.vdf`, which WinNative's agent already executes. Ubisoft Connect installs cleanly today on both the x86_64 (box64) and arm64ec (FEX) layers with no changes. The remaining blocker is CEF (the Chromium login UI) rendering under emulation.
2. **Adding EA / Ubisoft as first-class *stores*** (sign in, browse, download from EA/Ubisoft directly) is a large, separate build: a new store module comparable to the Epic one, plus a client for EA's or Ubisoft's private download API. Feasible but multi-week, ToS-adjacent, and not required for "play the games I own on Steam".

Recommendation: ship the Steam/Epic → Ubisoft/EA launch path first (it reuses everything that already exists), and treat "EA as a storefront" as a later, optional phase.

## What already exists in the app (no work needed)

- **installscript.vdf is executed** by the in-prefix agent: `app/src/main/cpp/wn-steam-launcher/src/main.cpp` `run_install_scripts()` (~2205) runs each `Run Process` block, honours `HasRunKey`/`HasRunStringKey`, expands `%INSTALLDIR%`, and `scan_and_install_redists()` handles `_CommonRedist`. This is exactly the mechanism that lays down Ubisoft Connect / the EA app on a real PC.
- **Shared depots** (`depotfromapp`/`sharedinstall`) are parsed and downloaded (`SteamServiceDepot.kt`, `KeyValueUtils.kt:101`). The EA app installer (app 3340990) and Ubisoft Connect client (app 1716750) ship as shared depots of the games, so they come down with the game.
- **FEX per-exe tuning** already lists `UplayWebCore.exe` (`FEXCoreManager.java:22`) with the same JIT flags GameHub uses.
- **The launch chooser** already brute-forces launch options and supports a direct-exe override; `link2ea://` / `uplay://` come through as the game's configured launch "executable".

## What is missing in the app (small, for the Steam/Epic launch path)

- **No `link2ea://` / `uplay://` / `steam2ea://` protocol handling.** When Steam's launch entry is a URL (Sims 4's is literally `link2ea://launchgame/1222670?platform=steam&theme=ts4`), nothing registers those HKCR classes or routes them. The installers DO register them (verified below), so once the client is installed this mostly resolves itself, but the app should (a) detect a URL launch entry and ShellExecute it via `start.exe`/`winebrowser` rather than trying to CreateProcess a "file", and (b) pre-register the classes as a safety net.
- **Dead metadata that should be wired up:** `requiresUbisoft`/`requiresOrigin`/`isEAManaged` are computed in the Epic path (`EpicGame.kt`) and never read; `thirdPartyManagedApp` is parsed but unused. These should drive "install the client first" and the DLL-override step.
- **No automatic DLL overrides for the CEF helper exes** (see the on-device finding below) — this is the single highest-value app-side change.

## On-device reproduction (this session)

Test app: Ubisoft Connect installer (`UbisoftConnectInstaller.exe`, 259 MB, NSIS, 32-bit) and the EA app bootstrapper (`EAappInstaller.exe`, 2.1 MB), both pulled from the official Akamai CDNs and pushed to the device.

1. **Silent install works on both layers.** `UbisoftConnectInstaller.exe /S` in the x86_64/box64 container installed the full client (457 MB, 195 files: `upc.exe`, `UbisoftConnect.exe`, `UplayWebCore.exe`, `libcef.dll` Chromium 135, `uplay_r1/r2` loaders, etc.) and wrote the correct registry: `HKLM\Software\WOW6432Node\Ubisoft\Launcher` InstallDir, the `Uplay` uninstall key, and the client's `settings.yaml`. No crashes during install.
2. **Client launch, before any fix:** `UbisoftConnect.exe` → `upc.exe` → loads `libcef.dll` → **657× `EXCEPTION_ACCESS_VIOLATION` (0xc0000005)** then `Unhandled page fault`, process becomes a zombie. Black screen. Same failure on both box64 (x86_64) and FEX (arm64ec) layers.
3. **The emulator's own SEH is fine.** A cross-compiled test (`sehtest32.exe`) confirmed vectored, unhandled, and `RaiseException` handlers all recover correctly under FEX/wowbox64 in the arm64ec container. So the crash is inside CEF, not the emulator's exception plumbing.
4. **The GameHub fix clears the fatal crash.** Writing `HKCU\Software\Wine\AppDefaults\<exe>\DllOverrides` = `d3d9/d3d10*/d3d11/dxgi = builtin` for `upc.exe`, `UbisoftConnect.exe`, `UplayWebCore.exe`, `UbisoftGameLauncher(64).exe` (so CEF uses wined3d, never DXVK) dropped the access violations from **657 to 1**, and `upc.exe` now stays alive at ~80% CPU instead of dying.
5. **The next blocker (now pinned):** with the override applied, the log fills with **657× `STATUS_INVALID_DISPOSITION` (0xc0000026)** — an SEH-dispatch storm inside CEF's crashpad handler under arm64ec/FEX emulation. Screen still black; `upc.exe` spins. This matches the well-documented "Ubisoft Connect takes minutes to start / hangs on a blank login window" behaviour that GE-Proton 11-6 addressed by "removing a redundant packed-code split lock that delayed Ubisoft Connect startup by minutes".

## Root-cause chain for the Ubisoft login UI (CEF)

- CEF (Chromium 135) tries to render its GPU/compositor path through ANGLE→D3D11→DXVK; under emulation that faults. Forcing the helper exes to Wine's builtin wined3d avoids the DXVK path (fix #4). Zink (GL-on-Vulkan) is already active in the container, matching GameHub's stack.
- After that, CEF's own SEH/crashpad path raises `STATUS_INVALID_DISPOSITION` in a loop. This is the remaining thing to fix, and it is a Wine-layer / FEX issue, not an app issue.
- `libcef.dll` also imports `USERENV.dll.DeriveAppContainerSidFromAppContainerName`, which the layer does **not** export (`dlls/userenv/userenv.spec`). Wine stubs it to a bogus address. Not the immediate crash (CEF loads past it), but it should be added while we are in the layer.

## Session 2 results (implementation + deeper reproduction)

**Implemented, app side (branch `feature/ea-sports-ubisoft`, uncommitted):**
- `WineUtils.applyEmbeddedBrowserHelperOverrides()` — writes `AppDefaults\<exe>\DllOverrides` = `dxgi,d3d9,d3d10,d3d10_1,d3d10core,d3d11 = builtin` for the 14 known Ubisoft/EA/Rockstar CEF and Qt helper exes, called from `applySystemTweaks()` so it lands at container setup. This is the automated form of the manual fix that removed the first crash.
- `XServerDisplayActivity.buildGuestProgramArgs()` — recognises `link2ea:`, `steam2ea:`, `uplay:` and `com.epicgames.launcher:` launch entries and routes them through `start.exe` (ShellExecute) instead of trying to CreateProcess them as a file. Sims 4's Steam launch entry is literally such a URL.
- Ludashi APK built and re-signed with the release key. Not yet installed/verified on device.

**Implemented, layer side (`p11-2-work` + both `build11-2` trees, uncommitted):**
- `DeriveAppContainerSidFromAppContainerName` implemented in `dlls/userenv/` (SHA-256 of the uppercased name under the AppContainer authority, matching the documented Windows algorithm), exported in `userenv.spec`, `bcrypt` added to imports. Builds clean for i386/x86_64/arm64ec.
- **Device-verified effect:** the "No implementation for USERENV.dll…" warning is gone, and on arm64ec/FEX the fatal `Unhandled page fault … 930D3D39` disappeared entirely. This is a real fix and worth upstreaming regardless of the rest.

**Remaining blocker, now precisely characterised.** Ubisoft Connect (168.0.12921, Chromium 135) installs and starts — it logs its own banner and correctly detects "Windows 10 64-bit build 19045, CPU Cortex-A520" — but the CEF UI process `UplayWebCore.exe` never comes up. The failure differs per 32-bit JIT (`emulator` field; the 64-bit game JIT stays FEX):
- **FEX 32-bit JIT:** an access violation occurs inside FEX's own JIT buffer (PC in no PE module, "exception data not found for pc"). Wine cannot unwind it, so it raises `STATUS_INVALID_DISPOSITION`, which faults identically — 657-deep recursion, then `upc.exe` dies.
- **wowbox64 32-bit JIT:** no crash, but `upc.exe` hangs, spinning at 99% CPU with only 2 threads, never writing UC's startup banner, and Wine emits a 0-byte debug log.

So the wall is delivering a fault raised inside emulated 32-bit code back to the guest's SEH handler. That is emulator-level work (FEX/box64 WoW64 exception translation), not app or Wine-layer work, and it is the one thing standing between here and a working first-time login for both EA and Ubisoft (EA's QtWebEngine is Chromium too).

*Measurement trap for future sessions: a 0-byte wine log makes `grep -c 'Exception'` return 0. Check log size before reading that as "no crashes" — it briefly produced a false positive here.*

## Session 3 results — EA app installed and running, blocked on its background service

**EA is the viable path, Ubisoft is the blocked one.** Every EA binary (`EADesktop.exe`, `EACefSubProcess.exe`, `EABackgroundService.exe`, `Link2EA.exe`, `EALocalHostSvc.exe`) is x86_64, so it runs on FEX's mature 64-bit JIT and never touches the broken 32-bit WoW64 exception path. Ubisoft Connect is inherently 32-bit in every component (`upc.exe`, `UbisoftConnect.exe`, `UplayWebCore.exe` are all PE32) and therefore cannot avoid it.

**Getting the EA app installed took working around three separate Wine bugs:**
1. `EAappInstaller.exe` deadlocks permanently right after `OnDetectUpdateBegin`, on both FEX and box64, with `/passive` and `/quiet` alike — all eight burn clean-room threads parked in `ppoll`/`pipe_read`, no elevated child spawned. Ruled out by measurement: purpose-built WinINet and WinHTTP probes both return HTTP 200 in ~1-2s from inside the container, so it is not network; `/quiet` creates no UI, so it is not the window.
2. So pull the payload straight from the burn manifest instead (7z the installer, file `0` is the XML): `https://origin-a.akamaihd.net/EA-Desktop-Client-Download/installer-releases/EAapp-<ver>-<build>.msi`, 245 MB, SHA-1 matching the manifest `Payload` hash. **wine-mono is required first** — Wine 11 wants 10.4.1 (`dlls/mscoree` MONO_VERSION) and the Proton layer ships none; without it the MSI's DTF managed custom actions die with `SFXCA: Failed to get requested CLR info 0x80004005`. With mono present it binds `CLR v4.0.30319`.
3. Do **not** run `msiexec /i` — it reaches `InstallFinalize`, crashes msiexec at the `StopServices` action, and rolls the whole install back (even with `DISABLEROLLBACK=1`). Use an administrative install: `msiexec /a EAapp.msi TARGETDIR=C:\EAextract /qn` → `ADMIN. Return value 1`, 807 MB extracted, no service actions run. Then copy `EAextract\Electronic Arts` into `C:\Program Files\` and write the registry by hand.

**Where it stands:** `EADesktop.exe` runs healthy — 64-bit, zero crashes, clean Wine log, sitting in NetLib/SocketRecv threads, and it creates `%LOCALAPPDATA%\Electronic Arts\EA Desktop\OfflineCache`. But **`EABackgroundService.exe` exits immediately with code -1**, and EADesktop waits on it forever, so no CEF window and no login. Ruled out for the service: every PE import is present (check case-insensitively — the device FS is case-sensitive and `MSVCP140.dll` vs `msvcp140.dll` produces a false negative), no failed module loads, no Wine fault, and passing the `-servicename=` argument it expects changes nothing. It imports `WINTRUST.dll` and ships encrypted `.rcc.enc` resources, so an internal integrity/anti-tamper check is the leading remaining hypothesis — which is not something to circumvent.

`EALocalHostSvc.exe` does run and listens on the Origin SDK port 3215, but asserts on a missing `cmd.eaLocalHostSvc.ipcPort` flag when started by hand rather than by EADesktop, and its DirtySDK HTTP layer errors (`a082ffff`) against `ratt.juno.ea.com`.

## Session 4 — two real WinNative/Wine bugs found and fixed; EA app now runs its service

**Bug 1 (fixed app-side): `nsiproxy` and `Ndis` are never registered in WinNative prefixes**, so `GetAdaptersAddresses` fails with error 2 and **every Windows network API sees zero adapters**. This is not the old demand-start problem — the service keys do not exist at all, so `changeServicesStatus()` cannot help it (`setCreateKeyIfNotExist(false)`). The `.sys` files do ship in the layer and prefix. Fix: `WineUtils.ensureNetworkDriverServices()`, called from `applyLaunchRegistryPolicy` before `changeServicesStatus`, creating the keys when absent (ImagePath, Type 1, Start 2, ErrorControl 1, Group "System Bus Extender", Tag 1). **Write under `System\ControlSet001\...`; a `CurrentControlSet` import does not persist.** Device-verified: before `GetAdaptersAddresses FAILED 2` → after `OK count=6` (wlan0, lo, dummy0, ifb0-2). Affects any app that enumerates adapters, not just EA. Raw DNS/TCP always worked, so it is invisible unless tested directly.

**Bug 2 (arm64ec only, not fixed): Wine's rpcrt4 crashes in the service-control RPC path.** `msiexec /i` reaches `InstallFinalize` and dies at `StopServices` with `page fault on write access to 0x10`, symbolized to `NdrContextHandleUnmarshall` (rpcrt4 +0x64aa4, instruction `str xzr,[x19]` — the `*ccontext = NULL` store with a bogus context handle). Same path explains `sc start` returning exit 1077. **box64 is unaffected**: there `InstallServices`/`StartServices` return 1 and `sc start EABackgroundService` reaches STATE 4 RUNNING (283 MB RSS). **Run EA in a box64 container, not arm64ec.**

**EA app status on box64:** installed, `EABackgroundService` RUNNING and healthy (creates firewall rules, registers COM servers, sets install state, computes machine hash, publishes its IPC port to `C:\ProgramData\EA Desktop\backgroundservice.ini`), `EALauncher.exe` runs and correctly spawns `EADesktop.exe -ls=Launcher` via "explorer credentials", and `EADesktop.exe` runs. **But no login window.** EADesktop sits idle in 3 ppoll threads and writes *no log at all*, unlike every other EA component.

**Systematically eliminated as causes:** display/rendering (a `cmd.exe` window renders fine in the same session, so the X path works), loopback IPC (the service's published port is reachable from inside the container on both `127.0.0.1` and `localhost`; hosts maps localhost correctly), adapter enumeration (fixed above), Network List Manager (reports `IsConnectedToInternet=TRUE`, connectivity `0x60` IPv4 internet+localnet), system clock (container/device/host agree within seconds), encrypted resource decryption (`*.rcc.enc` including `cacertificates.rcc.enc` all load without error, so anti-tamper is not blocking), missing DLLs (all imports present — check case-insensitively), and TLS generally (WinINet and WinHTTP both return HTTP 200 in ~1-2s, 32- and 64-bit).

**Remaining unknowns:** EA's own DirtySDK HTTP layer fails *every* request instantly (~5ms, error `a082ffff`) to every host including ones my probes reach fine, so server-time sync never succeeds; and EADesktop produces no log. A plausible untested lead is elevation — EALauncher deliberately de-elevates via explorer credentials, but under Wine everything reports `elevated[true]`, and the EA client may refuse to initialize when elevated.

## Session 5 — EA networking narrowed but not solved

Additional causes eliminated for EA's DirtySDK failure (every request to every host fails in ~5ms with `a082ffff`):
- **DNS registry**: Wine populates **no** `Services\Tcpip\Parameters` at all — no `Interfaces`, no `NameServer`, no `DhcpNameServer`. Populating them by hand (NameServer, DhcpNameServer, IPAddress, gateway, per-interface GUID key) changed nothing.
- **Adapter ordering**: Wine lists `ifb2, ifb1, ifb0, dummy0, lo` before the real `wlan0`, and the ifb/dummy ones have no IPv4. A netshim reorder was implemented but has **no effect once `nsiproxy` is registered**, because Wine then enumerates via netlink and never calls `getifaddrs`. Any ordering/filtering fix must go in Wine's nsiproxy.
- **IPv6**: half-configured — addresses present but no route; an IPv6 connect fails `WSAENETUNREACH` in **0ms** while IPv4 connects in 27ms. Signature matches, but EA's own hosts have no AAAA record, which weakens it as the sole cause.
- **Proxy test**: a logging CONNECT proxy on the LAN is reachable from the device, but setting `app.httpProxy` in `machine.ini` produced no connections — most likely the key is not read from there, so this test is inconclusive rather than negative.

**netshim staging trap:** `ensureImageFsNativeLibrary()` re-copies `libnetshim.so` from the APK at every launch, so a hand-pushed `.so` is silently reverted; you must install the rebuilt APK.

**Verdict:** EA is one bug away — installed, `EABackgroundService` RUNNING and healthy, COM registered, IPC port published and reachable, Qt complete, windows render — but its own DirtySDK HTTP layer fails universally and EADesktop waits on the service forever, so no login window. Further progress needs a packet capture on the device (root) or DirtySDK-level reverse engineering; the cheap environmental diagnostics are exhausted.

## Session 6 — EA networking SOLVED

**Root cause: bionic's `getaddrinfo()` rejects `AI_V4MAPPED`/`AI_ALL` with EAI_BADFLAGS.** Dual-stack Windows code (EA DirtySDK, Qt, Chromium) asks for `AF_INET6 + AI_V4MAPPED` and treats the failure as "no DNS". That is why *every* EA request failed in ~5ms while my own probes succeeded — I had forced `AF_INET`.

**Fix:** Banner's Wine patch `android/patches/dlls_ws2_32_unixlib.c.patch` from `github.com/The412Banner/proton-wine`, branch `aio-eanet/proton_11.6-GE` (commit c74e6e19). It strips the flags before the unix `getaddrinfo()` and emulates them with a second `AF_INET` lookup presented as `::ffff:a.b.c.d`. **It applies cleanly to our Proton 11.0-2 tree.** Rebuild `ws2_32`, replace `lib/wine/x86_64-unix/ws2_32.so` in the layer.

**Device-verified:** dirtyHttp errors **19 → 0**, real `status[200]` responses from `ratt.juno.ea.com`, 506 KB of healthy log, and the background service began downloading and installing VC++ redists (genuine first-run provisioning).

**The `a082ffff` error code is meaningless — do not chase it.** ProtoSSLStat does not implement the `'fail'` selector, so it returns the function's default `-1`; the app's decoder checks `'hres'` then `'fail'` then `'serr'`, and since `'fail'` is always nonzero it never reaches the `'serr'` check that would have shown the real socket error.

**Banner's other findings worth adopting:** his `nsiproxy.sys/ip.c` patch (Android hides the default route in per-network policy tables, so `SIO_ROUTING_INTERFACE_QUERY`/`GetBestRoute` fail and dual-stack clients think they are offline; needs `WINE_ANDROID_GATEWAY` exported from ConnectivityManager) — **does not apply to 11.0-2, needs porting**. His `ndis.c` and `dnsapi/libresolv.c` patches are already in our tree. Also: wine-mono and wine-gecko both required; `FEX_SMCCHECKS=none` breaks EA's Activation64 anti-tamper; EA Javelin anti-cheat titles unsupported; EA is launched through the real Steam client as a chain `Link2EA.exe → EADesktop.exe → EASteamProxy.exe → EACefSubProcess.exe`; CEF runs `--disable-gpu`.

**Still open:** `EADesktop.exe` runs but produces no window, no CEF subprocess, and no log of its own (zero stderr even with `QT_DEBUG_PLUGINS=1`), while every other EA component logs normally. Next lead is Banner's launch chain — he starts EA from a real Steam game via `link2ea://`, not standalone, so standalone launch may simply never show UI.

## Session 7 — default route ported; EA client reports ONLINE; blocked on needing an EA game

**Second Wine fix, hand-ported.** Our 11.0-2 tree already carried an *older, buggy* `wine_new_ndis` branch in `dlls/nsiproxy.sys/ip.c` `ipv4_forward_enumerate_all()`: it used the interface IP instead of the subnet, invented a bogus next-hop, emitted **no** `0.0.0.0/0` default route, leaked the ifaddrs list, and `while (ifap->ifa_next)` skipped the last entry. Banner's 11.6 patch does not apply, so his logic was ported by hand — on-link subnet route plus a real default route via `WINE_ANDROID_GATEWAY` (fallback subnet `.1`), with `freeifaddrs()`; needs `<stdlib.h>` and `<arpa/inet.h>`. Container env: `WINE_NEW_NDIS=1 WINE_ANDROID_GATEWAY=192.168.50.1`.

**Verified:** the EA service log now reads `Application state has changed to [online]`, then correctly "online, but do not have an ownership token" for a client that has not signed in. Previously it behaved as offline. Networking stays clean (0 dirtyHttp errors).

**Login UI — reframed, and blocked.** Banner's device-proven recipe never launches EA Desktop standalone: the chain is `Link2EA.exe → EADesktop.exe → EASteamProxy.exe → ActivationUI.exe → game exe`, driven by launching an **EA game** through the genuine Steam client. So EADesktop idling with no window when started on its own is plausibly correct behaviour rather than a bug. Tried and ruled out standalone: `-ls=Launcher`, `--disable-gpu`, `--no-sandbox --in-process-gpu --disable-gpu-compositing`, and launching a second instance to raise the window (later instances exit immediately — single-instance detection works, so the first really is alive and receiving the signal). It keeps 3 ppoll threads and 3 sockets and writes no log of its own.

**The blocker is content, not code:** the Steam account owns no EA title, and the device has only ~17 GB free (Conan 60 GB, MHR 34 GB, TF2 31 GB, L4D2 14 GB installed). The Sims 4 is free-to-play but its base install exceeds that. Reaching a real login needs either freeing space for a free EA title (user's call — deleting their games is not mine to make) or accepting the current milestone.

## Plan

### Phase 1 — Steam/Epic → Ubisoft launch (app-only, no layer rebuild)
1. Add automatic `AppDefaults\<exe>\DllOverrides = builtin` (d3d9/d3d10*/d3d11/dxgi) for the known CEF helper exes (`upc.exe`, `UbisoftConnect.exe`, `UplayWebCore.exe`, `UbisoftGameLauncher.exe`, EA's `EADesktop.exe`, `QtWebEngineProcess.exe`, `Link2EA.exe`) whenever a shortcut is Ubisoft/EA-managed. Reuse `WineRegistryEditor` (`WineUtils.java:816`).
2. Detect a URL launch entry (`link2ea://`, `steam2ea://`, `uplay://`) and ShellExecute it inside the prefix instead of treating it as a missing exe; pre-register the HKCR classes as a fallback.
3. Wire the already-parsed `thirdPartyManagedApp` / `requiresUbisoft` / `isEAManaged` flags to (a) run the client installer first, (b) apply step 1, (c) keep the client resident during play (it is the DRM pipe).
4. EA-specific: ensure `EABackgroundService` starts under Wine's SCM, set the `HKLM\SOFTWARE\WOW6432Node\Electronic Arts\EA Desktop\InstallSuccessful="true"` gate, keep localhost TCP 3216 (LSX) and DirtySDK's `WSALookupServiceBegin` working — the existing `nsiproxy` fix matters here.

### Phase 2 — CEF login UI (layer, built locally, both arm64ec + x86_64)
Toolchain is present locally (NDK 27.3.13750724, llvm-mingw 20250920, termuxfs, prefixPacks). Source tree: `/home/max/Build/Proton-Layers/p11-2-work`, build via `/home/max/Build/Proton-Layers/build11-2/run-build.sh` (~12 min both arches).
1. Add the missing `userenv` export(s) for the CEF sandbox.
2. Investigate/port the GE-Proton 11-6 "packed-code split lock" change and the `STATUS_INVALID_DISPOSITION` storm in `dlls/ntdll/signal_arm64ec.c` + `dlls/wow64/syscall.c` (the exception-dispatch path already read this session).
3. Confirm `DiscardVirtualMemory` and ECDSA TLS are present (they are, in Wine 11).
4. Build locally only; sideload via the Components import (`.wcp`); do not publish.

### Phase 3 — EA/Ubisoft as storefronts (large, optional, later)
Only if we want to download EA/Ubisoft games directly (not via Steam/Epic). Mirror the Epic store module: new `feature/stores/ea` + `feature/stores/ubisoft` (auth, Room entity + DAO, `DownloadCoordinator.Dispatcher`, `game_source`, `StoresScreen` cards, 23 `values*/` string sets, `GameSource` enum, nav). Client APIs are documented but private: EA via the `JUNO_PC_CLIENT` OAuth + `service-aggregation-layer.juno.ea.com` GraphQL `downloadUrl` (see the Maxima project); Ubisoft via `public-ubiservices.ubi.com` (see the GOG Galaxy Uplay plugin). Downloading outside the official client is only demonstrated in alpha tools and is ToS-adjacent — recommend deferring.

## Test game options (owned on the signed-in Steam account)
None of the owned Steam titles are EA/Ubisoft games, so end-to-end "buy on Steam → play" needs a free one: **Trackmania (2225070)** is free and Ubisoft-managed (`Trackmania.exe -upc_steam_free_package_id 62710 -uplay_steam_mode`), and **The Sims 4 (1222670)** is free-to-play and EA-managed (`link2ea://launchgame/1222670?platform=steam&theme=ts4`). Both are the natural end-to-end tests once Phase 1+2 land.

## Session 8 — EA app reaches sign-in

Three findings, all now handled automatically by the app.

**1. Silent install works; the installer UI does not have to be clicked.**
The thick installer bundled with a game installs EA Desktop unattended:

```
"<game>\__Installer\Origin\redist\internal\EAappInstaller.exe" /quiet /norestart
```

Exit code 0, ~1.4 GB installed, every registry class (`link2ea`, `ealink`, `origin2`) written by the installer itself. This sidesteps the fact that synthetic taps could not drive the installer wizard.

**2. Root cause of the EA app never drawing a window: wintab32.**
`EADesktop.exe` loaded every DLL cleanly, then sat at 0% CPU with 2 threads and never created a window, writing no log because it hung before logging init. `winedbg` attach plus `bt all` gave the stack:

```
win32u <- wintab32 <- qwindows <- qt5gui <- qt5widgets <- eadesktop
```

Qt's Windows platform plugin loads `wintab32.dll` for tablet support and Wine's implementation blocks forever. Disabling the DLL makes `LoadLibrary` fail, Qt skips tablet support, and the app proceeds. `WineUtils.EMBEDDED_BROWSER_HELPER_DISABLED_LIBS` now writes an empty override for every EA and Ubisoft helper executable.

**3. EABackgroundService must be a real Wine service.**
Started as a bare executable it exits and the app shows "Background services crashed". `WineUtils.ensureEaBackgroundService` registers it under both control sets with `Start=2`, resolving the active version directory from the `Link2EA.exe` path recorded in `system.reg`. Directory naming cannot be used for this: a staged update is named `<version>-<epoch>` while installed is bare `<version>`, but once the update is applied the epoch-suffixed directory becomes the active one.

Result: launching the EA app now spawns the background service and the CEF subprocesses on its own and renders the real sign-in screen.

Known remaining issues:

- The sign-in window draws at the left of the Wine desktop and sits behind the blank main EA window, so it is clipped.
- Taps reach Wine windows and focus fields, but key events from `adb input` do not produce characters. Signing in has to be done on the device.
- Ubisoft Connect is still blocked: every component is 32-bit and needs the broken WoW64 exception path.

## Session 9 — Ubisoft Connect

Ubisoft Connect is 32-bit only and pure CEF, with no Qt, so the wintab32 fix does not apply to it.

**The x86_64/box64 container cannot run it.** `upc.exe` loads `libcef.dll` and `chrome_elf.dll`, then dies within seconds on an unhandled page fault executing 0x930d3d39, jumping through EDI while EDX and EBX also hold garbage 0x9xxxxxxx values. The missing-export theory is ruled out: the 32-bit `userenv.dll` does export `DeriveAppContainerSidFromAppContainerName`, libcef resolves it, and libcef itself maps cleanly at 0x6fa80000-0x7b6e2000. The 32-bit NSIS installer runs fine on the same layer, so ordinary 32-bit code works and CEF does not.

The arm64ec container runs the whole stack once three things are fixed.

**Debug logging has to be off.** With wine and emulator logging enabled, FEX emits `Call-ret stack inbalance` continuously — 45 MB in 90 seconds. That alone held `upc.exe` at two threads. With logging off it reaches 33-45 threads and spawns three `UplayWebCore.exe` CEF children. A control run of plain winecfg on the same container produces none of those lines, so the spam is specific to this binary.

**The arm64ec layer was missing both networking fixes.** Its `ws2_32.so` and `nsiproxy.so` were still the 2026-08-23 builds while x86_64 had the 2026-09-06 ones. The ws2_32 source was patched but never rebuilt, and the nsiproxy default-route port had only been applied to the x86_64 tree. Copying `ip.c` across and rebuilding that layer incrementally fixed both. Ubisoft's own `logs/network_info.txt` now shows a correct routing table with the default route via `WINE_ANDROID_GATEWAY`, and the launcher writes `launcher_log.txt` at all, which it never did before.

**The Task Scheduler service was disabled by our own service trimming.** `changeServicesStatus` lists `Schedule:3`, and `startupSelection=1` turns listed services into `Start=4`. Ubisoft Connect then fails with `SCHED_E_SERVICE_NOT_INSTALLED` and stops. `WineUtils.ensureTaskSchedulerService` now restores it, called after `changeServicesStatus` because anything earlier gets overwritten, and gated on an EA or Ubisoft install being present.

The rpcrt4 `NdrContextHandleUnmarshall` fault at +0x64AA4 appears 251 times in a Ubisoft session and is a red herring: a control run of winecfg on the same container produces 152 of the identical faults. It remains the real cause of `sc start` and msiexec `StopServices` failing, but it does not block Ubisoft Connect.

Still open: with all of the above, `upc.exe` runs with two CEF children and reaches `UplayServicePipeHandler: Accepted connection`, but creates no top-level window. Wine's own task manager, run in the same session, lists only `cmd.exe` in its Applications tab while 16 processes are alive, so the window is never created rather than failing to paint. Giving `settings.yaml` a real `position:` block did not help, and `--disable-gpu` made it worse.

### Ubisoft: the exact stall point

The last real line in `launcher_log.txt` is `ConnectView.cpp (196) Using CEF with native rendering`. Everything after it is a `JobSendRemoteLogs` retry loop that grows the log forever without making progress. So Ubisoft Connect reaches CEF browser creation, in native (windowed) mode, and `CreateBrowser` never completes.

The child processes confirm it: `UplayWebCore.exe` spawns only `--type=gpu-process` and `--type=utility`, never `--type=renderer`. A renderer is only spawned once a browser view actually exists, so CEF is blocked waiting on its GPU process, which sits idle rather than either succeeding or crashing — a crash would at least make CEF fall back to software rendering.

Ruled out for this stall: the d3d wrapper choice (letting the CEF helper use DXVK instead of forced wined3d gives the identical stall and process set), window geometry, and network. `--disable-gpu` could not be tested: `UbisoftConnect.exe` does not forward arguments to `upc.exe`, and launching `upc.exe` directly stalls much earlier because UbisoftConnect.exe performs required setup first. `upc.exe` is packed with Ubisoft's own protector, so there is no static toggle to find either.

### What Banner's fork has for Ubisoft

Banner's fork is `github.com/The412Banner/proton-wine` (cloned locally at `Proton-Layers/the412banner-proton-wine`). Searching its history for Ubisoft work turns up four hacks, all originally by Paul Gofman for Proton:

1. `kernelbase: HACK: Force CEF software rendering for UplayWebCore.` — the important one. It works by matching the command line inside `CreateProcessInternalW` and appending a switch, which is exactly how you deliver a Chromium switch to a process you do not launch yourself. In current trees this lives in `hack_append_command_line()` in `dlls/kernelbase/process.c` as a table of `{exe_name, append, steamgameid}` rows.
2. `ntdll: HACK: Enable WINE_SIMULATE_WRITECOPY for UplayWebCore.` — sets `simulate_writecopy` in `hacks_init` when argv[1] contains UplayWebCore.exe.
3. `fsync: Add WINE_FSYNC_SIMULATE_SCHED_QUANTUM config option.`
4. `windows.ui: HACK: Implement IInputPane2::Try{Show,Hide} via tabtip.`

Of these, **we already have 2, 3 and 4**. Our tree carries the writecopy line, and the device log shows `hacks_init HACK: Simulating sched quantum in fsync` with `tabtip.exe` running.

The one nobody has is the first. Its history is: `--use-gl=swiftshader` (Jul 2021, "work around Uplay crash"), changed to `--use-angle=gl` (Feb 2023), changed to `--use-angle=vulkan` (May 2023), then **reverted entirely in Aug 2025** under CW-Bug-Id #25805. Every Banner branch checked (`aio-eanet/proton_11.0-2`, `aio-eanet/proton_11.6-GE`, `bleeding-edge-bionic`, `proton_11.0`) has `process.c` free of UplayWebCore and `loader.c` carrying the writecopy hack, matching our tree exactly.

That revert is plausibly why Ubisoft Connect stalls here: it was removed because CEF's normal GPU path works on desktop Linux, and that path is exactly what does not work on Zink plus FEX.

Adding `{L"UplayWebCore.exe", L" --use-gl=swiftshader"},` to the table builds cleanly and the string is present in the resulting binaries. It could not be validated yet, because hot-swapping a single freshly built `kernelbase.dll` into the layer already on the device regresses startup — Ubisoft Connect then spawns no CEF children at all, where the stock DLL spawns the GPU process. Restoring the stock DLL restores the previous behaviour, so nothing is broken, but the entry has to be tested from a fully rebuilt and repackaged arm64ec layer rather than a swapped file. Note the layer ships `llvm-strip --strip-all` binaries; an unstripped DLL is a much larger file and regresses on its own.

Both the 64-bit and the 32-bit kernelbase matter here, and the 32-bit one is the one that counts: `upc.exe` is a 32-bit process, so its `CreateProcess` runs through `syswow64\kernelbase.dll` from `lib/wine/i386-windows`.
