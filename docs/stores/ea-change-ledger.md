# EA / Steam+EA work — change ledger

Purpose: every change made for EA support, with the evidence that forced it and how to revert it.
Anything marked SPECULATIVE was not proven necessary and should be reverted unless it later earns
its place.

Classification:
- **NEEDED** — a defect was measured on device, or the change is required for the feature to exist.
- **SECONDARY** — real defect, but masked once a NEEDED fix lands. Keep only if it stands alone.
- **SPECULATIVE** — not proven necessary. Revert candidate.

Verification column: `measured` = confirmed on device this session; `compiled` = builds only;
`untested` = neither.

---

## A. Renderer / X server

| # | Change | File | Class | Evidence | Verified |
|---|--------|------|-------|----------|----------|
| A1 | ~~Carry an inherited root-space clip rect through the window tree and apply it to the submitted quad + UVs~~ **REVERTED 2026-09-08** | - | **NOT NEEDED for EA** (it neither causes nor fixes the blank login — see the retraction below) | **Device-tested, then reverted.** It stops a stray surface leaking onto the desktop. It has no effect on EA either way: the sign-in screen renders or stays blank depending on how many CEF children survive, with A1 present or absent | measured, reverted |
| A2 | `ReparentWindow` now reads and applies the x,y the X protocol carries instead of `skip(4)` | `runtime/display/xserver/requests/WindowRequests.java`, `runtime/display/xserver/WindowManager.java` | SECONDARY | Genuine X protocol conformance bug (every reparented window kept a stale position). Did not change EA's symptom. Keep only if it stands on its own | measured (no effect on EA) |
| A4 | Colormap support: `WindowAttributes` stores it (was `skip(4)`), `GetWindowAttributes` reports it (was hardcoded `writeInt(0)`), and the connection setup advertises a real `default-colormap` (was `0`) | `runtime/display/xserver/WindowAttributes.java`, `runtime/display/xserver/requests/WindowRequests.java`, `runtime/display/xserver/XClientRequestHandler.java` | SECONDARY | Real X conformance bug - the server told every client there was no colormap. **Measured: `Mesa: warning: Window 33554496 has no colormap!` goes from present to 0 occurrences.** But EA's window is still blank, so this was not the cause of the CEF rendering failure. winecfg unaffected | measured |
| A3 | ~~`CopyArea`/`GetImage` resolve `scanoutSource`~~ **REVERTED** | - | NOT NEEDED | `+x11drv` trace of a full EA session shows **zero** `XCopyArea`/`XGetImage`/`XPutImage` calls - EA moves content via Vulkan client surfaces. The fix was correct in principle but provably not on EA's path, and it touches a core drawing path | measured |

**A1 is reverted**, and the reason first given for reverting it was WRONG. Both renderer files are
back to their committed state (`git checkout -- runtime/display/renderer/{VulkanRenderer,RenderableWindow}.java`).

**Retraction.** An earlier entry here claimed A1 caused the blank EA login, on the strength of a
single run in which removing the clip made the sign-in screen appear. That was a confounded
measurement. Repeating the launch on the fully reverted build three times gave:

| run | EA + CEF processes alive | distinct colours inside the EA window |
|-----|--------------------------|----------------------------------------|
| 1 | 11 | 4 (blank) |
| 2 | **13** | **7471 (full sign-in screen)** |
| 3 | 10 | 4 (blank) |

Same binary, same container, same EA install. **The login renders when the whole CEF child set
survives and is blank when children die** — which is G2, not a clipping bug. Our renderer was never
at fault. Instrumenting it proved that directly: the window tree is

    win=41943050 class='eadesktop.exe' at 244,36 520x648 children=1
      win=37749126 class=''            at 244,36 520x648 children=0

The CEF surface is a child of the EA window at the identical rect, it is submitted every frame with an
allocated texture, and it is drawn last (on top). When it looks blank, CEF simply painted nothing into
it. Of the four renderable windows only two are ever dropped, and both are Wine's 1x1 dummy windows at
(-1,-1) with no content. `MAX_WINDOWS` is 64 and never approached.

A1 is therefore left reverted because it was made for EA and does nothing for EA — not because it
breaks anything. Its own justification (stopping a stray surface leaking onto the desktop, and correct
X child clipping) stands or falls on its own merits and can be restored independently. With it
reverted, that stray surface is visible again to the left of the EA window; it is cosmetic and the
login is fully usable in front of it.

Also tried and reverted as unnecessary during this investigation: skipping windows whose drawable is
0x0 before submission (redundant — `hasContent()` already drops them) and using
`Short.toUnsignedInt(drawable.width)` for the submitted quad. Neither changed the outcome.

---

## B. EA prefix seeding (launching EA games at all)

| # | Change | File | Class | Evidence | Verified |
|---|--------|------|-------|----------|----------|
| B1 | `resolveEaServiceExe()` locates `EA Desktop/EABackgroundService.exe` in BOTH the flat and versioned layouts; `ensureEaBackgroundService` now derives the Windows ImagePath by relativizing the located exe instead of re-composing a fixed shape | `runtime/wine/WineUtils.java` | NEEDED | Measured: container 1 has the flat layout, so `resolveEaInstallDir` returned null and `ensureEaBackgroundService` silently no-opped - the service was never registered | compiled |
| B2 | `SHELL_LAUNCH_PROTOCOLS` extended with `origin:`, `origin2:`, `ealink:`, `eadesktop:`, `epic2ea:`, `luna2ea:` | `runtime/display/XServerDisplayActivity.java` | NEEDED | Without these a shortcut or boot_exe holding an EA URL is handed to CreateProcess as if it were a PE image | compiled |
| B3 | `buildGuestProgramArgs()` no longer emits the empty console-title argument before a shell URL | `runtime/display/XServerDisplayActivity.java` | NEEDED | Measured twice. Wine trace of the old form: `CreateProcess error 87` then `SE_ERR_FNF`, because the empty title became `lpFile`. **Re-verified on device 2026-09-07 23:40**: the corrected form dispatched the URI and `Link2EA.exe` ran (its own log written, 5.1s session) | measured |
| B4 | Pre-existing (agent-authored this session): `ensureEaProtocolHandlers()` seeds `LauncherAppPath`/`DesktopAppPath`/`ErrorReporterPath` and registers `origin`/`origin2`/`eadesktop`/`link2ea` handlers, guarded so a non-empty command is never clobbered | `runtime/wine/WineUtils.java` | NEEDED | Measured by A/B/A' on device: container 1 had `@=" \"%1\""` (empty program part), so every direct EA launch died at ShellExecute | measured |

---

## C. Steam + EA hand-off

