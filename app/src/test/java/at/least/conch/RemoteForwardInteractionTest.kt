package at.least.conch

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Remote (-R) port forwarding: the exact bind() + SocketForwardingConnectListener
 * wiring SshSession.startTunnels() builds for remote tunnels. The server binds
 * the port; connections into it are bridged to a target resolved on the
 * client (phone) side — `ssh -R` semantics. Parity driver: ConnectBot
 * port-forwarding gaps (#1396/#1725), Termius background-tunnel complaints.
 */
class RemoteForwardInteractionTest {

    private lateinit var dir: File
    private lateinit var server: TestSshd
    private lateinit var echo: EchoServer
    private lateinit var ssh: SSHClient

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conch-rfwd").toFile()
        server = TestSshd().start()
        echo = EchoServer()
        ssh = connectTrusted(server, KnownHostsStore(dir))
    }

    @After
    fun tearDown() {
        runCatching { ssh.disconnect() }
        echo.close()
        server.close()
        dir.deleteRecursively()
    }

    @Test
    fun `connection into the server-bound port reaches the phone-side target`() {
        // mirrors SshSession.startTunnels() remote branch; port 0 = ephemeral
        val bound = ssh.remotePortForwarder.bind(
            RemotePortForwarder.Forward("127.0.0.1", 0),
            SocketForwardingConnectListener(InetSocketAddress("127.0.0.1", echo.port)),
        )
        val boundPort = bound.port
        assertTrue("server bound nothing", boundPort > 0)

        Socket("127.0.0.1", boundPort).use { sock ->
            sock.soTimeout = TimeUnit.SECONDS.toMillis(10).toInt()
            val payload = "hello-through-reverse-tunnel".toByteArray()
            sock.getOutputStream().write(payload)
            sock.getOutputStream().flush()
            val buf = ByteArray(payload.size)
            var read = 0
            while (read < buf.size) {
                val n = sock.getInputStream().read(buf, read, buf.size - read)
                assertTrue("echo stream ended early", n >= 0)
                read += n
            }
            assertArrayEquals(payload, buf)
        }
    }

    @Test
    fun `cancel tears the server-side bind down`() {
        val bound = ssh.remotePortForwarder.bind(
            RemotePortForwarder.Forward("127.0.0.1", 0),
            SocketForwardingConnectListener(InetSocketAddress("127.0.0.1", echo.port)),
        )
        ssh.remotePortForwarder.cancel(bound)
        val refused = try {
            Socket("127.0.0.1", bound.port).use { it.getInputStream().read() }
            false
        } catch (_: Exception) {
            true
        }
        assertTrue("port still accepting after cancel", refused)
    }
}
