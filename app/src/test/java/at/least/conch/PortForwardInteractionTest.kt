package at.least.conch

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * direct-tcpip channel interaction: the local port forwarder exactly as
 * SshSession.startTunnels() builds it. SOCKS5 coverage lives in
 * [SocksProxyTest].
 */
class PortForwardInteractionTest {

    private lateinit var dir: File
    private lateinit var server: TestSshd
    private lateinit var echo: EchoServer
    private lateinit var ssh: SSHClient
    private lateinit var forwardSocket: ServerSocket

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conch-fwd").toFile()
        server = TestSshd().start()
        echo = EchoServer()
        ssh = connectTrusted(server, KnownHostsStore(dir))
        forwardSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    }

    @After
    fun tearDown() {
        runCatching { forwardSocket.close() }
        runCatching { ssh.disconnect() }
        echo.close()
        server.close()
        dir.deleteRecursively()
    }

    /** Mirrors SshSession.startTunnels(). */
    private fun startForward(targetPort: Int) {
        val params = Parameters("127.0.0.1", forwardSocket.localPort, "127.0.0.1", targetPort)
        val lpf = ssh.newLocalPortForwarder(params, forwardSocket)
        thread(isDaemon = true, name = "test-lpf") {
            try {
                lpf.listen()
            } catch (_: Exception) {
            }
        }
    }

    private fun roundTrip(socket: Socket, payload: ByteArray): ByteArray {
        socket.soTimeout = 10_000
        val out: OutputStream = socket.getOutputStream()
        out.write(payload)
        out.flush()
        val input: InputStream = socket.getInputStream()
        val acc = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (acc.size() < payload.size) {
            val n = input.read(buf)
            if (n < 0) break
            acc.write(buf, 0, n)
        }
        return acc.toByteArray()
    }

    /** lpf.listen() starts async; the first connect may be refused — retry briefly. */
    private fun connectWithRetry(port: Int): Socket {
        var last: Exception? = null
        repeat(150) {
            try {
                return Socket("127.0.0.1", port)
            } catch (e: Exception) {
                last = e
                Thread.sleep(20)
            }
        }
        throw AssertionError("forwarder never accepted: $last")
    }

    @Test(timeout = 30_000)
    fun `local forward tunnels a tcp conversation`() {
        startForward(echo.port)
        connectWithRetry(forwardSocket.localPort).use { s ->
            val reply = roundTrip(s, "through the tunnel\n".toByteArray())
            assertEquals("through the tunnel\n", String(reply))
        }
    }

    @Test(timeout = 60_000)
    fun `large payload stays intact through the tunnel`() {
        val payload = ByteArray(256 * 1024) { (it * 31 + 7).toByte() }
        startForward(echo.port)
        connectWithRetry(forwardSocket.localPort).use { s ->
            assertArrayEquals(payload, roundTrip(s, payload))
        }
    }

    @Test(timeout = 30_000)
    fun `two concurrent streams through the same forwarder`() {
        startForward(echo.port)
        val payloadA = "stream-A-".repeat(500).toByteArray()
        val payloadB = "stream-B-".repeat(500).toByteArray()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val a = connectWithRetry(forwardSocket.localPort)
        val b = connectWithRetry(forwardSocket.localPort)
        try {
            val fa = thread {
                runCatching { assertArrayEquals(payloadA, roundTrip(a, payloadA)) }
                    .onFailure { errors.add("stream A: $it") }
            }
            val fb = thread {
                runCatching { assertArrayEquals(payloadB, roundTrip(b, payloadB)) }
                    .onFailure { errors.add("stream B: $it") }
            }
            fa.join(TimeUnit.SECONDS.toMillis(20))
            fb.join(TimeUnit.SECONDS.toMillis(20))
            assertTrue("workers must finish, not hang", !fa.isAlive && !fb.isAlive)
            assertTrue("worker failures: ${errors.joinToString()}", errors.isEmpty())
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }
}
