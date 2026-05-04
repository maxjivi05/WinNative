package com.winlator.cmod.feature.stores.steam.linux

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.winlator.cmod.runtime.display.connector.UnixSocketConfig
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.wine.WineInfo
import java.io.File
import java.io.IOException
import java.net.InetAddress

/**
 * Orchestrator for the native Linux ARM64 Steam Client launch path.
 *
 * Pulls the four POC pieces together:
 *   POC1   — [LinuxSteamClientManager]: fetches Steam from Valve's CDN.
 *   POC1.5 — [SniperRuntimeManager]:    fetches the glibc rootfs proot
 *                                        will chroot into.
 *   POC2   — [GlibcPreloadInstaller]:   stages the glibc-linked
 *                                        sysvshm shim Steam will preload.
 *   POC3   — [ProtonToolInstaller]:     drops the WinNative Proton
 *                                        compat tool inside Steam's
 *                                        compatibilitytools.d.
 *
 * Then renders a tiny launcher script at
 * `<imagefs>/opt/steam-arm64/launch-steam.sh` whose only job is to
 *   1. clean the bionic-shaped LD_PRELOAD inherited from the host
 *      launcher (it's full of Android paths that won't resolve in
 *      the sniper-arm64 chroot),
 *   2. exec the bundled `proot` binary with the right `--rootfs` /
 *      `--bind` set to expose Android system libs to bionic Wine
 *      while presenting a Debian-11 view to glibc Steam,
 *   3. inside proot, exec the sniper runtime's `env` to install a
 *      clean Steam-only env (LD_PRELOAD = glibc shim;
 *      WINNATIVE_WINE_BIN etc. for the Proton wrapper to find host
 *      Wine), then exec `/opt/steam-arm64/client/steam.sh`.
 *
 * The result is wrapped into a [LaunchPlan] whose `command` is what
 * [XServerDisplayActivity] should set as `GUEST_PROGRAM_LAUNCHER_COMMAND`
 * — the existing launcher infrastructure will then drive ProcessHelper
 * with the right env / cwd / lifecycle.
 *
 * Cost on first run (no caching): ~93 MiB of compressed Steam packages
 * + ~160 MiB sniper runtime tarball. All subsequent launches are no-ops
 * thanks to the per-installer SHA-256/marker idempotency.
 */
object LinuxSteamLauncher {

    private const val TAG = "LinuxSteamLauncher"

    /** Where we generate the wrapper script that the launcher actually invokes. */
    private const val LAUNCHER_SCRIPT_REL = "opt/steam-arm64/launch-steam.sh"

    data class LaunchPlan(
        /** Goes straight into `GUEST_PROGRAM_LAUNCHER_COMMAND` env var. */
        val command: String,
        /**
         * Working directory for [ProcessHelper.exec]. We use the imagefs
         * root so any relative path inside the wrapper resolves predictably.
         */
        val workingDir: File,
    )

    interface Listener {
        fun onStage(stage: String) {}
        fun onProgress(downloaded: Long, total: Long) {}
    }

    /**
     * Idempotent. Performs all four installs (downloads + extracts when
     * needed), regenerates the launcher script, and returns a [LaunchPlan]
     * with the right command + cwd. Throws on any failure so callers can
     * surface a single error to the user. Run from a worker thread.
     *
     * [appId] — when non-null, Steam launches with `-applaunch <appId>`
     * after startup, exactly like the legacy Windows path. When null,
     * just opens the Steam UI (used for "Launch Steam Client" without
     * a game shortcut).
     */
    @Throws(IOException::class)
    fun prepare(
        context: Context,
        wineInfo: WineInfo,
        appId: Int? = null,
        primaryDns: String? = null,
        listener: Listener? = null,
    ): LaunchPlan {
        val imageFs = ImageFs.find(context)
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val prootBin = File(nativeLibDir, "libproot.so")
        if (!prootBin.exists()) {
            throw IOException("proot not found at $prootBin — was the app built without proot?")
        }
        require(wineInfo.isArm64EC()) {
            "LinuxSteamLauncher only handles arm64ec containers (got ${wineInfo.identifier()})"
        }

        // Mirror every progress event into the Verbose Launch overlay so
        // the operator sees stage names, download bytes, and Steam's own
        // bootstrap output instead of a frozen "Loading…" spinner. The
        // tailer below additionally streams winnative-steam.log directly,
        // so the operator gets both our orchestration messages and Steam's
        // internal output without having to leave the device.
        val verboseListener = object : Listener {
            override fun onStage(stage: String) {
                listener?.onStage(stage)
                com.winlator.cmod.runtime.system.LaunchLogBus.post(
                    "linux-steam", stage,
                )
            }
            override fun onProgress(downloaded: Long, total: Long) {
                listener?.onProgress(downloaded, total)
                if (total > 0) {
                    val pct = (downloaded * 100 / total).coerceIn(0, 100)
                    com.winlator.cmod.runtime.system.LaunchLogBus.post(
                        "linux-steam", "downloading $pct% ($downloaded / $total bytes)",
                        com.winlator.cmod.runtime.system.LaunchLogBus.Level.DEBUG,
                    )
                }
            }
        }

        // Start the launcher-log tailer up-front so we capture every line
        // the bash wrapper / proot / Steam writes from the moment we exec.
        // Idempotent — start() replaces any prior tailer thread.
        SteamLogTailer.start(File(imageFs.rootDir, "usr/tmp/winnative-steam.log"))

        // 1) Steam client (POC1)
        verboseListener.onStage("Installing Steam client")
        val steamOk = LinuxSteamClientManager.install(
            context,
            object : LinuxSteamClientManager.ProgressListener {
                override fun onStage(stage: String) { verboseListener.onStage(stage) }
                override fun onPackageProgress(name: String, downloaded: Long, total: Long) {
                    verboseListener.onProgress(downloaded, total)
                }
                override fun onComplete(success: Boolean, error: String?) {}
            },
        )
        if (!steamOk) throw IOException("Linux Steam client install failed (see logcat)")

        // 2) sniper-arm64 runtime (POC1.5)
        verboseListener.onStage("Installing sniper runtime")
        val runtimeOk = SniperRuntimeManager.install(
            context,
            object : SniperRuntimeManager.ProgressListener {
                override fun onStage(stage: String) { verboseListener.onStage(stage) }
                override fun onProgress(downloaded: Long, total: Long) {
                    verboseListener.onProgress(downloaded, total)
                }
                override fun onComplete(success: Boolean, error: String?) {}
            },
        )
        if (!runtimeOk) throw IOException("Sniper runtime install failed (see logcat)")

        // 3) glibc preload (POC2) + 4) Proton compat tool (POC3) +
        //    proot-loader binary (cross-compiled host-side; can't ship via
        //    jniLibs because Android rejects static-PIE with res=-18).
        verboseListener.onStage("Staging preload + compat tool")
        val glibcPreload = GlibcPreloadInstaller.ensureInstalled(context)
        ProtonToolInstaller.ensureInstalled(context)
        val prootLoader = ProotLoaderInstaller.ensureInstalled(context)
        // 4.5) GTK 2 arm64 — `steamui.so` NEEDs libgtk-x11-2.0.so.0 +
        //      libgdk-x11-2.0.so.0; sniper-arm64 ships GTK 3 only. Drop
        //      the Debian 11 arm64 builds into sniper's lib dir so the
        //      dlmopen at Steam first paint resolves cleanly.
        Gtk2RuntimeInstaller.ensureInstalled(context)

        // 5) Make sure /etc/resolv.conf inside the chroot resolves DNS.
        //    The host's /etc/resolv.conf may not exist on Android, and proot
        //    silently skips missing binds — leaving Steam without DNS. So we
        //    write one into the imagefs and bind that. Use the active network
        //    DNS when available (matches the host's split/VPN/captive view);
        //    fall back to public anycast resolvers otherwise.
        val effectiveDns = primaryDns?.takeIf { it.isNotBlank() && isValidDns(it) }
            ?: queryActiveNetworkDns(context)
        val resolvConf = ensureResolvConf(imageFs, effectiveDns)

        // 6) Render the wrapper script. Rebuild every time because paths can
        //    shift across Wine version / container changes; the cost is tiny.
        verboseListener.onStage("Generating launch script")
        val script = renderLauncherScript(
            context = context,
            imageFs = imageFs,
            prootBin = prootBin,
            prootLoaderBin = prootLoader,
            sniperRoot = SniperRuntimeManager.rootfsDir(context),
            wineInfo = wineInfo,
            resolvConf = resolvConf,
            steamAppId = appId,
        )
        // Don't quote the path — ProcessHelper.splitCommand keeps the quotes
        // in the resulting token, which would make ProcessBuilder try to exec
        // a literal `"/path/..."`. The script lives under app files where no
        // path component contains spaces.
        return LaunchPlan(command = script.absolutePath, workingDir = imageFs.rootDir)
    }

