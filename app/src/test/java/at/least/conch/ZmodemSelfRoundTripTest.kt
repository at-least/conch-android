package at.least.conch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Our sender talking to our receiver. The receiver advertises no CANFC32,
 * so this is the only exercise of the sender's 16-bit frames and
 * subpackets (lrzsz's rz always negotiates CRC32) — including header and
 * CRC bytes that happen to be XON/XOFF/ZDLE and must travel escaped.
 */
class ZmodemSelfRoundTripTest {

    private val hostileBytes = byteArrayOf(0x11, 0x13, 0x18, 0x10, 0x90.toByte(), 0x91.toByte(), 0x93.toByte(), 0x7F)

    /** Every escapable byte, long enough for several subpackets and a non-zero ZEOF position. */
    private fun hostileData() = ByteArray(5_000) { i ->
        if (i % 9 < hostileBytes.size) hostileBytes[i % 9] else (i * 31 and 0xFF).toByte()
    }

    private class Received {
        val bytes = ByteArrayOutputStream()
        var name = ""
        var complete = false

        fun take(events: List<ZmodemReceiver.Event>) {
            for (e in events) {
                when (e) {
                    is ZmodemReceiver.Event.Offered -> name = e.name
                    is ZmodemReceiver.Event.Data -> bytes.write(e.chunk)
                    is ZmodemReceiver.Event.Complete -> complete = true
                    is ZmodemReceiver.Event.Failed -> throw AssertionError("receiver failed: ${e.reason}")
                    else -> {}
                }
            }
        }
    }

    @Test
    fun `16-bit path round-trips a hostile binary through both engines`() {
        val data = hostileData()
        val rx = ZmodemReceiver()
        val tx = ZmodemSender()
        val got = Received()

        // the remote "rz" side: our sender waits for ZRINIT, which our
        // receiver emits in answer to ZRQINIT
        val ready = tx.feed(rx.feed(ZmodemReceiver.hexFrame(ZmodemReceiver.ZRQINIT)).send)
        assertTrue(ready.events.contains(ZmodemSender.Event.Ready))
        var toRx = tx.begin("hostile.bin", data)

        var rounds = 0
        while (rounds++ < 40) {
            val r = rx.feed(toRx)
            got.take(r.events)
            val t = tx.feed(r.send)
            t.events.filterIsInstance<ZmodemSender.Event.Failed>().firstOrNull()?.let {
                throw AssertionError("sender failed: ${it.reason}")
            }
            toRx = t.send
            if (tx.isDone && toRx.isEmpty()) break
        }
        assertTrue("sender must finish", tx.isDone)
        assertTrue("receiver must report completion", got.complete)
        assertEquals("hostile.bin", got.name)
        assertArrayEquals(data, got.bytes.toByteArray())
        // and the receiver is back to plain terminal duty (the sender's own
        // "OO" sign-off, if any, was already fed through the loop above)
        val after = rx.feed("\r\n$ ".toByteArray())
        assertEquals("\r\n$ ", String(after.display))
    }

    @Test
    fun `16-bit subpacket CRC bytes are escaped when they are XON XOFF or ZDLE`() {
        // find a payload whose CRC16 has a hostile byte, then check the
        // wire form never carries it raw
        val hostileSet = setOf(0x10, 0x11, 0x13, 0x18, 0x90, 0x91, 0x93)
        var found = 0
        for (n in 0 until 5_000) {
            val payload = "chunk-$n".toByteArray()
            val crc = ZmodemReceiver.crc16(payload + byteArrayOf(ZmodemReceiver.ZCRCG.toByte()))
            val hi = (crc shr 8) and 0xFF
            val lo = crc and 0xFF
            if (hi !in hostileSet && lo !in hostileSet) continue
            found++
            val wire = ZmodemSender.subpacket(payload, ZmodemReceiver.ZCRCG)
            var i = 0
            while (i < wire.size) {
                val b = wire[i].toInt() and 0xFF
                if (b == ZmodemReceiver.ZDLE) {
                    i += 2 // escape pair or terminator — both consume the next byte
                    continue
                }
                assertTrue(
                    "raw hostile byte 0x${b.toString(16)} at $i",
                    b !in hostileSet,
                )
                i++
            }
        }
        assertTrue("test needs at least one hostile CRC to be meaningful", found > 0)
    }
}
