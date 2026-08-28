package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(30_000L, p.delayForAttempt(6)) // 32s would exceed the cap
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

    // ---------------------------------------------------- network-back retry

    @Test
    fun `retryNow connects immediately and drops the scheduled attempt`() {
        val f = FakeClock()
        var connects = 0
        val notified = mutableListOf<Pair<Int, Long>>()
        f.scheduler.onConnectionLost({ connects++ }) { _, _ -> }
        f.scheduler.onConnectionLost({ connects++ }) { _, _ -> }
        assertEquals(0, connects) // both are still waiting out their delay

        assertTrue(f.scheduler.retryNow({ connects++ }) { n, d -> notified.add(n to d) })

        assertEquals(1, connects)
        assertEquals("banner shows the same attempt, now", listOf(2 to 0L), notified)
        assertEquals("the pending post must be cancelled, not left to double-fire", 3, f.cancelled)
        // counter is NOT reset: a server that is genuinely down keeps backing off
        assertEquals(2, f.scheduler.attempt)
        f.scheduler.onConnectionLost({}) { n, d -> notified.add(n to d) }
        assertEquals(3 to 4_000L, notified.last())
    }

    @Test
    fun `retryNow is a no-op unless a retry is waiting`() {
        val f = FakeClock()
        var connects = 0

        // never dropped yet — nothing to hurry
        assertFalse(f.scheduler.retryNow({ connects++ }) { _, _ -> })

        f.scheduler.onConnectionLost({ connects++ }) { _, _ -> }
        assertTrue(f.scheduler.retryNow({ connects++ }) { _, _ -> })
        // a handover burst (Wi-Fi + cellular) must collapse into ONE attempt
        assertFalse(f.scheduler.retryNow({ connects++ }) { _, _ -> })
        assertFalse(f.scheduler.retryNow({ connects++ }) { _, _ -> })
        assertEquals(1, connects)

        // and once connected, later network events change nothing
        f.scheduler.onConnected()
        assertFalse(f.scheduler.retryNow({ connects++ }) { _, _ -> })
        assertEquals(1, connects)
    }

    @Test
    fun `retryNow after stop never resurrects the session`() {
        val f = FakeClock()
        var connects = 0
        f.scheduler.onConnectionLost({ connects++ }) { _, _ -> }
        f.scheduler.stop()
        assertFalse(f.scheduler.retryNow({ connects++ }) { _, _ -> })
        assertEquals(0, connects)
    }

    @Test
    fun `a retry that fires normally leaves nothing for retryNow to pull`() {
        val f = FakeClock()
        var connects = 0
        f.scheduler.onConnectionLost({ connects++ }) { _, _ -> }
        f.posted.last().second() // the postDelayed action comes due
        assertEquals(1, connects)
        assertFalse("already connecting — nothing pending", f.scheduler.retryNow({ connects++ }) { _, _ -> })
        assertEquals(1, connects)
    }

    @Test
    fun `a due retry that lands after stop does not connect`() {
        val f = FakeClock()
        var connects = 0
        f.scheduler.onConnectionLost({ connects++ }) { _, _ -> }
        val due = f.posted.last().second
        f.scheduler.stop() // user gave up while the delay was in flight
        due() // a Handler post already past the point of cancellation
        assertEquals(0, connects)
    }
}
