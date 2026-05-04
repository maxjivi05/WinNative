package com.winlator.cmod.feature.stores.steam.linux

import android.content.Context
import android.util.Log
import com.winlator.cmod.runtime.display.environment.ImageFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * Bootstrap for the native Linux ARM64 Steam Client.
 *
 * Fetches Valve's public-beta CDN manifest, downloads the per-package
 * blobs (preferring the LZMA-wrapped `.zip.vz` variants), verifies them
 * against the manifest's SHA-256, unwraps VZ when needed, and unpacks
 * everything into `<imagefs>/opt/steam-arm64/client/`.
 *
 * We deliberately drop:
 *   - `_steamrt_*` and `runtime_*` packages (Valve's pressure-vessel
 *     container runtime — we don't have unprivileged user namespaces
 *     under proot, so we'd never invoke it anyway)
 *   - non-ARM64 binaries (`*_amd64*`, `*_i386*`, `*_osx*`, `*_win*`)
 *
 * The `_all` resources packages and the `*_linuxarm64*` package set
 * (plus the `*_androidarm64_linuxarm64*` bonus packages built against
 * bionic) are kept.
 */
object LinuxSteamClientManager {

    private const val TAG = "LinuxSteamClientManager"

    private const val MANIFEST_URL =
        "https://media.steampowered.com/client/steam_client_publicbeta_linuxarm64"

    private const val PACKAGE_BASE_URL = "https://media.steampowered.com/client/"

    /** Subdirectory under `imagefs/opt/` that holds the unpacked client. */
    const val INSTALL_SUBDIR = "opt/steam-arm64/client"

    /** Marker file indicating a successful install of a given manifest version. */
    private const val INSTALL_MARKER = ".linux-steam.installed"

    interface ProgressListener {
        fun onStage(stage: String) {}

        fun onPackageProgress(name: String, downloaded: Long, total: Long) {}

        fun onComplete(success: Boolean, error: String?)
    }

    fun installDir(context: Context): File =
        File(ImageFs.find(context).rootDir, INSTALL_SUBDIR)

    fun isInstalled(context: Context): Boolean {
        val dir = installDir(context)
        if (!dir.isDirectory) return false
        val marker = File(dir, INSTALL_MARKER)
        // The launcher script Valve ships is `steam.sh` plus an ELF named `steam`.
        // We only require the marker — file presence inside is checked by callers.
        return marker.exists() && marker.length() > 0
    }

    /** Currently installed manifest version, or null if not installed. */
    fun installedVersion(context: Context): String? {
        val marker = File(installDir(context), INSTALL_MARKER)
        return if (marker.exists()) marker.readText().trim().ifEmpty { null } else null
    }

    /**
     * Blocking install. Run from a worker thread. Idempotent: if the
     * marker matches the manifest's version, returns true immediately.
     *
     * A process-wide file lock prevents concurrent calls from racing on
     * `cacheDir/stage`, partial downloads, or the target swap.
     */
    fun install(context: Context, listener: ProgressListener? = null): Boolean {
        val target = installDir(context)
        target.parentFile?.mkdirs()

        val lockFile = File(context.cacheDir, "linux-steam-client.lock")
        lockFile.parentFile?.mkdirs()
        return RandomAccessFile(lockFile, "rw").use { raf ->
            val lock: FileLock =
                try {
                    raf.channel.lock()
                } catch (e: Exception) {
                    Log.e(TAG, "Could not acquire install lock", e)
                    listener?.onComplete(false, "install lock unavailable: ${e.message}")
                    return@use false
                }
            try {
                // Crash recovery has to live inside the lock so it can never
                // race with the swap below (else a concurrent installer's
                // mid-swap .old could be moved back over the live target).
                try {
                    recoverFromInterruptedSwap(target)
                } catch (e: Exception) {
                    Log.e(TAG, "Crash recovery failed; aborting install", e)
                    listener?.onComplete(false, "recovery failed: ${e.message}")
                    return@use false
                }
                doInstall(context, target, listener)
            } finally {
                try { lock.release() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Repairs a target whose previous install died mid-swap. Possible states:
     *   target=valid, .old absent      → nothing to do
     *   target=valid, .old present     → install completed; just remove .old
     *   target absent, .old present    → restore .old → target
     *   target=present-but-no-marker   → partial; treat as if target absent
     *
     * "valid" here means the target has the install marker file. We never
     * see a partial copied target because the swap path is rename-only.
     */
    private fun recoverFromInterruptedSwap(target: File) {
        val backup = File(target.parentFile, target.name + ".old")
        val staging = stagingFor(target)
        // Drop any half-built staging tree from a prior run. If we can't
        // remove it, we can't trust extracting into it next.
        if (staging.exists() && !staging.deleteRecursively()) {
            throw IOException("Could not clean stale staging tree: $staging")
        }

        val targetHasMarker = File(target, INSTALL_MARKER).let { it.isFile && it.length() > 0 }
        if (!backup.exists()) {
            if (target.exists() && !targetHasMarker) {
                Log.w(TAG, "Removing partial target without marker: $target")
                target.deleteRecursively()
            }
            return
        }
        if (target.exists() && targetHasMarker) {
            Log.i(TAG, "Cleaning up leftover backup ${backup.name}")
            backup.deleteRecursively()
            return
        }
        // backup present, target missing or partial → restore.
        if (target.exists()) target.deleteRecursively()
        if (!backup.renameTo(target)) {
            throw IOException("Failed to restore backup install: $backup → $target")
        }
        Log.i(TAG, "Restored prior install from ${backup.name}")
    }

    private fun stagingFor(target: File): File =
        File(target.parentFile, target.name + ".staging")

    private fun doInstall(context: Context, target: File, listener: ProgressListener?): Boolean {
        return try {
            listener?.onStage("Fetching manifest")
            val manifestText = SteamDownloadUtil.fetchText(MANIFEST_URL)
            val manifest = LinuxSteamManifest.parse(manifestText)
            Log.i(
                TAG,
                "Manifest: rootKey=${manifest.rootKey} version=${manifest.version} " +
                    "packages=${manifest.packages.size}",
            )

            if (installedVersion(context) == manifest.version) {
                try {
                    assertArm64Payload(target)
                    Log.i(TAG, "Already at version ${manifest.version}; skipping install")
                    listener?.onComplete(true, null)
                    return true
                } catch (e: IOException) {
                    Log.w(TAG, "Installed Steam client marker exists but payload is incomplete; reinstalling", e)
                    File(target, INSTALL_MARKER).delete()
                }
            }

            val selected = manifest.packages.filter { keepPackage(it.name) }
            Log.i(
                TAG,
                "Selected ${selected.size}/${manifest.packages.size} packages " +
                    "(skipped: ${manifest.packages.size - selected.size})",
            )
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                val dropped = manifest.packages.filter { !keepPackage(it.name) }.map { it.name }
                Log.d(TAG, "Dropped packages: $dropped")
            }
            if (selected.isEmpty()) {
                throw IOException("Manifest contained no usable packages")
            }

            // Cache (downloads + VZ unwrap) lives in cacheDir; the *staging
            // tree* lives next to `target` so the final swap is always a
            // pure rename within one filesystem (no cross-fs copy fallback,
            // no partial-target hazard).
            val cacheDir = File(context.cacheDir, "linux-steam-client")
            cacheDir.mkdirs()
            val stagingTree = stagingFor(target)
            if (stagingTree.exists() && !stagingTree.deleteRecursively()) {
                throw IOException("Could not clean stale staging tree: $stagingTree")
            }
            if (!stagingTree.mkdirs() && !stagingTree.isDirectory) {
                throw IOException("Could not create staging tree: $stagingTree")
            }

            val installStart = System.currentTimeMillis()
            for ((index, pkg) in selected.withIndex()) {
                val pkgStart = System.currentTimeMillis()
                Log.i(TAG, "[${index + 1}/${selected.size}] downloading ${pkg.name} (${pkg.filename})")
                listener?.onStage("Package ${index + 1}/${selected.size}: ${pkg.name}")
                val zipFile = downloadAndPrepare(pkg, cacheDir, listener)
                val dlMs = System.currentTimeMillis() - pkgStart
                listener?.onStage("Extracting ${pkg.name}")
                Log.i(TAG, "[${index + 1}/${selected.size}] extracting ${pkg.name} (${zipFile.length() / 1024} KiB) — fetch took ${dlMs}ms")
                extractZip(zipFile, stagingTree)
                Log.i(TAG, "[${index + 1}/${selected.size}] done ${pkg.name} (${System.currentTimeMillis() - pkgStart}ms)")
            }
            Log.i(TAG, "All ${selected.size} packages staged in ${System.currentTimeMillis() - installStart}ms")

            // Finalize the staging tree (chmod + write marker) before
            // swapping it in, so any successful rename outcome leaves a
            // self-consistent install.
            chmodExecutables(stagingTree)
            assertArm64Payload(stagingTree)
            File(stagingTree, INSTALL_MARKER).writeText(manifest.version)

            // Pure-rename two-phase swap: target → .old, then staging → target,
            // then delete .old. Both renames are intra-fs and atomic. If the
            // second rename fails we restore the backup so we never lose a
            // working install.
            val backup = File(target.parentFile, target.name + ".old")
            if (backup.exists()) backup.deleteRecursively()
            if (target.exists()) {
                if (!target.renameTo(backup)) {
                    throw IOException("Could not rename target → ${backup.name}")
                }
            }
            if (!stagingTree.renameTo(target)) {
                if (backup.exists() && !backup.renameTo(target)) {
                    Log.e(TAG, "Failed to rollback backup → target after staging rename failure")
                }
                throw IOException("Could not rename staging → ${target.name}")
            }
            backup.deleteRecursively()

            Log.i(TAG, "Installed Linux ARM64 Steam Client ${manifest.version} to $target")
            listener?.onComplete(true, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Linux Steam install failed", e)
            // Make sure we don't leave a half-extracted staging tree behind.
            try { stagingFor(target).deleteRecursively() } catch (_: Exception) {}
            listener?.onComplete(false, e.message)
            false
        }
    }

    // ─── Internals ───────────────────────────────────────────────────────

    /**
     * Filter the manifest down to the packages we'll actually use on
     * Android-proot ARM64. The naming convention in the public-beta CDN
     * embeds platform tokens like `_linuxarm64`, `_amd64`, `_all`, etc.
     */
    private fun keepPackage(name: String): Boolean {
        val lower = name.lowercase()
        val tokens = lower.split('_').filter { it.isNotEmpty() }.toSet()

        // pressure-vessel container runtime — never invoked on Android
        if (lower.startsWith("runtime_")) return false
        if ("steamrt" in tokens || lower.contains("steamrt")) return false

        // Foreign-arch / foreign-OS payloads. Match on whole tokens so that
        // future names like "win_all" / "win_linuxarm64" are correctly dropped.
        val foreign = setOf("amd64", "i386", "i686", "x86", "osx", "macos", "win", "win32", "win64", "windows")
        if (tokens.any { it in foreign }) return false

        // Keep: arch-agnostic resource bundles + any arm64 build
        if ("all" in tokens) return true
        if (lower.contains("linuxarm64")) return true
        if (lower.contains("androidarm64")) return true

        // The manifest root key is "linuxarm64" itself; some manifests carry a
        // bare "client" or "linuxarm64" top-level package. Keep those too.
        return lower == "linuxarm64" || lower == "client"
    }

    private fun assertArm64Payload(root: File) {
        val required = listOf(
            "steamrtarm64/steam",
            "steamrtarm64/steamwebhelper",
            "steamrtarm64/libcef.so",
            "linuxarm64/steamclient.so",
        )
        val missing = required.filter { !File(root, it).exists() }
        if (missing.isNotEmpty()) {
            throw IOException("Linux Steam ARM64 payload incomplete; missing: $missing")
        }
    }

    /**
     * Downloads the package blob, verifies SHA-256, unwraps VZ if needed,
     * and returns the path to a usable .zip.
     */
    private fun downloadAndPrepare(
        pkg: LinuxSteamManifest.Package,
        cacheDir: File,
        listener: ProgressListener?,
    ): File {
        val blob = File(cacheDir, pkg.filename)
        if (!(blob.exists() && blob.length() > 0 && SteamDownloadUtil.sha256(blob).equals(pkg.sha256, ignoreCase = true))) {
            // (Re)download
            val tmp = File(cacheDir, pkg.filename + ".part")
            tmp.delete()
            SteamDownloadUtil.downloadFile(PACKAGE_BASE_URL + pkg.filename, tmp) { downloaded, total ->
                listener?.onPackageProgress(pkg.name, downloaded, total)
            }
            val got = SteamDownloadUtil.sha256(tmp)
            if (!got.equals(pkg.sha256, ignoreCase = true)) {
                tmp.delete()
                throw IOException(
                    "SHA-256 mismatch for ${pkg.filename}: expected ${pkg.sha256}, got $got",
                )
            }
            if (blob.exists()) blob.delete()
            if (!tmp.renameTo(blob)) {
                Files.copy(tmp.toPath(), blob.toPath(), StandardCopyOption.REPLACE_EXISTING)
                tmp.delete()
            }
        }

        if (!pkg.isVzWrapped) return blob

        // Always unwrap VZ to a fresh .part and rename in place. We can't
        // cheaply verify a cached `.unwrapped.zip` (the manifest only carries
        // a SHA-2 for the .vz form), and a truncated cached zip from a prior
        // crash would still extract a partial prefix without ZipInputStream
        // catching the inconsistency.
        val zip = File(cacheDir, pkg.filename + ".unwrapped.zip")
        val tmpZip = File(cacheDir, pkg.filename + ".unwrapped.zip.part")
        tmpZip.delete()
        VzDecoder.decode(blob, tmpZip)
        if (zip.exists()) zip.delete()
        if (!tmpZip.renameTo(zip)) {
            Files.copy(tmpZip.toPath(), zip.toPath(), StandardCopyOption.REPLACE_EXISTING)
            tmpZip.delete()
        }
        return zip
    }

    private fun extractZip(zip: File, destRoot: File) {
        val rootCanon = destRoot.canonicalPath
        val rootPrefix = rootCanon + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                try {
                    val safeName = entry.name.replace('\\', '/').trimStart('/')
                    if (safeName.isEmpty() || safeName == "." || safeName == "/") continue

                    val outFile = File(destRoot, safeName)
                    val outCanon = outFile.canonicalPath
                    if (outCanon != rootCanon && !outCanon.startsWith(rootPrefix)) {
                        Log.w(TAG, "Skipping zip entry that escapes destRoot: ${entry.name}")
                        continue
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            zin.copyTo(out, bufferSize = 64 * 1024)
                        }
                    }
                } finally {
                    zin.closeEntry()
                }
            }
        }
    }

    /**
     * Mark every ELF executable and `#!` script under [installRoot] +x.
     * ZIP doesn't preserve POSIX modes, so everything lands 0644 after
     * extract. Detect by header bytes — this catches steam, steamwebhelper,
     * steamerrorreporter, and any future renames or per-arch helper paths
     * (e.g. ubuntu12_64/) without us having to maintain a name allow-list.
     * `.so` libraries are skipped: they should stay 0644 (loader doesn't
     * need exec bit and chmod-ing them all is noise).
     */
    private fun chmodExecutables(installRoot: File) {
        val stack = ArrayDeque<File>()
        stack.addLast(installRoot)
        val header = ByteArray(4)
        val failures = mutableListOf<File>()
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val children = cur.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.addLast(child)
                    continue
                }
                if (!child.isFile || child.length() < 2) continue
                if (child.name.endsWith(".so") || child.name.contains(".so.")) continue
                val read = try {
                    child.inputStream().use { it.read(header) }
                } catch (_: Exception) { -1 }
                if (read < 2) continue

                val isElf = read >= 4 &&
                    header[0] == 0x7F.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
                val isShebang = header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte()
                if (isElf || isShebang) {
                    if (!child.setExecutable(true, false)) failures.add(child)
                }
            }
        }
        if (failures.isNotEmpty()) {
            // imagefs being mounted noexec, or filesDir under a fs that drops
            // mode bits, means the whole stack is broken — fail the install
            // rather than ship a tree where `steam` can't run.
            throw IOException(
                "Could not set executable bit on ${failures.size} files (first: ${failures.first()})",
            )
        }
    }

}
