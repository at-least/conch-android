package at.least.conch

import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The knobs a real hardened server turns, against OpenSSH on :2238
 * (tools/sshd-matrix entrypoint):
 *
 *   - a login `Banner` delivered during userauth,
 *   - a user authenticated ONLY by an OpenSSH certificate signed by a CA the
 *     server trusts (`TrustedUserCAKeys`), with no authorized_keys entry,
 *   - authorized_keys option accounts: a `command="…"` forced command, a
 *     `restrict`ed key (no forwarding), a `no-pty` key,
 *   - `MaxSessions 2` capping concurrent channels,
 *   - `PermitOpen 127.0.0.1:2223` limiting where -L tunnels may reach,
 *   - a `ForceCommand internal-sftp` + `ChrootDirectory` SFTP-only account.
 *
 * These are wire behaviours the in-process MINA server cannot reproduce.
 * Same opt-in as [DockerSshdAuthTest] (see [DockerMatrix]).
 */
class DockerHardenedServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore() = KnownHostsStore(tmp.newFolder())

    private fun keyConnect(user: String, keyName: String = "keyA") =
        DockerMatrix.connect(
            newStore(),
            DockerMatrix.HARDENED_PORT,
            user,
            authType = Host.AUTH_KEY,
            keyFile = DockerMatrix.keyFile(keyName),
        )

    @Test(timeout = 60_000)
    fun `certificate-only user is admitted by the trusted CA and the login banner is delivered`() {
        DockerMatrix.requireMatrix()
        // certuser has NO authorized_keys entry; the only way in is the
        // CA-signed keyCert-cert.pub (sshj auto-loads the sibling -cert.pub
        // exactly as the OpenSSH client does), matching principal "certuser".
        keyConnect(DockerMatrix.CERT_PRINCIPAL, "keyCert").use { ssh ->
            assertEquals(DockerMatrix.CERT_PRINCIPAL, DockerMatrix.exec(ssh, "whoami").trim())
            val banner = ssh.userAuth.banner
            assertTrue(
                "userauth Banner not delivered: '$banner'",
                banner != null && banner.contains("authorized use only"),
            )
        }
    }

    @Test(timeout = 60_000)
    fun `a bare key with no certificate cannot log in as the certificate user`() {
        DockerMatrix.requireMatrix()
        // keyB has neither an authorized_keys entry nor a cert → clean reject
        val e = runCatching { keyConnect(DockerMatrix.CERT_PRINCIPAL, "keyB").use { } }.exceptionOrNull()
        assertTrue("expected auth failure, got: $e", e is net.schmizz.sshj.userauth.UserAuthException)
    }

    @Test(timeout = 60_000)
    fun `a forced-command key runs only that command whatever the client asks`() {
        DockerMatrix.requireMatrix()
        keyConnect(DockerMatrix.CMD_USER).use { ssh ->
            // the client asks for an arbitrary command; the server substitutes
            // the authorized_keys command="…" and ignores the request
            val out = DockerMatrix.exec(ssh, "id; echo THIS_SHOULD_NOT_RUN").trim()
            assertEquals(DockerMatrix.FORCED_COMMAND_OUTPUT, out)
        }
    }

    @Test(timeout = 60_000)
    fun `a restricted key can exec but the server refuses its port forwards`() {
        DockerMatrix.requireMatrix()
        keyConnect(DockerMatrix.RESTRICT_USER).use { ssh ->
            assertEquals("ok", DockerMatrix.exec(ssh, "echo ok").trim())
            // restrict disables port forwarding for this key: the direct-tcpip
            // channel is refused even though the instance sets AllowTcpForwarding
            val e = runCatching {
                ssh.newDirectConnection("127.0.0.1", DockerMatrix.CONTAINER_SSH_PORT).close()
            }.exceptionOrNull()
            assertTrue("forwarding should be refused for a restricted key, got: $e", e is ConnectionException)
        }
    }

    @Test(timeout = 60_000)
    fun `a no-pty key still execs but pty allocation is refused`() {
        DockerMatrix.requireMatrix()
        keyConnect(DockerMatrix.NOPTY_USER).use { ssh ->
            assertEquals("exec", DockerMatrix.exec(ssh, "echo exec").trim())
            val session: Session = ssh.startSession()
            try {
                val e = runCatching {
                    session.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap())
                }.exceptionOrNull()
                assertTrue("pty should be refused for a no-pty key, got: $e", e is ConnectionException)
            } finally {
                runCatching { session.close() }
            }
        }
    }

    @Test(timeout = 60_000)
    fun `PermitOpen limits which targets a tunnel may reach`() {
        DockerMatrix.requireMatrix()
        keyConnect("bothuser").use { ssh ->
            // PermitOpen 127.0.0.1:2223 — the inner sshd is reachable…
            ssh.newDirectConnection("127.0.0.1", DockerMatrix.CONTAINER_SSH_PORT).use { ch ->
                assertTrue("expected an SSH banner from the permitted target", ch.inputStream.read() >= 0)
            }
            // …but any other target is refused by the server, not the client
            val e = runCatching {
                ssh.newDirectConnection("127.0.0.1", DockerMatrix.CONTAINER_FWD_PORT).close()
            }.exceptionOrNull()
            assertTrue("a non-permitted target must be refused, got: $e", e is ConnectionException)
        }
    }

    @Test(timeout = 60_000)
    fun `MaxSessions caps the number of concurrent channels`() {
        DockerMatrix.requireMatrix()
        keyConnect("bothuser").use { ssh ->
            val s1 = ssh.startSession()
            val s2 = ssh.startSession()
            try {
                // MaxSessions 2 → the third concurrent session channel is refused
                val e = runCatching { ssh.startSession() }.exceptionOrNull()
                assertTrue("third channel past MaxSessions should be refused, got: $e", e is ConnectionException)
            } finally {
                runCatching { s1.close() }
                runCatching { s2.close() }
            }
        }
    }

    @Test(timeout = 60_000)
    fun `a chrooted sftp-only account can transfer files but gets no shell`() {
        DockerMatrix.requireMatrix()
        DockerMatrix.connect(
            newStore(),
            DockerMatrix.HARDENED_PORT,
            DockerMatrix.SFTP_ONLY_USER,
            password = DockerMatrix.SFTP_ONLY_PASSWORD,
        ).use { ssh ->
            val sftp = ssh.newSFTPClient()
            try {
                // ChrootDirectory: the account's whole world is the chroot, so
                // home canonicalises to "/" and only the writable upload dir shows
                assertEquals("/", sftp.canonicalize("."))
                assertTrue(
                    "chroot root should list the upload dir: ${sftp.ls("/").map { it.name }}",
                    sftp.ls("/").any { it.name == "upload" },
                )
                val payload = ByteArray(20_000) { i -> ((i * 41 + 7) and 0xFF).toByte() }
                val local = tmp.newFile("chroot.bin").apply { writeBytes(payload) }
                val back = tmp.newFile("chroot.back")
                sftp.getFileTransfer().upload(local.absolutePath, "/upload/chroot.bin")
                sftp.getFileTransfer().download("/upload/chroot.bin", back.absolutePath)
                org.junit.Assert.assertArrayEquals(payload, back.readBytes())
                sftp.rm("/upload/chroot.bin")
            } finally {
                sftp.close()
            }
            // ForceCommand internal-sftp: an ordinary exec does NOT run a shell
            // command, so it can never echo the marker back
            val out = DockerMatrix.exec(ssh, "echo SHELL_MARKER")
            assertTrue("sftp-only account must not run a shell command: '$out'", !out.contains("SHELL_MARKER"))
        }
    }
}
