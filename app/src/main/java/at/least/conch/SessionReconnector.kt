package at.least.conch

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns one auto-reconnecting SSH session lifecycle: builds a fresh
 * [SshSession] per attempt, forwards data/connected events, and on every
 * disconnect schedules the next attempt with exponential backoff
 * ([ReconnectPolicy]) until [stop] is called.
 *
 * Extracted from TerminalActivity so the exact orchestration the UI relies
 * on — drop → schedule → rebuild → re-attach — runs in JVM tests against a
 * real in-process sshd.
 *
 * Callback order guarantees this class relies on (see SshSession):
 * - onDisconnected fires exactly once per session object
 * - a session's onDisconnected arrives before any retry is scheduled, so
 *   stale sessions never clobber their replacement
 *
 * [onSessionStopped][Listener.onSessionStopped] fires exactly once per
 * reconnector, no matter how often [stop] is called (banner tap followed by
 * onDestroy) or which path delivers it.
 */
class SessionReconnector(
    /** Builds a fresh, NOT-yet-connected session; [start] connects it. */
    private val newSession: (SshSession.Callbacks) -> SshSession,
    private val listener: Listener,
    postDelayed: (delayMs: Long, action: () -> Unit) -> Unit,
    cancelScheduled: () -> Unit,
    policy: ReconnectPolicy = ReconnectPolicy(),
) : SshSession.Callbacks {

    interface Listener {
        /** Session came up (initial connect or successful reconnect). */
        fun onSessionConnected()

        fun onSessionData(data: ByteArray)

        /** Connection dropped; next attempt [attempt] fires in [delayMs]. */
        fun onReconnecting(attempt: Int, delayMs: Long, reason: String)

        /** Terminal state: user stopped, or no session existed to stop. Fires once. */
        fun onSessionStopped(reason: String)
    }

    private val scheduler = ReconnectScheduler(policy, postDelayed, cancelScheduled)

    private val stopDelivered = AtomicBoolean(false)

    @Volatile
    private var current: SshSession? = null

    fun start() {
        // a retry that raced a stop on another thread must not resurrect
        if (scheduler.userClosed) return
        val s = newSession(this)
        // assign before connect: an instantly-failing connect must already
        // be visible to stop(), or its final callback would be orphaned
        current = s
        s.connect()
    }

    fun write(data: ByteArray) {
        current?.write(data)
    }

    fun resizePty(cols: Int, rows: Int) {
        current?.resizePty(cols, rows)
    }

    /**
     * User-initiated stop: no further retries, and late callbacks from the
     * dying session (e.g. an onConnected that raced past SshSession's
     * closed-check) are dropped in [onConnected]/[onData].
     */
    fun stop(reason: String = "Disconnected") {
        scheduler.stop()
        val s = current
        if (s == null) {
            // nothing in flight — nothing will deliver a final callback
            deliverStopped(reason)
        } else {
            // its onDisconnected (exactly once) forwards the stopped event
            s.disconnect(reason)
        }
    }

    private fun deliverStopped(reason: String) {
        if (stopDelivered.compareAndSet(false, true)) {
            listener.onSessionStopped(reason)
        }
    }

    override fun onConnected() {
        // late winner of the stop race: the user already saw "stopped"
        if (scheduler.userClosed) return
        scheduler.onConnected()
        listener.onSessionConnected()
    }

    override fun onData(data: ByteArray) {
        if (scheduler.userClosed) return
        listener.onSessionData(data)
    }

    override fun onDisconnected(reason: String) {
        current = null
        if (scheduler.userClosed) {
            deliverStopped(reason)
            return
        }
        scheduler.onConnectionLost(
            connect = { start() },
            onScheduled = { n, delayMs -> listener.onReconnecting(n, delayMs, reason) },
        )
    }
}
