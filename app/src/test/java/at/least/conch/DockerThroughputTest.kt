package at.least.conch

import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

/**
 * High-volume and lifecycle behaviour against real OpenSSH that MINA cannot
 * stress the same way: a multi-megabyte exec burst arriving without loss and
 * feeding cleanly through the app's [TerminalEmulator], exec exit codes
 * propagating, and a PTY resize delivering SIGWINCH so the remote `stty size`
 * updates mid-session.
 *
 * Same opt-in as [DockerSshdAuthTest] (see [DockerMatrix]).
 */
class DockerThroughputTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun connect(): SSHClient =
        DockerMatrix.connect(
            KnownHostsStore(tmp.newFolder()),
            DockerMatrix.PW_AND_KEY_PORT,
            "pwuser",
            password = "conch-pw-1"
        )

    @Test(timeout = 120_000)
    fun `a multi-megabyte exec burst arrives without loss and feeds the emulator`() {
        DockerMatrix.requireMatrix()
        val lines = 200_000
        connect().use { ssh ->
            val session = ssh.startSession()
            try {
                // deterministic, high-volume output: 200k numbered lines
                val cmd = session.exec("seq 1 $lines")
                val out = ByteArrayOutputStream()
                cmd.inputStream.copyTo(out, 64 * 1024)
                cmd.join()
                val text = out.toString("UTF-8")
                // nothing dropped: first, last and count all present
                val produced = text.split("\n").filter { it.isNotBlank() }
                assertEquals("line count mismatch — bytes were lost", lines, produced.size)
                assertEquals("1", produced.first())
                assertEquals("$lines", produced.last())
                assertEquals(0, cmd.exitStatus)

                // the same burst must feed the app's terminal engine without
                // throwing and leave the cursor in a valid position
                val emu = TerminalEmulator(80, 24)
                emu.feed(out.toByteArray())
                assertTrue("cursor row escaped the screen", emu.cursorRow in 0 until emu.rows)
                assertTrue("cursor col escaped the screen", emu.cursorCol in 0..emu.cols)
            } finally {
                runCatching { session.close() }
            }
        }
    }

    @Test(timeout = 60_000)
    fun `exec exit codes propagate`() {
        DockerMatrix.requireMatrix()
        connect().use { ssh ->
            for (code in listOf(0, 1, 7, 42)) {
                val session = ssh.startSession()
                try {
                    val cmd = session.exec("exit $code")
                    cmd.join()
                    assertEquals("wrong exit status", code, cmd.exitStatus)
                } finally {
                    runCatching { session.close() }
                }
            }
        }
    }

    @Test(timeout = 60_000)
    fun `a pty resize delivers SIGWINCH and the remote stty size updates`() {
        DockerMatrix.requireMatrix()
        connect().use { ssh ->
            val session = ssh.startSession()
            session.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap())
            val shell = session.startShell()
            try {
                // interactive size before the resize
                synchronized(shell.outputStream) {
                    shell.outputStream.write("stty size; echo SIZE'ONE'\r".toByteArray())
                    shell.outputStream.flush()
                }
                val before = readUntil(shell.inputStream, "SIZEONE", 15_000)
                assertTrue("initial size wrong: $before", before.contains("24 80"))

                // the app calls changeWindowDimensions on a rotate / keyboard
                // show; the remote must see the new size via SIGWINCH
                shell.changeWindowDimensions(120, 40, 0, 0)
                synchronized(shell.outputStream) {
                    shell.outputStream.write("stty size; echo SIZE'TWO'\r".toByteArray())
                    shell.outputStream.flush()
                }
                val after = readUntil(shell.inputStream, "SIZETWO", 15_000)
                assertTrue("resize not reflected by stty size: $after", after.contains("40 120"))
            } finally {
                runCatching { session.close() }
            }
        }
    }
}