    /**
     * Ensures `<imagefs>/etc/resolv.conf` contains usable DNS entries we can
     * bind into the proot chroot. Returns the host-side path to bind from.
     *
     * Android /etc/resolv.conf is often absent or a symlink the app sandbox
     * can't read. Use the launcher-derived `primaryDns` if provided, fall
     * back to public anycast resolvers so Steam can reach login servers
     * without us shipping a DNS resolver.
     */
    private fun ensureResolvConf(imageFs: ImageFs, primaryDns: String?): File {
        val out = File(imageFs.rootDir, "etc/resolv.conf")
        out.parentFile?.mkdirs()
        val dns = primaryDns?.takeIf { it.isNotBlank() && isValidDns(it) }
        val text = buildString {
            if (dns != null) {
                appendLine("# Generated by LinuxSteamLauncher (active network)")
                appendLine("nameserver $dns")
            }
            appendLine("nameserver 1.1.1.1")
            appendLine("nameserver 8.8.8.8")
            appendLine("options edns0")
        }
        out.writeText(text)
        return out
    }

    private fun isValidDns(s: String): Boolean = try {
        InetAddress.getByName(s); true
    } catch (_: Exception) { false }

    /**
     * Returns the first DNS server reported by Android's active network,
     * matching the heuristic [GuestProgramLauncherComponent] uses for
     * `ANDROID_RESOLV_DNS`. Returns null on networks that report no DNS
     * (e.g. Wi-Fi captive portal pre-auth) so [ensureResolvConf] falls
     * back to public anycast resolvers.
     */
    private fun queryActiveNetworkDns(context: Context): String? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork
        val link = active?.let { cm.getLinkProperties(it) }
        link?.dnsServers
            ?.asSequence()
            ?.map { it.hostAddress }
            ?.firstOrNull { !it.isNullOrBlank() }
    } catch (_: Exception) {
        null
    }

    /**
     * Materializes the wrapper bash script onto disk. Re-creates it
     * unconditionally so we pick up any changes to paths / Wine versions.
     */
    private fun renderLauncherScript(
        context: Context,
        imageFs: ImageFs,
        prootBin: File,
        prootLoaderBin: File,
        sniperRoot: File,
        wineInfo: WineInfo,
        resolvConf: File,
        steamAppId: Int?,
    ): File {
        val script = File(imageFs.rootDir, LAUNCHER_SCRIPT_REL)
        script.parentFile?.mkdirs()

        // The shim listens on a unix socket whose host path is
        // `<imagefs>/usr/tmp/.sysvshm/SM0`. Inside proot, our
        // `<imagefs>:/winnative-imagefs` bind makes that path reachable
        // as `/winnative-imagefs/usr/tmp/.sysvshm/SM0`. Always pass the
        // in-chroot path so proot's AF_UNIX sun_path translation lands
        // on the same inode the shim is actually bound to.
        val sysvshmServer = "/winnative-imagefs" + UnixSocketConfig.SYSVSHM_SERVER_PATH
        val wineIdentifier = wineInfo.identifier()
        val glibcPreloadInProot =
            "/opt/steam-arm64/preload/${GlibcPreloadInstaller.INSTALLED_FILENAME}"
        val wineBinInsideProot = "/winnative-imagefs/opt/$wineIdentifier/bin/wine"
        val wineDirInsideProot = "/winnative-imagefs/opt/$wineIdentifier/bin"
        val bionicPreload = "/winnative-imagefs/usr/lib/libandroid-sysvshm.so"
        val steamArgs = if (steamAppId == null) {
            listOf("-console", "-tcp")
        } else {
            listOf("-silent", "-tcp", "-applaunch", steamAppId.toString())
        }

        // The script uses `/system/bin/sh` because the host launcher is
        // running before any chroot is in effect. proot is invoked as a
        // bionic-native binary so its NEEDED libc/libdl/libm resolve
        // through Android's normal linker — no LD_LIBRARY_PATH manipulation
        // required for proot itself. Inside proot we hop to the sniper
        // runtime's `env` (glibc) and from there into Steam.
        //
        // Critical bind list:
        //   /system, /apex, /vendor, /linkerconfig, /proc, /sys, /dev
        //                            — Android system surface bionic Wine
        //                              will need when the Proton wrapper
        //                              execs it from inside the proot tree.
        //   /etc/resolv.conf         — DNS for Steam network (Android's
        //                              resolv.conf is read at runtime).
        //   <imagefs>:/winnative-imagefs
        //                            — host's imagefs visible inside proot
        //                              so the Proton wrapper can locate
        //                              the bionic Wine binary.
        //   <imagefs>/opt/steam-arm64:/opt/steam-arm64
        //                            — Steam install + glibc preload
        //                              under a stable in-chroot path.
        //   <imagefs>/home/xuser:/home/xuser
        //                            — writable HOME shared with Wine.
        //   <imagefs>/tmp:/tmp       — proot needs a writable /tmp inside
        //                              the chroot; sniper's /tmp is an
        //                              empty placeholder.
        val prootLoaderPath = prootLoaderBin.absolutePath
        val launcherLogPath = imageFs.rootDir.absolutePath + "/usr/tmp/winnative-steam.log"
        val text = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# Auto-generated by LinuxSteamLauncher.kt — do not edit by hand.")
            appendLine("# Re-rendered on every Linux Steam launch.")
            appendLine()
            appendLine("PROOT=${quote(prootBin.absolutePath)}")
            appendLine("PROOT_LOADER=${quote(prootLoaderPath)}")
            appendLine("ROOTFS=${quote(sniperRoot.absolutePath)}")
            appendLine("IMAGEFS=${quote(imageFs.rootDir.absolutePath)}")
            appendLine("XUSER_HOME=${quote(imageFs.home_path)}")
            appendLine("STEAM_OPT=${quote(imageFs.rootDir.absolutePath + "/opt/steam-arm64")}")
            appendLine("TMP=${quote(imageFs.rootDir.absolutePath + "/usr/tmp")}")
            appendLine("LOG=${quote(launcherLogPath)}")
            appendLine("export PROOT_LOADER")
            // proot keeps a "glue rootfs" workdir at PROOT_TMP_DIR (defaults
            // to /tmp). Android's /tmp is not writable from app context, so
            // every bind/chroot setup fails before the first exec. Point it
            // at our imagefs `/usr/tmp/proot/` instead.
            appendLine("PROOT_TMP_DIR=${quote(imageFs.rootDir.absolutePath + "/usr/tmp/proot")}")
            appendLine("export PROOT_TMP_DIR")
            // One-shot debug verbosity so the next run gives us full proot
            // tracing in the launcher log if anything still fails. Cheap to
            // leave on while we're stabilizing the launch chain; turn off
            // (or gate behind a debug flag) once Steam UI reaches first paint.
            appendLine("export PROOT_VERBOSE=1")
            appendLine()
            appendLine("# Make sure the writable directories the chroot expects exist.")
            appendLine("mkdir -p \"\$XUSER_HOME\" \"\$TMP\" \"\$PROOT_TMP_DIR\"")
            appendLine("mkdir -p \"\$(dirname \"\$LOG\")\"")
            // steam.sh hardcodes PLATFORM=ubuntu12_32 and calls
            // unpack_runtime("$STEAMROOT/$PLATFORM/steam-runtime") which
            // returns 1 if the directory is missing → "Unpack runtime
            // failed; Couldn't set up the Steam Runtime; exit 1". The
            // function returns 0 if the directory exists and no archive
            // is alongside it (sniper-arm64 IS the runtime; we don't ship
            // the legacy x86 scout archive). Create an empty placeholder
            // so steam.sh's existence check passes.
            appendLine("mkdir -p \"\$STEAM_OPT/client/ubuntu12_32/steam-runtime\"")
            // Steam's client updater fetches the manifest at
            //   https://client-update.steamstatic.com/<package_name>
            // where <package_name> is `steam_client_<channel>_linuxarm64`.
            // The default channel in this build is "" (stable), which 404s
            // on ARM64 — Steam is only published on the publicbeta channel
            // for ARM64 today. The well-known marker `package/beta` with
            // the channel name in it tells Steam's bootstrap to use that
            // channel. Drop a publicbeta marker so the update URL resolves
            // to the only manifest Valve actually ships for arm64.
            appendLine("mkdir -p \"\$STEAM_OPT/client/package\"")
            appendLine("printf publicbeta >\"\$STEAM_OPT/client/package/beta\"")
            // Codex review: harden the temp dir to 0700 and probe writability
            // before invoking proot — proot's mkdtemp() inside this dir is the
            // first thing that fails on Android's app sandbox, and we want a
            // loud, actionable error if our app UID can't actually write here.
            appendLine("chmod 700 \"\$PROOT_TMP_DIR\" 2>/dev/null || true")
            appendLine("if ! ( touch \"\$PROOT_TMP_DIR/.probe\" && rm \"\$PROOT_TMP_DIR/.probe\" ) 2>/dev/null; then")
            appendLine("    echo \"FATAL: PROOT_TMP_DIR not writable: \$PROOT_TMP_DIR\"")
            appendLine("    exit 70")
            appendLine("fi")
            appendLine()
            appendLine("# Redirect every step from this point on to the launcher log so")
            appendLine("# the host launcher's /dev/null redirection doesn't swallow proot/")
            appendLine("# Steam diagnostics. Tail the log on-device with:")
            appendLine("#     adb shell run-as com.tencent.ig cat \"\$LOG\"")
            appendLine("exec >>\"\$LOG\" 2>&1")
            appendLine("echo \"==== \$(date) launch-steam.sh starting (pid \$\$) ====\"")
            appendLine("echo \"PROOT=\$PROOT\"")
            appendLine("echo \"PROOT_LOADER=\$PROOT_LOADER\"")
            appendLine("echo \"ROOTFS=\$ROOTFS\"")
            appendLine("echo \"existence checks:\"")
            appendLine("for f in \"\$PROOT\" \"\$PROOT_LOADER\" \\")
            appendLine("         \"\$ROOTFS/lib/ld-linux-aarch64.so.1\" \\")
            appendLine("         \"\$ROOTFS/usr/bin/env\" \\")
            appendLine("         \"\$ROOTFS/bin/sh\" \\")
            appendLine("         \"\$STEAM_OPT/client/steam.sh\"; do")
            appendLine("  if [ -e \"\$f\" ]; then echo \"  OK   \$f\"; else echo \"  MISS \$f\"; fi")
            appendLine("done")
            appendLine()
            appendLine("# Strip bionic-shaped LD_PRELOAD inherited from the host launcher;")
            appendLine("# its paths point inside the imagefs and won't resolve under sniper.")
            appendLine("unset LD_PRELOAD")
            appendLine()
            // Repair `~/.steam/{bin,bin64,sdk64,root,steam}` symlinks. Valve's
            // steam.sh hardcodes bin64 -> ubuntu12_64 and sdk64 -> linux64 with
            // no ARM64 branch — both directories hold x86_64 binaries. On
            // ARM64 the client payload is split across two dirs: steamrtarm64/
            // holds the runtime-bound binaries (steam, steamwebhelper, libcef.so,
            // libtier0_s.so, libvstdlib_s.so, ffmpeg/avcodec, …) and
            // linuxarm64/ holds the generic SDK libs (steamclient.so,
            // crashhandler.so, steamerrorreporter, steam-launch-wrapper). Any
            // earlier launch through Valve's steam.sh leaves the wrong x86 links
            // on disk; main-Steam then forks/execs an x86_64 steamwebhelper,
            // exec fails silently (you get a [sh] zombie child), and Steam
            // settles into idle poll() on the IPC pipe waiting for a handshake
            // that will never arrive. Codex review (2x): map bin64+bin at
            // steamrtarm64 (where the runtime client binaries are) and sdk64
            // at linuxarm64 (where steamclient.so is) — split target, single
            // source of truth per role. Gate on BOTH canonical files so a
            // partial download doesn't paper over with broken links. Skip
            // bin32/sdk32 (no 32-bit on arm64; touching them risks future
            // i386 compat). Re-anchor root/steam at the client root
            // (idempotent if already correct). All targets are written in
            // proot-internal form because Steam follows them from inside the
            // chroot.
            appendLine("STEAM_DOTSTEAM=\"\$XUSER_HOME/.steam\"")
            appendLine("find_arm64_file_dir() {")
            appendLine("    name=\"\$1\"")
            appendLine("    find \"\$STEAM_OPT/client\" -type f -name \"\$name\" 2>/dev/null | while IFS= read -r f; do")
            appendLine("        if command -v file >/dev/null 2>&1; then")
            appendLine("            if file \"\$f\" 2>/dev/null | grep -Eiq 'aarch64|ARM aarch64|ARM64'; then")
            appendLine("                dirname \"\$f\"")
            appendLine("                break")
            appendLine("            fi")
            appendLine("        elif echo \"\$f\" | grep -Eiq '/(steamrtarm64|linuxarm64|androidarm64)(/|\$)'; then")
            appendLine("            dirname \"\$f\"")
            appendLine("            break")
            appendLine("        fi")
            appendLine("    done")
            appendLine("}")
            appendLine("host_to_proot_steam_path() {")
            appendLine("    path=\"\$1\"")
            appendLine("    prefix=\"\$STEAM_OPT/client\"")
            appendLine("    case \"\$path\" in")
            appendLine("        \"\$prefix\"*) printf '/opt/steam-arm64/client%s\\n' \"\${path#\$prefix}\" ;;")
            appendLine("        *) printf '%s\\n' \"\$path\" ;;")
            appendLine("    esac")
            appendLine("}")
            appendLine("STEAM_ARM64_BIN_DIR_HOST=\"\$(find_arm64_file_dir steamwebhelper)\"")
            appendLine("[ -n \"\$STEAM_ARM64_BIN_DIR_HOST\" ] || STEAM_ARM64_BIN_DIR_HOST=\"\$STEAM_OPT/client/steamrtarm64\"")
            appendLine("STEAMCLIENT_SO=\"\$(find \"\$STEAM_OPT/client\" -type f -name steamclient.so 2>/dev/null | head -n1)\"")
            appendLine("if [ -n \"\$STEAMCLIENT_SO\" ]; then")
            appendLine("    STEAM_ARM64_SDK_DIR_HOST=\"\$(dirname \"\$STEAMCLIENT_SO\")\"")
            appendLine("else")
            appendLine("    STEAM_ARM64_SDK_DIR_HOST=\"\$STEAM_OPT/client/linuxarm64\"")
            appendLine("fi")
            appendLine("STEAM_ARM64_BIN_DIR=\"\$(host_to_proot_steam_path \"\$STEAM_ARM64_BIN_DIR_HOST\")\"")
            appendLine("STEAM_ARM64_SDK_DIR=\"\$(host_to_proot_steam_path \"\$STEAM_ARM64_SDK_DIR_HOST\")\"")
            appendLine("echo \"STEAM_ARM64_BIN_DIR=\$STEAM_ARM64_BIN_DIR\"")
            appendLine("echo \"STEAM_ARM64_SDK_DIR=\$STEAM_ARM64_SDK_DIR\"")
            appendLine("if [ -x \"\$STEAM_ARM64_BIN_DIR_HOST/steamwebhelper\" ] && \\")
            appendLine("   [ -x \"\$STEAM_ARM64_BIN_DIR_HOST/steam\" ] && \\")
            appendLine("   [ -f \"\$STEAM_ARM64_BIN_DIR_HOST/libcef.so\" ] && \\")
            appendLine("   [ -f \"\$STEAM_ARM64_SDK_DIR_HOST/steamclient.so\" ]; then")
            appendLine("    mkdir -p \"\$STEAM_DOTSTEAM\"")
            appendLine("    ln -fsn \"\$STEAM_ARM64_BIN_DIR\" \"\$STEAM_DOTSTEAM/bin64\"")
            appendLine("    ln -fsn \"\$STEAM_ARM64_BIN_DIR\" \"\$STEAM_DOTSTEAM/bin\"")
            appendLine("    ln -fsn \"\$STEAM_ARM64_SDK_DIR\" \"\$STEAM_DOTSTEAM/sdk64\"")
            appendLine("    ln -fsn /opt/steam-arm64/client              \"\$STEAM_DOTSTEAM/root\"")
            appendLine("    ln -fsn /opt/steam-arm64/client              \"\$STEAM_DOTSTEAM/steam\"")
            appendLine("    echo \"Re-anchored Steam links:\"")
            appendLine("    ls -l \"\$STEAM_DOTSTEAM\"/bin \"\$STEAM_DOTSTEAM\"/bin64 \"\$STEAM_DOTSTEAM\"/sdk64 \"\$STEAM_DOTSTEAM\"/root \"\$STEAM_DOTSTEAM\"/steam")
            appendLine("else")
            appendLine("    echo \"FATAL: incomplete ARM64 Steam payload\"")
            appendLine("    find \"\$STEAM_OPT/client\" -maxdepth 3 -type f \\( -name steam -o -name steamwebhelper -o -name steamclient.so -o -name libcef.so \\) 2>/dev/null | while IFS= read -r f; do")
            appendLine("        ls -l \"\$f\" 2>&1 || true")
            appendLine("        command -v file >/dev/null 2>&1 && file \"\$f\" 2>&1 || true")
            appendLine("    done")
            appendLine("    exit 71")
            appendLine("fi")
            appendLine()
            // Delete stale single-instance IPC files. Without this, native arm64
            // Steam sees `~/.steam/steam.pipe` (a FIFO from a prior killed run)
            // and `~/.steam/steam.pid` (whatever pid was last written) and
            // concludes another Steam is already running. It then enters its
            // "second-instance forwarder" path: forwards our `-applaunch
            // 1446780` over the FIFO to that imagined-running first instance,
            // and blocks in poll() on the FIFO's read side waiting for an
            // acknowledgement that will never arrive — that's the exact
            // pattern we see (no webhelper spawn, no UI threads, no HTTP,
            // CHTTPClientThre + IOCP idle, only 4 fds + the steam.pipe FIFO
            // open r-only). Codex review (round 3): Steam owns these files;
            // deleting them at launch time forces it onto the first-instance
            // path which actually starts the UI, spawns webhelper, and
            // performs login. Do NOT delete `~/.steam/registry.vdf` or
            // `~/.steam/steam.token` — those carry intentional pre-launch
            // state. Leave bin32/sdk32 alone too (no 32-bit on arm64).
            appendLine("rm -f \"\$STEAM_DOTSTEAM/steam.pipe\" \"\$STEAM_DOTSTEAM/steam.pid\" 2>/dev/null || true")
            appendLine("echo \"Cleared stale \$STEAM_DOTSTEAM/{steam.pid,steam.pipe} (single-instance IPC)\"")
            appendLine()
            // Provide /etc/machine-id and /var/lib/dbus/machine-id. The sniper
            // rootfs ships without either (Valve relies on pressure-vessel to
            // bind-mount the host's machine-id at runtime; we don't have one to
            // bind from). On its own that lookup just returns "no machine id"
            // fast. The real risk is that GTK 2 / dbus pull in initialization
            // paths that, if dbus then proceeds to autolaunch a session bus
            // (because we don't set DBUS_SESSION_BUS_ADDRESS), spend a long
            // time in fork+exec under proot's ptrace overhead before failing.
            // Codex round-4 review: write machine-id only if absent or empty
            // (don't overwrite a packaged value), generated from the kernel's
            // RNG; symlink the dbus alias because some libdbus versions read
            // /var/lib/dbus/machine-id first.
            appendLine("if [ ! -s \"\$ROOTFS/etc/machine-id\" ]; then")
            appendLine("    if [ -r /proc/sys/kernel/random/uuid ]; then")
            appendLine("        cat /proc/sys/kernel/random/uuid | tr -d - > \"\$ROOTFS/etc/machine-id\"")
            appendLine("    else")
            appendLine("        printf '%s\\n' \"$(date +%s%N | sha256sum | cut -c1-32)\" > \"\$ROOTFS/etc/machine-id\"")
            appendLine("    fi")
            appendLine("    echo \"Wrote new \$ROOTFS/etc/machine-id\"")
            appendLine("fi")
            appendLine("mkdir -p \"\$ROOTFS/var/lib/dbus\"")
            appendLine("if [ ! -e \"\$ROOTFS/var/lib/dbus/machine-id\" ]; then")
            appendLine("    ln -sf /etc/machine-id \"\$ROOTFS/var/lib/dbus/machine-id\"")
            appendLine("fi")
            appendLine()
            // Disable PulseAudio autospawn. Without this, libpulse (loaded by
            // steamui.so for audio init) reads /etc/pulse/client.conf, sees
            // `autospawn = yes` (the Debian default), and forks
            //   /bin/sh -c "pulseaudio --start --log-target=syslog"
            // during pa_context_connect(). Under proot's ptrace overhead that
            // child stalls before exec'ing the daemon, the [sh] zombie at
            // PID N+1 we always see is exactly that fork, and Steam's main
            // thread blocks on the pulse async-connect callback that never
            // fires — that's the post-"Steam logging initialized" silent
            // stall we've been chasing for five rounds. Writing a
            // client.conf with autospawn=no plus pointing default-server
            // at an empty value makes libpulse return PA_ERR_CONNECTION
            // REFUSED immediately, so Steam falls through to its no-audio
            // path and continues. Codex round-4 ranking placed pulse
            // autospawn second-most-likely behind dbus autolaunch (already
            // disabled).
            appendLine("mkdir -p \"\$ROOTFS/etc/pulse\"")
            appendLine("cat > \"\$ROOTFS/etc/pulse/client.conf\" <<'EOF'")
            appendLine("autospawn = no")
            appendLine("default-server = ")
            appendLine("EOF")
            appendLine("echo \"Wrote \$ROOTFS/etc/pulse/client.conf (autospawn=no)\"")
            appendLine()
            appendLine("RUNTIME_UID=\"\$(id -u 2>/dev/null || echo 1000)\"")
            appendLine("RUNTIME_GID=\"\$(id -g 2>/dev/null || echo \"\$RUNTIME_UID\")\"")
            appendLine("echo \"Runtime identity: uid=\$RUNTIME_UID gid=\$RUNTIME_GID\"")
            appendLine()
            // Provide /etc/passwd, /etc/group, /etc/hosts, and an
            // override /etc/nsswitch.conf inside the sniper rootfs.
            //
            // Pressure-vessel synthesizes these from the host before
            // launching Steam — we don't have a host /etc/passwd to bind
            // from on Android. Without them:
            //   * `getpwuid(getuid())` returns NULL → libpulse / libsystemd /
            //     libdbus fall through to env-var fallbacks; some paths
            //     attempt nss-systemd which dials varlink at
            //     /run/systemd/userdb/io.systemd.NameServiceSwitch and
            //     blocks on poll() for the full 25s varlink timeout when
            //     no server is there. Forcing nsswitch to "files" only
            //     short-circuits every NSS module that could call out
            //     (systemd / dbus / LDAP / mdns).
            //   * `getaddrinfo("localhost")` falls through to DNS instead
            //     of the /etc/hosts hit; with no resolver configured for
            //     loopback this stalls.
            //   * `gethostbyname()` of the device hostname (varies per
            //     vendor) likewise spills to DNS — include the hostname
            //     mapping too for safety. Round-6 dual review (Codex +
            //     research-agent) ranked these as the single most likely
            //     unblocker; both insisted on shipping the trio together.
            appendLine("cat > \"\$ROOTFS/etc/passwd\" <<EOF")
            appendLine("root:x:0:0:root:/root:/bin/sh")
            appendLine("xuser:x:\$RUNTIME_UID:\$RUNTIME_GID:WinNative:/home/xuser:/bin/sh")
            appendLine("nobody:x:65534:65534:nobody:/nonexistent:/bin/sh")
            appendLine("EOF")
            appendLine("cat > \"\$ROOTFS/etc/group\" <<EOF")
            appendLine("root:x:0:")
            appendLine("xuser:x:\$RUNTIME_GID:")
            appendLine("audio:x:29:xuser")
            appendLine("video:x:44:xuser")
            appendLine("nogroup:x:65534:")
            appendLine("EOF")
            appendLine("cat > \"\$ROOTFS/etc/hosts\" <<'EOF'")
            appendLine("127.0.0.1 localhost")
            appendLine("::1 localhost ip6-localhost")
            appendLine("EOF")
            // Files-only nsswitch — eliminates every module that could
            // reach out (systemd / dbus / LDAP / mdns / nis). Sniper's
            // shipped nsswitch.conf includes `passwd: compat systemd`
            // which is one of the suspected blocking paths.
            appendLine("cat > \"\$ROOTFS/etc/nsswitch.conf\" <<'EOF'")
            appendLine("passwd:    files")
            appendLine("group:     files")
            appendLine("shadow:    files")
            appendLine("hosts:     files dns")
            appendLine("services:  files")
            appendLine("protocols: files")
            appendLine("networks:  files")
            appendLine("ethers:    files")
            appendLine("rpc:       files")
            appendLine("netgroup:  files")
            appendLine("EOF")
            // Bias IPv4 first in getaddrinfo so any v6 stalls don't add
            // 5+ seconds per DNS resolution (Codex round-6 add-on).
            appendLine("cat > \"\$ROOTFS/etc/gai.conf\" <<'EOF'")
            appendLine("precedence ::ffff:0:0/96  100")
            appendLine("EOF")
            appendLine("echo \"Wrote \$ROOTFS/etc/{passwd,group,hosts,nsswitch.conf,gai.conf}\"")
            appendLine()
            // xdg-* helper stubs. Steam's early init (libtier0 / steamui)
            // calls `system("xdg-user-dir DESKTOP")` and similar to find
            // the desktop directory and register `steam://` handlers.
            // The fork succeeds, sh fails to find xdg-user-dir on PATH,
            // sh exits — but Steam's calling thread is also blocked on
            // a sibling sync IPC and never wait()s the child. That's the
            // [sh] zombie at PID parent+2 we've seen in every run since
            // round 1. Dropping no-op stubs in PATH lets sh exec a real
            // command that exits 0 quickly, the parent reaps it, and
            // Steam's init loop can advance. Identified by the parallel
            // research-agent on round 6.
            appendLine("XDG_STUB_DIR=\"\$ROOTFS/usr/local/bin\"")
            appendLine("mkdir -p \"\$XDG_STUB_DIR\"")
            appendLine("cat > \"\$XDG_STUB_DIR/xdg-user-dir\" <<'EOF'")
            appendLine("#!/bin/sh")
            appendLine("case \"\$1\" in")
            appendLine("  DESKTOP)   echo \"\$HOME/Desktop\" ;;")
            appendLine("  DOWNLOAD)  echo \"\$HOME/Downloads\" ;;")
            appendLine("  DOCUMENTS) echo \"\$HOME/Documents\" ;;")
            appendLine("  MUSIC)     echo \"\$HOME/Music\" ;;")
            appendLine("  PICTURES)  echo \"\$HOME/Pictures\" ;;")
            appendLine("  VIDEOS)    echo \"\$HOME/Videos\" ;;")
            appendLine("  *)         echo \"\$HOME\" ;;")
            appendLine("esac")
            appendLine("exit 0")
            appendLine("EOF")
            appendLine("chmod 0755 \"\$XDG_STUB_DIR/xdg-user-dir\"")
            appendLine("for tool in xdg-open xdg-mime xdg-desktop-menu xdg-icon-resource xdg-screensaver update-mime-database update-desktop-database; do")
            appendLine("    if [ ! -x \"\$XDG_STUB_DIR/\$tool\" ]; then")
            appendLine("        printf '#!/bin/sh\\nexit 0\\n' > \"\$XDG_STUB_DIR/\$tool\"")
            appendLine("        chmod 0755 \"\$XDG_STUB_DIR/\$tool\"")
            appendLine("    fi")
            appendLine("done")
            appendLine("echo \"Staged xdg-* stubs in \$XDG_STUB_DIR\"")
            appendLine()
            // Pre-create /run/user/$RUNTIME_UID (the conventional XDG_RUNTIME_DIR
            // path that libpulse, libdbus, and pipewire fall back to when
            // their primary lookup fails). Owned by us, mode 0700 — anything
            // less restrictive triggers a libpulse "directory has wrong
            // permissions" abort. Bound into proot below as -b $RUN_USER.
            appendLine("RUN_USER_DIR=\"\$IMAGEFS/run/user/\$RUNTIME_UID\"")
            appendLine("XDG_RUNTIME_DIR_IN_PROOT=\"/run/user/\$RUNTIME_UID\"")
            appendLine("mkdir -p \"\$RUN_USER_DIR\"")
            appendLine("chmod 0700 \"\$RUN_USER_DIR\"")
            appendLine("echo \"Pre-created \$RUN_USER_DIR (mode 0700); XDG_RUNTIME_DIR=\$XDG_RUNTIME_DIR_IN_PROOT\"")
            appendLine()
            appendLine("echo \"==== pre-Steam X/socket probe ====\"")
            appendLine("echo \"DISPLAY will be :0\"")
            appendLine("ls -la \"\$TMP/.X11-unix\" 2>&1 || true")
            appendLine("ls -l \"\$TMP/.X11-unix/X0\" 2>&1 || true")
            appendLine("echo \"host X socket type:\"")
            appendLine("[ -S \"\$TMP/.X11-unix/X0\" ] && echo \"XSOCKET_OK\" || echo \"XSOCKET_MISSING\"")
            appendLine()
            appendLine("echo \"==== payload audit ====\"")
            appendLine("for f in \\")
            appendLine("  \"\$STEAM_ARM64_BIN_DIR_HOST/steam\" \\")
            appendLine("  \"\$STEAM_ARM64_BIN_DIR_HOST/steamwebhelper\" \\")
            appendLine("  \"\$STEAM_ARM64_BIN_DIR_HOST/libcef.so\" \\")
            appendLine("  \"\$STEAM_ARM64_SDK_DIR_HOST/steamclient.so\" \\")
            appendLine("  \"\$STEAM_OPT/client/steamui.so\"")
            appendLine("do")
            appendLine("    echo \"-- \$f\"")
            appendLine("    ls -l \"\$f\" 2>&1 || true")
            appendLine("    command -v file >/dev/null 2>&1 && file \"\$f\" 2>&1 || true")
            appendLine("done")
            appendLine()
            appendLine("run_sniper_preflight() {")
            appendLine("    \"\$PROOT\" \\")
            appendLine("        -r \"\$ROOTFS\" \\")
            appendLine("        -b /system \\")
            appendLine("        -b /apex \\")
            appendLine("        -b /vendor \\")
            appendLine("        -b /proc \\")
            appendLine("        -b /sys \\")
            appendLine("        -b /dev \\")
            appendLine("        -b ${quote(resolvConf.absolutePath)}:/etc/resolv.conf \\")
            appendLine("        -b \"\$IMAGEFS:/winnative-imagefs\" \\")
            appendLine("        -b \"\$STEAM_OPT:/opt/steam-arm64\" \\")
            appendLine("        -b \"\$XUSER_HOME:/home/xuser\" \\")
            appendLine("        -b \"\$TMP:/tmp\" \\")
            appendLine("        -b \"\$IMAGEFS/run:/run\" \\")
            appendLine("        -w /home/xuser \\")
            appendLine("        /usr/bin/env -i \\")
            appendLine("            HOME=/home/xuser \\")
            appendLine("            USER=xuser \\")
            appendLine("            DISPLAY=:0 \\")
            appendLine("            XDG_RUNTIME_DIR=\"\$XDG_RUNTIME_DIR_IN_PROOT\" \\")
            appendLine("            PATH=/usr/local/bin:/usr/bin:/bin \\")
            appendLine("            LD_PRELOAD=${quoteShell(glibcPreloadInProot)} \\")
            appendLine("            ANDROID_SYSVSHM_SERVER=${quoteShell(sysvshmServer)} \\")
            appendLine("            LD_LIBRARY_PATH=\"\$STEAM_ARM64_BIN_DIR:\$STEAM_ARM64_SDK_DIR\" \\")
            appendLine("            STEAM_ARM64_BIN_DIR=\"\$STEAM_ARM64_BIN_DIR\" \\")
            appendLine("            STEAM_ARM64_SDK_DIR=\"\$STEAM_ARM64_SDK_DIR\" \\")
            appendLine("            /bin/sh -c '")
            appendLine("                echo \"==== steamwebhelper dependency audit ====\"")
            appendLine("                ldd \"\$STEAM_ARM64_BIN_DIR/steamwebhelper\" 2>&1 || true")
            appendLine("                echo \"==== steamui dependency audit ====\"")
            appendLine("                ldd /opt/steam-arm64/client/steamui.so 2>&1 || true")
            appendLine("                echo \"==== steamwebhelper smoke test ====\"")
            appendLine("                if command -v timeout >/dev/null 2>&1; then")
            appendLine("                    timeout 15 \"\$STEAM_ARM64_BIN_DIR/steamwebhelper\" --version")
            appendLine("                else")
            appendLine("                    \"\$STEAM_ARM64_BIN_DIR/steamwebhelper\" --version &")
            appendLine("                    helper_pid=\$!")
            appendLine("                    sleep 15")
            appendLine("                    if kill -0 \"\$helper_pid\" 2>/dev/null; then")
            appendLine("                        echo \"steamwebhelper smoke timed out; killing pid=\$helper_pid\"")
            appendLine("                        kill \"\$helper_pid\" 2>/dev/null || true")
            appendLine("                        sleep 1")
            appendLine("                        kill -9 \"\$helper_pid\" 2>/dev/null || true")
            appendLine("                    fi")
            appendLine("                    wait \"\$helper_pid\"")
            appendLine("                fi")
            appendLine("                status=\$?")
            appendLine("                echo \"steamwebhelper smoke status=\$status\"")
            appendLine("                exit 0")
            appendLine("            '")
            appendLine("}")
            appendLine("run_sniper_preflight || echo \"WARN: sniper preflight failed status=\$?\"")
            appendLine()
            appendLine("set -e")
            appendLine()
            appendLine("exec \"\$PROOT\" \\")
            appendLine("    -r \"\$ROOTFS\" \\")
            appendLine("    -b /system \\")
            appendLine("    -b /apex \\")
            appendLine("    -b /vendor \\")
            appendLine("    -b /proc \\")
            appendLine("    -b /sys \\")
            appendLine("    -b /dev \\")
            appendLine("    -b ${quote(resolvConf.absolutePath)}:/etc/resolv.conf \\")
            appendLine("    -b \"\$IMAGEFS:/winnative-imagefs\" \\")
            appendLine("    -b \"\$STEAM_OPT:/opt/steam-arm64\" \\")
            appendLine("    -b \"\$XUSER_HOME:/home/xuser\" \\")
            appendLine("    -b \"\$TMP:/tmp\" \\")
            appendLine("    -b \"\$IMAGEFS/run:/run\" \\")
            appendLine("    -w /home/xuser \\")
            appendLine("    /usr/bin/env -i \\")
            appendLine("        HOME=/home/xuser \\")
            appendLine("        USER=xuser \\")
            appendLine("        DISPLAY=:0 \\")
            appendLine("        XDG_RUNTIME_DIR=\"\$XDG_RUNTIME_DIR_IN_PROOT\" \\")
            appendLine("        XDG_DATA_HOME=/home/xuser/.local/share \\")
            appendLine("        XDG_CONFIG_HOME=/home/xuser/.config \\")
            // xdg stub dir takes precedence so Steam's early system()
            // hits the no-op stubs before any real xdg-utils we might
            // accidentally inherit (none exist in sniper, but defensive).
            appendLine("        PATH=/usr/local/bin:/usr/bin:/bin \\")
            appendLine("        LD_PRELOAD=${quoteShell(glibcPreloadInProot)} \\")
            appendLine("        ANDROID_SYSVSHM_SERVER=${quoteShell(sysvshmServer)} \\")
            appendLine("        WINNATIVE_WINE_BIN=${quoteShell(wineBinInsideProot)} \\")
            appendLine("        WINNATIVE_BIONIC_LD_PRELOAD=${quoteShell(bionicPreload)} \\")
            appendLine("        WINNATIVE_BIONIC_LD_LIBRARY_PATH=/winnative-imagefs/usr/lib:/system/lib64 \\")
            appendLine("        WINNATIVE_BIONIC_TMPDIR=/winnative-imagefs/usr/tmp \\")
            appendLine("        WINNATIVE_BIONIC_PATH=${quoteShell("$wineDirInsideProot:/winnative-imagefs/usr/bin")} \\")
            appendLine("        WINNATIVE_PROTON_LOG=/tmp/winnative-proton.log \\")
            appendLine("        WINNATIVE_STEAM_EARLY_REAPER=1 \\")
            appendLine("        STEAM_RUNTIME=0 \\")
            appendLine("        STEAM_RUNTIME_PREFER_HOST_LIBRARIES=0 \\")
            // Tell libdbus there is no session/system bus available so it
            // returns NULL fast instead of attempting autolaunch via fork +
            // exec dbus-launch, which under proot's ptrace overhead can
            // stall arm64 main-Steam during GTK 2 / dbus init right after
            // "Steam logging initialized" (the symptom we hit in round 4
            // — Steam loaded full GTK 2 + libdbus + libsystemd then froze
            // in poll() before steamclient.so dlopen / webhelper spawn).
            // Codex review: disable: scheme is the documented fail-fast
            // path; arm64 Steam tolerates "no bus" gracefully (no
            // notifications / inhibit-sleep / tray, but UI starts).
            // libdbus's "disabled:" sentinel is NOT a recognized transport
            // — it parses, fails, and on some versions of libdbus / sd-bus
            // (loaded transitively via libsystemd) the caller silently
            // falls back to the system default `unix:abstract=...` lookup
            // through /proc/net/unix, which is unreadable inside Tencent's
            // app sandbox and stalls. Use `unix:path=/dev/null` instead —
            // a syntactically valid Unix transport whose `connect()`
            // returns ENOTSOCK immediately. Identified by the round-6
            // research-agent (FreeDesktop dbus 1.14 source: only `unix:`,
            // `tcp:`, `nonce-tcp:`, `launchd:`, `systemd:` are valid
            // transports).
            appendLine("        DBUS_SESSION_BUS_ADDRESS=unix:path=/dev/null \\")
            appendLine("        DBUS_SYSTEM_BUS_ADDRESS=unix:path=/dev/null \\")
            // Pulse + PipeWire fail-fast env. PULSE_SERVER set to the
            // empty string makes libpulse skip its default-discovery
            // sequence; PULSE_CLIENTCONFIG points at the no-autospawn
            // client.conf we just wrote into the rootfs. PIPEWIRE_REMOTE
            // pointed at a definitely-absent socket makes libpipewire
            // return error from pw_context_connect immediately rather
            // than blocking on its own discovery dance. Together these
            // prevent Steam's audio init from forking /bin/sh -c
            // pulseaudio under proot's ptrace overhead — the most likely
            // cause of the post-bootstrap silent stall.
            appendLine("        PULSE_SERVER= \\")
            appendLine("        PULSE_CLIENTCONFIG=/etc/pulse/client.conf \\")
            appendLine("        PIPEWIRE_REMOTE=/dev/null/pipewire-not-here \\")
            // The Steam Client ARM64 binary lives at
            //   <steam>/steamrtarm64/steam
            // and dlopen()s a dozen .so files from steamrtarm64/ + linuxarm64/.
            // Tell the loader where to find them with LD_LIBRARY_PATH; the
            // sniper rootfs already provides glibc / pthread / libdl /
            // ld-linux-aarch64.so.1 from the in-chroot /lib path.
            appendLine("        LD_LIBRARY_PATH=\"\$STEAM_ARM64_BIN_DIR:\$STEAM_ARM64_SDK_DIR\" \\")
            // Bypass steam.sh entirely. The bundled steam.sh hardcodes
            // PLATFORM=ubuntu12_32 (no ARM64 detection) and aborts on a
            // 32-bit libc.so.6 check. Exec the actual ARM64 Steam Client
            // binary directly. It accepts the same CLI args as the legacy
            // Linux client (-silent / -console / -applaunch <id> etc.).
            appendLine("        \"\$STEAM_ARM64_BIN_DIR/steam\" \\")
            steamArgs.forEach { arg ->
                appendLine("            ${quoteShell(arg)} \\")
            }
            appendLine("            \"\$@\"")
        }

        script.writeText(text)
        if (!script.setExecutable(true, false)) {
            throw IOException("Could not chmod +x $script")
        }
        Log.i(TAG, "Wrote launcher script: $script (${script.length()} bytes)")
        return script
    }

    /** Double-quoted shell escape for plain (no `$`) literals. */
    private fun quote(s: String): String =
        '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"'

    /** Same as [quote] — kept separate so future single-quote variants are obvious. */
    private fun quoteShell(s: String): String = quote(s)
}
