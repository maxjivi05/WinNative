package com.winlator.cmod.feature.stores.steam.linux

import android.content.Context
import android.util.Log
import com.winlator.cmod.runtime.display.environment.ImageFs
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Installs WinNative's Steam compatibility tool into the Linux Steam
 * client's `~/.steam/root/compatibilitytools.d/` so Steam picks it up
 * on next launch and exposes it as a Proton-equivalent.
 *
 * Why we ship our own compat tool instead of using Valve's Proton:
 *   - Proton uses `pressure-vessel` to set up an isolated container,
 *     which needs unprivileged user namespaces. Android's app sandbox
 *     doesn't grant those; pressure-vessel will fail at startup.
 *   - Proton expects to launch its own pinned Wine; we want to use the
 *     emulator's existing Wine ARM64EC + FEX install instead.
 *
 * Layout once installed:
 *   <imagefs>/home/xuser/.local/share/Steam/compatibilitytools.d/
 *     winnative-proton/
 *       compatibilitytool.vdf   (declares the tool to Steam)
 *       toolmanifest.vdf        (commandline contract)
 *       proton                  (bash wrapper — execs host Wine)
 *
 * The `proton` wrapper relies on env vars set by [XServerDisplayActivity]
 * before launching Steam — `WINNATIVE_WINE_BIN`, `WINNATIVE_BIONIC_LD_PRELOAD`,
 * `WINNATIVE_BIONIC_PATH`, plus the standard Steam-supplied
 * `STEAM_COMPAT_DATA_PATH`, `PROTON_VERB`, etc. Steam itself runs inside
 * proot; the wrapper stays inside that proot tree but execs the host
 * Wine binary which is reachable through a `--bind` from the launcher.
 *
 * Idempotent: reinstall on every Steam launch, but skip the disk write
 * if the bundled assets and what's on disk already match SHA-256.
 */
object ProtonToolInstaller {

    private const val TAG = "ProtonToolInstaller"

    private const val ASSET_PREFIX = "proton/winnative/"
    const val TOOL_NAME = "winnative-proton"

    /**
     * The Steam state dir under which `compatibilitytools.d/` lives. We
     * only ever write here, so creating it on demand is fine — Steam
     * will recognize the tool the next time it scans the directory.
     */
    private fun compatToolRoots(context: Context): List<File> {
        val imageFs = ImageFs.find(context)
        val rootDir = imageFs.rootDir
        val roots = mutableListOf(
            File(rootDir, "${ImageFs.HOME_PATH}/.local/share/Steam/compatibilitytools.d"),
        )

        val dotSteamRoot = File(rootDir, "${ImageFs.HOME_PATH}/.steam/root")
        if (dotSteamRoot.exists() && !Files.isSymbolicLink(dotSteamRoot.toPath())) {
            roots.add(File(dotSteamRoot, "compatibilitytools.d"))
        }

        roots.add(File(rootDir, "opt/steam-arm64/client/compatibilitytools.d"))
        return roots
    }

    private fun primaryCompatToolsDir(context: Context): File =
        File(ImageFs.find(context).rootDir, "${ImageFs.HOME_PATH}/.local/share/Steam/compatibilitytools.d")

    fun installDir(context: Context): File =
        File(primaryCompatToolsDir(context), TOOL_NAME)

    fun protonScript(context: Context): File =
        File(installDir(context), "proton")

    @Throws(IOException::class)
    fun ensureInstalled(context: Context): File {
        val files = listOf("compatibilitytool.vdf", "toolmanifest.vdf", "proton")
        val targets = compatToolRoots(context).map { File(it, TOOL_NAME) }
        for (target in targets) {
            installInto(context, target, files)
        }
        Log.i(TAG, "Installed Steam compat tool '$TOOL_NAME' at ${targets.joinToString()}")
        return targets.first()
    }

    @Throws(IOException::class)
    private fun installInto(context: Context, target: File, files: List<String>) {
        if (!target.mkdirs() && !target.isDirectory) {
            throw IOException("Could not create compat tool directory $target")
        }

        for (name in files) {
            val asset = ASSET_PREFIX + name
            val expectedSha = sha256Asset(context, asset)
            val dest = File(target, name)
            val current = if (dest.exists()) sha256(dest) else null
            if (current.equals(expectedSha, ignoreCase = true)) continue

            val tmp = File(
                target,
                "$name.${android.os.Process.myPid()}.${System.nanoTime()}.part",
            )
            tmp.delete()
            try {
                context.assets.open(asset).use { input ->
                    tmp.outputStream().buffered().use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                val gotSha = sha256(tmp)
                if (!gotSha.equals(expectedSha, ignoreCase = true)) {
                    throw IOException(
                        "asset sha256 mismatch for $asset: expected $expectedSha got $gotSha",
                    )
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    throw IOException("Could not promote $tmp → $dest")
                }
            } finally {
                tmp.delete()
            }
        }

        // The wrapper script must be executable so Steam's `chmod-less` exec()
        // works. The two .vdf files don't need it.
        val script = File(target, "proton")
        if (script.exists() && !script.setExecutable(true, false)) {
            throw IOException("Could not set executable bit on $script")
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Asset(context: Context, asset: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        context.assets.open(asset).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
