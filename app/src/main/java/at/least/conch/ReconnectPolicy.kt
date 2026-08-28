package at.least.conch

/**
 * Exponential backoff for automatic reconnects: 1s, 2s, 4s … capped at 30s.
 * Retries forever — with tmux attached the session lives server-side, so
 * coming back after a mobile-network drop is always worth another try.
 * Pure logic, JVM-testable.
 */
class ReconnectPolicy(
    private val baseMs: Long = 1_000L,
    private val maxMs: Long = 30_000L,
) {
    /** Delay before attempt [attempt] (1-based). */
    fun delayForAttempt(attempt: Int): Long {
        val exp = (attempt.coerceAtLeast(1) - 1).coerceAtMost(20)
        return minOf(maxMs, baseMs shl exp)
    }
}

/**
 * Tracks reconnect attempts around one connection lifecycle:
 * - [onConnectionLost] schedules the next connect via [postDelayed]
 * - [retryNow] pulls a scheduled attempt forward (network came back)
 * - [onConnected] resets the attempt counter
 * - [stop] marks the connection user-closed; no further scheduling
 *
 * The UI drives it from session callbacks; timing and the counter live
 * here so tests can verify the backoff without a real clock.
 */
class ReconnectScheduler(
    val policy: ReconnectPolicy = ReconnectPolicy(),
    private val postDelayed: (delayMs: Long, action: () -> Unit) -> Unit,
    private val cancelScheduled: () -> Unit,
) {
    var attempt: Int = 0
        private set

    @Volatile
    var userClosed: Boolean = false
        private set

    /** True while a retry is waiting out its backoff delay — see [retryNow]. */
    @Volatile
    private var retryPending: Boolean = false

    fun onConnectionLost(connect: () -> Unit, onScheduled: (attempt: Int, delayMs: Long) -> Unit) {
        if (userClosed) return
        cancelScheduled()
        attempt += 1
        val delay = policy.delayForAttempt(attempt)
        retryPending = true
        onScheduled(attempt, delay)
        postDelayed(delay) { fire(connect) }
    }

    /**
     * Connect NOW instead of waiting out the remaining backoff — the device
     * just regained a usable network, so the reason the last attempt failed
     * is very likely gone. Without this, a Wi-Fi→cellular handover leaves
     * the session dark for up to the 30s cap even though the radio is back.
     *
     * The attempt counter is deliberately NOT reset: if this attempt fails
     * too, backoff resumes where it left off rather than restarting at 1s
     * and hammering a server that is genuinely down. Only a waiting retry can
     * be pulled forward — during the connect attempt itself there is nothing
     * to hurry, so a burst of callbacks (Wi-Fi and cellular arriving
     * together) collapses into one attempt.
     *
     * @return true if an attempt was pulled forward.
     */
    fun retryNow(connect: () -> Unit, onScheduled: (attempt: Int, delayMs: Long) -> Unit): Boolean {
        if (userClosed || !retryPending) return false
        cancelScheduled()
        onScheduled(attempt, 0L)
        fire(connect)
        return true
    }

    private fun fire(connect: () -> Unit) {
        retryPending = false
        if (userClosed) return
        connect()
    }

    fun onConnected() {
        cancelScheduled()
        retryPending = false
        attempt = 0
    }

    fun stop() {
        userClosed = true
        retryPending = false
        cancelScheduled()
    }
}
