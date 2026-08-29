package at.least.conch

import androidx.compose.runtime.mutableIntStateOf
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
        /** The shell's OSC 7 working directory, null until reported (share-to-host destination). */
        private val cwdFn: () -> String? = { null },
        /**
         * A fresh SFTP client on this session's connection, null when not
         * connected. Blocking — call off the main thread. Still not the SSH
         * client itself: the caller gets one channel it must close.
         */
        private val sftpFn: () -> net.schmizz.sshj.sftp.SFTPClient? = { null },
    ) {
        fun disconnect() = disconnectFn()

        fun focus() = focusFn()

        fun cwd(): String? = cwdFn()

        fun openSftp(): net.schmizz.sshj.sftp.SFTPClient? = sftpFn()
    }

    private val sessions = ConcurrentHashMap<String, Live>()

    /**
     * Bumps on every change. The map itself is not snapshot state, so a
     * composable that only read [isEmpty]/[all] (the home screen's badge)
     * never recomposed when a session ended in another task; read this
     * alongside them to subscribe.
     */
    val version = mutableIntStateOf(0)

    private fun changed() {
        version.intValue = version.intValue + 1
    }

    fun register(live: Live) {
        sessions[live.id] = live
        changed()
    }

    fun unregister(id: String) {
        sessions.remove(id)
        changed()
    }

    fun all(): List<Live> = sessions.values.toList().sortedBy { it.startedAt }

    fun countForHost(hostId: String): Int = sessions.values.count { it.hostId == hostId }

    fun isEmpty(): Boolean = sessions.isEmpty()

    /** Disconnect every live session (used on app lock / forced cleanup). */
    fun disconnectAll() {
        sessions.values.forEach { runCatching { it.disconnect() } }
        sessions.clear()
        changed()
    }
}
