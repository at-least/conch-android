package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class PortKnockerTest {

    @Test
    fun `parse accepts comma and whitespace separators and drops junk`() {
        assertEquals(listOf(7000, 8000, 9000), PortKnocker.parse("7000, 8000 9000"))
        assertEquals(listOf(22), PortKnocker.parse(" 22,abc,0,65536,-1,\n"))
        assertEquals(emptyList<Int>(), PortKnocker.parse(""))
    }

    @Test
    fun `format is the iOS editor spelling`() {
        assertEquals("7000, 8000, 9000", PortKnocker.format(listOf(7000, 8000, 9000)))
        assertEquals("", PortKnocker.format(emptyList()))
    }

    @Test
    fun `knock sends one datagram per port in order`() {
        val a = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val b = DatagramSocket(0, InetAddress.getLoopbackAddress())
        try {
            a.soTimeout = 3000
            b.soTimeout = 3000
            val sent = PortKnocker.knock("127.0.0.1", listOf(a.localPort, b.localPort), gapMs = 10)
            assertEquals(2, sent)
            val pa = DatagramPacket(ByteArray(64), 64).also { a.receive(it) }
            val pb = DatagramPacket(ByteArray(64), 64).also { b.receive(it) }
            assertEquals("conch", String(pa.data, 0, pa.length, Charsets.US_ASCII))
            assertEquals("conch", String(pb.data, 0, pb.length, Charsets.US_ASCII))
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun `unresolvable host and empty sequence send nothing and never throw`() {
        assertEquals(0, PortKnocker.knock("no-such-host.invalid", listOf(1000), gapMs = 0))
        assertEquals(0, PortKnocker.knock("127.0.0.1", emptyList()))
        assertEquals(0, PortKnocker.knock("", listOf(1000)))
    }
}
