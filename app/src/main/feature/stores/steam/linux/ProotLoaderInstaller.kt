package com.winlator.cmod.feature.stores.steam.linux

import android.content.Context
import android.util.Log
import com.winlator.cmod.runtime.display.environment.ImageFs
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Stages the proot-loader binary onto disk so [LinuxSteamLauncher] can
 * point `PROOT_LOADER` at a real path.
 *
 * Why an asset and not a `lib/arm64-v8a/` jniLib: the loader has to be a
 * self-contained static-PIE executable with no `libc`/`libdl`/`libm`
 * NEEDED entries, no PT_INTERP, no PLT relocations — the kernel `execve`s
 * it directly into the tracee and jumps to `_start` with no dynamic
 * linker run. Android's PackageManager validates files in `lib/<abi>/`
 * as shared libraries; a clean static-PIE binary fails that check with
 * `INSTALL_FAILED_CONTAINER_ERROR / Failed to extract native libraries,
 * res=-18`. Building it under jniLibs in any other shape pulls in
 * `crtbegin_so` + `libc.so/libdl.so/libm.so` deps, which segfault the
 * tracee at startup (see `_start` calling `memset@plt` with no resolved
 * relocations). Cross-compiling with `aarch64-linux-gnu-gcc -nostdlib
 * -static-pie` then shipping the binary as an asset sidesteps both
 * problems.
 *
 * Layout after install:
 *   <imagefs>/opt/proot/proot-loader   (executable, mode 0755)
 *
 * Idempotent — re-run on each launch is a SHA-256 check then a no-op.
 */
object ProotLoaderInstaller {

    private const val TAG = "ProotLoaderInstaller"
    private const val ASSET_PATH = "proot/loader/proot-loader"

    /** Subdir under `imagefs/` for the staged loader binary. */
    const val INSTALL_SUBDIR = "opt/proot"
    const val INSTALLED_FILENAME = "proot-loader"

    fun installDir(context: Context): File =
        File(ImageFs.find(context).rootDir, INSTALL_SUBDIR)

    fun installedFile(context: Context): File =
        File(installDir(context), INSTALLED_FILENAME)

    @Throws(IOException::class)
    fun ensureInstalled(context: Context): File {
        val dest = installedFile(context)
        dest.parentFile?.mkdirs()

        val expectedSha = sha256Asset(context)
        val current = if (dest.exists()) sha256(dest) else null
        if (current.equals(expectedSha, ignoreCase = true)) {
            applyMode(dest)
            return dest
        }

        // Race-safe temp file; the atomic rename below picks one winner if
        // two installers ever overlap. Same pattern as GlibcPreloadInstaller.
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
            // Race-recheck: if a parallel caller landed first with the
            // matching bytes, drop our temp.
            if (dest.exists() && sha256(dest).equals(expectedSha, ignoreCase = true)) {
                applyMode(dest)
                return dest
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                throw IOException("Could not promote $tmp → $dest")
            }
            applyMode(dest)
            Log.i(TAG, "Installed proot-loader: $dest (${dest.length()} bytes)")
            return dest
        } finally {
            tmp.delete()
        }
    }

    private fun applyMode(file: File) {
        if (!file.setReadable(true, false)) {
            Log.w(TAG, "could not set readable: $file")
        }
        // proot will execve() this. The exec bit is mandatory.
        if (!file.setExecutable(true, false)) {
            throw IOException("could not set executable bit on $file")
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
