package at.least.conch

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
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
        val stopped = ConcurrentLinkedQueue<String>()

        override fun onSessionConnected() {
            connected.incrementAndGet()
        }

        override fun onSessionData(data: ByteArray) {
            // a failing session never streams PTY data; nothing to record
        }

        override fun onReconnecting(attempt: Int, delayMs: Long, reason: String) {
            reconnecting.add(attempt to delayMs)
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

    @Test
    fun `failed connects double the delay to the 30s cap and keep retrying`() = runTest {
        val listener = RecordingListener()
        val pending = CopyOnWriteArrayList<Job>()
        val gate = AtomicBoolean(false)
        val reconnector = SessionReconnector(
            newSession = { cb ->
                SshSession(
                    context = null,
                    host = Host(hostname = "127.0.0.1", username = "u", authType = Host.AUTH_PASSWORD),
                    initialCols = 80,
                    initialRows = 24,
                    callbacks = cb,
                    tofuPrompt = null,
                    post = { it.run() },
                    connector = { _, _ -> throw IOException("network down") },
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
        )

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
        val reconnector = SessionReconnector(
            newSession = { cb ->
                SshSession(
                    context = null,
                    host = Host(hostname = "127.0.0.1", username = "u", authType = Host.AUTH_PASSWORD),
                    initialCols = 80,
                    initialRows = 24,
                    callbacks = cb,
                    tofuPrompt = null,
                    post = { it.run() },
                    connector = { _, _ -> throw IOException("network down") },
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
        )

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
        val reconnector = SessionReconnector(
            newSession = { cb ->
                SshSession(
                    context = null,
                    host = Host(hostname = "127.0.0.1", username = "u", authType = Host.AUTH_PASSWORD),
                    initialCols = 80,
                    initialRows = 24,
                    callbacks = cb,
                    tofuPrompt = null,
                    post = { it.run() },
                    connector = { _, _ ->
                        throw net.schmizz.sshj.userauth.UserAuthException("bad credentials")
                    },
                )
            },
            listener = listener,
            postDelayed = { delayMs, action ->
                pending += launch {
                    delay(delayMs)
                    action()
                }
            },
            cancelScheduled = {
                pending.forEach(Job::cancel)
                pending.clear()
            },
        )

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
}
