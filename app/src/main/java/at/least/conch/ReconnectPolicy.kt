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

    fun onConnectionLost(connect: () -> Unit, onScheduled: (attempt: Int, delayMs: Long) -> Unit) {
        if (userClosed) return
        cancelScheduled()
        attempt += 1
        val delay = policy.delayForAttempt(attempt)
        onScheduled(attempt, delay)
        postDelayed(delay, connect)
    }

    fun onConnected() {
        cancelScheduled()
        attempt = 0
    }

    fun stop() {
        userClosed = true
        cancelScheduled()
    }
}
