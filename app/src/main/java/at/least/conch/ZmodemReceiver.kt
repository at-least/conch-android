package at.least.conch

import java.io.ByteArrayOutputStream

/**
 * Pure ZMODEM receiver engine (improvement plan 2.3): downloads triggered by
 * `sz` on the remote. Byte-exact protocol core (hex/bin frames, CRC16,
 * ZDLE escaping, data subpackets) with no Android imports, pinned against
 * the REAL lrzsz `sz` binary in ZmodemInteropTest. Parity driver: Termius's
 * single most-reacted feature request (rz/sz, termius-cli#124, 36 👍).
 *
 * Only 16-bit-CRC bin frames are negotiated (no CANFC32 in our ZRINIT) —
 * the on-wire form every sz speaks. ZDLE decode rule is the canonical one:
 * the byte after ZDLE is XOR 0x40 of the payload byte; 'h'..'k' after ZDLE
 * are subpacket terminators, not payload.
 */
class ZmodemReceiver {

    companion object {
        const val ZPAD = 0x2A
        const val ZDLE = 0x18
        const val ZBIN = 0x42 // 'B'
        const val ZHEX = 0x30 // '0'

        const val ZRQINIT = 0
        const val ZRINIT = 1
        const val ZSINIT = 2
        const val ZACK = 3
        const val ZFILE = 4
        const val ZSKIP = 5
        const val ZNAK = 6
        const val ZABORT = 7
        const val ZFIN = 8
        const val ZRPOS = 9
        const val ZDATA = 10
        const val ZEOF = 11
        const val ZFERR = 12
        const val ZCRC = 13
        const val ZCHALLENGE = 14
        const val ZCOMPL = 15
        const val ZCAN = 16

        // Subpacket terminators (zmodem.h): 'j' = ZCRCQ (more follows, ZACK
        // wanted), 'k' = ZCRCW (frame ends, ZACK wanted). sz ends every
        // ZFILE subpacket with 'k'.
        const val ZCRCE = 0x68 // 'h' frame ends, no ZACK
        const val ZCRCG = 0x69 // 'i' more subpackets follow
        const val ZCRCQ = 0x6A // 'j' ZACK expected, more follows
        const val ZCRCW = 0x6B // 'k' end, ZACK expected

        const val CANFDX = 0x01
        const val CANOVIO = 0x02

        private const val HEXDIGITS = "0123456789abcdefABCDEF"

        fun isHexDigit(c: Int): Boolean = c.toChar() in HEXDIGITS

        /** CRC16-CCITT (poly 0x1021, init 0) — the ZMODEM variant. */
        fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size, crc: Int = 0): Int {
            var c = crc
            for (i in from until to) {
                c = c xor ((data[i].toInt() and 0xFF) shl 8)
                repeat(8) {
                    c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1
                    c = c and 0xFFFF
                }
            }
            return c
        }

        /**
         * ZRINIT reply advertising 16-bit CRC only (no CANFC32). Flags live
         * in ZF0 = header byte 3 (ZF0..ZF3 are ZP3..ZP0 read backwards);
         * bytes 0–1 are the receive-buffer size, 0 = unlimited (full
         * streaming, which is what our receiver does).
         */
        fun zrinitBytes(): ByteArray = hexFrame(ZRINIT, intArrayOf(0, 0, 0, CANFDX or CANOVIO))

        /**
         * Hex frame: ZPAD ZDLE 'B', 14 hex chars (type+4 bytes+CRC16), CR LF.
         * The 'B' marker is followed by ASCII hex digits (vs raw binary in a
         * bin frame) — the lrzsz wire format.
         */
        fun hexFrame(type: Int, hdr: IntArray = IntArray(4)): ByteArray {
            val body = ByteArray(5)
            body[0] = type.toByte()
            for (i in 0 until 4) body[1 + i] = hdr[i].toByte()
            val crc = crc16(body)
            val sb = StringBuilder("**")
            sb.append(ZDLE.toChar()).append('B')
            sb.append("%02x".format(type))
            for (i in 0 until 4) sb.append("%02x".format(hdr[i]))
            sb.append("%04x".format(crc))
            sb.append("\r\n")
            return sb.toString().toByteArray(Charsets.ISO_8859_1)
        }

