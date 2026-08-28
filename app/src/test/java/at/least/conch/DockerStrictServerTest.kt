package at.least.conch

import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The "strict" sshd on :2239 (bookworm, OpenSSH 9.2): `MaxAuthTries 1`,
 * shell channels reaped by the server after 12 s idle (`ChannelTimeout`),
 * unused transports closed 5 s later (`UnusedConnectionTimeout`). What the
 * app owes the user against such a server, and what the in-process MINA
 * server cannot reproduce:
 *
 *  - a wrong password is ONE clean, bounded failure the user can read as an
 *    auth problem (not a lockout hang, not a bare "Disconnected"),
 *  - a server-reaped idle shell surfaces as a disconnect, not a silent freeze,
 *  - once the server has closed the transport, the next action fails bounded.
 *
 * Mirrors conch-ios's StrictServerIntegrationTests (iOS-parity directive).
 * Same opt-in as [DockerSshdAuthTest] (see [DockerMatrix]).
 */
class DockerStrictServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore() = KnownHostsStore(tmp.newFolder())

    @Test(timeout = 60_000)
    fun `MaxAuthTries 1 is one clean auth failure then the right password logs in`() {
        DockerMatrix.requireMatrix()
        val t0 = System.currentTimeMillis()
        val e = runCatching {
            DockerMatrix.connect(newStore(), DockerMatrix.STRICT_PORT, "pwuser", password = "definitely-wrong").use { }
        }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("wrong password should fail, not succeed", e != null)
        assertTrue("MaxAuthTries 1 failure took too long (${elapsed}ms) — must not hang", elapsed < 15_000)
        // the app must render it as an AUTH problem, not a bare transport drop:
        // whether sshj raises UserAuthException or the server's "Too many
        // authentication failures" disconnect, describeError must say so
        val msg = SshConnectionFactory.describeError(e as Exception)
        assertTrue("auth failure surfaced unhelpfully as '$msg' ($e)", msg.contains("auth", ignoreCase = true))

        // and the correct password still logs in right afterwards (the one
        // failed try did not lock the account out for this client)
        DockerMatrix.connect(newStore(), DockerMatrix.STRICT_PORT, "pwuser", password = "conch-pw-1").use { ssh ->
            assertEquals("MATRIX_OK", DockerMatrix.exec(ssh, "echo MATRIX_OK").trim())
        }
    }

    @Test(timeout = 60_000)
    fun `an idle shell reaped by the server surfaces as a disconnect`() {
        DockerMatrix.requireMatrix()
        // pin the host key first so the background-shaped SshSession connect is promptless
        DockerMatrix.connect(newStore(), DockerMatrix.STRICT_PORT, "pwuser", password = "conch-pw-1").use { }

        val store = newStore()
        DockerMatrix.connect(store, DockerMatrix.STRICT_PORT, "pwuser", password = "conch-pw-1").use { }
        val host = Host(
            hostname = "127.0.0.1",
            username = "pwuser",
            authType = Host.AUTH_PASSWORD,
            tmuxAutoAttach = false, // stay idle: an attach line would be channel traffic
        ).apply { port = DockerMatrix.STRICT_PORT }

        val connected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val reason = AtomicReference<String>()
        val gotEcho = CountDownLatch(1)
        val text = StringBuilder()
        val session = SshSession(
            context = null,
            host = host,
            initialCols = 80,
            initialRows = 24,
            callbacks = object : SshSession.Callbacks {
                override fun onConnected() = connected.countDown()
                override fun onData(data: ByteArray) {
                    synchronized(text) { text.append(String(data)) }
                    if (synchronized(text) { text.contains("IDLE-READY") }) gotEcho.countDown()
                }
                override fun onDisconnected(r: String) {
                    reason.set(r)
                    disconnected.countDown()
                }
            },
            tofuPrompt = null,
            post = { it.run() },
            connector = { h, _ ->
                SshConnectionFactory.connect(
                    host = h,
                    prompt = null,
                    store = store,
                    keyProvider = { _, _ -> error("password auth in this test") },
                    password = { "conch-pw-1" },
                )
            },
        )
        session.connect()
        try {
            assertTrue("session never connected", connected.await(30, TimeUnit.SECONDS))
            session.write("echo IDLE-'READY'\r".toByteArray())
            assertTrue("shell never answered before idling", gotEcho.await(15, TimeUnit.SECONDS))
            // now go fully idle: the server's ChannelTimeout (12 s) reaps the
            // shell, the reader loop hits EOF, and the app reports a disconnect
            assertTrue(
                "server-reaped idle shell did not surface as a disconnect",
                disconnected.await(25, TimeUnit.SECONDS),
            )
            assertTrue(
                "a reaped shell must not read as a clean user exit: ${reason.get()}",
                !reason.get().isNullOrBlank(),
            )
        } finally {
            session.disconnect("test done")
        }
    }

    @Test(timeout = 60_000)
    fun `an unused connection closed by the server makes the next channel fail bounded`() {
        DockerMatrix.requireMatrix()
        // no channel is ever opened; keep-alive off so nothing resets the
        // server's UnusedConnectionTimeout (5 s) clock
        val host = Host(
            hostname = "127.0.0.1",
            username = "pwuser",
            authType = Host.AUTH_PASSWORD,
            keepAlive = false,
        ).apply { port = DockerMatrix.STRICT_PORT }
        val ssh: SSHClient = SshConnectionFactory.connect(
            host = host,
            prompt = DockerMatrix.acceptPrompt,
            store = newStore(),
            keyProvider = { _, _ -> error("password auth in this test") },
            password = { "conch-pw-1" },
        )
        try {
            // wait past UnusedConnectionTimeout with no channel open
            Thread.sleep(9_000)
            val t0 = System.currentTimeMillis()
            val e = runCatching { ssh.startSession().use { it.exec("echo LATE").close() } }.exceptionOrNull()
            val elapsed = System.currentTimeMillis() - t0
            assertTrue("opening a channel on a server-closed transport should fail, got success", e != null)
            assertTrue("the failure must be bounded (${elapsed}ms)", elapsed < 15_000)
        } finally {
            runCatching { ssh.disconnect() }
        }
    }
}
