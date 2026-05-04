package com.winlator.cmod.feature.stores.steam.linux

import android.content.Context
import android.util.Log
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.shared.io.FileUtils
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Stages GTK 2 (`libgtk-x11-2.0.so.0` + `libgdk-x11-2.0.so.0`) for arm64
 * into the sniper rootfs's library path so Steam's `steamui.so` can dlopen.
 *
 * Why we need this: Valve's ARM64 Steam Client links `steamui.so` against
 * GTK 2 (`NEEDED libgtk-x11-2.0.so.0`). The sniper-arm64 platform runtime
 * ships GTK 3 but not GTK 2 — and Steam's bundled steamrt3c "platform"
 * runtime only carries GTK 2 for `i386-linux-gnu` and `x86_64-linux-gnu`,
 * not arm64. Without the two libs, `dlmopen("steamui.so")` fails with
 *   "libgtk-x11-2.0.so.0: cannot open shared object file"
 * and Steam exits with `Fatal error: Failed to load steamui.so`.
 *
 * We bundle Debian 11 (bullseye) arm64 builds as APK assets and extract
 * them on first launch into the sniper rootfs's `aarch64-linux-gnu` lib
 * dir. Sniper already provides every transitive GTK 2 dependency
 * (libpango, libatk, libgio, libcairo, libgdk_pixbuf, libX*) so we only
 * need the two GTK 2 ELFs plus the standard `lib*.so.0` SONAME symlinks.
 *
 * Source: `libgtk2.0-0_2.24.33-2+deb11u1_arm64.deb` from Debian's
 * `pool/main/g/gtk+2.0/` mirror.
 */
object Gtk2RuntimeInstaller {

    private const val TAG = "Gtk2RuntimeInstaller"
    private const val ASSET_DIR = "gtk2-arm64"

    /** Subdir under `<imagefs>/opt/steam-runtime/files/lib/aarch64-linux-gnu/`. */
    private const val LIB_DIR_REL = "opt/steam-runtime/files/lib/aarch64-linux-gnu"

    private val FILES = listOf(
        "libgtk-x11-2.0.so.0.2400.33" to "libgtk-x11-2.0.so.0",
        "libgdk-x11-2.0.so.0.2400.33" to "libgdk-x11-2.0.so.0",
    )

    fun installDir(context: Context): File =
        File(ImageFs.find(context).rootDir, LIB_DIR_REL)

    @Throws(IOException::class)
    fun ensureInstalled(context: Context): File {
        val dest = installDir(context)
        if (!dest.exists()) {
            throw IOException(
                "sniper rootfs lib dir missing: $dest — was the runtime installed?",
            )
        }

        for ((versioned, soname) in FILES) {
            val asset = "$ASSET_DIR/$versioned"
            val targetVer = File(dest, versioned)
            val targetSoname = File(dest, soname)

            val expectedSha = sha256Asset(context, asset)
            val current = if (targetVer.exists()) sha256(targetVer) else null
            if (!current.equals(expectedSha, ignoreCase = true)) {
                copyAsset(context, asset, targetVer)
                val gotSha = sha256(targetVer)
                if (!gotSha.equals(expectedSha, ignoreCase = true)) {
                    targetVer.delete()
                    throw IOException(
                        "asset sha256 mismatch for $asset: expected $expectedSha got $gotSha",
                    )
                }
            }
            applyMode(targetVer)

            // SONAME symlink — Steam's loader resolves NEEDED entries by
            // SONAME (`libgtk-x11-2.0.so.0`), not the version-suffixed
            // filename. Drop a relative symlink alongside the real file.
            ensureSymlink(targetSoname, versioned)
        }

        Log.i(TAG, "GTK 2 arm64 staged into $dest")
        return dest
    }

    private fun copyAsset(context: Context, asset: String, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(
            dest.parentFile,
            "${dest.name}.${android.os.Process.myPid()}.${System.nanoTime()}.part",
        )
        tmp.delete()
        try {
            context.assets.open(asset).use { input ->
                tmp.outputStream().buffered().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                throw IOException("Could not promote $tmp → $dest")
            }
        } finally {
            tmp.delete()
        }
    }

    private fun ensureSymlink(link: File, target: String) {
        val path = link.toPath()
        if (java.nio.file.Files.isSymbolicLink(path)) {
            val current = java.nio.file.Files.readSymbolicLink(path).toString()
            if (current == target) return
            link.delete()
        } else if (link.exists()) {
            link.delete()
        }
        FileUtils.symlink(target, link.absolutePath)
        if (!java.nio.file.Files.isSymbolicLink(path) ||
            java.nio.file.Files.readSymbolicLink(path).toString() != target
        ) {
            throw IOException("symlink ${link.absolutePath} → $target failed")
        }
    }

    private fun applyMode(file: File) {
        if (!file.setReadable(true, false)) {
            Log.w(TAG, "could not set readable: $file")
        }
        // The dynamic linker mmaps these but doesn't require +x; matching
        // the imagefs convention anyway.
        file.setExecutable(true, false)
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
