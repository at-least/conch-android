package at.least.conch

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import org.apache.sshd.server.Environment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PTY allocation, interactive shell I/O and window resizing over a real
 * connection — the exact sshj calls SshSession performs.
 */
class SshShellPtyInteractionTest {

    private lateinit var dir: File
    private lateinit var server: TestSshd

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conch-shell").toFile()
        server = TestSshd().start()
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    /** Mirrors SshSession.connect(): startSession -> allocatePTY -> startShell. */
    private fun openShell(cols: Int = 80, rows: Int = 24): Pair<SSHClient, Session.Shell> {
        val ssh = connectTrusted(server, KnownHostsStore(dir))
        val s = ssh.startSession()
        s.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
        val sh = s.startShell()
        return ssh to sh
    }

    @Test(timeout = 30_000)
    fun `pty request carries term type and initial size`() {
        val (ssh, _) = openShell(cols = 97, rows = 31)
        try {
            val shell = server.shells.first()
            shell.awaitStarted()
            assertEquals("xterm-256color", shell.envAtStart[Environment.ENV_TERM])
            assertEquals("97", shell.envAtStart[Environment.ENV_COLUMNS])
            assertEquals("31", shell.envAtStart[Environment.ENV_LINES])
            assertTrue("pty modes should be empty when client sends none", shell.ptyModesAtStart.isEmpty())
        } finally {
            ssh.disconnect()
        }
    }

    @Test(timeout = 30_000)
    fun `shell echoes bytes written by the client`() {
        val (ssh, sh) = openShell()
        try {
            sh.outputStream.write("hello conch\r".toByteArray())
            sh.outputStream.flush()
            assertEquals("hello conch\r", readUntil(sh.inputStream, "hello conch\r"))
            val serverShell = server.shells.first()
            serverShell.awaitStarted()
            serverShell.awaitReceived("hello conch\r".toByteArray().size)
            assertEquals("hello conch\r", String(serverShell.receivedBytes()))
        } finally {
            ssh.disconnect()
        }
    }

    @Test(timeout = 30_000)
    fun `window resize reaches the server`() {
        val (ssh, sh) = openShell(cols = 80, rows = 24)
        try {
            sh.changeWindowDimensions(120, 45, 0, 0)
            sh.changeWindowDimensions(200, 60, 0, 0)
            val shell = server.shells.first()
            awaitWindowSize(shell, 2)
            assertEquals(listOf("120" to "45", "200" to "60"), shell.windowSizes.toList())
        } finally {
            ssh.disconnect()
        }
    }

    private fun awaitWindowSize(shell: TestSshd.RecordedShell, count: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (shell.windowSizes.size >= count) return
            Thread.sleep(20)
        }
        assertEquals("window sizes recorded", count, shell.windowSizes.size)
    }

    @Test(timeout = 30_000)
    fun `client disconnect ends the server shell`() {
        val (ssh, _) = openShell()
        val shell = server.shells.first()
        shell.awaitStarted()
        ssh.disconnect()
        shell.awaitExited()
    }

    @Test(timeout = 30_000)
    fun `rapid writes arrive in order`() {
        val (ssh, sh) = openShell()
        try {
            val payload = (0 until 50).joinToString("") { "line$it\n" }
            sh.outputStream.write(payload.toByteArray())
            sh.outputStream.flush()
            val serverShell = server.shells.first()
            serverShell.awaitStarted()
            serverShell.awaitReceived(payload.toByteArray().size)
            assertEquals(payload, String(serverShell.receivedBytes()))
        } finally {
            ssh.disconnect()
        }
    }
}
