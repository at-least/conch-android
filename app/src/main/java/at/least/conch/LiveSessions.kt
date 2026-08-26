package at.least.conch

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-level registry of live terminal sessions (C52 sessions-switcher
 * parity with iOS's `AppState.liveSessions`). Android runs each terminal in
 * its own Activity task, so without a central registry there's no way to
 * list / switch / disconnect concurrent sessions from one place.
 *
 * Each [TerminalActivity] registers on connect and unregisters on destroy.
 * The registry holds only display metadata + a disconnect handle — never
 * the SSH client or PTY (those stay owned by the Activity). Thread-safe.
 */
object LiveSessions {

    data class Live(
        val id: String,
        val hostId: String,
        val displayName: String,
        val startedAt: Long,
        /** Disconnect + finish the owning Activity. Safe to call from any thread. */
        private val disconnectFn: () -> Unit,
        /** Bring the owning Activity's task to the foreground. Safe to call from any thread. */
        private val focusFn: () -> Unit = {},
    ) {
        fun disconnect() = disconnectFn()

        fun focus() = focusFn()
    }

    private val sessions = ConcurrentHashMap<String, Live>()

    fun register(live: Live) {
        sessions[live.id] = live
    }

    fun unregister(id: String) {
        sessions.remove(id)
    }

    fun all(): List<Live> = sessions.values.toList().sortedBy { it.startedAt }

    fun countForHost(hostId: String): Int = sessions.values.count { it.hostId == hostId }

    fun isEmpty(): Boolean = sessions.isEmpty()

    /** Disconnect every live session (used on app lock / forced cleanup). */
    fun disconnectAll() {
        sessions.values.forEach { runCatching { it.disconnect() } }
        sessions.clear()
    }
}
