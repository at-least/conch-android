package at.least.conch

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Mobile-grade links, produced with `tc netem` on the matrix container's
 * interface (run.sh grants NET_ADMIN): latency + jitter + loss on every
 * packet between the app and sshd. Pins that SFTP transfers stay correct
 * (not merely "eventually finish") and that interactive exec stays usable
 * under a 5% loss link — the shape of a train-tunnel LTE session.
 *
 * The qdisc is removed in @After even when an assertion fails, so the
 * shared matrix is never left slow for the next class.
 *
 * Same opt-in as [DockerSshdAuthTest] (see [DockerMatrix]).
 */
class DockerSlowNetworkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var shaped = false

    @After
    fun tearDown() {
        if (shaped) DockerMatrix.dockerExec("tc qdisc del dev eth0 root", allowFailure = true)
    }

    private fun shape(spec: String) {
        val out = DockerMatrix.dockerExec("tc qdisc add dev eth0 root netem $spec 2>&1 && echo TC_OK", allowFailure = true)
        // a kernel without sch_netem is a fixture limit, not an app failure
        assumeTrue("tc netem unavailable on this docker host: $out", !out.contains("not supported", true))
        assertTrue("tc failed: $out", out.contains("TC_OK"))
        shaped = true
    }

    private fun connect() =
        DockerMatrix.connect(KnownHostsStore(tmp.newFolder()), DockerMatrix.PW_AND_KEY_PORT, "pwuser", password = "conch-pw-1")

    @Test(timeout = 180_000)
    fun `sftp round-trip stays byte-exact over a lossy high-latency link`() {
        DockerMatrix.requireMatrix()
        shape("delay 60ms 20ms loss 3%")
        val payload = ByteArray(1_000_000) { i -> ((i * 131 + 17) and 0xFF).toByte() }
        val local = File(tmp.newFolder(), "slow.bin").apply { writeBytes(payload) }
        val back = File(tmp.newFolder(), "slow.back")
        connect().use { ssh ->
            val sftp = ssh.newSFTPClient()
            try {
                val t0 = System.currentTimeMillis()
                sftp.getFileTransfer().upload(local.absolutePath, "slow.bin")
                sftp.getFileTransfer().download("slow.bin", back.absolutePath)
                println("slow-link sftp 1 MB up+down: ${System.currentTimeMillis() - t0} ms")
                assertEquals(sha256(payload), sha256(back.readBytes()))
                // server-side hash agrees too (the upload was not silently truncated)
                val remote = DockerMatrix.exec(ssh, "sha256sum slow.bin | cut -d' ' -f1", 30_000).trim()
                assertEquals(sha256(payload), remote)
                sftp.rm("slow.bin")
            } finally {
                sftp.close()
            }
        }
    }

    @Test(timeout = 180_000)
    fun `exec and pty stay usable under 5 percent loss with 150ms latency`() {
        DockerMatrix.requireMatrix()
        shape("delay 150ms 30ms loss 5%")
        connect().use { ssh ->
            repeat(5) { i ->
                assertEquals("SLOW_$i", DockerMatrix.exec(ssh, "echo SLOW_$i", 30_000).trim())
            }
            val session = ssh.startSession()
            session.allocatePTY("xterm-256color", 100, 30, 0, 0, emptyMap())
            val shell = session.startShell()
            try {
                synchronized(shell.outputStream) {
                    shell.outputStream.write("stty size; echo PTY'SLOW'\r".toByteArray())
                    shell.outputStream.flush()
                }
                val acc = readUntil(shell.inputStream, "PTYSLOW", 30_000)
                assertTrue("stty size missing: $acc", acc.contains("30 100"))
            } finally {
                session.close()
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
