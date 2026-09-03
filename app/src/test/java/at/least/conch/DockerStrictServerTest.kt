package at.least.conch

import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
        DockerMatrix.connectPw(newStore(), DockerMatrix.STRICT_PORT).use { ssh ->
            assertEquals("MATRIX_OK", DockerMatrix.exec(ssh, "echo MATRIX_OK").trim())
        }
    }

    @Test(timeout = 60_000)
    fun `an idle shell reaped by the server surfaces as a disconnect`() {
        DockerMatrix.requireMatrix()
        val store = newStore()
        // pin the host key first so the background-shaped SshSession connect is promptless
        DockerMatrix.pinHostKey(store, DockerMatrix.STRICT_PORT)
        // stay idle: a tmux attach line would be channel traffic
        val host = DockerMatrix.pwHost(DockerMatrix.STRICT_PORT) { tmuxAutoAttach = false }

        val cb = RecordingCallbacks()
        val session = SshSession(
            context = null,
            host = host,
            initialCols = 80,
            initialRows = 24,
            callbacks = cb,
            tofuPrompt = null,
            post = { it.run() },
            connector = { h, _ -> DockerMatrix.connect(store, h, prompt = null) },
        )
        session.connect()
        try {
            cb.awaitConnected(30_000)
            session.write("echo IDLE-'READY'\r".toByteArray())
            cb.awaitText("IDLE-READY", 15_000)
            // now go fully idle: the server's ChannelTimeout (12 s) reaps the
            // shell, the reader loop hits EOF, and the app reports a disconnect
            val reason = cb.awaitDisconnected(25_000)
            // The old assertion here was `reason.isNotBlank()`, which no
            // change could ever fail: cleanCloseReason() returns one of two
            // non-empty constants. Pin the real classification instead.
            // The shell lived ~12 s (past MIN_SESSION_MS) before the
            // server's ChannelTimeout reaped it, so the reader-loop EOF is
            // read as a clean end-of-session.
            assertEquals(SshSession.REASON_SESSION_ENDED, reason)
            // KNOWN GAP, pinned so it is visible rather than implied: that
            // makes isTerminalFailure() true, so a server-side idle reap
            // does NOT reconnect — the app cannot tell it from the user
            // typing `exit`. Flip these two lines the day it can.
            assertTrue(
                "a server-side reap is currently indistinguishable from a user exit",
                SshSession.isTerminalFailure(reason),
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
        val host = DockerMatrix.pwHost(DockerMatrix.STRICT_PORT) { keepAlive = false }
        val ssh: SSHClient = DockerMatrix.connect(newStore(), host)
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
