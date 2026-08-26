package at.least.conch

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * SshSession lifecycle over a real connection — the exact object
 * TerminalActivity drives: connect, echo shell I/O, tmux attach write,
 * resize, server-initiated closes, and post-disconnect writes.
 */
class SshSessionLifecycleInteractionTest {

    private lateinit var dir: File
    private lateinit var server: TestSshd
    private lateinit var store: KnownHostsStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conch-session").toFile()
        server = TestSshd().start()
        store = KnownHostsStore(dir)
        store.add("127.0.0.1", server.port, server.hostPublicKey)
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private class RecordingCallbacks : SshSession.Callbacks {
        val connected = AtomicInteger(0)
        val disconnected = AtomicInteger(0)
        val reasons = ConcurrentLinkedQueue<String>()
        val received = ConcurrentLinkedQueue<ByteArray>()

        override fun onConnected() {
            connected.incrementAndGet()
        }

        override fun onData(data: ByteArray) {
            received.add(data)
        }

        override fun onDisconnected(reason: String) {
            disconnected.incrementAndGet()
            reasons.add(reason)
        }

        fun text() = received.joinToString("") { String(it) }

        fun awaitText(expected: String, timeoutMs: Long = 10_000) {
            awaitTrue("waiting for \"$expected\", got \"${text()}\"", timeoutMs) {
                text().contains(expected)
            }
        }

        fun awaitDisconnected(timeoutMs: Long = 10_000): String {
            awaitTrue("never got onDisconnected (reasons so far: $reasons)", timeoutMs) {
                disconnected.get() > 0
            }
            return reasons.first()
        }
    }

    private fun testHost(port: Int = server.port): Host =
        Host(hostname = "127.0.0.1", username = server.user, authType = Host.AUTH_PASSWORD)
            .apply {
                this.port = port
                // pinned: Host() now defaults tmux ON for new hosts, but these
                // tests assert raw shell behavior without the tmux attach line
                this.tmuxAutoAttach = false
            }

    private fun newSession(
        host: Host,
        callbacks: RecordingCallbacks = RecordingCallbacks(),
    ): Pair<SshSession, RecordingCallbacks> {
        val session = SshSession(
            context = null,
            host = host,
            initialCols = 80,
            initialRows = 24,
            callbacks = callbacks,
            tofuPrompt = null,
            post = { it.run() },
            connector = { h, _ ->
                SshConnectionFactory.connect(
                    host = h,
                    prompt = null,
                    store = store,
                    keyProvider = { _, _ -> throw IllegalStateException("no key") },
                    password = { server.password ?: "" },
                )
            },
        )
        return session to callbacks
    }

    private fun connectNow(host: Host = testHost()): Pair<SshSession, RecordingCallbacks> {
        val (session, cb) = newSession(host)
        session.connect()
        awaitTrue("never connected", 10_000) { cb.connected.get() > 0 }
        return session to cb
    }

    @Test(timeout = 30_000)
    fun `happy path connects echoes resizes and disconnects`() {
        val (session, cb) = connectNow()
        try {
            val shell = server.shells.first()
            shell.awaitStarted()

            session.write("ping ".toByteArray())
            session.write("pong\r".toByteArray())
            cb.awaitText("ping pong\r")
            shell.awaitReceived("ping pong\r".toByteArray().size)
            assertEquals("ping pong\r", String(shell.receivedBytes()))

            session.resizePty(120, 40)
            awaitTrue("server never saw the resize") {
                shell.windowSizes.contains("120" to "40")
            }
        } finally {
            session.disconnect("user closed")
        }
        assertEquals("user closed", cb.awaitDisconnected())
        assertEquals("onDisconnected must fire exactly once", 1, cb.disconnected.get())
    }

    @Test(timeout = 30_000)
    fun `burst of writes arrives in exact typed order`() {
        val (session, cb) = connectNow()
        try {
            val shell = server.shells.first()
            shell.awaitStarted()
            // 30 rapid writes from the caller thread must serialize through the
            // single-thread writer executor in submission order
            val parts = (0 until 30).map { "w$it;".toByteArray() }
            parts.forEach { session.write(it) }
            val expected = parts.reduce { acc, b -> acc + b }
            shell.awaitReceived(expected.size)
            assertEquals(String(expected), String(shell.receivedBytes()))
        } finally {
            session.disconnect()
        }
        cb.awaitDisconnected()
    }

    @Test(timeout = 30_000)
    fun `tmux auto attach writes the exact command to the shell`() {
        val host = testHost().apply { tmuxAutoAttach = true }
        val (session, cb) = connectNow(host)
        val expected = "COLORTERM=truecolor tmux new -A -s conch\r"
        try {
            val shell = server.shells.first()
            shell.awaitStarted()
            shell.awaitReceived(expected.toByteArray().size)
            assertEquals(expected, String(shell.receivedBytes()))
        } finally {
            session.disconnect()
        }
        cb.awaitDisconnected()
    }

