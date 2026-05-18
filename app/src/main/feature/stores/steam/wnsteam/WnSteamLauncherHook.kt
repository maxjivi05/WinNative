package com.winlator.cmod.feature.stores.steam.wnsteam

import android.content.Context
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.Shortcut
import com.winlator.cmod.runtime.display.environment.ImageFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * The "Bionic Steam" launcher hook — invoked from the Wine launch path
 * (XServerDisplayActivity.setupXEnvironment) when a Steam game is about
 * to start. Wires together:
 *
 *   1. WnSteamAssetsInstaller.install  — stages libsteamclient.so +
 *      lsteamclient.dll into the Wine prefix (idempotent).
 *   2. WnWineEnvVars.build              — builds the canonical Wine env
 *      pack lsteamclient.dll needs (WINESTEAMCLIENTPATH64, Steam3Master,
 *      STEAMID, SteamGameId, OWNED_DLCS, ...).
 *   3. WnSteamBootstrap.start           — fires off the JNI dlopen +
 *      CreateInterface + CreateSteamPipe + ConnectToGlobalUser sequence
 *      in the background so it's ready by the time Wine's lsteamclient
 *      .dll attempts its first IPC.
 *   4. WnSteamBootstrap.prepareApp     — warms libsteamclient.so's own
 *      PICS cache for the game + DLC.
 *
 * Best-effort throughout: missing binaries, no logged-in Steam session,
 * non-Proton Wine variants — all logged and skipped silently so the
 * launch isn't broken. The launcher still spawns Wine; only the Steam
 * IPC features won't work in those cases.
 *
 * Engaged per-launch via the shortcut/container `launchBionicSteam` flag
 * (set in the Steam tab of the shortcut settings dialog — mutually
 * exclusive with `launchRealSteam`). When that flag is off the hook
 * short-circuits before doing any work, so the legacy launch paths
 * (real Steam, ColdClient, direct exe) run untouched.
 */
object WnSteamLauncherHook {

    private const val TAG = "WnSteamLauncher"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Should we engage the Bionic Steam path for this launch? Returns false
     * when:
     *  - the shortcut/container isn't a Steam game, or
     *  - the container's Wine variant has no bundled IPC binaries, or
     *  - the user hasn't opted in via the shortcut's `launchBionicSteam`
     *    flag (set from the Steam tab of the shortcut settings dialog).
     */
    fun shouldEnable(context: Context, shortcut: Shortcut?, container: Container?): Boolean {
        if (shortcut == null || container == null) return false
        if (shortcut.getExtra("game_source") != "STEAM") return false
        if (!WnSteamAssetsInstaller.isSupportedFor(container)) {
            Timber.tag(TAG).i("not enabling: container wine variant '%s' has no bundled IPC binaries",
                              container.wineVersion)
            return false
        }
        // Per-shortcut opt-in. Shortcut override beats container default;
        // empty override falls through to the container flag (matches the
        // pattern every other Steam toggle in this codebase uses).
        val perShortcut = shortcut.getExtra("launchBionicSteam")
        val enabled = when {
            !perShortcut.isNullOrEmpty() -> perShortcut == "1"
            else                          -> container.isLaunchBionicSteam
        }
        if (!enabled) {
            Timber.tag(TAG).i("not enabling: launchBionicSteam is off " +
                              "(shortcut='%s', container=%s)",
                              perShortcut ?: "<unset>", container.isLaunchBionicSteam)
            return false
        }
        return true
    }

