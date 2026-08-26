package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Boundary pins for the clean-exit threshold: exactly MIN_SESSION_MS
 * counts as lived-in (inclusive >=), one millisecond under does not, and
 * the never-connected case (uptime 0) falls back to the drop reason.
 */
class CleanExitBoundaryTest {

    @Test
    fun `exactly MIN_SESSION_MS is a clean exit`() {
        assertEquals(
            SshSession.REASON_SESSION_ENDED,
            SshSession.cleanCloseReason(SshSession.MIN_SESSION_MS),
        )
    }

    @Test
    fun `one millisecond under the threshold is a drop`() {
        assertEquals(
            "Connection closed by remote",
            SshSession.cleanCloseReason(SshSession.MIN_SESSION_MS - 1),
        )
    }

    @Test
    fun `never-established session (uptime 0) is a drop`() {
        assertEquals(
            "Connection closed by remote",
            SshSession.cleanCloseReason(0),
        )
    }

    @Test
    fun `far past the threshold stays a clean exit`() {
        assertEquals(
            SshSession.REASON_SESSION_ENDED,
            SshSession.cleanCloseReason(86_400_000L),
        )
    }
}