| # | Change | File | Class | Evidence | Verified |
|---|--------|------|-------|----------|----------|
| C1 | `matchesRegistryKeyPrefix()` requires a key boundary (`]` or `\`) in `extractRegistrySubtree`/`removeRegistrySubtree` | `runtime/display/XServerDisplayActivity.java` | NEEDED | The Steam registry hide/restore used a raw string-prefix match on `Software\\Classes\\steam`, which also matched `steam2ea` and deleted EA's protocol registration | compiled |
| C2 | Dropped the `break` in `extractRegistrySubtree` so a non-contiguous subtree is not truncated | `runtime/display/XServerDisplayActivity.java` | SECONDARY | Same function; a truncated backup loses keys on restore. Not independently measured | compiled |
| C4 | IMPLEMENTED (untested): for a Steam app whose only launch entry is a `link2ea:`/`steam2ea:` URI, the STEAM branch now starts the Plan W agent with `start.exe` as the game and the URI as its argument, so Steam is logged on before the URI is dispatched | `runtime/display/XServerDisplayActivity.java` (STEAM branch) | NEEDED | **Defect measured on device 2026-09-07 23:40**: with `Link2EA.exe` launching correctly but zero Steam processes running, it logs `SteamSDKHelper::Impl::init SteamSDK failed to initialize` -> `Exchange token is empty` -> shuts down after 5.1s. Link2EA needs `ISteamUser::GetAuthSessionTicket`, which requires a live Steam client. **Device-verified 2026-09-08**: the agent reaches `Steam_BLoggedOn=true`, `BIsSubscribedApp(1222670)=1`, `BGameConnectTokensAvailable=1`, then dispatches the URI; `Link2EA.exe` runs with a live Steam client and `onSteamTicketActivated: Result:1` | measured |
| C5 | Plan W game spec gains an optional third line carrying game arguments; the agent reads it and appends it to the `CreateProcess` command line | `cpp/wn-steam-launcher/src/main.cpp`, `runtime/display/XServerDisplayActivity.java` | NEEDED (for C4) | The agent previously built `cmd` as just `"<exe>"` with no argument support, so it could not dispatch a URI. NOTE: `app/src/main/cpp/wn-steam-launcher/build.sh` must be run by hand after editing this - **the Gradle build does not compile the agent, it only packages the prebuilt asset**. Rebuilt and verified. **Device-verified 2026-09-08**: `spec file ... args="link2ea://launchgame/1222670?platform=steam&theme=ts4"` -> `launching "start.exe" with explicit args (shell-URL dispatch)` -> `game process started`, and Link2EA's own log records `Command line: [... Link2EA.exe link2ea://launchgame/1222670?platform=steam&theme=ts4]` | measured |
| C3 | `getWindowsLaunchUrls()` + `getInstalledExe()` falls back to a shell-protocol launch entry when the app has no `.exe` entry | `feature/stores/steam/service/SteamService.kt` | NEEDED | Measured: TS4's appinfo has exactly two launch entries, `link2ea://` and `steam2ea://`, and zero `.exe`, so `getInstalledExe` returned "" and the launcher fell through to picking the wrong exe by directory scan | measured |

---

## D. EA store module (sign-in + tab)

All new files under `app/src/main/feature/stores/ea/`. Modelled on the Epic store; auth uses the
same WebView-on-the-provider's-real-login-page shape, so the app never sees the password.

| # | Change | File | Class | Evidence | Verified |
|---|--------|------|-------|----------|----------|
| D1 | `EaConstants` - `ORIGIN_JS_SDK` implicit flow: `response_type=token`, `redirect_uri=nucleus:rest`. No client secret, no `pc_sign`, no PKCE | `feature/stores/ea/service/EaConstants.kt` | NEEDED | **Measured live from the device.** This exact URL returns `302 -> https://signin.ea.com/p/juno/login` | measured |
| D2 | ~~`EaPcSign`~~ **DELETED** | - | NOT NEEDED | See section E. The research's HMAC keys do not exist in the shipped binary and were rejected live | measured |
| D3 | `EaAuthClient` - parses the token JSON EA returns for `redirect_uri=nucleus:rest`, decodes identity from the JWS `nexus` claim, and does cookie-replay silent re-auth via `prompt=none` | `feature/stores/ea/service/EaAuthClient.kt` | NEEDED | `prompt=none` measured live: returns `{"error":"login_required","error_number":"102100"}` with no cookies, proving the endpoint and shape | measured |
| D4 | `EaSecureStore` - AES-GCM under a plain `AndroidKeyStore` key; on any failure it wipes and forces re-login rather than throwing | `feature/stores/ea/service/EaSecureStore.kt` | NEEDED | The pinned `androidx.security-crypto` 1.1.0 has `EncryptedSharedPreferences`/`MasterKey` deprecated in the shipped bytecode. Steam's `PrefManager` rethrows on secure-storage failure and is referenced from ~51 files - deliberately not copied | compiled |
| D5 | `EaAuthManager` - `StoreAuthStatus`, token persistence, silent re-auth from the WebView cookie jar (`remid`/`sid`), sign-out clears cookies | `feature/stores/ea/service/EaAuthManager.kt` | NEEDED | Implicit flow returns no refresh token, so the session is carried by EA's cookies - the same approach Lutris and Playnite use | compiled |
| D6 | `EaTokenRefreshWorker` - periodic refresh sized from the server's real `expires_in` (half-life), not a hardcoded interval | `feature/stores/ea/service/EaTokenRefreshWorker.kt` | NEEDED | Epic's worker hardcodes 4h; EA's token lifetime was never verified against a real token, so it is derived at runtime | compiled |
| D7 | `EaOAuthActivity` - reuses Epic's `AuthWebViewDialog`, reads the token JSON out of `document.body.innerText` (the same trick Epic already uses), persists in-activity so no token crosses an Intent | `feature/stores/ea/ui/auth/EaOAuthActivity.kt` | NEEDED | - | compiled |
| D8 | Manifest activity registration | `AndroidManifest.xml` | NEEDED | - | compiled |
| D0 | **RESULT: device-verified.** The EA card appears in Connected Stores next to Steam/Epic/GOG/itch, and tapping Sign In opens EA's real account page in the WebView | (whole module) | NEEDED | Screenshot on device 2026-09-08 00:28: EA card renders with red accent, 'Not Signed In', Sign In button; WebView shows 'Sign in to your EA Account' with phone/email, Keep me signed in, NEXT, Create Account | measured |
| D11 | Interactive login uses `ORIGIN_SPA_ID` + `response_type=code`; the token is then minted from the resulting cookies via `ORIGIN_JS_SDK prompt=none`. Two-stage, no client secret | `feature/stores/ea/service/EaConstants.kt`, `feature/stores/ea/ui/auth/EaOAuthActivity.kt` | NEEDED | **Measured.** `ORIGIN_JS_SDK` is a silent-token client: its login page has no client context and EA's own JS logged `authentication error: 10007 with cid: ` (empty cid) at the identifier step. `ORIGIN_SPA_ID`+`token` is rejected (`response_type is invalid`), `ORIGIN_SPA_ID`+`code` returns 302 - it is the client origin.com itself used | measured |
| D12 | `AuthWebViewDialog` gains an opt-in `acceptThirdPartyCookies` flag; only EA sets it | `feature/stores/epic/ui/component/dialog/AuthWebViewDialog.kt`, `feature/stores/ea/ui/auth/EaOAuthActivity.kt` | NEEDED | Android WebView defaults `setAcceptThirdPartyCookies` to FALSE. EA's login spans `signin.ea.com`, `accounts.ea.com` and `eaaccounts.akamaized.net`, so its cross-site cookie writes were dropped. **Measured: with D11+D12 the 10007 error disappears and the console advances to `updated to LoginMethod = emailPassword`, reaching the password screen.** Opt-in so Epic/GOG/itch behaviour is untouched | measured |
| D13 | Redirect detection widened - `www.origin.com/views/login.html` now 302s to `www.ea.com/games/library/pc-download` | `feature/stores/ea/service/EaConstants.kt` | NEEDED | Measured: origin.com is retired, so matching only the registered redirect would never fire | measured |
| D14 | `EaLibraryClient` - GraphQL `ownedGameProducts` query against `service-aggregation-layer.juno.ea.com`, paged, using the stored token via `EaAuthManager.getValidAccessToken()` | `feature/stores/ea/service/EaLibraryClient.kt` | NEEDED | **END-TO-END PROOF the token works.** On device: `status=ACTIVE loggedIn=true hasCreds=true cookies=1520` then `EA library: 1 owned games / owned: Apex Legends [BASE_GAME] Origin.OFR.50.0002694` | measured |
| D15 | EA API requests send `User-Agent: EADesktop/<version>` | `feature/stores/ea/service/EaConstants.kt`, `EaLibraryClient.kt` | NEEDED | **Non-obvious and load-bearing.** Without a UA the juno endpoint resets the HTTP/2 stream (`INTERNAL_ERROR`) and times out on HTTP/1.1 - reproduced from BOTH the device and the workstation. With `-A "EADesktop/13.783.0.6296"` the same request returns `200 {"data":{"__typename":"Query"}}` on both protocols | measured |
| D16 | ~~Pin EA API calls to HTTP/1.1~~ **REVERTED** | - | NOT NEEDED | Was an attempt to work around the stream reset. Once D15 (User-Agent) is in place HTTP/2 works fine, so the pin was removed | measured |
| D17 | TEMPORARY: library fetch triggered from `StoresFragment.refresh()` purely to validate the token, logging to `EAStore` | `feature/settings/stores/StoresFragment.kt` | SPECULATIVE | Validation scaffolding only. It fires a network call on every Stores refresh and logs game names. **Replace with the real library tab, or revert, before shipping** | measured |
| D9 | EA store card + `isEaLoggedIn`/`eaUserName` state + sign-in/out callbacks | `feature/settings/stores/StoresScreen.kt`, `feature/settings/stores/StoresFragment.kt` | NEEDED | - | compiled |
| D10 | `ea_store_title` string added to all 23 locale files plus the default | `res/values*/strings.xml` | NEEDED | Standing rule: all user-visible strings fully translated. "EA" is a brand name so the value is identical in every locale | compiled |

Deliberately NOT added yet (would be dead code until a library tab exists):
`eaDownloadFolder` in `PrefManager`, the EA entry in `StoreInstallPathSafety.configuredDownloadRoots()`,
a Room entity/DAO, `StoreArtworkCache` EA refs, and the five duplicated store-tab key lists
(`UnifiedActivityStartup`, `UnifiedActivityHub`, `UnifiedActivityDrawer`, `PrefManager.DEFAULT_STORE_VISIBLE`
plus its one-time merge flag). If a library tab is added later, all of those are required together.

---

## G. Steam+EA hand-off — where it actually stops (measured 2026-09-08)

The hand-off chain is now complete up to EA's account-linking call. Measured end to end on device,
container 1 (arm64ec), Steam appId 1222670 (The Sims 4):

| Step | Result | Evidence |
|------|--------|----------|
| Plan W agent logs the Steam client on | works | `Steam_BLoggedOn=true after 48x100ms` |
| Steam confirms entitlement | works | `BIsSubscribedApp(appId=1222670)=1 after 2600ms (license sync complete)` |
| Steam issues game-connect tokens | works | `BGameConnectTokensAvailable -> 1` |
| Agent dispatches the launch URI | works | `launching "start.exe" with explicit args (shell-URL dispatch)` |
| `start.exe` resolves the `link2ea:` handler | works | `Link2EA.exe link2ea://launchgame/1222670?platform=steam&theme=ts4` |
| Link2EA obtains the Steam encrypted app ticket | works | `SteamSDKHelper::Impl::onSteamTicketActivated: Result:1` |
| Link2EA reaches EA's linking endpoint with that ticket | works | `GET accounts.ea.com/connect/linking/ondevice?...&client_id=JUNO_PC_CLIENT&external_party=steam&steam_code=<ticket>&pc_sign=...` -> `status[302/302]` |
| EA returns an auth code | **FAILS** | `user.auth.ends {"error":"AuthCodeError_EmptyAuthCode","flow":"ExternalLogin","status":"silentFailure"}` and `link2ea.accountLink.info {"mesg":"Account Linking Requested"}` |
| Link2EA falls back to an interactive EA login | reached, does not render | `LoginUIHandler::uiLogin for [ExternalLogin] flow` -> `createQmlView [Auth]` -> `Browser created, id:[1]`, then silence |

### G1. The remaining functional gate is account linking, not code

EA answers the silent `linking/ondevice` call with a 302 carrying no `code=`, and Link2EA's own
telemetry names it: *Account Linking Requested*. That is EA's documented first-run behaviour when a
Steam account has never been linked to an EA account. Nothing in WinNative can mint that link; it is
a one-time account action. Once linked, the silent path should return a code and no UI is needed.

### G2. The interactive fallback cannot render — CEF's utility processes die

When the silent path fails, Link2EA opens its own `Auth` window backed by CEF. Measured: of the four
CEF children it launches, only two survive.

| CEF child | State |
|-----------|-------|
| `--type=gpu-process` | alive |
| `--type=renderer` | alive |
| `--type=utility --utility-sub-type=network.mojom.NetworkService` | **exited** |
| `--type=utility --utility-sub-type=storage.mojom.StorageService` | **exited** |

With no NetworkService the browser can never fetch the login page, so `Browser created, id:[1]` is
the last thing Link2EA logs and the `Auth` window never becomes visible. This — not the X server —
is why the EA login is blank. It reframes section A: A1/A2/A4 are X conformance fixes, but the EA
window was never going to paint a login page that CEF could not fetch.

### G3. Link2EA crashes intermittently before it gets that far

Same input, three runs: two reached EA Desktop, one died with
`ACCESS_VIOLATION writing address 0x67774` at `Link2EA.exe+0x3c37bd` on the main thread, immediately
after `loadCloudConfig: Cloud config signature check passed`. An earlier run crashed at a different
point (after `AuthComponent` init). A crash whose point moves under identical input is a race or an
emulation defect, not a code path. Not yet root-caused.

### G4. Wine trace logging was a large confound

`enable_wine_debug=true` with classes `trace,warn,err,fixme` produced 12 MB of
`trace:seh:RtlInitializeExtendedContext2` in one session and coincided with
`UI thread has not serviced the CEF message pump for [5534] ms`. Any timing measurement of EA taken
with the debug drawer attached is suspect. This is a user setting, not a code change — no source was
modified for it.

### G5. Benign noise, ruled out

- `IWbemLocator->ConnectServer failed hresult=0x80041002` — WMI absent; EA only loses the antivirus name.
- `EnableNonClientDpiScaling() failed ... (Call not implemented.)` — Qt logs it and continues.
- `WSALookupServiceBegin failed with: 0` in `cef.log` — present on every run including the two that succeeded.
- `DataDecryptError` / `Failed to deserialize the config store` — present on runs that crashed and runs that did not.

---

## H. CEF rendering + the account-link route (2026-09-08, later session)

### H1. Measured: the EA login page LOADS; it is the paint that fails

Launching EA Desktop directly on container 2 (x86_64/box64) produces, in `EADesktop.log`:

    createQmlView [Auth]
    AuthViewHolder::handlePageLoading   Login page load started
    AuthViewHolder::handlePageLoadSuccess  Login page load succeeded, HttpStatus:[200]

The window frame, EA logo and window controls all render. The CEF content area stays empty.
So for container 2 the blocker is **not** network and **not** EA — the page is fetched and the
frame paints; only the CEF surface never appears. This is a different failure from G2 (container 1,
where the CEF NetworkService child dies).

### H2. NEEDED: `EACefSubProcess.exe` was missing from the embedded-browser overrides

`EMBEDDED_BROWSER_HELPER_EXES` covered `EADesktop.exe`, `Link2EA.exe`, `EABackgroundService.exe`
and friends, but **not `EACefSubProcess.exe`** — the process that actually does CEF's GPU and
renderer work. Measured on container 1: adding the d3d-builtin overrides for it took the surviving
CEF children from **2 to 5** and produced the first EA window that has ever rendered on arm64ec.

`applyEmbeddedBrowserHelperOverrides` now writes two groups, matching GameNative's shipped EA App
support (`utkarshdalal/GameNative` PR 1859):

| Group | Exes | d3d dlls | `AppDefaults\<exe>\Direct3D` `renderer` |
|-------|------|----------|------------------------------------------|
| Qt hosts | the existing `EMBEDDED_BROWSER_HELPER_EXES` | `builtin` | **removed** |
| CEF hosts | `EMBEDDED_BROWSER_SOFTWARE_RENDERER_EXES` = `EACefSubProcess.exe` | `builtin` | `no3d` |

Revert: delete `EMBEDDED_BROWSER_SOFTWARE_RENDERER_EXES` and its loop, and the `removeValue` call.

Measured limits: `renderer=no3d` alone does **not** fix H1 — with it applied the container HUD drops
from `DXVK` to `Vulkan` (so the switch is taking effect) and the content area is still blank. It is
kept because the d3d half is measured to help and the split is what the only known-working
implementation does; the paint bug is still open. Re-verified after the APK build: the app writes
`EACefSubProcess.exe\Direct3D` `renderer=no3d` and leaves `EADesktop.exe` with no `Direct3D` block,
and container 2 still renders the frame with an empty content area.

### H3. NEEDED: X server had no `X_ListInputDevices` handler

`XInput2Extension` handled minor opcodes 1, 45, 46, 47 and 48. Opcode **2** (`X_ListInputDevices`,
XI 1.x) fell to `default:`, which skips the request and **sends no reply** — a protocol violation for
a request that expects one. GameNative had to add this handler for EA Desktop's Qt UI. Added as a
minimal conforming reply (`ndevices = 0`).

Honest scope: no `unhandled minor opcode=2` line was observed in this session's logs, so this is a
conformance fix and a removed hazard, **not** a measured cause of the current symptom. Device-tested
after the build: it changes nothing about H1, and regresses nothing.

### H4. NEEDED: the Steam↔EA account link, done in our own WebView

G1 established that the Steam→EA launch stops because the accounts are not linked, and that
`pc_sign` puts the in-client linking call out of reach. The link does **not** require CEF or the
container: it is a normal web flow on EA's account site, and WinNative already has a working EA
WebView sign-in.

Added: `EaConstants.EA_CONNECTED_ACCOUNTS_URL`, `EaLinkActivity` (the existing `AuthWebViewDialog`
with third-party cookies enabled, pointed at `myaccount.ea.com/am/ui/connected-accounts`), an
optional secondary action on `StoreCard`, and a **Link Steam** button on the EA card that is shown
only while signed in. One new string, `ea_store_link_steam`, translated into all 22 locales.

This is the route that actually unblocks "EA games on Steam": once the accounts are linked, Link2EA's
silent `connect/linking/ondevice` call should return an auth code and the interactive login — and
therefore the whole CEF paint problem — is never reached on the Steam path.

**Device-verified 2026-09-08**: the button renders on the EA card only while signed in, and opening it
loads EA's real account site and reaches EA's own two-factor prompt ("Verify your identity" ->
SEND CODE). Completing the sign-in and the Steam link is a user action and was deliberately not
performed.

Revert: delete `EaLinkActivity`, its manifest entry, the `secondaryLabel`/`onSecondary` parameters
on `StoreCard`, `onEaLinkSteam`, the constant and the string.

### H5. Device residue left behind by testing (not code)

Container 1's `user.reg` was hand-appended during A/B testing with d3d-builtin overrides for the
whole EA helper set (`EASteamProxy.exe`, `GetGameToken.exe`, `PolicyProxy64.exe`, …), not just
`EACefSubProcess.exe`. They are inert overrides, but they are **test residue, not shipped
behaviour** — the code writes only the two groups in H2. Backups: `files/user.reg.bak` (container 1),
`files/user2.reg.bak` (container 2). A custom `EA App` shortcut was also added to container 1's
Desktop directory.

The `enable_wine_debug` device setting was turned **off** during this session (see G4); it is a user
setting and no source was changed for it.

### H6. Rejected by verification — do not re-explore

- `--enable-features=NetworkServiceInProcess` is a **silent no-op** on Chromium 109; the registered
  feature string is `NetworkServiceInProcess2` (confirmed in EA's own shipped `libcef` binary).
- There is **no environment variable** that injects Chromium switches on Windows; `CHROMIUM_FLAGS`
  is a distro wrapper convention.
- Wine implements **no IFEO `Debugger` interception**, so the "wrap any exe" trick is unavailable.
- `WSALookupServiceBegin failed with: 0` is a Chromium logging artifact over a Wine stub, present on
  every run including the ones that worked. Benign; stop chasing it.
- The CEF sandbox is ruled out by source: `--no-sandbox` forces `kNoSandbox`, so `LowerToken()` is
  never called for either utility process.
- `--field-trial-handle` shared-memory bootstrap cannot be the crash in a release CEF build.
- WineHQ AppDB/Bugzilla, ProtonDB, Lutris, Bottles, Heroic and CrossOver have **nothing** on this
  symptom. The only useful sources are GameNative and FEX-Emu.

### H8. CLOSED BY MEASUREMENT: no Chromium switch can be injected into EA Desktop

Tested on device with a `.cmd` wrapper (`buildGuestProgramArgs` already routes `.bat`/`.cmd` through
`cmd.exe /c`, so a custom shortcut pointing at a script is a supported way to pass switches — the
shortcut `Exec=` field itself cannot carry arguments, because `Shortcut.java:90-97` takes everything
after `wine ` as the executable path and only strips quotes when the whole string is quoted).

Launching `EADesktop.exe --disable-gpu-watchdog` through that wrapper works, but the running process's
own command line is:

    wine C:\...\EA Desktop\EADesktop.exe -ls=Launcher

`-ls=Launcher` was never passed by us. **EA Desktop re-execs itself**, discarding the original command
line (its log already said `Client started from updater: [true]`). The switch is therefore absent from
the browser process and from every CEF child — verified by reading `/proc/<pid>/cmdline` for all four
children (`--type=gpu-process`, `--type=renderer` x2, `--type=utility` x2): none carries it.

Consequence: **the entire switch-injection route is dead for EA Desktop** — `--disable-gpu`,
`--in-process-gpu`, `--enable-features=NetworkServiceInProcess2` and every other lever in H6/H7 cannot
be delivered to it, whichever way we start it. It may still work for `Link2EA.exe`, whose own log shows
our arguments arriving intact, but Link2EA only matters on the Steam path — which the H4 account link
is designed to make silent anyway.

**CORRECTION (2026-09-08, found while preparing the commits): a per-exe Chromium-switch table DOES
exist.** `hack_append_command_line()` in `dlls/kernelbase/process.c:610-629` appends switches by
executable name at `CreateProcess` time, and this tree already carries a WinNative entry in it:

    {L"UplayWebCore.exe", L" --use-angle=swiftshader"},

alongside upstream entries for `UnrealCEFSubProcess.exe`, EverQuest and others (some gated on a
SteamGameId).

This matters: because the table matches on the executable being started, a switch added for
`EACefSubProcess.exe` or `EADesktop.exe` **would survive EA Desktop's self re-exec**, which is the very
thing H8 concluded made switches unreachable. H8's measurement stands — a switch appended to the
*launch command* is discarded — but its conclusion that no switch can reach EA was wrong, and the
`--disable-gpu` / `--in-process-gpu` levers listed in H6/H7 are back on the table via this route.

Not attempted yet. Note H16 measured that CEF is healthiest when left entirely alone on this stack, so
any entry here needs the same before/after counts (`Exiting GPU process`, `eglInitialize`,
`SPA load success`) rather than being assumed to help.

### H16. SOLVED: the CEF instability was MY overrides. H2 is retracted and reverted.

`cef.log` names the cause outright. With `renderer=no3d` on `EACefSubProcess.exe`, ANGLE cannot bring
up EGL at all, so the GPU process exits and is relaunched forever:

    EGL Driver message (Critical) eglInitialize: No available renderers.
    eglInitialize D3D11 failed with error EGL_NOT_INITIALIZED, trying next display type
    eglInitialize D3D9 failed with error EGL_NOT_INITIALIZED
    GLDisplayEGL::Initialize failed.
    Exiting GPU process due to errors during initialization

Chromium's ANGLE needs a working D3D to initialise EGL, and `no3d` makes wined3d refuse every device.
Without a `--disable-gpu` switch to tell Chromium not to try — and H8 proved no switch can reach EA —
it just crash-loops. That crash loop *is* H9: the dying children, the blank login, the `ERR_ABORTED`
SPA aborts.

Three configurations for `EACefSubProcess.exe`, measured on container 2 with the same launch:

| | `renderer=no3d` + d3d builtin | d3d builtin only | **no overrides (CEF on DXVK)** |
|---|---|---|---|
| `eglInitialize` failures | 30+ | 0 | **0** |
| `Couldn't create surface` | - | 3 | **0** |
| `Exiting GPU process` | 44 | 3 | **0** |
| EA SPA | `ERR_ABORTED` | `ERR_ABORTED` | **`SPA load success` x4** |
| EA library | never | never | **renders, with The Sims 4 + Steam badge** |

The middle column's failure is its own distinct error — EGL comes up, then Skia cannot make a GL
output surface (`framebuffer_info.fFBOID=0`, `fFormat=0`, `willGlFBO0=1`) because the stack is
ANGLE -> D3D11(wined3d) -> OpenGL -> Zink. Letting CEF use DXVK's D3D11 straight to Vulkan removes it.

**H2 is therefore wrong and fully reverted.** `EACefSubProcess.exe` is not in
`EMBEDDED_BROWSER_HELPER_EXES` and gets no `Direct3D\renderer`; the stale keys were also deleted from
the device prefix. The "2 -> 5 surviving children" measurement that justified H2 was a real
observation misattributed to the override — the correct configuration is to leave CEF's own process
alone entirely.

### H22. ROOT CAUSE of the last mile: Wine's `netprofm` reports no internet, so EA goes offline

Corrected first: the background service **is** authenticated. `"authenticated":false` in its telemetry
is not meaningful for a service — its log plainly shows the token arriving:

    UserSessionController::onSessionChanged   Handling user session event
    ContentInstallComponent::handleUserSessionActive   AuthToken set to [value], nuchash=[1085c7a6...]

The real failure is the very next line:

    ContentLibraryComponent::handleNucleusCacheLoaded   Nucleus Entitlement Cache load failed, moving to full
    ContentLibraryComponent::handleLoadEntitlementFailure   failed to load entitlement due to [Offline]

With no entitlements loaded, `EALaunchHelper` dereferences null (H21) and the game exits.

EA is **flapping**: 70 x `Application state has changed to [offline]` against 24 x `[online]`, and the
transition is the *system/link* half, not the internet half:

    updateSysInternetConnectivity   Sys connectivity changed from [online] to [offline]
    connectivity::impl::updateState Connectivity changed from [connected|online] to [disconnected|online]

A targeted Wine trace (`WINEDEBUG` classes `fixme,err,warn`, channels
`winsock,iphlpapi,wininet,nsi,netprofm`) names the API EA is asking, over a thousand times in one run:

    fixme:netprofm:list_manager_GetConnectivity ...   (x1000+)

`INetworkListManager::GetConnectivity` is a **Wine FIXME stub**. Reading
`dlls/netprofm/list.c:1357-1377`, it returns `NLM_CONNECTIVITY_DISCONNECTED` and only ORs in
`NLM_CONNECTIVITY_IPV4_INTERNET` for a network whose `connected_to_internet` is set — and
`init_networks` (`:1810-1819`) sets that **only when `aa->FirstGatewayAddress` is non-NULL**.
`get_network_adapters` (`:1765`) does pass `GAA_FLAG_INCLUDE_GATEWAYS`, so the whole thing reduces to
whether `GetAdaptersAddresses` yields a gateway, which
`gateway_and_prefix_addresses_alloc` (`iphlpapi_main.c:1060-1094`) fills only from an NSI forward-table
route with a **non-zero next hop** for that adapter's LUID.

Supporting evidence from the same trace:
- `warn:iphlpapi:GetBestRoute2 Could not find matching unicast addr for system route` x11 — routes
  exist for interfaces with no unicast address (the `ifb0-2`/`dummy0` adapters).
- `fixme:winsock:WSALookupServiceBeginW ... Stub!` — the NLA path is also stubbed.
- `warn:nsi:notification_thread_proc nsi_get_notification failed`.

Note `init_networks` runs **once per list-manager instance**, so a given instance's answer is fixed;
the flapping means EA re-creates the manager and gets different answers on different polls. That fits
a route table whose default route is not reliably present.

Container 2 already has `WINE_NEW_NDIS=1` and `WINE_ANDROID_GATEWAY=192.168.50.1`, so the ported
default-route work is active — this is about whether that route reaches `FirstGatewayAddress`.

**MEASURED, and it exonerates netprofm.** `ipconfig /all` inside container 2 (run through a `.cmd`
shortcut) gives `wlan0` a correct IPv4 setup:

    Ethernet adapter wlan0
        IPv4 address. . . : 192.168.50.116
        Default gateway . : 192.168.50.1

So `FirstGatewayAddress` **is** populated, `connected_to_internet` **is** set for that network, and
`GetConnectivity` returns `NLM_CONNECTIVITY_IPV4_INTERNET`. The `fixme:` is Wine flagging an
incomplete implementation, not a wrong answer. **Do not "fix" netprofm** — I over-read the FIXME the
same way I over-read `renderer=no3d`'s effect earlier.

(The other adapters — `ifb0-2`, `dummy0` — carry only IPv6 link-local addresses and no gateway, so
they OR in `NLM_CONNECTIVITY_IPV4_LOCALNETWORK` alongside wlan0's INTERNET bit. Real Windows would not
set both; whether EA minds is untested.)

### H26. Self-sufficiency achieved, verified on a clean device (2026-09-08)

Tested on a second device (OnePlus CPH2749, Android 16, `com.winnative.cmod`) that had only the APK and
the `versionCode 3` Proton layers installed — no hand-built EA client, nothing carried over from the
tablet.

| step | result |
|------|--------|
| EA client present beforehand | no |
| `EaClientInstaller` staged the MSI from the game's own `__Installer` | yes |
| EA client installed into the prefix | yes — `Program Files\Electronic Arts\EA Desktop\EA Desktop\EADesktop.exe` |
| Steam -> EA authentication | `{"flow":"ExternalAuthCode","status":"success","user_input":"none"}` |
| EA background service | `online=1`, `entfail=0`, `AuthToken set to [value]` x4 |
| `EALaunchHelper` | no crash, clean `goodbye` |
| The Sims 4 | starts (`game process started pid=1032`) |

So **app + Proton layers + press Play** is now enough on a fresh device: the EA client installs itself
from the shared depot Steam already delivered, and the Steam->EA hand-off authenticates silently with
no sign-in and no UI. Nothing is downloaded from us and nothing of EA's is redistributed.

Two fixes were needed to get there, both found on this device:

**H26a. The installscript root was wrong.** `resolve_game_root_dir` derived the root from the launch
target, which in the Steam+EA path is `start.exe`, so the agent scanned `C:\windows\system32` and
logged `installscript: none found`. It now derives the root from the post-dispatch executable when one
is set, so the game's own install scripts and redistributables are actually seen.

**H26b. That fix then exposed the EA bootstrapper deadlock.** With the correct root, the redist scan
found `__Installer/Origin/redist/internal/EAappInstaller.exe` and ran it — and it hangs exactly as
recorded months ago, sitting in its WiX Burn clean-room copy under `C:\windows\Temp\{GUID}\.cr\`
for minutes while the launch splash reads "Installing EAappInstaller". `scan_and_install_redists` now
skips that one executable by name, because the EA client is installed from the extracted MSI instead.

### H28. The EA-reinstall-every-launch regression, and its fix

Reported from the phone as "it installs EA every time". It was real, it was **mine**, and it came from
H26a: once the install-script root resolved correctly, `scan_and_install_redists` found
`__Installer/Origin/redist/internal/EAappInstaller.exe` and ran it on **every** launch. EA's WiX Burn
bootstrapper re-extracts itself into `C:\windows\Temp\{GUID}\.cr\` and then deadlocks — exactly the
hang recorded months ago — so each launch sat for minutes with the splash reading
"Installing EAappInstaller" (the text `WnLauncherStatusTailer` derives from the agent's
`installscript: running "..."` line).

Fixed by skipping that one executable by name in the redist scan. Verified on device: the agent log
from the `Sep 8 2026 22:39:09` build contains **zero** `installscript` / `redist` lines, and no
`EAappInstaller.exe` process appears. The EA client is installed once, from the extracted MSI.

Watch for this if the root resolution is ever touched again — a correct game root means the redist
scanner sees everything the title ships, including installers that must not be run.

### H29. Where `EALaunchHelper` actually gives up

`EALaunchHelperVerbose.log`, the last 0.2s of its life:

    Mailbox [LaunchHelperComponent Inbox] ... [CheckAuthV...]
    Mailbox [LaunchHelperComponent Inbox] ... [ExternalAction]
    MainFlow::Impl::rtpLaunchPending        LaunchHelper flow: rtpLaunchPending = true
    MainFlow::Impl::onEnterWaitingForRtpLaunch  RTP launch timer, not active. Starting now.
    MainFlow::Impl::startRtpLaunchTimer
    Mailbox ... [ChangeSplashV...]
    MainFlow::Impl::onTerm                  LaunchHelper flow: terminating      <-- 0.17s later
    AppMain                                 Closing app, starting teardown

It is **not** timing out. It enters `WaitingForRtpLaunch`, starts the timer, and is terminated almost
immediately. RTP is EA's run-time protection hand-off, and the flow is built around EA being the party
that launches the title. We start the game ourselves (H20) and the game then calls back into EA — so
EA is asked to wait for a launch that has already happened. That ordering is now the leading suspect,
ahead of the session-propagation theory in H27.

**Experiment set up but not completed** (the phone dropped off the network mid-run): the agent gained
`WN_STEAM_POST_DISPATCH` (default 1) so the post-dispatch launch can be turned off from the container
environment, and the phone's container 1 was set to `WN_STEAM_POST_DISPATCH=0` to see whether EA
launches The Sims 4 itself once it is authorised and its entitlements load. **That variable is still
set on the phone** — with it at 0 nothing starts the game, so it must be removed or set to 1 before
normal use.

### H27. The one remaining defect: `EALaunchHelper` never sees the session

Same on both devices and both containers, and now measurable with everything else healthy:

    EABackgroundService.log:  "AuthToken set to [value]"  x4     "authenticated":true
    EALaunchHelper.log:       "authenticated":false       x14    (never true)

The game starts, performs its DRM callback correctly — `origin2://game/launch/?offerIds=1011164,...`
reaches `EALaunchHelper`, which logs `game.launch.strt` — and then the helper exits and the game exits
with it, ~12s after starting. On the phone the helper exits cleanly (`goodbye`, no crash report); on
the tablet it null-dereferenced, which H25 fixed.

Crucially the game does **not** crash: a `-all,err+seh` capture of the whole launch contains zero
unhandled exceptions, zero missing-module errors. TS4 decides to quit.

So the background service holds an authenticated session and the launch helper, which connects to it
over IPC, does not inherit one. That is the single thing left between here and the game running.

Measurement note: do not enable the `module` debug channel on a real launch — one run produced a
**5.5 GB** `wine_tail` log, which is both useless and slow enough to distort the run. `err` on `seh`
alone gives a 54 KB capture with the same diagnostic value.

### H25. SOLVED: `netprofm` caches a failed adapter enumeration forever

Bisected it properly instead of guessing. Added a temporary `ERR` to
`list_manager_GetConnectivity` printing the value it actually hands back, and ran the Steam->EA launch:

| value returned to EA | meaning | calls |
|----------------------|---------|-------|
| `0x60` | `IPV4_INTERNET \| IPV4_LOCALNETWORK` | 941 |
| **`0x0`** | **`NLM_CONNECTIVITY_DISCONNECTED`** | **376** |

So netprofm's data is right *most* of the time, and catastrophically wrong the rest — which is why the
symptom looked like flapping. The cause is structural: `init_networks()` runs **once, in
`list_manager_create()`**, and simply returns early if `get_network_adapters()` yields nothing. Any
`INetworkListManager` instance created during such a moment holds an empty network list **for its
entire lifetime** and answers `DISCONNECTED` to every query. EA creates these instances repeatedly, so
it periodically gets a permanently-dead one — and if that is the instance its `PlatformNetworkMonitor`
holds, EA is offline for the whole session.

**Fix (layer):** `dlls/netprofm/list.c` gains

    static void refresh_networks( struct list_manager *mgr )
    {
        if (list_empty( &mgr->networks )) init_networks( mgr );
    }

called at the top of `GetConnectivity`, `IsConnectedToInternet` and `IsConnected`. An empty list is
meaningless, so re-enumerating costs nothing when enumeration is working and repairs the instance when
it is not. Applied to `build11-2/{x86_64,arm64ec}`; saved as
`docs/stores/wine-netprofm-refresh-networks.patch` so it survives (see the `git checkout` trap in H24).

**Measured on device, same launch as before the fix:**

| | before | after |
|---|--------|-------|
| `changed to [online]` | 0 | **1** |
| `handleLoadEntitlementFailure` | 1 | **0** |
| `EALaunchHelper` crash | yes (`ACCESS_VIOLATION reading 0x00`) | **no — clean `boot.sess.stop`, "goodbye"** |

The entitlement load succeeds and the null-deref in `EALaunchHelper` is gone. H21's crash and H23's
`[Offline]` are both resolved by this one change.

**Verified on container 1 (arm64ec) too.** Built and deployed the same change for the arm64ec layer
and ran EA there:

    c1 run1: (launch did not start — no log, no processes)
    c1 run2: online=1  entfail=0  EADesktop=running  EACefSubProcess=running

Same signals as container 2, so the fix is not x86_64-specific. Container 1 remains the flakier of the
two — one of two runs never got the container up at all, which is the pre-existing arm64ec
non-determinism (G3), not something this change introduced.

**Packaging note that cost a false alarm:** the freshly built arm64ec `netprofm.dll` is 1179648 bytes
against the shipped 589824, and EA Desktop died on the first run with it. That looked like the new DLL
breaking arm64ec. It was not — **the shipped layer is stripped**. `llvm-strip --strip-all` brings the
build to *exactly* 589824, the same size as stock (the same tell recorded for the rpcrt4 fix), and it
then behaves correctly. Strip layer DLLs before deploying, and never read a single arm64ec run as a
verdict.

Device left with: container 1 `netprofm.dll` 589824 (stripped, patched), container 2 `netprofm.dll`
245760 (patched, unstripped — measured working, worth stripping before it ships), container 2
`nsiproxy.so` back to stock 94512, `WINE_DISABLE_IPV6` removed, Wine and app debug logging off.

**Still open:** The Sims 4 now starts (`game process started pid=624`), runs ~15s and exits on its own
without writing anything. That is a new, narrower question than the EA-side chain and needs its own
investigation — the EA authorisation path itself is now healthy.

### H24. ROOT CAUSE FOUND: EA probes the internet over IPv6, which has no route

A `+winsock` trace of the background service's startup catches it directly. EA opens AF_INET6 sockets
for everything (v4-mapped, `::ffff:23.79.181.214:443` etc. — Banner's `AI_V4MAPPED` patch is doing its
job), and among them:

    trace:winsock:connect  addr { family AF_INET6, address 2001:4860:4860::8888, port 443 }   x2
    trace:winsock:WS2_sendto status 0xc000023c                                                 x7
    warn:winsock:try_send sendmsg: Network is unreachable                                      x8

`2001:4860:4860::8888` is **Google Public DNS over IPv6**, and `0xc000023c` is
`STATUS_NETWORK_UNREACHABLE`. That is EA's connectivity probe. The container has IPv6 *addresses*
(link-local plus an `fdfb:78d:...` ULA on wlan0) but **no IPv6 route** — the ported
`ipv4_forward_enumerate_all` emits IPv4 routes only — so the probe fails instantly and
`PlatformNetworkMonitor` latches `Sys connectivity ... to [offline]` on its first evaluation. From
there: entitlement load fails `[Offline]`, `EALaunchHelper` finds no entitlements and null-derefs, and
the game exits.

This is the same "addresses present, no route" signature recorded in the project notes months ago, now
tied to a specific failing probe rather than inferred.

**Fix applied (layer, env-gated):** `dlls/nsiproxy.sys/ip.c` gains `wine_disable_ipv6()`, read once
from `WINE_DISABLE_IPV6`. When set, `ip_unicast_enumerate_all` reports zero AF_INET6 addresses and
`ip_unicast_get_all_parameters` returns `STATUS_NOT_FOUND` for AF_INET6, so the guest's view matches
reality: IPv4-only internet. Applied to both `build11-2/x86_64` and `build11-2/arm64ec`. Default
behaviour is unchanged — with the variable unset nothing differs, so no other game is affected.

Revert: delete `wine_disable_ipv6()` and its two call sites.

**MEASURED, AND REVERTED — the filter is not the fix.** The change works exactly as designed: with
`WINE_DISABLE_IPV6=1`, `ipconfig /all` in the container shows `wlan0` with only
`IPv4 address 192.168.50.116` / `Default gateway 192.168.50.1` and **no IPv6 addresses at all**.

EA does not care. With the guest showing zero IPv6, the trace is byte-for-byte the same:

    2001:4860:4860 connects = 2      (unchanged)
    Network is unreachable  = 8      (unchanged)
    non-mapped AF_INET6 connects = 2 (unchanged)

and `offline=1 online=0 entfail=1`, game still exits. **EA's probe target is hardcoded**, chosen
independently of whether the machine has any IPv6 address. So hiding the addresses cannot help.

Reverted in `build11-2/{x86_64,arm64ec}` and the rebuilt `nsiproxy.so` was rolled back on the device
from `files/nsiproxy.so.bak`; the `WINE_DISABLE_IPV6` container variable was removed.

**Trap hit while reverting, worth recording:** `git checkout -- dlls/nsiproxy.sys/ip.c` also discarded
the **uncommitted default-route port** that lives in that file (`WINE_ANDROID_GATEWAY`), because it is
a working-tree modification and *not* in `android/patches/dlls_nsiproxy.sys_ip.c.patch`. Restored by
copying `p11-2-work/dlls/nsiproxy.sys/ip.c` back over both trees — diff is again 40 insertions /
22 deletions with `WINE_ANDROID_GATEWAY` present and no IPv6 filter. **Never `git checkout` a layer
file to undo an edit; that tree carries uncommitted ports.**

Remaining levers for the probe itself, in order of preference:
1. `netshim` (ours, already `LD_PRELOAD`ed) — intercept `connect`/`sendto` to non-mapped IPv6
   destinations and satisfy them over IPv4, so the probe reports reachable. Note the staging trap:
   `GuestProgramLauncherComponent` re-copies `libnetshim.so` from the APK every launch, so a rebuilt
   `.so` must be delivered by installing the APK, not pushed by hand.
2. Reverse EA's `NetConn` connectivity logic to learn exactly what it requires before deciding, rather
   than inferring from one probe.

### H23. Where the last defect actually sits

`EABackgroundService`'s connectivity monitor reports offline on its **first** evaluation, every run,
immediately after the thread starts:

    VERBOSE  makeNamedThread   Thread started [PlatformNetworkMonitor], TID:[156]
    INFO     updateSysInternetConnectivity   Sys connectivity changed from [online] to [offline]

It is the *system/link* half that flips (`[connected|online] -> [disconnected|online]`), and the
entitlement load then fails against that state:

    handleNucleusCacheLoaded        Nucleus Entitlement Cache load failed, moving to full
    handleLoadEntitlementFailure    failed to load entitlement due to [Offline]; trigger: [InvalidCacheLoad]

and never retries — no `changed to [online]` follows in the service log.

Two things ruled out by measurement:
- **Not a warm-up race.** Delaying `sc start EABackgroundService` by 25s so the container network is
  fully up first changes nothing: still `offline=1 online=0 entfail=1`, game still exits. The handler
  was restored to the H14 form afterwards.
- **Not netprofm** (see H22) — wlan0's gateway is present and correct.

Remaining lead, with direct evidence: the container's **IPv6 is half-configured** — wlan0 carries ULA
and link-local IPv6 addresses but `ipconfig` shows **no IPv6 default gateway**, and the Wine trace of
the same run contains `warn:winsock:try_send sendmsg: Network is unreachable` x8. That is the exact
signature recorded months ago in the project notes ("addresses present, no route; an IPv6 connect
returns WSAENETUNREACH in 0ms"). If EA's monitor probes over IPv6 first, it would latch offline while
IPv4 works fine — which is precisely what the logs show.

Next step: stop exposing IPv6 to the guest (filter it in nsiproxy's address enumeration, or otherwise
disable IPv6 in the container) and re-check whether `Sys connectivity` still flips to offline. The
secondary lead is the `DataDecryptError` on `CONF-production`, which is what makes the entitlement
cache invalid (`InvalidCacheLoad`) and forces the online refresh in the first place — a valid cache
would sidestep the connectivity question entirely.

### H20. IMPLEMENTED + MEASURED: the agent now starts the game after EA authorises

`wn-steam-launcher/src/main.cpp` gained an optional **fourth spec line** — the real game executable.
When present, after dispatching the `link2ea://` URI the agent waits for `EADesktop.exe` to appear
(`WN_STEAM_EA_WAIT_MS`, default 120s), settles (`WN_STEAM_EA_SETTLE_MS`, default 20s), then
`CreateProcess`es the game and watches *it* instead of the URI dispatcher.
`XServerDisplayActivity.writePlanWGameSpec` gained a matching `postDispatchExeWinPath` parameter, and
the C4 branch fills it from `gameDirName` + `relativeExe`.

Device-measured:

    spec file C:\wn-steam-game.spec -> exe=C:\windows\system32\start.exe appId=1222670
       args="link2ea://launchgame/1222670?platform=steam&theme=ts4"
       post=C:\Program Files (x86)\Steam\steamapps\common\The Sims 4\Game\Bin\TS4_DX9_x64.exe
    post-dispatch: EADesktop.exe is up after 6000ms; settling 20000ms before starting "TS4_DX9_x64.exe"
    game process started pid=1296 via CreateProcess fallback ("TS4_DX9_x64.exe")
    post-dispatch: "TS4_DX9_x64.exe" started; watching it instead of the URI dispatcher

One bug found and fixed while doing it: `create_process_game`'s already-running guard matches by
**game directory** as well as exe name, so after `wn_launcher_set_game_exe(postName)` it saw unrelated
processes and returned success *without launching*. Added `g_bypassRunningGuard` for the post-dispatch
call, and it now sets `wn_launcher_set_game_dir` to the game's own directory.

**Remember: `app/src/main/cpp/wn-steam-launcher/build.sh` must be run by hand — Gradle only packages
the prebuilt asset.**

### H21. The Sims 4 now launches and runs. It exits because `EALaunchHelper.exe` crashes.

Confirmed on device: `ts4_running=1` with EA Desktop authenticated (21 x `"authenticated":true`).
The game starts, runs, and performs its DRM handshake correctly — `EALaunchHelper.log` shows the game
calling EA back:

    Received command protocol request on boot: [origin2://game/launch/?offerIds=1011164,1015875,...]
    game.launch.strt {"request_owner":"EA","request_source":"RTP","title_launch_source":"rtp_launch"}

So the full chain works: Steam logon -> entitlement -> encrypted app ticket -> EA auth -> EA Desktop ->
game start -> game's own `origin2://` runtime-protection callback -> `EALaunchHelper`.

It then dies:

    ExceptionHandlerClient::Notify  Whoops!  ************  CRASH DETECTED  ************
    EALaunchHelper.exe.txt: ACCESS_VIOLATION reading address 0x00
      at EALaunchHelper.exe+0x439d68 (0x000000014043ad68), call stack empty

and the game exits with it, ~25-60s after starting.

The likely cause is right there in the logs: **`EABackgroundService` never reports an authenticated
session** — 0 occurrences of `"authenticated":true` against 694 of `"authenticated":false`, even on
runs where `EADesktop.exe` is authenticated 21 times. `EALaunchHelper` connects to the background
service (`IPC client [EALaunchHelper.exe], connected to [localhost:37919]`), asks for the session, and
dereferences null. EA Desktop and the background service hold their session state separately, and only
EA Desktop has one.

That is the single remaining defect. Next step is to establish why the background service never
authenticates while EA Desktop does — its own log is the place to look, not the game's.

### H19. What is actually missing: nobody starts the game executable

Investigated the "EA does not launch it" symptom properly. Everything EA needs is present and correct:

| Checked | Result |
|---|---|
| `Software\Valve\Steam\Apps\1222670` | complete — `Installed=1`, `Name`, `Running`, `Updating` |
| `Software\Maxis\The Sims 4` | `Install Dir = F:\Documents\Games\The Sims 4\`, `Product GUID` |
| Drive `F:` in container 2 | present, `-> /storage/emulated/0` |
| Game files | `TS4_x64.exe`, `TS4_DX9_x64.exe`, `Data/`, `Delta/` all present |
| `EAStore.ini` | `StoreName=Steam`, `ManagementMode=ReadOnly` — the Steam-managed marker |
| `__Installer/installerdata.xml` | 14 contentIDs, matching the `origin2://game/launch/?offerIds=...` EA itself uses |
| EA library | shows The Sims 4 with a **Steam** badge |

So the earlier theory that our Steam-registry hide/restore strips what EA reads is **refuted** — the
subtree is intact, and the hide path only runs for *custom* shortcuts anyway
(`XServerDisplayActivity:7101-7103`), not Steam ones.

The real gap is structural. TS4's Steam appinfo has **no `.exe` launch entry** (C3), so Steam never
starts an executable; on a PC EA does it after authorising. Our C4 path replaces the game exe with
`start.exe` purely to dispatch the URI — so once EA authorises, **nothing in the chain starts
`TS4_x64.exe`**. EA's own `installedOfferIds` resolver erroring is a symptom of the same thing, not
the cause.

GameNative reached the same conclusion and does exactly this: *"start the existing game executable
after EA has authenticated the request."*

**Planned fix (not yet implemented):** have the Plan W agent, for a URI-dispatch launch, (1) dispatch
the `link2ea://` URI, (2) wait for `EADesktop.exe` plus a settle window, (3) `CreateProcess` the real
game exe. The agent already carries the exe + args in its spec file, so this is a fourth spec line and
a wait loop in `wn-steam-launcher/src/main.cpp`, plus the exe path from `SteamService` in the C4
branch. The Steam agent stays alive throughout, so the game keeps a live Steam client *and* an
authorised EA session.

Attempted as a shortcut first by chaining the game launch into the `link2ea` cmd handler
(`... & start "" Link2EA.exe "%1" & timeout /t 75 & "F:\...\TS4_x64.exe"`). It did not fire — nested
quoting with `start` inside `cmd /c "..."` is fragile, and the game never ran. The handler has been
restored to the known-good H14 form. Do this in the agent, not in a cmd one-liner.

### H17. Now blocking: EA never launches the Steam-installed game

With CEF healthy, auth succeeding and the launch request pending, the game still does not start:

- `installedOfferIds resolver]: error  undefined` (repeatedly) — EA cannot resolve which offers are installed.
- `1222670` appears **zero** times in `EADesktopVerbose.log` — EA has no idea about the Steam appid.
- EA never emits a `steam://` URL, so nothing hands the launch back.
- Container 2 had **no `steam` protocol handler at all**; container 1 has
  `@="\"C:\Program Files (x86)\Steam\steam.exe\" -- \"%1\""`. Added it to container 2 by hand;
  no change yet, so it is a genuine gap but **not** proven to be the fix. Not in code.

EA's library does show The Sims 4 with a Steam badge, so entitlements are fine — it is the *installed*
state EA cannot see. `Software\Valve\Steam\Apps\1222670 "Installed"=dword:1` is present in
container 2's `user.reg`, but the fuller key (with `Name`/`Running`) only exists in
`steam_registry_backup.reg`, which suggests the Steam-registry hide/restore (C1/C2) may be stripping
what EA reads.

### H18. Secondary blocker: a blank EA window covers the library

Two EA top-level windows are mapped: the back one renders the library correctly (banner *"Some
features have been disabled because the EA app launched from another application"*, "My games", the
TS4 tile), and a blank one in front covers it. Clicking the library does not raise it. This is the
artifact A1 was written for. Reproducible, and it blocks reaching EA's own Play button.

### H13. SOLVED: Steam -> EA authentication now succeeds

After the user completed the one-time confirmation in the Auth window, the Steam path authenticates:

    user.auth.ends {"flow":"ExternalAuthCode","status":"success","reme":"valid",
                    "request_owner":"STEAM","retries":0,"user_input":"none"}

No error, no UI. That is the whole G1/H12 chain closed — Steam logon, entitlement, encrypted app
ticket, EA linking call and auth code all work end to end.

### H14. NEEDED: let EABackgroundService settle before Link2EA fires

Immediately after auth started succeeding, EA Desktop still refused to launch the game and its state
machine said:

    DesktopFSM[awaitingAuthentication]: rejected (isPendingLink2EA == false)

i.e. EA received the authentication but had already discarded the launch request. GameNative documents
exactly this ("EA parks the link2ea request as PendingLink2EARequest and silently drops it") and fixes
it by starting `EABackgroundService` and giving it a settle window before handing over to `Link2EA.exe`.

Reproduced the fix on device by rewriting `HKCR\link2ea\shell\open\command` to

    "C:\windows\system32\cmd.exe" /d /s /c ""C:\windows\system32\sc.exe" start EABackgroundService >nul 2>&1
      & "C:\windows\system32\timeout.exe" /t 20 /nobreak >nul 2>&1
      & "<...>\Link2EA.exe" "%1""

**Measured:** the rejection disappears and Link2EA instead reaches
`Link2EARequest::doRequest: Waiting for the game launch to finish before shutting down ...`, with EA
Desktop transitioning `awaitingAuthentication -> authenticated` (`accepted (isAuthenticated == true)`).
The request is no longer dropped.

Implemented in `WineUtils.ensureEaProtocolHandlers` as `eaServiceSettleCommand()`. `link2ea` is now
handled separately from the other EA schemes: it is rewritten on every launch rather than skipped when
a command already exists, because EA rewrites its own protocol keys whenever the service starts.
`cmd.exe`, `sc.exe` and `timeout.exe` are all present in the prefix. Revert: delete
`eaServiceSettleCommand`, `EA_SERVICE_SETTLE_SECONDS` and the `link2ea` branch in the scheme loop.

### H15. Still blocking the game itself: EA Desktop's own UI falls over

With auth succeeding and the request pending, the game still does not start, because EA Desktop's SPA
dies before it gets there:

    LoadHandler::OnLoadError  browser-id:[1], code=[-3], errorText=[ERR_ABORTED], url=[https://pc.ea.com/...]
    -> "Couldn't connect to servers - We ran into a problem, but a quick restart should fix it."

Not a network fault: minutes earlier the same URL loaded with `SPA load success` /
`http-status=[200]`. `ERR_ABORTED` here is the renderer going away — H9 again. EA's own RESTART APP
button does not recover it. So H9 is now the *only* thing between a working authentication and the
game actually starting.

### H12. The link is done, and the remaining step is EA's one-time interactive confirmation

The user linked Steam to EA with the H4 button. Verified by opening EA's own Connected Accounts page:
**Linked -> Steam -> Voyager-1986**, with an Unlink button. Verified it is the *correct* Steam account:
the ticket Link2EA sends decodes to SteamID64 **76561198883608718**, the Plan W agent reports
`steamId=76561198883608718 user=fiberflyllc`, and both prefixes' `localconfig.vdf` give
`PersonaName = "Voyager-1986#2"`. Same account.

`connect/linking/ondevice` **still** answers with no auth code (`AuthCodeError_EmptyAuthCode`), so an
existing account link is not sufficient on its own. The request carries
`save_external_party_session=true`, and the fallback UI that EA then opens says, at the bottom of its
own sign-in page, *"Your Steam info is shared with EA"*. So `ExternalLogin` wants a one-time
**interactive confirmation for this device/session**, not merely a linked pair of accounts.

That makes the CEF rendering defect (H9) the last thing in the way, because the confirmation can only
be given in the Auth window. On container 2 that window does render, intermittently — it took two
attempts, and on the successful one Link2EA had 5 live CEF children and logged
`AuthViewHolder::handlePageLoadSuccess ... HttpStatus:[200]`, then painted EA's password page.

Practical guidance until H9 is fixed: relaunch the title until the Auth window paints (roughly one
attempt in two on container 2), complete the sign-in once, and subsequent launches should take the
silent path.

Ruled out while chasing this: the `setUpWmi ... ConnectServer failed` error that appears inside
`requestAuthCode` every time is **not** related — EA's own log line states its only consequence is
that it cannot retrieve the antivirus name.

### H10. Signing EA Desktop in does NOT create the Steam link (measured 2026-09-08)

The user completed EA's sign-in inside container 2's EA Desktop. Confirmed in `EADesktop.log`:
`Telemetry Event [login]` with `"authenticated":true`. The EA app is genuinely signed in in-prefix.

It changes nothing for the Steam path. Relaunching The Sims 4 from Steam on container 1 still gives:

    onSteamTicketActivated: Result:1
    Starting link2ea request. Access token exists.
    requestAuthCode  for ExternalLogin flow
    LoginUIHandler::uiLogin  Setting up UIlogin for [ExternalLogin] flow
    user.auth.ends {"authenticated":false,"error":"AuthCodeError_EmptyAuthCode",
                    "flow":"ExternalLogin","request_owner":"STEAM","status":"silentFailure"}

Two separate things, and only the second one is the gate:
1. **An EA session in a prefix** — per-container, stored in that prefix, and container 1 and container 2
   do not share it.
2. **A Steam↔EA account link** — server-side, container-independent, and what `ExternalLogin` needs.

So G1 stands unchanged: the only remaining functional gate on EA-games-from-Steam is the one-time
account link, and it must be done from EA's account site (the **Link Steam** button, H4) or by using
the **Steam** SSO button on EA's own sign-in screen. Signing in with email/EA ID does not create it.

### H11. A1 cleared structurally, not just statistically

Instrumenting the window tree while EA Desktop runs shows:

    root
      win=41943050 class='eadesktop.exe' at 101,72 806x576 children=1
        win=37749229 class=''            at 101,72 806x576 children=0

The CEF surface is a child occupying **exactly** its parent's rect. Clipping a child to its parent can
never remove it, so A1 could not have been hiding the login under any circumstances. This replaces the
statistical argument in the A1 retraction with a structural one.

The two stacked EA frames seen after a container restart are transient — a second sample minutes later
shows a single `eadesktop.exe` window in the tree, with only Wine's 1x1 dummy windows beside it.

### H9. The real remaining bug, stated precisely

**EA's login renders when every CEF child survives, and is blank when they die.** Measured by
repetition on container 2 (see the A1 retraction table): 13 processes -> full sign-in screen,
10-11 processes -> blank. Nothing in WinNative's renderer or X server is involved.

That makes the container 1 symptom (G2, `network.mojom.NetworkService` and
`storage.mojom.StorageService` exiting) and the container 2 symptom the **same bug**, differing only
in how often it bites. It is the single open defect standing between here and a working in-container
EA login.

What is now known about it:
- It is not the sandbox, not a field-trial handle, not proxy/WPAD, and not `WSALookupServiceBegin` (H6).
- It cannot be worked around with a Chromium switch, because EA Desktop re-execs itself and discards
  the command line (H8).
- It is intermittent under identical inputs, which points at a startup race or an emulation defect
  rather than a missing Windows API — consistent with every network DLL source being byte-identical
  between the arm64ec and x86_64 trees.

Next step that would actually move it: capture why a `--type=utility` child exits. It writes nothing to
`cef.log` (only the browser process has `--log-file`), so it needs Wine stderr captured with a narrow
channel set (`enable_wine_debug` on, classes `err`, channels `seh` — never the default `trace` set,
which is itself a confound, see G4), or a `--utility-startup-dialog` probe if a switch route is ever
found for a process EA does not re-exec.

### H7. Open, with the evidence needed to continue

- **The CEF paint bug (H1).** Next levers, one at a time, never stacked: `--disable-gpu-watchdog`,
  then `--disable-gpu --disable-gpu-compositing`, then `--in-process-gpu`. Note that appending
  switches to a WinNative-created `.desktop` did not launch in this session — use the protocol
  handler registry command instead, and put switches **after** `"%1"` so EA's own `argv[1]` URL
  parsing is not shifted.
- **Whether CEF honours appended switches at all.** Free check: after adding one, `ps` the container.
  If the switch shows up on the surviving `--type=gpu-process`/`--type=renderer` children, EA does
  not set `CefSettings.command_line_args_disabled`. If it does not, no switch route can work and the
  fix has to be the Wine per-exe Chromium-switch table.
- **Link2EA is non-deterministic on container 1**: four runs, four outcomes (crash / full success /
  CEF children die / hang before SteamSDK init). Treat any single run as noise.

---

## E-pre. Signing (how to reproduce an installable build)

`signing.properties` (gitignored) points at `/home/max/Documents/Key/WinNative.jks`, whose cert
SHA-256 `94de8fe1...` matches the installed app, so `adb install -r` upgrades in place with no data
loss. TRAP: the info file's "Key password" field is prose, not a password - keyPassword must be set
to the STORE password or packaging fails with "Given final block not properly padded".

---

## E. Explicitly NOT done, and why

| Item | Why not |
|------|---------|
| LSX shim (native EA game launching with no EA client) | Research showed The Sims 4 uses legacy OOA DRM (`Activation64.dll` has no LSX strings), so an LSX server would not launch it. Also no LSX traffic has ever been observed in this project. Needs a recorded transcript first. |
| FEX `HALFBARRIERTSOENABLED` change for the arm64ec CEF hang | Root-cause lead only, unverified. Touching emulator memory ordering affects every game. |
| ntdll `arm64x_check_call` null-exit-thunk guard | Built and measured earlier this session: moved the failure rather than fixing it. Reverted. |
| FEX `ret_sp_misaligned` x4 adjustment | Built, deployed and measured: changed nothing, both failing calls took the aligned path. Reverted. |
| `pc_sign` / `EaPcSign` (DELETED) | The research claimed HMAC keys `ISa3dp...` (v1) and `nt5FfJ...` (v2). **Neither string exists in the shipped `Link2EA.exe`**, and all 8 construction variants (v1/v2 x urlsafe/standard base64 x HMAC-over-raw/over-encoded) were rejected live with `{"error_description":"sig is invalid","code":102105}`. The real key table sits at `Link2EA.exe` 0xb74738 followed by ~256 bytes of high-entropy data - the keys are obfuscated in this build. Sidestepped entirely by using a client_id that does not require `pc_sign`. |
| `JUNO_PC_CLIENT` + client secret | Requires a valid `pc_sign` (`code:102105` without it), so it is blocked on the obfuscated keys. The extracted secret is therefore unused and is NOT shipped in the source. |
| `ORIGIN_SPA_ID` | Authorize works without `pc_sign` (302), but the token exchange returns `{"error":"invalid_client","code":101102}` without a secret we do not have, and it only accepts `https://www.origin.com/views/login.html` as a redirect. |
| Migrating Epic/GOG/itch token storage to encrypted storage | Out of scope for EA work; the three stores have different storage shapes and a botched migration loses sessions. |
| Patching Wine's `programs/start/start.c` quote handling | Real Wine bug (`start "" "url"` is a cmd.exe builtin idiom the standalone start.exe does not implement; the empty title becomes `lpFile`). Fixed app-side in B3 instead, so no layer rebuild is needed. Revisit only if another caller needs the cmd.exe idiom. |
| `findGameExe` DX9 deprioritisation | Real defect (picks `TS4_DX9_x64.exe` over `TS4_x64.exe` by directory order) but masked once B3+C3 land, because TS4 then launches via its `link2ea://` URI and never reaches `findGameExe`. Revert candidate unless a title without a launch URI needs it. |
| `boot_exe` working-directory fix | Real gap vs the shortcut branches, but not implicated in any measured EA failure. |
| Plan W companion-process allowlist / watchdog idle timeout | Needed for Steam+EA once sign-in works, but the measured blocker upstream of it is `SteamSDKHelper::Impl::init` failing inside Link2EA - fixing the watchdog first would not have changed the outcome. |

---

## F. Prior verified fix carried in from earlier this session

| # | Change | File | Class | Evidence | Verified |
|---|--------|------|-------|----------|----------|
| F1 | ARM64EC varargs argument recovery for the four rpcrt4 client-call entry points | `Proton-Layers/build11-2/{arm64ec,x86_64,p11-2-work}/dlls/rpcrt4/ndr_stubless.c` | NEEDED | winecfg on arm64ec: 153 access violations -> 0, with 2258 `:seh:` lines proving the channel was live. Both `OpenSCManagerW` call paths marshal correctly. `EABackgroundService.exe` starts as a result. | measured |

Note: two "cleaner" C-varargs rewrites of F1 were built and measured and BOTH regressed to 153 AVs.
The anchor heuristic is empirical, not principled. Do not retry the rewrites.