    /**
     * Run the Bionic Steam install + bootstrap. Synchronously returns the
     * env vars to merge into Wine's exec environment; the JNI dlopen happens
     * in the background (the bootstrap fires off on a worker so we don't
     * block UI; Wine's lsteamclient.dll will retry the TCP connect a few
     * times if the bootstrap isn't quite ready when it first tries).
     *
     * @return env vars map to merge (empty when [shouldEnable] is false or
     *         any required precondition fails).
     */
    fun prepare(
        context: Context,
        container: Container,
        shortcut: Shortcut,
        appId: Int,
    ): Map<String, String> {
        if (!shouldEnable(context, shortcut, container)) return emptyMap()
        if (appId <= 0) {
            Timber.tag(TAG).w("not enabling: appId=%d invalid", appId)
            return emptyMap()
        }
        val accountName  = PrefManager.username
        val refreshToken = PrefManager.refreshToken
        val steamId64    = PrefManager.steamUserSteamId64
        if (accountName.isEmpty() || refreshToken.isEmpty() || steamId64 == 0L) {
            Timber.tag(TAG).w(
                "not enabling: missing credentials (user='%s' tokenLen=%d steamId=%d)",
                accountName, refreshToken.length, steamId64,
            )
            return emptyMap()
        }

        if (!WnSteamAssetsInstaller.install(context, container)) {
            Timber.tag(TAG).w("asset install failed; skipping")
            return emptyMap()
        }

        val imageFs        = ImageFs.find(context)
        val steamRootLinux = "${imageFs.wineprefix}/drive_c/Program Files (x86)/Steam"
        val breakpadDir    = File(imageFs.tmpDir, "breakpad").apply { mkdirs() }.absolutePath
        // libsteamclient.so was staged here by WnSteamAssetsInstaller; this
        // is the path wine's lsteamclient.dll needs to find via the
        // WINESTEAMCLIENTPATH env vars. If pointing elsewhere (the legacy
        // <wineprefix>/Steam/linux64/ default) Steam.exe crashes at init.
        val libSteamClientSoPath =
            File(imageFs.libDir, "libsteamclient.so").absolutePath

        val ownedDlcAppIds = ownedDlcsFor(appId)

        val envMap = WnWineEnvVars.build(
            WnWineEnvVars.Inputs(
                steamId64                  = steamId64,
                accountName                = accountName,
                appId                      = appId,
                ownedDlcAppIds             = ownedDlcAppIds.toList(),
                steamRootLinux             = steamRootLinux,
                breakpadDir                = breakpadDir,
                steam3MasterHostPort       = "127.0.0.1:57343",
                steamClientServiceHostPort = "127.0.0.1:57344",
                libSteamClientSoPath       = libSteamClientSoPath,
            ),
        )

        // Fire the bootstrap off-thread. setupXEnvironment runs on the main
        // thread; dlopen + CreateSteamPipe + ConnectToGlobalUser combined
        // can take a second or two. By the time Wine actually launches
        // steam.exe (~3s in), the bootstrap is ready.
        //
        // Process-singleton: we deliberately do NOT call WnSteamBootstrap.stop()
        // here. libsteamclient.so spawns background threads (steamservice,
        // network, callback dispatcher) that don't unwind cleanly — calling
        // Steam_LogOff + reinitializing in the same process SIGSEGVs inside
        // Steam_BLoggedOn on the second launch (verified Phase 8b.10
        // reproduction). WnSteamBootstrap.start is itself idempotent: it
        // returns rc=0 immediately when already initialized.
        scope.launch {
            val libPath    = libSteamClientSoPath
            val nativeHome = "${imageFs.wineprefix}/drive_c/Program Files (x86)"
            // Env subset propagated into the Android process so
            // libsteamclient.so sees the SAME values lsteamclient.dll
            // does inside Wine. Steam3Master / SteamClientService go
            // through dedicated JNI args; everything else as KEY=VALUE.
            val passthrough = listOf(
                "_STEAM_SETENV_MANAGER", "BREAKPAD_DUMP_LOCATION",
                "STEAM_BASE_FOLDER",     "ENABLE_VK_LAYER_VALVE_steam_overlay_1",
                "STEAMVIDEOTOKEN",       "SteamUser",
            )
            // libsteamclient.so opens TLS sockets itself — the :443 WebSocket
            // CM connection AND the ISteamDirectory/GetCMListForConnect HTTPS
            // call. Its bundled OpenSSL has no CA store on Android, so cert
            // validation fails and every CM connect ends in
            // SteamServerConnectFailure(EResult.NoConnection). Point OpenSSL
            // (SSL_CERT_FILE/DIR) and curl (CURL_CA_BUNDLE) at the same
            // single-file CA bundle the working wn-steam-client uses.
            val caBundlePath = runCatching {
                CaBundleExtractor.ensureBundle(context)
            }.getOrDefault("")
            val sslEnv = if (caBundlePath.isNotEmpty()) {
                listOf(
                    "SSL_CERT_FILE=$caBundlePath",
                    "CURL_CA_BUNDLE=$caBundlePath",
                    "SSL_CERT_DIR=/system/etc/security/cacerts",
                )
            } else {
                emptyList()
            }
            val extraEnv = (
                passthrough.mapNotNull { k -> envMap[k]?.let { v -> "$k=$v" } } +
                    sslEnv
            ).toTypedArray()
            Timber.tag(TAG).i("libsteamclient TLS: SSL_CERT_FILE=%s", caBundlePath)

            val rc = WnSteamBootstrap.start(
                context,
                libPath, nativeHome,
                envMap["Steam3Master"]       ?: "127.0.0.1:57343",
                envMap["SteamClientService"] ?: "127.0.0.1:57344",
                extraEnv,
                accountName, refreshToken, steamId64,
            )
            Timber.tag(TAG).i(
                "WnSteamBootstrap.start rc=%d (loggedOn=%s libSteamId=%d)",
                rc, WnSteamBootstrap.isLoggedOn(), WnSteamBootstrap.steamId(),
            )
            if (rc == 0) {
                WnSteamBootstrap.prepareApp(appId, ownedDlcAppIds)
            }
        }

        Timber.tag(TAG).i(
            "Bionic Steam prepared for app=%d (dlc=%d) user=%s",
            appId, ownedDlcAppIds.size, accountName,
        )
        return envMap
    }

    /**
     * Termination callback after wine exits. No-op by design: see the
     * comment in [prepare] — libsteamclient.so cannot be safely torn down
     * within a process lifetime, so we leave the bootstrap alive for the
     * next launch. Process death is the only teardown.
     */
    fun tearDown() {
        Timber.tag(TAG).i("tearDown: leaving libsteamclient.so loaded (process-singleton)")
    }

    /** Pull the DLC appid list from the live wn-steam-client library, if any. */
    private fun ownedDlcsFor(appId: Int): IntArray {
        val store = SteamService.wnLibrary ?: return IntArray(0)
        val app   = store.current.ownedApps.firstOrNull { it.id == appId }
            ?: return IntArray(0)
        return app.dlcAppIds.toIntArray()
    }
}
