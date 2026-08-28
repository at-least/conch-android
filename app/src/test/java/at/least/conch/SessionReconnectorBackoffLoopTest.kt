package at.least.conch

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The FULL reconnect loop (SessionReconnector + ReconnectScheduler +
 * ReconnectPolicy wiring) under virtual time: every connect fails
 * instantly, so the delay SEQUENCE the loop produces is pinned here —
 * doubling to the 30s cap, retrying forever — plus stop() halting a
 * pending retry.
 *
 * ReconnectPolicyTest pins the delay math in isolation;
 * SessionReconnectorInteractionTest pins one real reconnect against a
 * live sshd. What only this test pins: the wiring that chains attempt
 * after attempt through the real SessionReconnector/SshSession
 * failure path, without waiting 151s of wall clock.
 *
 * Threading: each attempt fails on a real ssh-reader thread, then
 * schedules its retry as a virtual-time delay. That real-thread hop is
 * what bounds advanceUntilIdle() — it always returns between attempts.
 */
class SessionReconnectorBackoffLoopTest {

    private class RecordingListener : SessionReconnector.Listener {
        val connected = AtomicInteger(0)
        val reconnecting = ConcurrentLinkedQueue<Pair<Int, Long>>()
        val reasons = ConcurrentLinkedQueue<String>()
        val stopped = ConcurrentLinkedQueue<String>()

        override fun onSessionConnected() {
            connected.incrementAndGet()
        }

        override fun onSessionData(data: ByteArray) {
            // a failing session never streams PTY data; nothing to record
        }

        override fun onReconnecting(attempt: Int, delayMs: Long, reason: String) {
            reconnecting.add(attempt to delayMs)
            reasons.add(reason)
        }

        override fun onSessionStopped(reason: String) {
            stopped.add(reason)
        }
    }

