package at.least.conch

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The same app paths against every OpenSSH version / userland users are
 * likely to hit (tools/sshd-matrix/run.sh --variants): Ubuntu 20.04's
 * OpenSSH 8.2 (SHA-1 RSA still on), 24.04's 9.6, Alpine's busybox userland
 * (no `df -B1`), Debian trixie's OpenSSH 10 (post-quantum kex default,
 * DSA gone), Rocky 9's crypto policies. One row = one container; a row
 * that is not running skips unless CI demanded the whole matrix
 * (-Dconch.distroMatrix=true).
 *
 * What a row pins: kex/cipher/host-key negotiation succeeds (password and
 * pubkey), a wrong password is an auth failure (not a transport error),
 * SFTP through that version's internal-sftp, PTY dimensions, the Monitor
 * probe parsing that userland's `free`/`df` output, and the Docker tab's
 * list command degrading to "no containers" where no daemon is reachable.
 */
@RunWith(Parameterized::class)
class DockerDistroMatrixTest(private val v: DockerMatrix.Variant) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun rows(): List<DockerMatrix.Variant> = listOf(DockerMatrix.DEFAULT_VARIANT) + DockerMatrix.VARIANTS
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore() = KnownHostsStore(tmp.newFolder())

    private fun connectPw(): SSHClient =
        DockerMatrix.connect(newStore(), v.pwPort, "pwuser", password = "conch-pw-1")

    @Test(timeout = 60_000)
    fun `password auth negotiates and execs`() {
        DockerMatrix.requireVariant(v)
        connectPw().use { ssh ->
            val version = DockerMatrix.sshVersion(ssh)
            println("[$v] $version")
            assertTrue("no OpenSSH version reported: $version", version.contains("OpenSSH_"))
            assertEquals("MATRIX_OK", DockerMatrix.exec(ssh, "echo MATRIX_OK").trim())
        }
    }

    @Test(timeout = 60_000)
    fun `pubkey auth on the key-only instance`() {
        DockerMatrix.requireVariant(v)
        DockerMatrix.connect(
            newStore(),
            v.keyOnlyPort,
            "keyuser",
            authType = Host.AUTH_KEY,
            keyFile = DockerMatrix.keyFile("keyB"),
        ).use { ssh ->
            assertEquals("keyuser", DockerMatrix.exec(ssh, "whoami").trim())
        }
    }

    @Test(timeout = 60_000)
    fun `wrong password is an auth failure not a transport error`() {
        DockerMatrix.requireVariant(v)
        val e = runCatching {
            DockerMatrix.connect(newStore(), v.pwPort, "pwuser", password = "nope").use { }
        }.exceptionOrNull()
        assertTrue("[$v] expected UserAuthException, got: $e", e is UserAuthException)
    }

    @Test(timeout = 60_000)
    fun `sftp round-trips through this version's internal-sftp`() {
        DockerMatrix.requireVariant(v)
        connectPw().use { ssh ->
            val sftp = ssh.newSFTPClient()
            try {
                val payload = ByteArray(40_000) { i -> ((i * 29 + 3) and 0xFF).toByte() }
                val local = tmp.newFile("m.bin").apply { writeBytes(payload) }
                val back = tmp.newFile("m.back")
                sftp.getFileTransfer().upload(local.absolutePath, "matrix.bin")
                sftp.getFileTransfer().download("matrix.bin", back.absolutePath)
                assertArrayEquals(payload, back.readBytes())
                sftp.rm("matrix.bin")
            } finally {
                sftp.close()
            }
        }
    }

    @Test(timeout = 60_000)
    fun `pty carries TERM and dimensions`() {
        DockerMatrix.requireVariant(v)
        connectPw().use { ssh ->
            val session = ssh.startSession()
            session.allocatePTY("xterm-256color", 132, 43, 0, 0, emptyMap())
            val shell = session.startShell()
            try {
                synchronized(shell.outputStream) {
                    shell.outputStream.write("echo TERM=\$TERM; stty size; echo PTY'DONE'\r".toByteArray())
                    shell.outputStream.flush()
                }
                val acc = readUntil(shell.inputStream, "PTYDONE")
                assertTrue("[$v] TERM missing: $acc", acc.contains("TERM=xterm-256color"))
                assertTrue("[$v] stty size missing: $acc", acc.contains("43 132"))
            } finally {
                session.close()
            }
        }
    }

    @Test(timeout = 60_000)
    fun `monitor probe parses this userland`() {
        DockerMatrix.requireVariant(v)
        connectPw().use { ssh ->
            val raw = DockerMatrix.exec(ssh, MonitorParser.PROBE, 30_000)
            val snap = MonitorParser.parse(raw)
            assertNotNull("[$v] probe output did not parse:\n$raw", snap)
            snap!!
            assertTrue("[$v] cpu out of range: ${snap.cpuPercent}", snap.cpuPercent in 0.0..100.0)
            assertTrue("[$v] mem total missing:\n$raw", snap.memTotalBytes > 0)
            assertTrue("[$v] mem used > total:\n$raw", snap.memUsedBytes in 0..snap.memTotalBytes)
            assertTrue("[$v] uptime missing:\n$raw", snap.uptimeSeconds > 0)
            // every base in the matrix (Alpine included) ships a df that
            // honours -B1, so the DISK section is always populated
            assertTrue("[$v] disk total missing:\n$raw", snap.diskTotalBytes > 0)
            assertTrue("[$v] disk used > total:\n$raw", snap.diskUsedBytes in 0..snap.diskTotalBytes)
        }
    }

    @Test(timeout = 60_000)
    fun `docker list command degrades to no containers without a daemon`() {
        DockerMatrix.requireVariant(v)
        if (v == DockerMatrix.DEFAULT_VARIANT) return // has the host socket; see DockerContainerListTest
        connectPw().use { ssh ->
            val raw = DockerMatrix.exec(ssh, "${DockerParser.LIST_COMMAND} 2>&1", 30_000)
            assertTrue("[$v] expected a daemon error, got: $raw", raw.contains("docker daemon", true) || raw.contains("permission denied", true))
            assertEquals("[$v] error text must not parse as containers", emptyList<DockerParser.Container>(), DockerParser.parse(raw))
        }
    }
}