    @Test(timeout = 30_000)
    fun `server closing the shell reports connection closed by remote`() {
        val (session, cb) = connectNow()
        val shell = server.shells.first()
        shell.awaitStarted()
        shell.serverExit()
        // channel teardown delivers EOF to the read loop → exact mapping
        assertEquals("Connection closed by remote", cb.awaitDisconnected())
        assertEquals(1, cb.disconnected.get())
    }

    @Test(timeout = 30_000)
    fun `disconnect racing a slow connect leaks nothing and stays ordered`() {
        val racedClient = java.util.concurrent.atomic.AtomicReference<net.schmizz.sshj.SSHClient>()
        val cb = RecordingCallbacks()
        val session = SshSession(
            context = null,
            host = testHost(),
            initialCols = 80,
            initialRows = 24,
            callbacks = cb,
            tofuPrompt = null,
            post = { it.run() },
            connector = { h, _ ->
                // widen the race window: user hits disconnect mid-handshake
                Thread.sleep(400)
                SshConnectionFactory.connect(
                    host = h,
                    prompt = null,
                    store = store,
                    keyProvider = { _, _ -> throw IllegalStateException("no key") },
                    password = { server.password ?: "" },
                ).also { racedClient.set(it) }
            },
        )
        session.connect()
        Thread.sleep(100) // connector is now sleeping inside the race window
        session.disconnect("cancelled")
        assertEquals("cancelled", cb.awaitDisconnected())

        // after the connector wakes, the raced client must be closed, not leaked
        awaitTrue("raced client never got closed") { racedClient.get()?.isConnected != true }
        // and onConnected must never arrive after onDisconnected
        Thread.sleep(600)
        assertEquals(0, cb.connected.get())
        assertEquals(1, cb.disconnected.get())
    }

    @Test(timeout = 60_000)
    fun `reconnect after a server drop reattaches tmux and restores the session`() {
        // reserve a port so the "restarted" server can come back on it
        val probe = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = probe.localPort
        probe.close()

        val server1 = TestSshd(fixedPort = port).start()
        try {
            store.add("127.0.0.1", port, server1.hostPublicKey)
            val host = Host(
                hostname = "127.0.0.1",
                username = server1.user,
                authType = Host.AUTH_PASSWORD,
            ).apply {
                this.port = port
                tmuxAutoAttach = true
            }

            // first connection: tmux attach line goes out, session is alive
            val (s1, cb1) = newSession(host)
            s1.connect()
            awaitTrue("first connect never came up") { cb1.connected.get() > 0 }
            val expected = "COLORTERM=truecolor tmux new -A -s conch\r"
            server1.shells.first().awaitStarted()
            server1.shells.first().awaitReceived(expected.toByteArray().size)

            // network dies: hard server kill, client sees the disconnect
            server1.stopForcibly()
            cb1.awaitDisconnected()
        } finally {
            server1.close()
        }

        // the server comes back (same port, same host key → TOFU still trusts);
        // the app's reconnect path builds a fresh SshSession for the same host
        val server2 = TestSshd(fixedPort = port).start()
        try {
            val host = Host(
                hostname = "127.0.0.1",
                username = server2.user,
                authType = Host.AUTH_PASSWORD,
            ).apply {
                this.port = port
                tmuxAutoAttach = true
            }

            val (s2, cb2) = newSession(host)
            s2.connect()
            awaitTrue("reconnect never came up") { cb2.connected.get() > 0 }
            val expected = "COLORTERM=truecolor tmux new -A -s conch\r"
            val shell2 = server2.shells.first()
            shell2.awaitStarted()
            shell2.awaitReceived(expected.toByteArray().size)
            assertEquals(expected, String(shell2.receivedBytes()))

            // and the restored shell is fully usable
            s2.write("after-restore\r".toByteArray())
            cb2.awaitText("after-restore\r")
            s2.disconnect()
            cb2.awaitDisconnected()
        } finally {
            server2.close()
        }
    }

    @Test(timeout = 30_000)
    fun `server killing the transport surfaces a readable reason`() {
        val (session, cb) = connectNow()
        server.shells.first().awaitStarted()
        server.stopForcibly()
        val reason = cb.awaitDisconnected()
        assertTrue("reason must not be empty", reason.isNotBlank())
        assertEquals(1, cb.disconnected.get())
    }

    @Test(timeout = 30_000)
    fun `write and resize after disconnect are silently dropped`() {
        val (session, cb) = connectNow()
        server.shells.first().awaitStarted()
        session.disconnect("gone")
        cb.awaitDisconnected()
        // must not throw RejectedExecutionException (would crash the UI thread)
        session.write("should be dropped\r".toByteArray())
        session.resizePty(99, 99)
        Thread.sleep(200)
        assertEquals(1, cb.disconnected.get())
    }

    @Test(timeout = 30_000)
    fun `refused connection maps to a friendly onDisconnected reason`() {
        // grab a port and close it so nothing listens
        val s = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val deadPort = s.localPort
        s.close()

        val (session, cb) = newSession(testHost(deadPort))
        session.connect()
        val reason = cb.awaitDisconnected()
        assertTrue("expected refused mapping, got: [$reason]", reason.startsWith("Connection refused"))
        assertEquals(0, cb.connected.get())
    }
}

private fun awaitTrue(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(20)
    }
    throw AssertionError(message)
}
