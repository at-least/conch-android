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
 *
 * It also subscribes itself to [networkSignal] for its own lifetime, so a
 * retry waiting out its backoff is pulled forward the moment the device has
 * a network again — see [onNetworkAvailable].
 */
class SessionReconnector(
    /** Builds a fresh, NOT-yet-connected session; [start] connects it. */
    private val newSession: (SshSession.Callbacks) -> SshSession,
    private val listener: Listener,
    postDelayed: (delayMs: Long, action: () -> Unit) -> Unit,
    cancelScheduled: () -> Unit,
    policy: ReconnectPolicy = ReconnectPolicy(),
    private val networkSignal: NetworkSignal = NetworkWatcher,
) : SshSession.Callbacks, NetworkSignal.Listener {

    interface Listener {
        /** Session came up (initial connect or successful reconnect). */
        fun onSessionConnected()

        fun onSessionData(data: ByteArray)

        /** Connection dropped; next attempt [attempt] fires in [delayMs]. */
        fun onReconnecting(attempt: Int, delayMs: Long, reason: String)

        /** Terminal state: user stopped, or no session existed to stop. Fires once. */
        fun onSessionStopped(reason: String)
    }

    companion object {
        /** Banner reason when a retry is pulled forward by [onNetworkAvailable]. */
        const val NETWORK_BACK_REASON = "Network available"
    }

    private val scheduler = ReconnectScheduler(policy, postDelayed, cancelScheduled)

    private val stopDelivered = AtomicBoolean(false)

    @Volatile
    private var current: SshSession? = null

    init {
        // Subscribed here rather than by the caller: this is the object that
        // knows whether a retry is waiting, so no construction site can
        // forget the wiring or double-register. Dropped in deliverStopped —
        // the single funnel through which a reconnector reaches its end.
        networkSignal.addListener(this)
    }

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

    /** Run a one-shot exec on the current live session's shared connection. */
    fun exec(command: String): String? = current?.exec(command)

    /** Open an SFTP client on the current live session's shared connection. */
    fun sftpClient() = current?.sftpClient()

    /** Live-connection display signal for the health dot. */
    val isConnected: Boolean get() = current?.isConnected == true

    /**
     * The device regained a usable network (Wi-Fi↔cellular handover, airplane
     * mode off, tunnel back up). If a retry is sitting out its backoff, run it
     * now instead of leaving the terminal dark for up to the 30s cap — the
     * failure that scheduled it was almost certainly the missing network.
     * A no-op when connected, connecting, or stopped by the user.
     *
     * @return true if a waiting retry was pulled forward.
     */
    override fun onNetworkAvailable(): Boolean =
        scheduler.retryNow(
            connect = { start() },
            onScheduled = { n, delayMs -> listener.onReconnecting(n, delayMs, NETWORK_BACK_REASON) },
        )

    /** Stop all local-port-forward tunnels on the live session (shell stays). */
    fun stopTunnels() = current?.stopTunnels()

    /** Active tunnel count for the session-card capsule. */
    val tunnelCount: Int get() = current?.tunnelCount ?: 0

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
            networkSignal.removeListener(this)
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
        // `exit` / CTRL+D on a healthy session, or an auth rejection: the
        // user (or the server) ended it on purpose — deliver the terminal
        // state, never loop back in.
        if (SshSession.isTerminalFailure(reason)) {
            deliverStopped(reason)
            return
        }
        scheduler.onConnectionLost(
            connect = { start() },
            onScheduled = { n, delayMs -> listener.onReconnecting(n, delayMs, reason) },
        )
    }
}
