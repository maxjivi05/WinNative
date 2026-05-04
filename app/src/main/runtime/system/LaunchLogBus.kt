package com.winlator.cmod.runtime.system

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Broadcast bus for verbose launch-time events.
 *
 * Subscribed to by the verbose launch overlay (replaces the normal
 * "Loading…" preloader when the user enables Settings → Debug → Verbose
 * launch). [ProcessHelper] and [LinuxSteamLauncher] post to it for every
 * exec and significant lifecycle event so the operator can watch the
 * actual command stream instead of an animated dot.
 *
 * Design notes
 * - Bounded ring buffer (MAX_HISTORY = 500) so a busy launch can't OOM us.
 * - [events] is a SharedFlow with replay=0 — overlay subscribes after
 *   buffer flush so it sees the prior history via the snapshot StateFlow
 *   ([state]) and live updates via the SharedFlow.
 * - Codex review: do **not** log full env (it can carry Steam/Wine
 *   refresh tokens). Argv is fine; callers are responsible for redacting
 *   sensitive args at the call site if the command line itself carries a
 *   secret (we redact known patterns below).
 * - `@JvmStatic` API so Java callers (ProcessHelper, GuestProgramLauncher
 *   Component) can hit one entry point without ceremony.
 */
object LaunchLogBus {

    private const val MAX_HISTORY = 500
    private const val TAG = "LaunchLogBus"

    enum class Level { INFO, WARN, ERROR, DEBUG }

    data class Event(
        val timestampMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    private val history = ArrayDeque<Event>(MAX_HISTORY)
    private val mutationLock = Any()

    private val _state = MutableStateFlow<List<Event>>(emptyList())
    val state: StateFlow<List<Event>> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** Clear at the start of a new launch so the overlay shows just this run. */
    @JvmStatic
    fun reset() {
        synchronized(mutationLock) { history.clear() }
        _state.value = emptyList()
    }

    @JvmStatic
    @JvmOverloads
    fun post(tag: String, message: String, level: Level = Level.INFO) {
        val redacted = redactSecrets(message)
        val event = Event(System.currentTimeMillis(), level, tag, redacted)
        synchronized(mutationLock) {
            if (history.size >= MAX_HISTORY) history.removeFirst()
            history.addLast(event)
            _state.value = history.toList()
        }
        // tryEmit because we're called from synchronous Java code paths
        // (ProcessHelper) where suspending is not an option.
        if (!_events.tryEmit(event)) {
            Log.w(TAG, "dropped event due to overflow: $tag — $redacted")
        }
    }

    /**
     * Convenience for command-execution hooks: format a `pid + argv` line.
     * argv elements are joined with single spaces; embedded spaces are not
     * shell-quoted because this is operator-readable, not copy-pasteable.
     */
    @JvmStatic
    fun postCommand(pid: Int, argv: Array<String>) {
        val line = "pid=$pid argv=" + argv.joinToString(" ")
        post("exec", line, Level.INFO)
    }

    @JvmStatic
    fun postCommandFinish(pid: Int, exitCode: Int) {
        post("exec", "pid=$pid exit=$exitCode", if (exitCode == 0) Level.INFO else Level.WARN)
    }

    /**
     * Strip patterns that would otherwise leak credentials into the
     * overlay or saved logs. Keep the redaction conservative — false
     * positives are fine, missed secrets are not.
     */
    private fun redactSecrets(s: String): String {
        var out = s
        // JWT-shaped tokens: 3 dot-separated base64-url segments, ≥8 chars each.
        out = out.replace(Regex("eyJ[A-Za-z0-9_=-]+\\.[A-Za-z0-9_=-]+\\.[A-Za-z0-9_=-]+"),
            "<redacted-jwt>")
        // generic refresh tokens advertised as 'token=...' or steam-token blobs
        out = out.replace(Regex("(?i)(token|password|secret)=([\\w.+/=-]{6,})"), "$1=<redacted>")
        return out
    }
}
