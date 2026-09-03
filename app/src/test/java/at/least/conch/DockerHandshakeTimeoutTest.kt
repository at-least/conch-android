package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Two ways a TCP connection can "succeed" and still never yield an SSH
 * session — the shapes a captive portal, a half-open firewall or a wedged
 * server produce:
 *
 *   :2270 accepts the connection and never sends a byte,
 *   :2271 sends an SSH identification banner and then stalls forever.
 *
 * Neither can be reproduced by the in-process MINA server (which always
 * completes its handshake).
 *
 * THESE DRIVE THE APP'S OWN PATH ([SshConnectionFactory.connect] via
 * [DockerMatrix.connect]). An earlier version of this file did not: it built
 * its own `SSHClient` with `connectTimeout`/`timeout` values the app never
 * set, so it passed no matter what the app did — and it stayed green for the
 * whole time the app genuinely hung against these very fixtures. The lower
 * bound on `elapsed` below exists for the same reason: without it the test
 * would also pass if the fixture merely refused the connection, which is not
 * what it claims to prove.
 *
 * The two fixtures are bounded by DIFFERENT mechanisms, which is why both
 * are worth keeping:
 *  - :2270 stalls before the banner, inside the read that
 *    [SshConnectionFactory.HANDSHAKE_TIMEOUT_MS] bounds (~45 s). This is the
 *    case that hung indefinitely until that budget existed.
 *  - :2271 sends its banner and stalls during key exchange, which sshj's own
 *    transport event timeout already bounds (~30 s) — but whose error wording
 *    ("Timeout expired: 30000 MILLISECONDS") needed a describeError mapping
 *    before the user saw anything readable.
 *
 * Same opt-in as [DockerSshdAuthTest] (see [DockerMatrix]).
 */
class DockerHandshakeTimeoutTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val budgetMs = SshConnectionFactory.HANDSHAKE_TIMEOUT_MS

    /**
     * Connects through the app path and returns how long the failure took.
     * Fails the test if a session is somehow established.
     */
    private fun timeToFailure(port: Int): Pair<Long, Exception> {
        val host = DockerMatrix.pwHost(port)
        val t0 = System.currentTimeMillis()
        val e = runCatching {
            DockerMatrix.connect(KnownHostsStore(tmp.newFolder()), host, prompt = null).use { }
        }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("a peer that never completes a handshake must not yield a session", e != null)
        return elapsed to (e as Exception)
    }

    private fun assertBoundedStall(port: Int, what: String) {
        val (elapsed, e) = timeToFailure(port)
        // Upper: the whole point — it must give up, not block until TCP does.
        assertTrue(
            "$what: handshake was not bounded (${elapsed}ms, budget ${budgetMs}ms): $e",
            elapsed < budgetMs + 25_000,
        )
        // Lower: proves the fixture really stalled. A refused port fails in
        // milliseconds and would make every assertion above vacuously true —
        // which is exactly how the iOS twin of this test passed in 5 ms while
        // testing nothing. Kept well clear of both real bounds (30 s / 45 s)
        // so it discriminates refusal-vs-stall without racing either.
        assertTrue(
            "$what: failed in ${elapsed}ms — the fixture refused instead of stalling, so this proves nothing",
            elapsed > 20_000,
        )
        assertEquals("$what: unhelpful message", "Connection timed out", SshConnectionFactory.describeError(e))
    }

    @Test(timeout = 120_000)
    fun `a port that accepts and never speaks gives up within the handshake budget`() {
        DockerMatrix.requireMatrix()
        assertBoundedStall(DockerMatrix.SILENT_ACCEPT_PORT, "silent accept")
    }

    @Test(timeout = 120_000)
    fun `a banner-then-stall server gives up within the handshake budget`() {
        DockerMatrix.requireMatrix()
        // The banner arrives at once and the stall happens in the NEXT read,
        // so this pins that the budget covers key exchange, not just the
        // first byte.
        assertBoundedStall(DockerMatrix.BANNER_STALL_PORT, "banner then stall")
    }
}