    /** Drives virtual time until [condition] holds; the reader-thread hop
     *  means events land off-scheduler, so yield real time between advances. */
    private fun pumpUntil(
        scheduler: TestCoroutineScheduler,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition never held" }
            scheduler.advanceUntilIdle()
            if (!condition()) Thread.sleep(5)
        }
    }

    /** Collects listeners so a test can fire the network-back signal itself. */
    private class FakeNetworkSignal : NetworkSignal {
        private val listeners = CopyOnWriteArrayList<NetworkSignal.Listener>()

        override fun addListener(listener: NetworkSignal.Listener) {
            listeners += listener
        }

        override fun removeListener(listener: NetworkSignal.Listener) {
            listeners -= listener
        }

        val subscribed: Boolean get() = listeners.isNotEmpty()

        /** @return true if some listener acted on the signal. */
        fun fire(): Boolean = listeners.map { it.onNetworkAvailable() }.any { it }
    }

    /**
     * The harness every case here shares: a reconnector whose every connect
     * fails instantly, with [postDelayed] on virtual time. [gate] closes the
     * scheduling door so a test can stop the loop without racing one last
     * retry into flight.
     */
    private fun TestScope.failingReconnector(
        listener: SessionReconnector.Listener,
        pending: CopyOnWriteArrayList<Job>,
        gate: AtomicBoolean = AtomicBoolean(false),
        networkSignal: NetworkSignal = FakeNetworkSignal(),
        connector: () -> Nothing = { throw IOException("network down") },
    ) = SessionReconnector(
        newSession = { cb ->
            SshSession(
                context = null,
                host = Host(hostname = "127.0.0.1", username = "u", authType = Host.AUTH_PASSWORD),
                initialCols = 80,
                initialRows = 24,
                callbacks = cb,
                tofuPrompt = null,
                post = { it.run() },
                connector = { _, _ -> connector() },
            )
        },
        listener = listener,
        postDelayed = { delayMs, action ->
            if (!gate.get()) {
                pending += launch {
                    delay(delayMs)
                    action()
                }
            }
        },
        cancelScheduled = {
            pending.forEach(Job::cancel)
            pending.clear()
        },
        networkSignal = networkSignal,
    )

    @Test
    fun `failed connects double the delay to the 30s cap and keep retrying`() = runTest {
        val listener = RecordingListener()
        val pending = CopyOnWriteArrayList<Job>()
        val gate = AtomicBoolean(false)
        val reconnector = failingReconnector(listener, pending, gate)

        reconnector.start()
        pumpUntil(testScheduler) { listener.reconnecting.size >= 9 }

        // the wiring, not the math: attempts 1..9 produce the pinned sequence
        assertEquals((1..9).toList(), listener.reconnecting.map { it.first })
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L, 30_000L),
            listener.reconnecting.map { it.second },
        )
        // event N fires after delay N-1 elapsed: 31s doubling + 3×30s
        assertEquals(121_000L, testScheduler.currentTime)
        assertEquals("a failed connect must never surface onSessionConnected", 0, listener.connected.get())
        assertTrue("loop must still be trying — no stop happened", listener.stopped.isEmpty())

        // cleanup + pin: stop delivers exactly one stopped event from here
        gate.set(true)
        reconnector.stop("done")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("done"), listener.stopped.toList())
        assertEquals(9, listener.reconnecting.size)
    }

    @Test
    fun `stop while a retry is pending halts the loop`() = runTest {
        val listener = RecordingListener()
        val pending = CopyOnWriteArrayList<Job>()
        val gate = AtomicBoolean(false)
        val reconnector = failingReconnector(listener, pending, gate)

        reconnector.start()
        pumpUntil(testScheduler) { listener.reconnecting.size >= 3 }

        // a retry IS pending right now (delay #4 in virtual flight)
        gate.set(true)
        reconnector.stop("user closed")
        testScheduler.advanceUntilIdle()
        Thread.sleep(100) // let any in-flight failure land, then prove nothing follows
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("user closed"), listener.stopped.toList())
        assertEquals("no 4th attempt may be scheduled after stop", 3, listener.reconnecting.size)
    }

    @Test
    fun `authentication failure is terminal - no retry loop`() = runTest {
        val listener = RecordingListener()
        val pending = CopyOnWriteArrayList<Job>()
        val reconnector = failingReconnector(listener, pending) {
            throw net.schmizz.sshj.userauth.UserAuthException("bad credentials")
        }

        reconnector.start()
        pumpUntil(testScheduler) { listener.stopped.isNotEmpty() }

        // Retrying a rejected credential forever spams the server; the loop
        // must deliver the terminal state on the FIRST failure instead.
        assertTrue("no reconnect may be scheduled for an auth failure", listener.reconnecting.isEmpty())
        assertTrue(listener.stopped.peek()?.startsWith("Authentication failed") == true)
    }

    @Test
    fun `terminal failure classification`() {
        assertTrue(SshSession.isTerminalFailure(SshSession.REASON_SESSION_ENDED))
        assertTrue(SshSession.isTerminalFailure("Authentication failed: bad credentials"))
        assertTrue(SshSession.isTerminalFailure("Authentication failed (wrong user/password/key?)"))
        org.junit.Assert.assertFalse(SshSession.isTerminalFailure("Connection timed out"))
        org.junit.Assert.assertFalse(SshSession.isTerminalFailure("Connection closed by remote"))
    }

    @Test
    fun `the network coming back pulls a waiting retry forward`() = runTest {
        val listener = RecordingListener()
        val pending = CopyOnWriteArrayList<Job>()
        val gate = AtomicBoolean(false)
        val network = FakeNetworkSignal()
        val reconnector = failingReconnector(listener, pending, gate, network)

        // the reconnector subscribes itself — no caller has to remember to
        assertTrue("a live reconnector must be listening for the network", network.subscribed)

        reconnector.start()
        // several failures deep, so the backoff has visibly grown
        pumpUntil(testScheduler) { listener.reconnecting.size >= 3 }
        val timeWhenNetworkReturned = testScheduler.currentTime

        // Radio back (Wi-Fi↔cellular handover). Which attempt is in flight at
        // this instant is up to the real ssh-reader threads, so keep firing
        // until one is actually sitting in its backoff — that wait advances NO
        // virtual time, which is what makes the assertions below meaningful.
        val deadline = System.currentTimeMillis() + 10_000
        while (!network.fire()) {
            check(System.currentTimeMillis() < deadline) { "no retry ever became pending" }
            Thread.sleep(5)
        }
        val pulledAt = listener.reconnecting.size - 1
        val pulled = listener.reconnecting.toList()[pulledAt]

        assertEquals("the pulled-forward attempt must be reported with no delay", 0L, pulled.second)
        assertEquals(SessionReconnector.NETWORK_BACK_REASON, listener.reasons.last())
        // it really was immediate: not one millisecond of backoff elapsed
        assertEquals(timeWhenNetworkReturned, testScheduler.currentTime)

        // and the backoff resumes where it left off rather than restarting at 1s
        pumpUntil(testScheduler) { listener.reconnecting.size >= pulledAt + 2 }
        val next = listener.reconnecting.toList()[pulledAt + 1]
        assertEquals(pulled.first + 1, next.first)
        assertEquals(ReconnectPolicy().delayForAttempt(next.first), next.second)

        gate.set(true)
        reconnector.stop("done")
        testScheduler.advanceUntilIdle()
        assertTrue("a stopped reconnector must unsubscribe itself", !network.subscribed)
    }
}
