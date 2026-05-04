package com.winlator.cmod.feature.stores.steam.linux

import android.content.Context
import android.util.Log
import com.winlator.cmod.runtime.display.environment.ImageFs
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Stages a glibc-linked copy of the existing SysV-shm shim onto disk so
 * the native Linux ARM64 Steam client (running inside proot under the
 * sniper-arm64 runtime) can `LD_PRELOAD` it.
 *
 * Why a second copy: `imagefs/usr/lib/libandroid-sysvshm.so` is bionic
 * (`NEEDED libc.so`). Steam is glibc (`NEEDED libc.so.6`). The ELF
 * loader can't satisfy both ABIs from one `.so` — a glibc process that
 * preloads the bionic copy fails before `main` because no `libc.so`
 * exists in the sniper rootfs.
 *
 * The cross-compiled glibc copy is shipped as an APK asset at
 * `assets/preload/libandroid-sysvshm-glibc.so` (cross-built with
 * `aarch64-linux-gnu-gcc` from `android_sysvshm/android_sysvshm.c`,
 * the same source the imagefs's bionic `.so` is built from). At
 * install time we extract it to `<imagefs>/opt/steam-arm64/preload/`
 * so POC4 can preload it with an absolute, proot-bind-friendly path.
 *
 * The shim's wire protocol is unchanged from the bionic build, so
 * `SysVSHMRequestHandler` already serves it correctly — both ABIs talk
 * to the same `ANDROID_SYSVSHM_SERVER` unix socket.
 */
object GlibcPreloadInstaller {

    private const val TAG = "GlibcPreloadInstaller"

    private const val ASSET_PATH = "preload/libandroid-sysvshm-glibc.so"

    /** Subdir under `imagefs/` for staged glibc preloads. */
    const val INSTALL_SUBDIR = "opt/steam-arm64/preload"

    /** Final filename inside the install dir. Preserves the bionic SONAME. */
    const val INSTALLED_FILENAME = "libandroid-sysvshm.so"

    fun installDir(context: Context): File =
        File(ImageFs.find(context).rootDir, INSTALL_SUBDIR)

    fun installedFile(context: Context): File =
        File(installDir(context), INSTALLED_FILENAME)

    /**
     * Copies the asset to `<imagefs>/opt/steam-arm64/preload/libandroid-sysvshm.so`
     * if it's missing or its SHA-256 doesn't match the asset. Idempotent and
     * cheap to call — re-runs only on APK upgrade or manual cache wipe.
     *
     * Returns the installed file path on success.
     */
    @Throws(IOException::class)
    fun ensureInstalled(context: Context): File {
        val dest = installedFile(context)
        dest.parentFile?.mkdirs()

        val expectedSha = sha256Asset(context)
        val current = if (dest.exists()) sha256(dest) else null
        if (current.equals(expectedSha, ignoreCase = true)) {
            // Bytes are good. Mode might still be wrong if a previous run
            // landed here without setting it (e.g. process killed mid-install).
            // setReadable/setExecutable are no-ops if already set.
            applyMode(dest)
            return dest
        }

        // Race-safe temp: each call gets its own .part suffixed with PID +
        // a random tag so two concurrent first-run callers can't delete each
        // other's temp file. The atomic rename below picks one winner.
        val tmp = File(
            dest.parentFile,
            "$INSTALLED_FILENAME.${android.os.Process.myPid()}.${System.nanoTime()}.part",
        )
        tmp.delete()
        try {
            context.assets.open(ASSET_PATH).use { input ->
                tmp.outputStream().buffered().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            val gotSha = sha256(tmp)
            if (!gotSha.equals(expectedSha, ignoreCase = true)) {
                throw IOException(
                    "asset sha256 mismatch after copy: expected $expectedSha got $gotSha",
                )
            }
            // Re-check destination: a concurrent caller may have already
            // installed correct bytes between our SHA-mismatch read above
            // and now — in that case, drop our temp.
            if (dest.exists() && sha256(dest).equals(expectedSha, ignoreCase = true)) {
                applyMode(dest)
                return dest
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                throw IOException("Could not promote $tmp → $dest")
            }
            applyMode(dest)
            Log.i(TAG, "Installed glibc sysvshm preload: $dest (${dest.length()} bytes)")
            return dest
        } finally {
            tmp.delete()
        }
    }

    /**
     * Mode normalization for the preload file. proot's bind-mounted
     * `/opt/steam-arm64` lets the dynamic linker mmap the .so even
     * without the exec bit, but readable+executable matches the
     * imagefs convention and avoids surprises if proot ever evolves.
     */
    private fun applyMode(file: File) {
        if (!file.setReadable(true, false)) {
            Log.w(TAG, "could not set readable: $file")
        }
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

    private fun sha256Asset(context: Context): String {
        val md = MessageDigest.getInstance("SHA-256")
        context.assets.open(ASSET_PATH).use { input ->
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
