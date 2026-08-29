package at.least.conch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receiver's life AROUND a transfer: what happens to the terminal
 * stream after ZFIN, after a failure, and across two `sz` runs on one
 * instance — the view keeps a single receiver, so a receiver that stayed
 * "done" after the first download swallowed every later byte of shell
 * output (the terminal went dark until the transfer was cancelled).
 */
class ZmodemReceiverLifecycleTest {

    private fun zrqinit() = ZmodemReceiver.hexFrame(ZmodemReceiver.ZRQINIT)

    @Test
    fun `after ZFIN the shell's output flows again and sz's OO sign-off is swallowed`() {
        val rx = ZmodemReceiver()
        val started = rx.feed(zrqinit())
        assertTrue(started.active)
        assertTrue(started.events.any { it is ZmodemReceiver.Event.Started })

        val fin = rx.feed(ZmodemReceiver.hexFrame(ZmodemReceiver.ZFIN))
        assertFalse("ZFIN ends the transfer", fin.active)
        assertArrayEquals(
            "receiver answers ZFIN with ZFIN and nothing else (OO is the sender's line)",
            ZmodemReceiver.hexFrame(ZmodemReceiver.ZFIN),
            fin.send,
        )

        val after = rx.feed("OO$ ls\r\n".toByteArray())
        assertEquals("$ ls\r\n", String(after.display))
        assertFalse(after.active)
    }

    @Test
    fun `lrzsz's CR 0x8A hex-frame terminator never reaches the screen`() {
        // zm.c zsendhhdr ends every hex header with CR and LF|0x80. The
        // receiver used to stop skipping at 0x0A, leaving 0x8A in the buffer
        // to be shown as a stray byte after the transfer.
        val rx = ZmodemReceiver()
        rx.feed(zrqinit())
        val ours = ZmodemReceiver.hexFrame(ZmodemReceiver.ZFIN)
        assertEquals(0x0A, ours.last().toInt() and 0xFF)
        val lrzszFin = ours.copyOf(ours.size - 1) + 0x8A.toByte()
        val fin = rx.feed(lrzszFin)
        assertFalse(fin.active)
        assertEquals("nothing of the frame is shown", "", String(fin.display))
        // without the fix the leftover 0x8A also stopped OO from being recognised
        val out = rx.feed("OO$ ls\r\n".toByteArray())
        assertEquals("$ ls\r\n", String(out.display))
    }

    @Test
    fun `OO is only swallowed when it is the very next thing sz says`() {
        val rx = ZmodemReceiver()
        rx.feed(zrqinit())
        rx.feed(ZmodemReceiver.hexFrame(ZmodemReceiver.ZFIN))
        val out = rx.feed("prompt> OOPS\r\n".toByteArray())
        assertEquals("prompt> OOPS\r\n", String(out.display))
    }

    @Test
    fun `one receiver notices a second sz after the first finished`() {
        val rx = ZmodemReceiver()
        rx.feed(zrqinit())
        rx.feed(ZmodemReceiver.hexFrame(ZmodemReceiver.ZFIN))
        rx.feed("OO\r\n$ sz other.bin\r\n".toByteArray())

        val again = rx.feed(zrqinit())
        assertTrue("second transfer must be detected", again.active)
        assertTrue(again.events.any { it is ZmodemReceiver.Event.Started })
        assertArrayEquals(ZmodemReceiver.zrinitBytes(), again.send)
    }

    @Test
    fun `a remote abort returns to sniffing instead of eating the terminal`() {
        val rx = ZmodemReceiver()
        rx.feed(zrqinit())
        val aborted = rx.feed(ZmodemReceiver.hexFrame(ZmodemReceiver.ZABORT))
        assertTrue(aborted.events.any { it is ZmodemReceiver.Event.Failed })
        assertFalse(aborted.active)

        val out = rx.feed("back at the shell\r\n".toByteArray())
        assertEquals("back at the shell\r\n", String(out.display))
    }

    @Test
    fun `position headers are little-endian like lrzsz`() {
        val rx = ZmodemReceiver()
        // ZP0 is the low byte: 768 -> 00 03 00 00
        assertArrayEquals(intArrayOf(0x00, 0x03, 0x00, 0x00), rx.pos4(768))
        assertEquals(768L, rx.hdrToLong(intArrayOf(0x00, 0x03, 0x00, 0x00)))
        // lrzsz observed: ZEOF for a 19-byte file carries 13 00 00 00
        assertEquals(19L, rx.hdrToLong(intArrayOf(0x13, 0, 0, 0)))
        for (v in longArrayOf(0, 1, 255, 256, 70_000, 0x7FFFFFFF)) {
            assertEquals(v, rx.hdrToLong(rx.pos4(v)))
        }
    }

    @Test
    fun `ZRINIT carries its capability flags in ZF0 and an unlimited buffer`() {
        // hex frame: "**" ZDLE 'B' then hex(type, hdr0..3, crc16) CR LF
        val frame = String(ZmodemReceiver.zrinitBytes(), Charsets.ISO_8859_1)
        val hex = frame.removePrefix("**B").removeSuffix("\r\n")
        val bytes = hex.chunked(2).map { it.toInt(16) }
        assertEquals(ZmodemReceiver.ZRINIT, bytes[0])
        assertEquals("ZP0/ZP1 = receive buffer size, 0 = stream everything", 0, bytes[1] or bytes[2])
        assertEquals(
            "ZF0 (header byte 3) = CANFDX|CANOVIO",
            ZmodemReceiver.CANFDX or ZmodemReceiver.CANOVIO,
            bytes[4],
        )
    }

    @Test
    fun `subpacket terminators match zmodem_h`() {
        assertEquals('h'.code, ZmodemReceiver.ZCRCE)
        assertEquals('i'.code, ZmodemReceiver.ZCRCG)
        assertEquals('j'.code, ZmodemReceiver.ZCRCQ)
        assertEquals('k'.code, ZmodemReceiver.ZCRCW)
    }
}
