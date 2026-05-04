package com.winlator.cmod.feature.stores.steam.linux

import android.content.Context
import android.util.Log
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.shared.io.FileUtils
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.zip.GZIPInputStream

/**
 * Bootstrap for Valve's pre-built **Steam Linux Runtime sniper-arm64**
 * platform image — the glibc 2.31 / Debian 11 rootfs that proot will use
 * as `--rootfs` when launching the native Linux ARM64 Steam Client.
 *
 * Source: `https://repo.steampowered.com/steamrt-images-sniper/snapshots/`
 * `latest-container-runtime-public-beta/` — specifically the
 * `Platform-arm64-sniper-runtime.tar.gz` artifact (≈160 MiB), NOT the
 * `SteamLinuxRuntime_sniper-arm64.tar.xz` compat-tool bundle. The latter
 * is a pressure-vessel container we don't need; the former is the flat
 * rootfs that contains `./files/lib/ld-linux-aarch64.so.1`.
 *
 * Layout after extract:
 *   <imagefs>/opt/steam-runtime/files/                 ← becomes proot's `/`
 *     lib/ld-linux-aarch64.so.1 → aarch64-linux-gnu/ld-2.31.so
 *     lib/aarch64-linux-gnu/libc.so.6, libdl.so.2, ...
 *     usr/lib/aarch64-linux-gnu/...
 *     usr/bin/...
 *   <imagefs>/opt/steam-runtime/.sniper.installed       ← marker (build_id)
 *
 * Atomic install: download → SHA-256 verify against the published
 * `SHA256SUMS` index → extract into a sibling `.staging` directory →
 * intra-fs swap. Crash mid-install never leaves a half-built tree at the
 * target path; an interrupted run repairs from the lone `.old` backup.
 *
 * One file lock guards both this manager and [LinuxSteamClientManager];
 * the two installs share `<cacheDir>/linux-steam-client.lock` so they
 * can't race on disk space during the user's first launch.
 */
object SniperRuntimeManager {

    private const val TAG = "SniperRuntimeManager"

    private const val INDEX_BASE_URL =
        "https://repo.steampowered.com/steamrt-images-sniper/snapshots/" +
            "latest-container-runtime-public-beta/"

    private const val RUNTIME_FILENAME =
        "com.valvesoftware.SteamRuntime.Platform-arm64-sniper-runtime.tar.gz"

    private const val BUILD_ID_FILENAME =
        "com.valvesoftware.SteamRuntime.Platform-arm64-sniper-buildid.txt"

    private const val SHA256SUMS_FILENAME = "SHA256SUMS"

    /** Subdirectory under `imagefs/` that holds the unpacked runtime. */
    const val INSTALL_SUBDIR = "opt/steam-runtime"

    private const val INSTALL_MARKER = ".sniper.installed"

    interface ProgressListener {
        fun onStage(stage: String) {}
        fun onProgress(downloaded: Long, total: Long) {}
        fun onComplete(success: Boolean, error: String?)
    }

    fun installDir(context: Context): File =
        File(ImageFs.find(context).rootDir, INSTALL_SUBDIR)

    /** Path inside the install where proot's `--rootfs` should point. */
    fun rootfsDir(context: Context): File = File(installDir(context), "files")

    fun isInstalled(context: Context): Boolean {
        val marker = File(installDir(context), INSTALL_MARKER)
        if (!marker.isFile || marker.length() == 0L) return false
        // A real install always carries the glibc dynamic linker.
        return File(rootfsDir(context), "lib/ld-linux-aarch64.so.1").exists()
    }

    fun installedBuildId(context: Context): String? {
        val marker = File(installDir(context), INSTALL_MARKER)
        return if (marker.exists()) marker.readText().trim().ifEmpty { null } else null
    }

