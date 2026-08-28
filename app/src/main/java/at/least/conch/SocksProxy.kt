package at.least.conch

import net.schmizz.sshj.SSHClient
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal SOCKS5 server (CONNECT only, no-auth) bridging each client
 * connection to a direct-tcpip SSH channel. Pure Kotlin apart from sockets.
 *
 * Wire subset implemented: greeting (05 01 00), request header
 * (05 01 00 ATYP ADDR PORT), replies. Enough for browsers/curl.
 */
class SocksProxy(private val client: SSHClient) {

    private var serverSocket: ServerSocket? = null
    private val stopped = AtomicBoolean(false)
    private var acceptThread: Thread? = null

    /** Starts listening on 127.0.0.1:[port] (port 0 = ephemeral). */
    fun start(port: Int): Int {
        val ss = ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        acceptThread = Thread {
            while (!stopped.get()) {
                val sock = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                Thread {
                    try {
                        handle(sock)
                    } catch (_: Exception) {
                    } finally {
                        runCatching { sock.close() }
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply {
            isDaemon = true
            name = "socks-accept"
        }
        acceptThread?.start()
        return ss.localPort
    }

    fun stop() {
        stopped.set(true)
        runCatching { serverSocket?.close() }
    }

    private fun handle(sock: Socket) {
        val input = sock.getInputStream()
        val output = sock.getOutputStream()

        // greeting: VER NMETHODS METHODS*
        val ver = input.read()
        if (ver != 5) return
        val n = input.read()
        if (n < 0) return
        val methods = ByteArray(n)
        readFully(input, methods)
        // we only support NO-AUTH (0x00)
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        // request: VER CMD RSV ATYP DST.ADDR DST.PORT
        if (input.read() != 5) return
        val cmd = input.read()
        input.read() // RSV
        val atyp = input.read()
        if (cmd != 1) { // only CONNECT
            reply(output, 0x07)
            return
        }
        val host = readAddress(input, atyp)
        if (host == null) {
            reply(output, 0x08) // address type not supported
            return
        }
        val portHi = input.read()
        val portLo = input.read()
        if (portHi < 0 || portLo < 0) return
        val port = (portHi shl 8) or portLo

        // open a direct-tcpip channel through SSH
        val chan = try {
            client.newDirectConnection(host, port)
        } catch (_: Exception) {
            reply(output, 0x04) // host unreachable
            return
        }
        try {
            reply(output, 0x00) // succeeded

            // bridge both directions; closing one side closes the other
            val t1 = pump(sock.getInputStream(), chan.outputStream)
            val t2 = pump(chan.inputStream, output)
            t1.join()
            t2.join()
        } finally {
            // a client that hung up before the reply must not leave the
            // direct-tcpip channel open on the shared SSH connection
            runCatching { sock.close() }
            runCatching { chan.close() }
        }
    }

    /** DST.ADDR for [atyp]; null for an unsupported type (or a truncated domain length). */
    private fun readAddress(input: InputStream, atyp: Int): String? = when (atyp) {
        0x01 -> {
            val b = ByteArray(4)
            readFully(input, b)
            b.joinToString(".") { (it.toInt() and 0xFF).toString() }
        }
        0x03 -> {
            val len = input.read()
            if (len < 0) {
                null
            } else {
                val b = ByteArray(len)
                readFully(input, b)
                String(b)
            }
        }
        0x04 -> {
            val b = ByteArray(16)
            readFully(input, b)
            // build ipv6 text
            buildString {
                for (i in 0 until 8) {
                    if (i > 0) append(':')
                    append(
                        String.format(
                            Locale.ROOT,
                            "%x",
                            ((b[i * 2].toInt() and 0xFF) shl 8) or (b[i * 2 + 1].toInt() and 0xFF)
                        )
                    )
                }
            }
        }
        else -> null
    }

    private fun reply(output: OutputStream, status: Int) {
        output.write(byteArrayOf(0x05, status.toByte(), 0, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    private fun pump(from: InputStream, to: OutputStream): Thread =
        Thread {
            try {
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        to.write(buf, 0, n)
                        to.flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                // signal EOF so the other side's read() unblocks
                runCatching { (to as? java.io.Closeable)?.close() }
            }
        }.apply {
            isDaemon = true
            start()
        }
}
