package at.least.conch

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
 * SshSession.startTunnels() builds it, plus the app's hand-rolled SOCKS5
 * proxy on top of direct connections.
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
        startForward(echo.port)
        val payload = ByteArray(256 * 1024) { (it * 31 + 7).toByte() }
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

    /** Consumes the remaining 8 bytes of a 10-byte SOCKS5 reply (after VER+REP). */
    private fun drainReplyTail(input: InputStream) {
        val tail = ByteArray(8)
        var off = 0
        while (off < 8) {
            val n = input.read(tail, off, 8 - off)
            if (n < 0) fail("reply tail truncated")
            off += n
        }
    }

    @Test(timeout = 30_000)
    fun `socks proxy bridges ipv4 connect to echo server`() {
        val proxy = SocksProxy(ssh)
        try {
            val bound = proxy.start(0)
            Socket("127.0.0.1", bound).use { s ->
                s.soTimeout = 10_000
                val out = s.getOutputStream()
                val input = s.getInputStream()

                // greeting: NO-AUTH
                out.write(byteArrayOf(5, 1, 0))
                out.flush()
                assertEquals(5, input.read())
                assertEquals(0, input.read())

                // CONNECT 127.0.0.1:<echo>
                val p = echo.port
                out.write(
                    byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (p shr 8).toByte(), p.toByte()),
                )
                out.flush()
                assertEquals(5, input.read())
                assertEquals("reply must report success", 0, input.read())
                drainReplyTail(input)

                val payload = "socks says hi".toByteArray()
                out.write(payload)
                out.flush()
                val buf = ByteArray(payload.size)
                val acc = ByteArrayOutputStream()
                while (acc.size() < payload.size) {
                    val n = input.read(buf)
                    if (n < 0) break
                    acc.write(buf, 0, n)
                }
                assertEquals("socks says hi", acc.toString())
            }
        } finally {
            proxy.stop()
        }
    }

    @Test(timeout = 30_000)
    fun `socks proxy resolves domain atyp targets`() {
        val proxy = SocksProxy(ssh)
        try {
            val bound = proxy.start(0)
            Socket("127.0.0.1", bound).use { s ->
                s.soTimeout = 10_000
                val out = s.getOutputStream()
                val input = s.getInputStream()

                out.write(byteArrayOf(5, 1, 0))
                out.flush()
                input.read(); input.read()

                val host = "localhost".toByteArray()
                val p = echo.port
                out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()))
                out.write(host)
                out.write(byteArrayOf((p shr 8).toByte(), p.toByte()))
                out.flush()
                assertEquals(5, input.read())
                assertEquals("domain CONNECT must succeed", 0, input.read())
                drainReplyTail(input)

                out.write("via domain".toByteArray())
                out.flush()
                val expect = "via domain".toByteArray()
                val got = ByteArray(expect.size)
                var off = 0
                while (off < got.size) {
                    val n = input.read(got, off, got.size - off)
                    if (n < 0) fail("echo truncated after ${off} bytes")
                    off += n
                }
                assertEquals("via domain", String(got))
            }
        } finally {
            proxy.stop()
        }
    }

    @Test(timeout = 30_000)
    fun `socks proxy rejects non-connect commands`() {
        val proxy = SocksProxy(ssh)
        try {
            val bound = proxy.start(0)
            Socket("127.0.0.1", bound).use { s ->
                s.soTimeout = 10_000
                val out = s.getOutputStream()
                val input = s.getInputStream()

                out.write(byteArrayOf(5, 1, 0))
                out.flush()
                input.read(); input.read()

                // BIND (0x02) is not supported
                out.write(byteArrayOf(5, 2, 0, 1, 127, 0, 0, 1, 0, 80))
                out.flush()
                assertEquals(5, input.read())
                assertEquals("command not supported must be 0x07", 0x07, input.read())
            }
        } finally {
            proxy.stop()
        }
    }

    @Test(timeout = 30_000)
    fun `socks proxy reports unreachable targets`() {
        val proxy = SocksProxy(ssh)
        try {
            val bound = proxy.start(0)
            Socket("127.0.0.1", bound).use { s ->
                s.soTimeout = 10_000
                val out = s.getOutputStream()
                val input = s.getInputStream()

                out.write(byteArrayOf(5, 1, 0))
                out.flush()
                input.read(); input.read()

                // port 1 on loopback: nothing listens there
                out.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, 0, 1))
                out.flush()
                assertEquals(5, input.read())
                assertEquals("host unreachable must be 0x04", 0x04, input.read())
            }
        } finally {
            proxy.stop()
        }
    }

    @Test(timeout = 30_000)
    fun `socks proxy works with the jdk socks client`() {
        val proxy = SocksProxy(ssh)
        try {
            val bound = proxy.start(0)
            val sock = Socket(
                java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", bound)),
            )
            sock.soTimeout = 10_000
            try {
                sock.connect(java.net.InetSocketAddress("127.0.0.1", echo.port), 10_000)
                sock.getOutputStream().apply { write("jdk socks".toByteArray()); flush() }
                val got = ByteArray("jdk socks".toByteArray().size)
                var off = 0
                while (off < got.size) {
                    val n = sock.getInputStream().read(got, off, got.size - off)
                    if (n < 0) fail("stream closed early")
                    off += n
                }
                assertEquals("jdk socks", String(got))
            } finally {
                runCatching { sock.close() }
            }
        } finally {
            proxy.stop()
        }
    }
}
