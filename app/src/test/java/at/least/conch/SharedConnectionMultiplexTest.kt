package at.least.conch

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * H1: one sshj SSHClient can drive a PTY shell AND concurrent exec channels
 * AND an SFTP client at the same time, without disrupting the shell. This
 * is the foundation for the in-session 4-tab redesign (Terminal/Monitor/
 * Docker/Files all ride one connection, matching conch-ios's bridge.session).
 */
class SharedConnectionMultiplexTest {

    private lateinit var dir: File
    private lateinit var sftpRoot: File
    private lateinit var server: TestSshd

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conch-multiplex").toFile()
        sftpRoot = File(dir, "sftp").apply { mkdirs() }
        File(sftpRoot, "hello.txt").writeText("hi")
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.close()
        dir.deleteRecursively()
    }

    @Test(timeout = 60_000)
    fun `pty shell plus exec plus sftp share one connection without disruption`() {
        server = TestSshd(
            sftpRoot = sftpRoot,
            execHandler = { cmd -> ExecResult("ran [$cmd]\n".toByteArray()) },
        ).start()
        val ssh = connectTrusted(server, KnownHostsStore(dir))

        try {
            // (a) open a PTY shell and read back the first echo of a marker
            val shellSession = ssh.startSession()
            shellSession.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap())
            val sh = shellSession.startShell()
            val shOut = sh.outputStream
            val shIn = sh.inputStream
            val marker = "MARKER42"
            synchronized(shOut) {
                shOut.write("$marker\r".toByteArray())
                shOut.flush()
            }
            val firstEcho = readUntil(shIn, marker)
            assertTrue("shell echoed the marker back", firstEcho.contains(marker))

            // (b) while the shell is open, run an exec channel on the SAME client
            val execSession = ssh.startSession()
            val cmd = execSession.exec("probe")
            val execOut = cmd.inputStream.readBytes().decodeToString()
            cmd.close()
            execSession.close()
            assertEquals("ran [probe]\n", execOut)
            assertEquals(listOf("probe"), server.recordedCommands.toList())

            // (c) open an SFTP client on the SAME client and list the root
            val sftp = ssh.newSFTPClient()
            val names = sftp.ls("/").map { it.name }
            assertTrue("sftp root listing includes hello.txt", names.any { it.contains("hello") })
            sftp.close()

            // (d) send another keystroke to the shell and confirm the shell
            // stream is still alive (identity + echo), i.e. exec/sftp did NOT
            // tear down or starve the shell channel
            val second = "ALIVE99"
            synchronized(shOut) {
                shOut.write("$second\r".toByteArray())
                shOut.flush()
            }
            val secondEcho = readUntil(shIn, second)
            assertTrue("shell still echoes after exec+sftp", secondEcho.contains(second))

            shOut.close()
            sh.close()
            shellSession.close()
        } finally {
            ssh.disconnect()
        }
    }
}