    /**
     * Blocking install. Run from a worker thread. Idempotent: if the
     * marker matches the index's current `BUILD_ID`, returns true
     * immediately without touching disk.
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

    private fun stagingFor(target: File): File =
        File(target.parentFile, target.name + ".staging")

    /**
     * Mirror of [LinuxSteamClientManager.recoverFromInterruptedSwap]. See
     * that contract; we pick "valid" via marker file presence. Two-rename
     * swap means we can never see a partially-copied tree at the target.
     */
    private fun recoverFromInterruptedSwap(target: File) {
        val backup = File(target.parentFile, target.name + ".old")
        val staging = stagingFor(target)
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
        if (target.exists()) target.deleteRecursively()
        if (!backup.renameTo(target)) {
            throw IOException("Failed to restore backup runtime: $backup → $target")
        }
        Log.i(TAG, "Restored prior sniper runtime from ${backup.name}")
    }

    private fun doInstall(context: Context, target: File, listener: ProgressListener?): Boolean {
        return try {
            listener?.onStage("Fetching sniper runtime index")
            val sha256Map = fetchSha256Sums()
            val expectedSha = sha256Map[RUNTIME_FILENAME]
                ?: throw IOException("$RUNTIME_FILENAME missing from SHA256SUMS")
            val buildId = SteamDownloadUtil.fetchText(INDEX_BASE_URL + BUILD_ID_FILENAME)
                .trim()
                .ifEmpty { throw IOException("empty BUILD_ID") }

            if (installedBuildId(context) == buildId) {
                Log.i(TAG, "Already at build $buildId; skipping install")
                // Heal the merged-/usr self-loop symlink in-place — older
                // installs may predate it, and re-downloading 160 MiB just
                // to add a single symlink would be silly. The symlink target
                // contract is identical regardless of build_id.
                ensureUsrSymlink(File(target, "files"))
                listener?.onComplete(true, null)
                return true
            }

            val cacheDir = File(context.cacheDir, "linux-steam-runtime")
            cacheDir.mkdirs()

            val blob = File(cacheDir, RUNTIME_FILENAME)
            if (!(blob.exists() && SteamDownloadUtil.sha256(blob).equals(expectedSha, ignoreCase = true))) {
                listener?.onStage("Downloading sniper runtime ($buildId)")
                val tmp = File(cacheDir, "$RUNTIME_FILENAME.part")
                tmp.delete()
                SteamDownloadUtil.downloadFile(INDEX_BASE_URL + RUNTIME_FILENAME, tmp) { d, t ->
                    listener?.onProgress(d, t)
                }
                val got = SteamDownloadUtil.sha256(tmp)
                if (!got.equals(expectedSha, ignoreCase = true)) {
                    tmp.delete()
                    throw IOException(
                        "SHA-256 mismatch for $RUNTIME_FILENAME: expected $expectedSha, got $got",
                    )
                }
                if (blob.exists()) blob.delete()
                if (!tmp.renameTo(blob)) {
                    throw IOException("Could not promote download .part to $blob")
                }
            }

            val staging = stagingFor(target)
            if (staging.exists() && !staging.deleteRecursively()) {
                throw IOException("Could not clean stale staging tree: $staging")
            }
            if (!staging.mkdirs() && !staging.isDirectory) {
                throw IOException("Could not create staging tree: $staging")
            }

            listener?.onStage("Extracting sniper runtime")
            extractTarGz(blob, staging)

            // Sanity-check the extracted rootfs before we expose it: a usable
            // sniper image always contains ./files/lib/ld-linux-aarch64.so.1
            // (a symlink into aarch64-linux-gnu/). If that's missing, we
            // downloaded the wrong artifact.
            val ld = File(staging, "files/lib/ld-linux-aarch64.so.1")
            if (!ld.exists()) {
                throw IOException(
                    "Extracted runtime missing files/lib/ld-linux-aarch64.so.1; wrong tarball?",
                )
            }

            ensureUsrSymlink(File(staging, "files"))

            File(staging, INSTALL_MARKER).writeText(buildId)

            // Pure-rename swap: target → .old, staging → target, then drop .old.
            val backup = File(target.parentFile, target.name + ".old")
            if (backup.exists()) backup.deleteRecursively()
            if (target.exists()) {
                if (!target.renameTo(backup)) {
                    throw IOException("Could not rename target → ${backup.name}")
                }
            }
            if (!staging.renameTo(target)) {
                if (backup.exists() && !backup.renameTo(target)) {
                    Log.e(TAG, "Failed to rollback backup → target after staging rename failure")
                }
                throw IOException("Could not rename staging → ${target.name}")
            }
            backup.deleteRecursively()

            Log.i(TAG, "Installed sniper-arm64 runtime build=$buildId to $target")
            listener?.onComplete(true, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Sniper runtime install failed", e)
            try { stagingFor(target).deleteRecursively() } catch (_: Exception) {}
            listener?.onComplete(false, e.message)
            false
        }
    }

    /**
     * Fetch and parse the snapshot's `SHA256SUMS`. Format is one line per
     * file: `<sha256> *<filename>` (BSD-style with leading `*` flag).
     */
    private fun fetchSha256Sums(): Map<String, String> {
        val text = SteamDownloadUtil.fetchText(INDEX_BASE_URL + SHA256SUMS_FILENAME)
        val out = mutableMapOf<String, String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) continue
            val sha = parts[0]
            val name = parts[1].removePrefix("*")
            out[name] = sha
        }
        return out
    }

    /**
     * Stream-extract a `.tar.gz`. Preserves directories, regular files,
     * and symlinks (Valve ships ld-linux as a symlink chain, so we'd
     * otherwise hand proot a broken loader). Hardlinks are first tried
     * as `Files.createLink()`; if the FS rejects them we fall back to a
     * relative symlink so the install never duplicates bytes (the sniper
     * image carries a 46 MB DRI driver linked under ~30 driver names).
     * Zip-slip-equivalent guard: every resolved path must stay under
     * `destRoot`, and hardlink/symlink linkNames are validated the same
     * way before being followed.
     */
    private fun extractTarGz(archive: File, destRoot: File) {
        val rootCanon = destRoot.canonicalPath
        val rootPrefix = rootCanon + File.separator
        val chmodFailures = mutableListOf<File>()

        fun resolveSafe(rel: String): File? {
            val safe = rel.replace('\\', '/').trimStart('/')
            if (safe.isEmpty() || safe == "." || safe == "/") return null
            val out = File(destRoot, safe)
            val outCanon = out.canonicalPath
            return if (outCanon == rootCanon || outCanon.startsWith(rootPrefix)) out else null
        }

        archive.inputStream().buffered(64 * 1024).use { fis ->
            GZIPInputStream(fis).use { gz ->
                TarArchiveInputStream(gz).use { tar ->
                    while (true) {
                        val entry: TarArchiveEntry = tar.nextEntry ?: break
                        if (!tar.canReadEntryData(entry)) continue

                        val outFile = resolveSafe(entry.name)
                        if (outFile == null) {
                            Log.w(TAG, "Skipping tar entry that escapes destRoot: ${entry.name}")
                            continue
                        }

                        when {
                            entry.isDirectory -> {
                                outFile.mkdirs()
                            }
                            entry.isSymbolicLink -> {
                                outFile.parentFile?.mkdirs()
                                if (outFile.exists() ||
                                    java.nio.file.Files.isSymbolicLink(outFile.toPath())
                                ) {
                                    outFile.delete()
                                }
                                FileUtils.symlink(entry.linkName, outFile.absolutePath)
                                verifySymlinkTarget(outFile, entry.linkName)
                            }
                            entry.isLink -> {
                                // Tar hardlink. Resolve the source under destRoot, then
                                // try a real hardlink first (free disk-wise). If the
                                // filesystem rejects it (different mount, no link
                                // permission), fall back to a relative symlink — never
                                // copy the bytes, because the sniper image hardlinks
                                // 46 MB DRI drivers ~30× and we'd add ~1.5 GiB.
                                val source = resolveSafe(entry.linkName)
                                if (source == null) {
                                    Log.w(
                                        TAG,
                                        "Skipping tar hardlink with unsafe target: ${entry.linkName}",
                                    )
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    if (outFile.exists()) outFile.delete()
                                    if (!source.exists()) {
                                        throw IOException(
                                            "Hardlink ${entry.name} references missing source: ${entry.linkName}",
                                        )
                                    }
                                    try {
                                        java.nio.file.Files.createLink(outFile.toPath(), source.toPath())
                                    } catch (_: Exception) {
                                        val parent = outFile.parentFile
                                            ?: throw IOException("hardlink fallback: outFile has no parent: $outFile")
                                        val rel = parent.toPath().toAbsolutePath()
                                            .relativize(source.toPath().toAbsolutePath())
                                            .toString()
                                        FileUtils.symlink(rel, outFile.absolutePath)
                                        verifySymlinkTarget(outFile, rel)
                                    }
                                }
                            }
                            entry.isFile -> {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out ->
                                    tar.copyTo(out, bufferSize = 64 * 1024)
                                }
                            }
                            else -> {
                                // device nodes, FIFOs, etc. — sniper image shouldn't carry
                                // any, but skip gracefully if Valve adds one later.
                                Log.d(TAG, "Skipping non-regular tar entry: ${entry.name}")
                            }
                        }

                        // Preserve the executable bit. tar carries POSIX modes; mirror
                        // any-x to all-x. Ignore for symlinks/hardlinks (mode lives on
                        // the target). Collect failures so a noexec-mounted FS fails
                        // the install instead of silently shipping non-runnable files.
                        if (!entry.isSymbolicLink && !entry.isLink &&
                            (entry.mode and 0b001_001_001) != 0
                        ) {
                            if (outFile.isFile && !outFile.setExecutable(true, false)) {
                                chmodFailures.add(outFile)
                            }
                        }
                    }
                }
            }
        }

        if (chmodFailures.isNotEmpty()) {
            throw IOException(
                "Could not set executable bit on ${chmodFailures.size} runtime files " +
                    "(first: ${chmodFailures.first()})",
            )
        }
    }

    /**
     * Sniper ships a "merged-/usr" layout where the tarball's `files/`
     * directory IS what pressure-vessel normally mounts as `/usr` of
     * its container. We use proot with `--rootfs=files`, so software
     * looking for `/usr/bin/foo` would otherwise find nothing — there
     * is no `/usr` directory inside `files/`. Drop a single self-loop
     * symlink `files/usr -> .` so `/usr/bin/env` resolves to `/bin/env`,
     * `/usr/lib/...` to `/lib/...`, etc., without us having to
     * duplicate the tree or mess with the binaries' RPATHs.
     *
     * Idempotent: callable on a freshly-extracted tree or on an existing
     * install missing only the symlink. Skips work if the link already
     * points where we expect.
     */
    private fun ensureUsrSymlink(filesDir: File) {
        val usrLink = File(filesDir, "usr")
        if (java.nio.file.Files.isSymbolicLink(usrLink.toPath())) {
            val current = java.nio.file.Files.readSymbolicLink(usrLink.toPath()).toString()
            if (current == ".") return
            usrLink.delete()
        } else if (usrLink.exists()) {
            // A real directory called `usr` exists — sniper hasn't shipped
            // one historically, but if Valve ever flips this we don't want
            // to clobber a real tree. Bail loudly.
            throw IOException(
                "files/usr exists but is not a symlink; refusing to overwrite ${usrLink.absolutePath}",
            )
        }
        FileUtils.symlink(".", usrLink.absolutePath)
        verifySymlinkTarget(usrLink, ".")
    }

    /**
     * Verify a freshly-created symlink resolves to [expectedTarget]. We
     * can't trust [FileUtils.symlink] alone — it swallows ErrnoException
     * — so a stale link with a different target after a failed
     * delete/recreate would otherwise pass the existence check.
     */
    private fun verifySymlinkTarget(link: File, expectedTarget: String) {
        val path = link.toPath()
        if (!java.nio.file.Files.isSymbolicLink(path)) {
            throw IOException("symlink missing after creation: ${link.absolutePath} → $expectedTarget")
        }
        val actual = java.nio.file.Files.readSymbolicLink(path).toString()
        if (actual != expectedTarget) {
            throw IOException(
                "symlink target mismatch at ${link.absolutePath}: expected '$expectedTarget' got '$actual'",
            )
        }
    }
}
