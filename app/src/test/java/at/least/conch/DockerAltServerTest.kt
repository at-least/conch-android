package at.least.conch

import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * conch against SSH servers that are NOT OpenSSH — the implementations real
 * users actually meet (tools/sshd-matrix/servers, run.sh --servers):
 *
 *   Dropbear   routers, OpenWrt, NAS, embedded (:2263 pw, :2264 key, :2265 fwd)
 *   tinyssh    ed25519-only, no password, no RSA (:2266 key)
 *   x/crypto   Gitea / gliderlabs / bespoke bastions (:2267 pw+key)
 *   Paramiko   Fabric / pysftp / network automation (:2268 pw+key)
 *
 * Each pins that the handshake, auth and a session channel work against a
 * different code base — catching any place conch silently assumes OpenSSH's
 * banner, algorithm ordering or channel timing.
 *
 * These bring their own opt-in gate ([DockerMatrix.requireServer]); with
 * -Dconch.distroMatrix=true a missing server FAILS (CI runs --servers),
 * otherwise a server that is not up just skips.
 */
class DockerAltServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore() = KnownHostsStore(tmp.newFolder())

    private fun ptyTerm(ssh: SSHClient, term: String = "xterm-256color"): String {
        val session = ssh.startSession()
        session.allocatePTY(term, 100, 30, 0, 0, emptyMap())
        val shell = session.startShell()
        return try {
            synchronized(shell.outputStream) {
                shell.outputStream.write("echo TERM=\$TERM; echo PTY'DONE'\r".toByteArray())
                shell.outputStream.flush()
            }
            readUntil(shell.inputStream, "PTYDONE", 20_000)
        } finally {
            runCatching { session.close() }
        }
    }

    // ---- Dropbear -------------------------------------------------------

    @Test(timeout = 60_000)
    fun `dropbear accepts a password and execs`() {
        DockerMatrix.requireServer(DockerMatrix.DROPBEAR, DockerMatrix.DROPBEAR.pwPort)
        DockerMatrix.connect(newStore(), DockerMatrix.DROPBEAR.pwPort, "pwuser", password = "conch-pw-1").use { ssh ->
            assertEquals("DB_OK", DockerMatrix.exec(ssh, "echo DB_OK").trim())
        }
    }

    @Test(timeout = 60_000)
    fun `dropbear accepts a public key and execs`() {
        DockerMatrix.requireServer(DockerMatrix.DROPBEAR, DockerMatrix.DROPBEAR.keyOnlyPort)
        DockerMatrix.connect(
            newStore(),
            DockerMatrix.DROPBEAR.keyOnlyPort,
            "keyuser",
            authType = Host.AUTH_KEY,
            keyFile = DockerMatrix.keyFile("keyB"),
        ).use { ssh ->
            assertEquals("keyuser", DockerMatrix.exec(ssh, "whoami").trim())
        }
    }

    @Test(timeout = 60_000)
    fun `dropbear serves a real pty with TERM set`() {
        DockerMatrix.requireServer(DockerMatrix.DROPBEAR, DockerMatrix.DROPBEAR.forwardingPort)
        DockerMatrix.connect(
            newStore(),
            DockerMatrix.DROPBEAR.forwardingPort,
            "pwuser",
            password = "conch-pw-1"
        ).use { ssh ->
            val acc = ptyTerm(ssh)
            assertTrue("dropbear pty did not carry TERM: $acc", acc.contains("TERM=xterm-256color"))
        }
    }

    // ---- tinyssh --------------------------------------------------------

    @Test(timeout = 60_000)
    fun `tinyssh admits an ed25519 key and pins its ed25519 host key`() {
        DockerMatrix.requireServer(DockerMatrix.TINYSSH, DockerMatrix.TINYSSH.keyOnlyPort)
        val store = newStore()
        DockerMatrix.connect(
            store,
            DockerMatrix.TINYSSH.keyOnlyPort,
            "keyuser",
            authType = Host.AUTH_KEY,
            keyFile = DockerMatrix.keyFile("keyB"),
        ).use { ssh ->
            assertEquals("TINY_OK", DockerMatrix.exec(ssh, "echo TINY_OK").trim())
        }
        // tinyssh offers ONLY an ed25519 host key — the app pinned that type
        val line = store.file.readLines().first { it.isNotBlank() }
        assertTrue("tinyssh host key should be ed25519: $line", line.contains("ssh-ed25519"))
    }

    // ---- golang.org/x/crypto/ssh ---------------------------------------

    @Test(timeout = 60_000)
    fun `x-crypto server negotiates its own banner and execs by password and key`() {
        DockerMatrix.requireServer(DockerMatrix.GOSSH, DockerMatrix.GOSSH.pwPort)
        DockerMatrix.connect(newStore(), DockerMatrix.GOSSH.pwPort, "pwuser", password = "conch-pw-1").use { ssh ->
            // a non-OpenSSH identification string must not trip the app up
            assertTrue(
                "unexpected server ident: ${ssh.transport.serverVersion}",
                ssh.transport.serverVersion.contains("gossh"),
            )
            assertEquals("GO_OK", DockerMatrix.exec(ssh, "echo GO_OK").trim())
        }
        DockerMatrix.connect(
            newStore(),
            DockerMatrix.GOSSH.pwPort,
            "bothuser",
            authType = Host.AUTH_KEY,
            keyFile = DockerMatrix.keyFile("keyA"),
        ).use { ssh ->
            assertEquals("GO_KEY_OK", DockerMatrix.exec(ssh, "echo GO_KEY_OK").trim())
        }
    }

    @Test(timeout = 60_000)
    fun `x-crypto server carries TERM through a pty request`() {
        DockerMatrix.requireServer(DockerMatrix.GOSSH, DockerMatrix.GOSSH.pwPort)
        DockerMatrix.connect(newStore(), DockerMatrix.GOSSH.pwPort, "pwuser", password = "conch-pw-1").use { ssh ->
            // this server echoes the negotiated TERM on shell start
            val session = ssh.startSession()
            session.allocatePTY("screen-256color", 90, 30, 0, 0, emptyMap())
            val shell = session.startShell()
            try {
                val acc = readUntil(shell.inputStream, "TERM=screen-256color", 15_000)
                assertTrue("x/crypto pty TERM missing: $acc", acc.contains("TERM=screen-256color"))
            } finally {
                runCatching { session.close() }
            }
        }
    }

    // ---- Paramiko -------------------------------------------------------

    @Test(timeout = 60_000)
    fun `paramiko server execs by password and key`() {
        DockerMatrix.requireServer(DockerMatrix.PARAMIKO, DockerMatrix.PARAMIKO.pwPort)
        DockerMatrix.connect(newStore(), DockerMatrix.PARAMIKO.pwPort, "pwuser", password = "conch-pw-1").use { ssh ->
            assertEquals("PARA_OK", DockerMatrix.exec(ssh, "echo PARA_OK").trim())
        }
        DockerMatrix.connect(
            newStore(),
            DockerMatrix.PARAMIKO.pwPort,
            "bothuser",
            authType = Host.AUTH_KEY,
            keyFile = DockerMatrix.keyFile("keyA"),
        ).use { ssh ->
            assertEquals("PARA_KEY_OK", DockerMatrix.exec(ssh, "echo PARA_KEY_OK").trim())
        }
    }

    @Test(timeout = 60_000)
    fun `paramiko server carries TERM through a pty request`() {
        DockerMatrix.requireServer(DockerMatrix.PARAMIKO, DockerMatrix.PARAMIKO.pwPort)
        DockerMatrix.connect(newStore(), DockerMatrix.PARAMIKO.pwPort, "pwuser", password = "conch-pw-1").use { ssh ->
            val session = ssh.startSession()
            session.allocatePTY("tmux-256color", 90, 30, 0, 0, emptyMap())
            val shell = session.startShell()
            try {
                val acc = readUntil(shell.inputStream, "TERM=tmux-256color", 15_000)
                assertTrue("paramiko pty TERM missing: $acc", acc.contains("TERM=tmux-256color"))
            } finally {
                runCatching { session.close() }
            }
        }
    }
}
