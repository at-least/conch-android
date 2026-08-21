package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `backoff doubles from 1s and caps at 30s`() {
        val p = ReconnectPolicy()
        assertEquals(1_000L, p.delayForAttempt(1))
        assertEquals(2_000L, p.delayForAttempt(2))
        assertEquals(4_000L, p.delayForAttempt(3))
        assertEquals(8_000L, p.delayForAttempt(4))
        assertEquals(16_000L, p.delayForAttempt(5))
        assertEquals(30_000L, p.delayForAttempt(6))  // 32s would exceed the cap
        assertEquals(30_000L, p.delayForAttempt(7))
        assertEquals(30_000L, p.delayForAttempt(999))
    }

    @Test
    fun `attempt zero or negative is clamped to the base delay`() {
        val p = ReconnectPolicy()
        assertEquals(1_000L, p.delayForAttempt(0))
        assertEquals(1_000L, p.delayForAttempt(-5))
    }

    @Test
    fun `huge attempt numbers do not overflow`() {
        val p = ReconnectPolicy()
        assertEquals(30_000L, p.delayForAttempt(Int.MAX_VALUE))
    }

    private class FakeClock {
        val posted = mutableListOf<Pair<Long, () -> Unit>>()
        var cancelled = 0
        val scheduler = ReconnectScheduler(
            postDelayed = { d, a -> posted.add(d to a) },
            cancelScheduled = { cancelled++ },
        )
    }

    @Test
    fun `scheduler grows delays notifies and resets after success`() {
        val f = FakeClock()
        val notified = mutableListOf<Pair<Int, Long>>()

        f.scheduler.onConnectionLost({}) { n, d -> notified.add(n to d) }
        f.scheduler.onConnectionLost({}) { n, d -> notified.add(n to d) }

        assertEquals(listOf(1_000L, 2_000L), f.posted.map { it.first })
        assertEquals(listOf(1 to 1_000L, 2 to 2_000L), notified)

        f.scheduler.onConnected()
        assertEquals(0, f.scheduler.attempt)

        f.scheduler.onConnectionLost({}) { n, d -> notified.add(n to d) }
        assertEquals(1_000L, f.posted.last().first) // counter reset → back to 1s
        assertEquals(1, notified.last().first)
    }

    @Test
    fun `stop prevents any further scheduling`() {
        val f = FakeClock()
        f.scheduler.stop()
        f.scheduler.onConnectionLost({}) { _, _ -> }
        assertTrue("nothing may be scheduled after stop", f.posted.isEmpty())
    }

    @Test
    fun `scheduled action is cancelled before a new one is posted`() {
        val f = FakeClock()
        f.scheduler.onConnectionLost({}) { _, _ -> }
        f.scheduler.onConnectionLost({}) { _, _ -> }
        assertEquals(2, f.cancelled) // once per onConnectionLost call
    }
}
