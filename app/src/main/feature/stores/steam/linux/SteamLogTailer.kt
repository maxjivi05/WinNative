package com.winlator.cmod.feature.stores.steam.linux

import android.util.Log
import com.winlator.cmod.runtime.system.LaunchLogBus
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tails `<imagefs>/usr/tmp/winnative-steam.log` and forwards each new
 * line to [LaunchLogBus]. The launcher script redirects every step from
 * the bash wrapper, every proot info line, and every byte of Steam's own
 * stdout/stderr to that file — tailing it surfaces "Downloading manifest:
 * https://…", "Verifying installation… 42%", "Show window", etc. to the
 * verbose-launch overlay so the operator can watch what Steam is actually
 * doing instead of staring at a frozen "Loading…" spinner.
 *
 * Lifecycle: [start] spawns one daemon thread that polls the file every
 * [POLL_MS] ms and emits any new bytes. [stop] flips the running flag and
 * the thread exits at the next poll boundary. Idempotent — calling start
 * twice replaces the existing tailer.
 */
object SteamLogTailer {

    private const val TAG = "SteamLogTailer"
    private const val POLL_MS = 250L
    /** Max bytes drained per poll — keeps a runaway logger from flooding the bus. */
    private const val DRAIN_CHUNK = 64 * 1024

    private val running = AtomicBoolean(false)

    @Volatile private var thread: Thread? = null

    @JvmStatic
    fun start(logFile: File) {
        stop()
        running.set(true)
        val t = Thread({ tailLoop(logFile) }, "SteamLogTailer")
        t.isDaemon = true
        thread = t
        t.start()
    }

    @JvmStatic
    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun tailLoop(logFile: File) {
        var position = 0L
        var inode: Long = -1L

        // Don't replay history — the log file may carry many launches' worth
        // of output. Start at the current end and only emit new bytes that
        // arrive after start().
        runCatching {
            if (logFile.exists()) {
                position = logFile.length()
                inode = logFile.lastModified()
            }
        }

        val buf = ByteArray(DRAIN_CHUNK)
        var pendingLine = StringBuilder()

        while (running.get()) {
            try {
                if (!logFile.exists()) {
                    Thread.sleep(POLL_MS)
                    continue
                }
                val len = logFile.length()
                val mtime = logFile.lastModified()
                // Detect rotation / truncation.
                if (len < position || (inode > 0 && mtime != inode && len < position)) {
                    position = 0L
                    pendingLine = StringBuilder()
                }
                inode = mtime
                if (len > position) {
                    RandomAccessFile(logFile, "r").use { raf ->
                        raf.seek(position)
                        var remaining = (len - position).toInt().coerceAtMost(DRAIN_CHUNK)
                        while (remaining > 0 && running.get()) {
                            val n = raf.read(buf, 0, remaining)
                            if (n <= 0) break
                            position += n
                            remaining -= n
                            // Split into lines and post each. Carry over a
                            // trailing partial line into the next poll.
                            for (i in 0 until n) {
                                val c = buf[i].toInt().toChar()
                                if (c == '\n') {
                                    val line = pendingLine.toString().trimEnd('\r')
                                    pendingLine = StringBuilder()
                                    if (line.isNotEmpty()) {
                                        LaunchLogBus.post("steam", line, classifyLevel(line))
                                    }
                                } else {
                                    pendingLine.append(c)
                                }
                            }
                        }
                    }
                }
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Throwable) {
                Log.w(TAG, "tail loop error (continuing): ${e.message}")
                runCatching { Thread.sleep(POLL_MS * 4) }
            }
        }
    }

    /** Heuristic level mapping: any "error"/"failed"/"fatal" → ERROR, "warn" → WARN. */
    private fun classifyLevel(line: String): LaunchLogBus.Level {
        val lower = line.lowercase()
        return when {
            "fatal" in lower || " error" in lower || lower.startsWith("error") ||
                lower.contains("failed") || lower.contains("missing") ->
                LaunchLogBus.Level.ERROR
            lower.contains("warn") || lower.contains("warning") ->
                LaunchLogBus.Level.WARN
            else -> LaunchLogBus.Level.INFO
        }
    }
}
