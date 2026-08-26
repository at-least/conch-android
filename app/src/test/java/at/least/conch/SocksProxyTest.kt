package at.least.conch

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.nio.file.Files

/**
 * End-to-end SOCKS5 wire tests against the in-process sshd: a raw client
 * socket performs the real greeting/request handshake with [SocksProxy],
 * which bridges to a direct-tcpip channel on the shared SSHClient. The
 * IPv6 (ATYP 0x04) branch has no loopback target in the JVM harness and
 * is covered by inspection only.
 */
class SocksProxyTest {

    private lateinit var dir: File
    private lateinit var server: TestSshd
    private var echo: EchoServer? = null
    private var ssh: net.schmizz.sshj.SSHClient? = null
    private var proxy: SocksProxy? = null
    private var client: Socket? = null

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conch-socks").toFile()
    }

    @After
    fun tearDown() {
        runCatching { client?.close() }
        runCatching { proxy?.stop() }
        runCatching { ssh?.disconnect() }
        runCatching { echo?.close() }
        if (::server.isInitialized) server.close()
        dir.deleteRecursively()
    }

    /** Server + trusted client + proxy on an ephemeral port. */
    private fun startProxy(): Int {
        server = TestSshd().start()
        ssh = connectTrusted(server, KnownHostsStore(dir))
        val p = SocksProxy(ssh!!)
        proxy = p
        return p.start(0)
    }

    private fun connect(port: Int): Socket =
        Socket("127.0.0.1", port).also {
            it.soTimeout = 10_000
            client = it
        }

    /** SOCKS5 greeting; asserts NO-AUTH (00) is selected. */
    private fun greet(sock: Socket) {
        sock.getOutputStream().apply {
            write(byteArrayOf(5, 1, 0)) // VER NMETHODS METHODS(00)
            flush()
        }
        assertArrayEquals("NO-AUTH must be selected", byteArrayOf(5, 0), readN(sock.getInputStream(), 2))
    }

    private fun request(out: java.io.OutputStream, cmd: Int, atyp: Int, addr: ByteArray, port: Int) {
        val bytes = byteArrayOf(5, cmd.toByte(), 0, atyp.toByte()) + addr +
            byteArrayOf((port shr 8).toByte(), port.toByte())
        out.write(bytes)
        out.flush()
    }

    private fun readReply(inp: InputStream): Byte = readN(inp, 10)[1]

    @Test(timeout = 60_000)
    fun `connect by ipv4 address bridges bytes to the target`() {
        val echo = EchoServer().also { this.echo = it }
        val port = startProxy()
        val sock = connect(port)
        greet(sock)

        request(sock.getOutputStream(), 1, 0x01, byteArrayOf(127, 0, 0, 1), echo.port)
        val reply = readN(sock.getInputStream(), 10)
        assertEquals("CONNECT must succeed", 0, reply[1].toInt())

        val payload = "PING-SOCKS"
        sock.getOutputStream().apply {
            write(payload.toByteArray())
            flush()
        }
        val echoed = readN(sock.getInputStream(), payload.length)
        assertArrayEquals(payload.toByteArray(), echoed)
    }

    @Test(timeout = 60_000)
    fun `connect by hostname resolves server-side`() {
        val echo = EchoServer().also { this.echo = it }
        val port = startProxy()
        val sock = connect(port)
        greet(sock)

        // ATYP 0x03 + "localhost": the sshd (server side) resolves it
        val host = "localhost".toByteArray()
        request(sock.getOutputStream(), 1, 0x03, byteArrayOf(host.size.toByte()) + host, echo.port)
        assertEquals("CONNECT via hostname must succeed", 0, readReply(sock.getInputStream()).toInt())
    }

    @Test(timeout = 60_000)
    fun `non-CONNECT command is refused with 0x07`() {
        val port = startProxy()
        val sock = connect(port)
        greet(sock)

        request(sock.getOutputStream(), cmd = 2, atyp = 0x01, addr = byteArrayOf(127, 0, 0, 1), port = 80)
        assertEquals("BIND must be refused", 0x07, readReply(sock.getInputStream()).toInt())
    }

    @Test(timeout = 60_000)
    fun `unsupported address type is refused with 0x08`() {
        val port = startProxy()
        val sock = connect(port)
        greet(sock)

        request(sock.getOutputStream(), cmd = 1, atyp = 0x05, addr = ByteArray(0), port = 80)
        assertEquals("unknown ATYP must be refused", 0x08, readReply(sock.getInputStream()).toInt())
    }

    @Test(timeout = 60_000)
    fun `unreachable target replies 0x04`() {
        val port = startProxy()
        val sock = connect(port)
        greet(sock)

        // nothing listens on 127.0.0.1:1 — the direct-tcpip open must fail
        request(sock.getOutputStream(), cmd = 1, atyp = 0x01, addr = byteArrayOf(127, 0, 0, 1), port = 1)
        assertEquals("unreachable target must map to 0x04", 0x04, readReply(sock.getInputStream()).toInt())
    }

    @Test(timeout = 60_000)
    fun `non-socks5 greeting is dropped silently`() {
        val port = startProxy()
        val sock = connect(port)

        sock.getOutputStream().apply {
            write(byteArrayOf(4, 1, 0)) // SOCKS4 greeting
            flush()
        }
        assertEquals("connection must close without a reply", -1, sock.getInputStream().read())
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("stream closed after $off of $n bytes")
            off += r
        }
        return buf
    }
}
