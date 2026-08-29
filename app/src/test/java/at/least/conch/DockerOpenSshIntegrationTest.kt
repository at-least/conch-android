package at.least.conch

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Real-OpenSSH integration coverage that the in-process MINA server cannot
 * give (its SFTP, forwarding and PTY are MINA's own implementations) and
 * that subprocess interop tests (Zmodem*InteropTest) only cover over pipes:
 * SFTP against internal-sftp, L/-R tunnels against a forwarding-enabled
 * sshd, PTY semantics, real tmux, and ZMODEM end-to-end through a real SSH
 * PTY with the real lrzsz binaries.
 *
 * Same opt-in as [DockerSshdAuthTest] (see [DockerMatrix]):
 *   tools/sshd-matrix/run.sh
 *   ./gradlew testFossDebugUnitTest -Dconch.localSshdTest=true \
 *       --tests '*.DockerOpenSshIntegrationTest'
 */
class DockerOpenSshIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore() = KnownHostsStore(tmp.newFolder())

    private fun connectPw(port: Int = DockerMatrix.PW_AND_KEY_PORT): SSHClient =
        DockerMatrix.connectPw(newStore(), port)

    @Test
    fun `sftp round-trips a file against real internal-sftp`() {
        DockerMatrix.requireMatrix()
        connectPw().use { ssh ->
            val sftp = ssh.newSFTPClient()
            try {
                val name = "conch-sftp-roundtrip.bin"
                val payload = ByteArray(70_000) { i -> ((i * 31 + 7) and 0xFF).toByte() }
                val local = File.createTempFile("conch-sftp", ".bin").apply { writeBytes(payload) }
                sftp.getFileTransfer().upload(local.absolutePath, name)
                assertTrue(
                    "uploaded file not listed in home: ${sftp.ls(".").map { it.name }}",
                    sftp.ls(".").any { it.name == name },
                )
                val back = File.createTempFile("conch-sftp", ".back")
                sftp.getFileTransfer().download(name, back.absolutePath)
                assertArrayEquals(payload, back.readBytes())
                sftp.rm(name)
                local.delete()
                back.delete()
            } finally {
                sftp.close()
            }
        }
    }

    @Test
    fun `local tunnel bridges direct-tcpip to a container service`() {
        DockerMatrix.requireMatrix()
        connectPw(DockerMatrix.FORWARDING_PORT).use { ssh ->
            val entry = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            // exactly what SshSession.startTunnels() builds for a local tunnel
            val lpf = ssh.newLocalPortForwarder(
                Parameters(
                    "127.0.0.1",
                    entry.localPort,
                    "127.0.0.1",
                    DockerMatrix.CONTAINER_SSH_PORT,
                ),
                entry,
            )
            thread(isDaemon = true) { runCatching { lpf.listen() } }
            try {
                Socket("127.0.0.1", entry.localPort).use { s ->
                    s.soTimeout = 10_000
                    val banner = BufferedReader(InputStreamReader(s.getInputStream())).readLine()
                    // target is the container's own :2223 sshd → its banner
                    assertTrue("unexpected banner: $banner", banner.startsWith("SSH-2.0-OpenSSH"))
                }
            } finally {
                entry.close()
            }
        }
    }

    @Test
    fun `remote tunnel bridges container connections back to this machine`() {
        DockerMatrix.requireMatrix()
        val echo = EchoServer()
        connectPw(DockerMatrix.FORWARDING_PORT).use { ssh ->
            // exactly what SshSession.startTunnels() builds for -R
            val bound = ssh.remotePortForwarder.bind(
                RemotePortForwarder.Forward("127.0.0.1", 18923),
                SocketForwardingConnectListener(InetSocketAddress("127.0.0.1", echo.port)),
            )
            try {
                // from inside the container: nc to the -R port, payload must
                // come back through the SSH client to the JVM echo server
                val out = DockerMatrix.exec(ssh, "echo remote-rw-OK | nc -q 3 127.0.0.1 18923")
                assertTrue("no echo through -R tunnel: '$out'", out.contains("remote-rw-OK"))
            } finally {
                runCatching { ssh.remotePortForwarder.cancel(bound) }
                echo.close()
            }
        }
    }

    @Test
    fun `real pty receives TERM and dimensions from the pty request`() {
        DockerMatrix.requireMatrix()
        connectPw().use { ssh ->
            DockerMatrix.withPtyShell(ssh, cols = 120, rows = 40) { shell ->
                // the echo of this command line contains the QUOTED marker,
                // only real output produces the unquoted one
                writeShell(shell, "echo TERM=\$TERM; stty size; echo PTY'DONE'\r")
                val acc = readUntil(shell.inputStream, "PTYDONE")
                assertTrue("TERM missing: $acc", acc.contains("TERM=xterm-256color"))
                assertTrue("stty size missing: $acc", acc.contains("40 120"))
            }
        }
    }

    @Test
    fun `real tmux session can be created and listed over a pty`() {
        DockerMatrix.requireMatrix()
        connectPw().use { ssh ->
            DockerMatrix.withPtyShell(ssh, cols = 120, rows = 40) { shell ->
                try {
                    writeShell(shell, "tmux new -d -s conchtest 'sleep 60'; tmux ls; echo TMX'DONE'\r")
                    val acc = readUntil(shell.inputStream, "TMXDONE")
                    assertTrue("tmux session not listed: $acc", acc.contains("conchtest"))
                } finally {
                    runCatching { writeShell(shell, "tmux kill-session -t conchtest\r") }
                }
            }
        }
    }

    @Test
    fun `zmodem download runs over a real ssh pty with real sz`() {
        DockerMatrix.requireMatrix()
        connectPw().use { ssh ->
            // random 64KB server-side — hostile to any escaping bug
            val serverSha = DockerMatrix.exec(
                ssh,
                "head -c 65536 /dev/urandom > /tmp/zdown.bin && sha256sum /tmp/zdown.bin | cut -d' ' -f1",
            ).trim()
            assertEquals("server fixture not created", 64, serverSha.length)

            val session = ssh.startSession()
            session.allocatePTY("xterm-256color", 120, 40, 0, 0, emptyMap())
            val shell = session.startShell()
            try {
                val engine = ZmodemReceiver()
                var name: String? = null
                val data = java.io.ByteArrayOutputStream()
                val complete = CountDownLatch(1)
                val reader = thread(isDaemon = true) {
                    try {
                        val buf = ByteArray(8192)
                        val input = shell.inputStream
                        val output = shell.outputStream
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            val res = engine.feed(buf.copyOf(n))
                            if (res.send.isNotEmpty()) {
                                synchronized(output) {
                                    output.write(res.send)
                                    output.flush()
                                }
                            }
                            for (e in res.events) {
                                when (e) {
                                    is ZmodemReceiver.Event.Offered -> name = e.name
                                    is ZmodemReceiver.Event.Data -> data.write(e.chunk)
                                    is ZmodemReceiver.Event.Complete -> complete.countDown()
                                    else -> {}
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                writeShell(shell, "sz /tmp/zdown.bin\r")
                assertTrue("ZMODEM download never completed", complete.await(60, TimeUnit.SECONDS))
                reader.join(5_000)

                assertEquals("zdown.bin", name)
                assertEquals(serverSha, sha256Hex(data.toByteArray()))
            } finally {
                runCatching { writeShell(shell, "rm -f /tmp/zdown.bin\r") }
                session.close()
            }
        }
    }

    @Test
    fun `zmodem upload runs over a real ssh pty with real rz`() {
        DockerMatrix.requireMatrix()
        connectPw().use { ssh ->
            runCatching { DockerMatrix.exec(ssh, "rm -f /tmp/zup.bin") }
            val payload = ByteArray(50_000) { i -> ((i * 37 + 11) and 0xFF).toByte() }
            val localSha = sha256Hex(payload)

            val session = ssh.startSession()
            session.allocatePTY("xterm-256color", 120, 40, 0, 0, emptyMap())
            val shell = session.startShell()
            try {
                val sender = ZmodemSender()
                val complete = CountDownLatch(1)
                val reader = thread(isDaemon = true) {
                    try {
                        val buf = ByteArray(8192)
                        val input = shell.inputStream
                        val output = shell.outputStream
                        var begun = false
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            val res = sender.feed(buf.copyOf(n))
                            if (res.events.any { it is ZmodemSender.Event.Ready } && !begun) {
                                begun = true
                                synchronized(output) {
                                    output.write(sender.begin("zup.bin", payload))
                                    output.flush()
                                }
                            }
                            if (res.send.isNotEmpty()) {
                                synchronized(output) {
                                    output.write(res.send)
                                    output.flush()
                                }
                            }
                            for (e in res.events) {
                                if (e is ZmodemSender.Event.Complete || e is ZmodemSender.Event.Failed) {
                                    complete.countDown()
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                writeShell(shell, "cd /tmp && rz -y\r")
                assertTrue("ZMODEM upload never completed", complete.await(60, TimeUnit.SECONDS))
                reader.join(5_000)

                val remoteSha = DockerMatrix.exec(ssh, "sha256sum /tmp/zup.bin | cut -d' ' -f1").trim()
                assertEquals(localSha, remoteSha)
            } finally {
                runCatching { writeShell(shell, "rm -f /tmp/zup.bin\r") }
                session.close()
            }
        }
    }

    @Test
    fun `no agent is ever forwarded to the server`() {
        // agent forwarding is offered by neither app (ProxyJump covers the
        // use case); the server must never see an SSH_AUTH_SOCK from us
        DockerMatrix.requireMatrix()
        connectPw().use { ssh ->
            val out = DockerMatrix.exec(ssh, "test -n \"\$SSH_AUTH_SOCK\" && echo SOCK || echo NO_SOCK")
            assertTrue("SSH_AUTH_SOCK set without forwarding: '$out'", out.contains("NO_SOCK"))
        }
    }

    @Test
    fun `saf backend round-trips against real openssh`() {
        DockerMatrix.requireMatrix()
        val store = newStore()
        val host = DockerMatrix.pwHost().copy(id = "saf-docker")
        val fs = SftpProviderFs(
            loadHost = { if (it == host.id) host else null },
            connectHost = { h -> DockerMatrix.connect(store, h) },
        )
        try {
            val home = fs.homePath(host.id)
            assertEquals("/home/pwuser", home)

            fs.mkdir(host.id, "$home/saf-dir")
            val payload = ByteArray(50_000) { i -> ((i * 53 + 5) and 0xFF).toByte() }
            fs.openWrite(host.id, "$home/saf-dir/t.bin").use { it.write(payload) }
            assertArrayEquals(payload, fs.openRead(host.id, "$home/saf-dir/t.bin").use { it.readBytes() })

            fs.rename(host.id, "$home/saf-dir/t.bin", "$home/saf-dir/u.bin")
            assertEquals(
                listOf("u.bin"),
                fs.list(host.id, "$home/saf-dir").map { it.displayName },
            )
            fs.delete(host.id, "$home/saf-dir/u.bin")
            fs.delete(host.id, "$home/saf-dir")
            assertTrue(fs.list(host.id, home).none { it.displayName == "saf-dir" })
        } finally {
            fs.close()
        }
    }
}