        /** Abort sequence: ZCAN(0x18)*8 + backspaces. */
        fun cancelBytes(): ByteArray =
            ByteArray(8) { 0x18 } + byteArrayOf(0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08)
    }

    sealed class Event {
        data class Offered(val name: String, val size: Long) : Event()
        data class Data(val chunk: ByteArray) : Event()
        data class Complete(val name: String, val size: Long) : Event()
        data class Failed(val reason: String) : Event()
        data object Started : Event()

        /**
         * The remote ran `rz` and wants OUR files — the UI offers a file
         * pick, then a ZmodemSender takes over the stream. Carries the
         * receiver's CANFC32 capability so the sender adopts the right CRC
         * mode without waiting for a ZRINIT retransmit.
         */
        data class UploadRequested(val canFc32: Boolean) : Event()
    }

    data class FeedResult(
        /** bytes that are NOT part of a transfer — still meant for the display */
        val display: ByteArray,
        /** protocol bytes to write back to the remote */
        val send: ByteArray,
        val events: List<Event>,
        /** true while a transfer is in progress (caller suppresses terminal feed) */
        val active: Boolean,
    )

    /**
     * SNIFF: terminal bytes pass through until a frame start. ACTIVE: a
     * transfer is running. DONE: the stream was handed to a [ZmodemSender]
     * (remote `rz`); bytes pass through as a safety net. A finished or
     * failed download returns to SNIFF, so the same instance keeps
     * watching for the next `sz` — and never blacks out the terminal.
     */
    private enum class Phase { SNIFF, ACTIVE, DONE }

    private var phase = Phase.SNIFF
    private var buf = ByteArray(0)
    private val frameBinPattern = intArrayOf(ZPAD, ZDLE, ZBIN)
    private val frameHexPattern = intArrayOf(ZPAD, ZDLE, ZHEX)
    private var fileName = ""
    private var fileSize = 0L
    private var received = 0L
    private var expectedPos = 0L
    private var events = mutableListOf<Event>()
    private val send = ByteArrayOutputStream()
    private val display = ByteArrayOutputStream()

    /** "OO" (over-and-out) bytes sz still owes after our ZFIN; swallowed, not shown. */
    private var overAndOutLeft = 0

    val isActive: Boolean get() = phase == Phase.ACTIVE

    fun feed(input: ByteArray): FeedResult {
        events = mutableListOf()
        send.reset()
        display.reset()
        buf += input
        try {
            when (phase) {
                Phase.SNIFF -> sniff()
                Phase.ACTIVE -> parse()
                Phase.DONE -> {
                    display.write(buf)
                    buf = ByteArray(0)
                }
            }
        } catch (e: Exception) {
            fail("zmodem: ${e.message}")
        }
        return FeedResult(
            display.toByteArray(),
            send.toByteArray(),
            events.toList(),
            phase == Phase.ACTIVE,
        )
    }

    fun cancel(): ByteArray {
        if (phase == Phase.ACTIVE) fail("cancelled")
        return cancelBytes()
    }

    private fun fail(reason: String) {
        events.add(Event.Failed(reason))
        // whatever sz still had in flight is protocol junk, not terminal
        // output — drop it and go back to watching for the next transfer
        buf = ByteArray(0)
        resetToSniff()
    }

    private fun resetToSniff() {
        phase = Phase.SNIFF
        awaitSub = 0
        inData = false
        fileName = ""
        fileSize = 0
        received = 0
        expectedPos = 0
    }

    // -------------------------------------------------------------- SNIFF

    /** Pass bytes through until a frame start "**␘B" / "*␘B" / "**␘0" / "*␘0" appears. */
    private fun sniff() {
        while (overAndOutLeft > 0 && buf.isNotEmpty()) {
            if (buf[0] != 'O'.code.toByte()) {
                overAndOutLeft = 0
                break
            }
            buf = buf.copyOfRange(1, buf.size)
            overAndOutLeft--
        }
        var i = 0
        while (i < buf.size) {
            if (isFrameStartAt(i)) {
                display.write(buf, 0, i)
                buf = buf.copyOfRange(i, buf.size)
                phase = Phase.ACTIVE
                events.add(Event.Started)
                parse()
                return
            }
            i++
        }
        // hold back only a tail that could still grow into a frame start:
        // trailing ZPADs, or ZPAD ZDLE (suffix of "**␘B" / "**␘0")
        var keep = 0
        if (buf.size >= 2 && (buf[buf.size - 2].toInt() and 0xFF) == ZPAD &&
            (buf[buf.size - 1].toInt() and 0xFF) == ZDLE
        ) {
            keep = 2
        } else {
            var t = 0
            while (t < 2 && buf.size - 1 - t >= 0 &&
                (buf[buf.size - 1 - t].toInt() and 0xFF) == ZPAD
            ) {
                t++
            }
            keep = t
        }
        display.write(buf, 0, buf.size - keep)
        buf = buf.copyOfRange(buf.size - keep, buf.size)
    }

    /** "*␘B" / "*␘0" here, or the same one ZPAD later ("**␘B"). */
    private fun isFrameStartAt(i: Int): Boolean {
        val single = (matchesAt(i, frameBinPattern) || matchesAt(i, frameHexPattern))
        if (single) return true
        if (i + 1 < buf.size && (buf[i].toInt() and 0xFF) == ZPAD &&
            (buf[i + 1].toInt() and 0xFF) == ZPAD
        ) {
            return matchesAt(i + 1, frameBinPattern) || matchesAt(i + 1, frameHexPattern)
        }
        return false
    }

    private fun matchesAt(i: Int, pat: IntArray): Boolean {
        if (i + pat.size > buf.size) return false
        for (k in pat.indices) {
            if ((buf[i + k].toInt() and 0xFF) != pat[k]) return false
        }
        return true
    }

    // -------------------------------------------------------------- ACTIVE

    private class Frame(val type: Int, val hdr: IntArray)

    private fun parse() {
        var guard = 0
        while (guard++ < 10_000) {
            if (awaitSub != 0) {
                val sub = subpacket() ?: return
                val type = awaitSub
                awaitSub = 0
                if (type == ZFILE) applyZfile(sub)
                continue
            }
            if (inData) {
                val ended = dataSubpackets()
                if (!ended) return // more subpacket bytes still to come
                inData = false
                continue
            }
            val f = nextFrame() ?: return
            handle(f)
            if (phase != Phase.ACTIVE) return
        }
    }

    /** Finds and consumes one frame; reads trailing data subpackets for ZFILE/ZDATA. */
    private fun nextFrame(): Frame? {
        // skip junk between frames (CR/LF/ZDLE padding sz emits)
        var i = 0
        while (i < buf.size) {
            val b = buf[i].toInt() and 0xFF
            if (b == ZPAD) break
            if (b == 0x0D || b == 0x0A || b == ZDLE) {
                i++
                continue
            }
            i++ // tolerate stray junk
        }
        if (i >= buf.size) {
            buf = ByteArray(0)
            return null
        }
        if (i + 2 >= buf.size) {
            buf = buf.copyOfRange(i, buf.size)
            return null // incomplete header prefix
        }
        val b1 = buf[i + 1].toInt() and 0xFF
        if (b1 == ZPAD) {
            buf = buf.copyOfRange(i + 1, buf.size)
            return nextFrame()
        }
        if (b1 != ZDLE) {
            buf = buf.copyOfRange(i + 1, buf.size)
            return null
        }
        val fmt = buf[i + 2].toInt() and 0xFF
        val consumed: Int
        val type: Int
        val hdr = IntArray(4)
        // 'B' after ZDLE is ambiguous: ASCII hex digits follow in a hex
        // frame, raw (escaped) binary in a bin frame. ZHEX '0' is an
        // alternative hex marker some peers emit.
        val hexish = fmt == ZHEX || (fmt == ZBIN && i + 3 < buf.size &&
            isHexDigit(buf[i + 3].toInt() and 0xFF))
        when {
            hexish -> {
                val start = if (fmt == ZHEX) i + 3 else i + 3
                val r = parseHex(start) ?: return null
                type = r.type
                System.arraycopy(r.hdr, 0, hdr, 0, 4)
                consumed = r.end
            }
            fmt == ZBIN || fmt == 0x41 -> { // 'B' or 'A': 16-bit bin frame
                val r = parseBin(i + 3) ?: return null
                type = r.type
                System.arraycopy(r.hdr, 0, hdr, 0, 4)
                consumed = r.end
            }
            else -> {
                buf = buf.copyOfRange(i + 3, buf.size)
                return null
            }
        }
        buf = buf.copyOfRange(consumed, buf.size)
        val frame = Frame(type, hdr)
        if (type == ZFILE || type == ZSINIT) {
            val sub = subpacket() ?: run {
                awaitSub = type
                return null
            }
            if (type == ZFILE) {
                applyZfile(sub)
            }
            return frame
        }
        if (type == ZDATA) {
            if (hdrToLong(hdr) != expectedPos) {
                send.write(hexFrame(ZRPOS, pos4(expectedPos)))
            } else {
                inData = true
            }
        }
        return frame
    }

    /** frame type whose data subpacket is still incomplete in buf */
    private var awaitSub = 0

    /** mid-ZDATA: subpacket chain not yet terminated by ZCRCE */
    private var inData = false

    private class Parsed(val type: Int, val hdr: IntArray, val end: Int)

    /** Tolerated between frame marker and hex digits (lrzsz pads with ZDLE). */
    private fun isHexPadding(c: Int): Boolean =
        c == ZDLE || c == 0x0D || c == 0x0A || c == 0x20 || c == 0x09

    private fun parseHex(from: Int): Parsed? {
        // collect 14 hex digits (skipping ZDLE padding lrzsz inserts), then CRC hex, then CRLF
        val digits = StringBuilder()
        var i = from
        while (i < buf.size && digits.length < 14) {
            val c = buf[i].toInt() and 0xFF
            if (isHexDigit(c)) {
                digits.append(c.toChar()); i++
            } else if (isHexPadding(c)) {
                if (digits.isEmpty()) i++ else return null // junk mid-digits
            } else {
                return null
            }
        }
        if (digits.length < 14) return null
        // trailing CR/LF (sz appends CR LF + sometimes XON padding)
        while (i < buf.size) {
            val c = buf[i].toInt() and 0xFF
            if (c == 0x0D || c == 0x0A) i++ else break
        }
        val type = digits.substring(0, 2).toInt(16)
        val hdr = IntArray(4) { digits.substring(2 + it * 2, 4 + it * 2).toInt(16) }
        return Parsed(type, hdr, i)
    }

    private class Unesc(val value: Int, val next: Int)

    private fun unesc(i: Int): Unesc? {
        val b = buf[i].toInt() and 0xFF
        if (b != ZDLE) return Unesc(b, i + 1)
        if (i + 1 >= buf.size) return null
        return Unesc((buf[i + 1].toInt() and 0xFF) xor 0x40, i + 2)
    }

    private fun parseBin(from: Int): Parsed? {
        val body = IntArray(5)
        var i = from
        for (k in 0 until 5) {
            val u = unesc(i) ?: return null
            body[k] = u.value
            i = u.next
        }
        val c1 = unesc(i) ?: return null
        val c2 = unesc(c1.next) ?: return null
        val bodyArr = ByteArray(5) { body[it].toByte() }
        val sentCrc = ((c1.value and 0xFF) shl 8) or (c2.value and 0xFF)
        if (crc16(bodyArr) != sentCrc) {
            throw IllegalStateException("header CRC mismatch")
        }
        return Parsed(body[0], IntArray(4) { body[1 + it] }, c2.next)
    }

    /** One data subpacket: escaped bytes until ZDLE+terminator, then CRC16. */
    private class Sub(val data: ByteArray, val terminator: Int)

    private fun subpacket(): Sub? {
        var i = 0
        val out = ByteArrayOutputStream()
        var terminator = -1
        while (true) {
            if (i >= buf.size) return null
            val b = buf[i].toInt() and 0xFF
            if (b == ZDLE) {
                if (i + 1 >= buf.size) return null
                val c = buf[i + 1].toInt() and 0xFF
                if (c == ZCRCE || c == ZCRCG || c == ZCRCW || c == ZCRCQ) {
                    terminator = c
                    i += 2
                    break
                }
                val u = unesc(i) ?: return null
                out.write(u.value and 0xFF)
                i = u.next
            } else {
                out.write(b)
                i++
            }
        }
        val c1 = unesc(i) ?: return null
        val c2 = unesc(c1.next) ?: return null
        val data = out.toByteArray()
        val withTerm = data + byteArrayOf(terminator.toByte())
        val sentCrc = ((c1.value and 0xFF) shl 8) or (c2.value and 0xFF)
        if (crc16(withTerm) != sentCrc) {
            throw IllegalStateException("subpacket CRC mismatch")
        }
        buf = buf.copyOfRange(c2.next, buf.size)
        return Sub(data, terminator)
    }

    /**
     * After a ZDATA header: consume subpackets until ZCRCE (frame end).
     * @return true when the chain ended (ZCRCE seen), false when more
     *         subpacket bytes are still to arrive.
     */
    private fun dataSubpackets(): Boolean {
        var guard = 0
        while (guard++ < 100_000) {
            val sub = subpacket() ?: return false
            events.add(Event.Data(sub.data))
            received += sub.data.size
            expectedPos += sub.data.size
            when (sub.terminator) {
                ZCRCW -> send.write(hexFrame(ZACK, pos4(expectedPos)))
                ZCRCE -> return true
                else -> {}
            }
        }
        return false
    }

    private fun applyZfile(sub: Sub) {
        val text = sub.data.toString(Charsets.ISO_8859_1)
        fileName = text.substringBefore('\u0000').substringAfterLast('/')
        val rest = text.substringAfter('\u0000', "")
        fileSize = rest.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
        received = 0
        expectedPos = 0
        events.add(Event.Offered(fileName, fileSize))
        send.write(hexFrame(ZRPOS, pos4(0)))
    }

    private fun handle(f: Frame) {
        when (f.type) {
            ZRQINIT -> send.write(zrinitBytes())
            ZRINIT -> {
                // hand the stream to a ZmodemSender; this receiver is done
                events.add(Event.UploadRequested((f.hdr[3] and 0x20) != 0))
                phase = Phase.DONE
            }
            ZEOF -> {
                events.add(Event.Complete(fileName, received))
                send.write(zrinitBytes())
            }
            ZFIN -> {
                // "OO" is the SENDER's sign-off (sz writes it after our
                // ZFIN); a receiver that sends it lands "OO" in the remote
                // shell's input once sz has exited
                send.write(hexFrame(ZFIN))
                overAndOutLeft = 2
                resetToSniff()
            }
            ZNAK, ZCRC, ZCHALLENGE, ZCOMPL, ZACK, ZSKIP -> {}
            ZABORT, ZCAN, ZFERR -> fail("remote aborted")
        }
    }

    /**
     * Position headers are little-endian: ZP0 is the low byte (zmodem.h;
     * lrzsz sends ZEOF len 19 as `13 00 00 00`). Every happy-path position
     * is 0 so a big-endian mistake stays invisible — until a ZRPOS resync
     * or a ZCRCW ack at a non-zero offset, where sz reads pos 768 as
     * 196608 and the transfer stalls.
     */
    internal fun hdrToLong(hdr: IntArray): Long =
        ((hdr[3].toLong() and 0xFF) shl 24) or ((hdr[2].toLong() and 0xFF) shl 16) or
            ((hdr[1].toLong() and 0xFF) shl 8) or (hdr[0].toLong() and 0xFF)

    internal fun pos4(v: Long): IntArray = intArrayOf(
        (v and 0xFF).toInt(),
        (v ushr 8 and 0xFF).toInt(),
        (v ushr 16 and 0xFF).toInt(),
        (v ushr 24 and 0xFF).toInt(),
    )
}
