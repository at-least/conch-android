package at.least.conch

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.file.Files

/**
 * SOCKS5 wire tests against the in-process sshd: a raw client socket (and,
 * in one case, the JDK's own SOCKS client) performs the real greeting/
 * request handshake with [SocksProxy], which bridges to a direct-tcpip
 * channel on the shared SSHClient. Covers happy paths, every reply-status
 * error path, and malformed greetings.
 *
 * The IPv6 (ATYP 0x04) branch has no loopback target in the JVM harness
 * and is covered by inspection only.
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

    /** Server + trusted client + echo target + proxy on an ephemeral port. */
    private fun startProxy(): Int {
        server = TestSshd().start()
        ssh = connectTrusted(server, KnownHostsStore(dir))
        echo = EchoServer()
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

    private fun request(out: OutputStream, cmd: Int, atyp: Int, addr: ByteArray, port: Int) {
        val bytes = byteArrayOf(5, cmd.toByte(), 0, atyp.toByte()) + addr +
            byteArrayOf((port shr 8).toByte(), port.toByte())
        out.write(bytes)
        out.flush()
    }

    /** Reads a full 10-byte reply; returns the REP status byte. */
    private fun readReply(inp: InputStream): Int = readN(inp, 10)[1].toInt()

    /** Writes [payload] and asserts it comes back through the tunnel. */
    private fun assertEchoed(sock: Socket, payload: String) {
        sock.getOutputStream().apply {
            write(payload.toByteArray())
            flush()
        }
        assertArrayEquals(payload.toByteArray(), readN(sock.getInputStream(), payload.length))
    }

    @Test(timeout = 30_000)
    fun `connect by ipv4 address bridges bytes to the target`() {
        val bound = startProxy()
        val sock = connect(bound)
        greet(sock)

        request(sock.getOutputStream(), 1, 0x01, byteArrayOf(127, 0, 0, 1), echo!!.port)
        assertEquals("CONNECT must succeed", 0, readReply(sock.getInputStream()))
        assertEchoed(sock, "socks says hi")
    }

    @Test(timeout = 30_000)
    fun `connect by hostname resolves server-side`() {
        val bound = startProxy()
        val sock = connect(bound)
        greet(sock)

        // ATYP 0x03 + "localhost": the sshd (server side) resolves it
        val host = "localhost".toByteArray()
        request(
            sock.getOutputStream(),
            1,
            0x03,
            byteArrayOf(host.size.toByte()) + host,
            echo!!.port,
        )
        assertEquals("domain CONNECT must succeed", 0, readReply(sock.getInputStream()))
        assertEchoed(sock, "via domain")
    }

    @Test(timeout = 30_000)
    fun `non-CONNECT command is refused with 0x07`() {
        val bound = startProxy()
        val sock = connect(bound)
        greet(sock)

        request(sock.getOutputStream(), cmd = 2, atyp = 0x01, addr = byteArrayOf(127, 0, 0, 1), port = 80)
        assertEquals("command not supported must be 0x07", 0x07, readReply(sock.getInputStream()))
    }

    @Test(timeout = 30_000)
    fun `unsupported address type is refused with 0x08`() {
        val bound = startProxy()
        val sock = connect(bound)
        greet(sock)

        request(sock.getOutputStream(), cmd = 1, atyp = 0x05, addr = ByteArray(0), port = 80)
        assertEquals("unknown ATYP must be refused", 0x08, readReply(sock.getInputStream()))
    }

    @Test(timeout = 30_000)
    fun `unreachable target replies 0x04`() {
        val bound = startProxy()
        val sock = connect(bound)
        greet(sock)

        // nothing listens on 127.0.0.1:1 — the direct-tcpip open must fail
        request(sock.getOutputStream(), cmd = 1, atyp = 0x01, addr = byteArrayOf(127, 0, 0, 1), port = 1)
        assertEquals("host unreachable must be 0x04", 0x04, readReply(sock.getInputStream()))
    }

    @Test(timeout = 30_000)
    fun `non-socks5 greeting is dropped silently`() {
        val bound = startProxy()
        val sock = connect(bound)

        sock.getOutputStream().apply {
            write(byteArrayOf(4, 1, 0)) // SOCKS4 greeting
            flush()
        }
        // FIN gives read()==-1; a RST close surfaces as SocketException —
        // both mean "dropped without a reply"
        val dropped = try {
            sock.getInputStream().read()
        } catch (_: java.net.SocketException) {
            -1
        }
        assertEquals("connection must close without a reply", -1, dropped)
    }

    @Test(timeout = 30_000)
    fun `jdk socks client works end to end`() {
        val bound = startProxy()
        val sock = Socket(
            java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", bound)),
        )
        sock.soTimeout = 10_000
        client = sock
        sock.connect(java.net.InetSocketAddress("127.0.0.1", echo!!.port), 10_000)
        assertEchoed(sock, "jdk socks")
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
