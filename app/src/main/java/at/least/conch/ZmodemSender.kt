package at.least.conch

import java.io.ByteArrayOutputStream

/**
 * Pure ZMODEM sender engine (rz on the remote wants OUR files): after the
 * remote's ZRINIT is detected by [ZmodemReceiver], the UI offers a file
 * pick, [begin] announces it (ZFILE), and the engine streams ZDATA
 * subpackets in response to ZRPOS — verified against the REAL lrzsz `rz`
 * binary in ZmodemSendInteropTest. 16-bit CRC frames only, matching the
 * receiver side.
 */
class ZmodemSender {

    companion object {
        const val ZDLE = ZmodemReceiver.ZDLE
        const val ZRINIT = ZmodemReceiver.ZRINIT
        const val ZACK = ZmodemReceiver.ZACK
        const val ZFILE = ZmodemReceiver.ZFILE
        const val ZSKIP = ZmodemReceiver.ZSKIP
        const val ZNAK = ZmodemReceiver.ZNAK
        const val ZFIN = ZmodemReceiver.ZFIN
        const val ZRPOS = ZmodemReceiver.ZRPOS
        const val ZDATA = ZmodemReceiver.ZDATA
        const val ZEOF = ZmodemReceiver.ZEOF
        const val ZABORT = ZmodemReceiver.ZABORT
        const val ZCAN = ZmodemReceiver.ZCAN

        const val ZCRCE = ZmodemReceiver.ZCRCE
        const val ZCRCG = ZmodemReceiver.ZCRCG
        const val ZCRCW = ZmodemReceiver.ZCRCW
        const val ZCRCQ = ZmodemReceiver.ZCRCQ

        // Subpacket size sz itself uses; lrzsz's receiver rejects larger
        // single subpackets (its window buffer).
        private const val CHUNK = 1024

        /**
         * Payload escaping, byte-for-byte with live lrzsz traffic: ONLY the
         * exact 7-bit values ZDLE/DLE/XON/XOFF — not 0x7F, not their
         * parity variants (0x98 = ZDLE|0x80 goes raw), not NULs or
         * newlines. Frame headers/CRCs use the wider [escape32] set.
         */
        fun escape(src: ByteArray, from: Int = 0, to: Int = src.size): ByteArray {
            val out = ByteArrayOutputStream(src.size + 16)
            for (i in from until to) {
                val b = src[i].toInt() and 0xFF
                if (isWireHostile(b)) {
                    out.write(ZDLE)
                    out.write(b xor 0x40)
                } else {
                    out.write(b)
                }
            }
            return out.toByteArray()
        }

        /** sz.c's zsendline table: exact ZDLE/DLE/XON/XOFF + 0x90/0x91/0x93. */
        private fun isWireHostile(b: Int): Boolean =
            b == ZDLE || b == 0x10 || b == 0x11 || b == 0x13 ||
                b == 0x90 || b == 0x91 || b == 0x93

        /**
         * One 16-bit data subpacket: escaped payload, ZDLE + terminator,
         * CRC16. The CRC covers the RAW (unescaped) payload + terminator —
         * brute-force-verified against live lrzsz wire bytes (the text-only
         * tests passed before only because they contained no escapable
         * bytes).
         */
        fun subpacket(payload: ByteArray, terminator: Int): ByteArray {
            val escaped = escape(payload)
            val crc = ZmodemReceiver.crc16(payload + byteArrayOf(terminator.toByte()))
            return escaped + byteArrayOf(ZDLE.toByte(), terminator.toByte()) +
                byteArrayOf(((crc shr 8) and 0xFF).toByte(), (crc and 0xFF).toByte())
        }

        private fun pos4(v: Long): IntArray = intArrayOf(
            (v ushr 24 and 0xFF).toInt(),
            (v ushr 16 and 0xFF).toInt(),
            (v ushr 8 and 0xFF).toInt(),
            (v and 0xFF).toInt(),
        )

        /** lrzsz sends position headers little-endian (observed: ZEOF len 19 -> 13 00 00 00). */
        private fun pos4le(v: Long): IntArray = intArrayOf(
            (v and 0xFF).toInt(),
            (v ushr 8 and 0xFF).toInt(),
            (v ushr 16 and 0xFF).toInt(),
            (v ushr 24 and 0xFF).toInt(),
        )

        private const val CANFC32 = 0x20

        /**
         * Escape set lrzsz applies inside 32-bit frame headers and CRCs
         * (observed live): ZDLE, DLE, XON, XOFF, DEL, and the high-bit
         * (parity) variants of those control bytes (e.g. 0x91 = XON|0x80
         * is escaped as ZDLE 0xD1; 0xDD and 0xF0 go raw).
         */
        private fun escape32(b: Int): Boolean {
            val v = b and 0xFF
            val low = v and 0x7F
            return low == ZDLE || low == 0x10 || low == 0x11 || low == 0x13 || v == 0x7F
        }

        private fun crc32(data: ByteArray): Long =
            java.util.zip.CRC32().apply { update(data) }.value
    }

    sealed class Event {
        /** ZRINIT received — call [begin] with the picked file, or cancel. */
        data object Ready : Event()
        data class Progress(val sent: Long, val total: Long) : Event()
        data class Complete(val name: String) : Event()
        data class Failed(val reason: String) : Event()
        data object Skipped : Event()
    }

    data class FeedResult(val send: ByteArray, val events: List<Event>, val done: Boolean)

    private enum class Phase { WAIT_ZRINIT, WAIT_ZRPOS, WAIT_ZEOF_ACK, DONE }

    private var phase = Phase.WAIT_ZRINIT
    private var buf = ByteArray(0)
    private var fileName = ""
    private var fileData = ByteArray(0)
    private var offset = 0
    private val send = ByteArrayOutputStream()
    private var events = mutableListOf<Event>()

    val isReady: Boolean get() = phase == Phase.WAIT_ZRPOS && fileData.isEmpty()
    val isDone: Boolean get() = phase == Phase.DONE

    /** Announce the file (ZFILE + subpacket); returns bytes to send. */
    fun begin(name: String, data: ByteArray): ByteArray {
        require(phase == Phase.WAIT_ZRPOS && fileData.isEmpty()) { "sender not ready" }
        fileName = name
        fileData = data
        offset = 0
        val info = "$name\u0000${data.size} 0 0\u0000".toByteArray(Charsets.ISO_8859_1)
        // byte-identical shape to what lrzsz sz emits: bin-frame ('A') ZFILE
        // header followed by the escaped subpacket — hex-framed ZFILEs trip
        // lrzsz rz's CRC check (observed live)
        return frameFor(ZFILE, IntArray(4)) + subpacketFor(info, ZCRCQ)
    }

    fun feed(input: ByteArray): FeedResult {
        events = mutableListOf()
        send.reset()
        buf += input
        try {
            var guard = 0
            while (guard++ < 1000 && phase != Phase.DONE) {
                val f = nextFrame() ?: break
                handle(f)
            }
        } catch (e: Exception) {
            fail("zmodem send: ${e.message}")
        }
        return FeedResult(send.toByteArray(), events.toList(), phase == Phase.DONE)
    }

    /**
     * Adopts a ZRINIT the receiver already consumed (flags included), so the
     * sender is immediately ready without waiting for a retransmit.
     */
    fun adoptRemoteZrinit(canFc32: Boolean) {
        check(phase == Phase.WAIT_ZRINIT) { "sender already adopted" }
        phase = Phase.WAIT_ZRPOS
        useCrc32 = canFc32
    }

    /** Bytes to send for an abort (also resets the engine). */
    fun cancel(): ByteArray {
        if (phase != Phase.DONE) fail("cancelled")
        return ZmodemReceiver.cancelBytes()
    }

    private fun fail(reason: String) {
        events.add(Event.Failed(reason))
        phase = Phase.DONE
    }

    private class Frame(val type: Int, val hdr: IntArray)

    private fun nextFrame(): Frame? {
        var i = 0
        while (i < buf.size) {
            val b = buf[i].toInt() and 0xFF
            if (b == ZmodemReceiver.ZPAD) break
            if (b == 0x0D || b == 0x0A || b == ZDLE) {
                i++
            } else {
                i++
            }
        }
        if (i + 2 >= buf.size) {
            buf = if (i < buf.size) buf.copyOfRange(i, buf.size) else ByteArray(0)
            return null
        }
        val b1 = buf[i + 1].toInt() and 0xFF
        if (b1 == ZmodemReceiver.ZPAD) {
            buf = buf.copyOfRange(i + 1, buf.size)
            return nextFrame()
        }
        if (b1 != ZDLE) {
            buf = buf.copyOfRange(i + 1, buf.size)
            return null
        }
        val fmt = buf[i + 2].toInt() and 0xFF
        val hexish = fmt == ZmodemReceiver.ZHEX || (fmt == ZmodemReceiver.ZBIN &&
            i + 3 < buf.size && ZmodemReceiver.isHexDigit(buf[i + 3].toInt() and 0xFF))
        if (!hexish) {
            buf = buf.copyOfRange(i + 3, buf.size)
            return null
        }
        // hex frame: 14 digits + CR/LF
        val digits = StringBuilder()
        var j = i + 3
        while (j < buf.size && digits.length < 14) {
            val c = buf[j].toInt() and 0xFF
            if (ZmodemReceiver.isHexDigit(c)) {
                digits.append(c.toChar()); j++
            } else if (digits.isEmpty() && (c == ZDLE || c == 0x0D || c == 0x0A)) {
                j++
            } else {
                break
            }
        }
        if (digits.length < 14) {
            buf = buf.copyOfRange(i, buf.size)
            return null
        }
        while (j < buf.size) {
            val c = buf[j].toInt() and 0xFF
            if (c == 0x0D || c == 0x0A) j++ else break
        }
        buf = buf.copyOfRange(j, buf.size)
        val type = digits.substring(0, 2).toInt(16)
        val hdr = IntArray(4) { digits.substring(2 + it * 2, 4 + it * 2).toInt(16) }
        return Frame(type, hdr)
    }

    private fun handle(f: Frame) {
        when (f.type) {
            ZRINIT -> if (phase == Phase.WAIT_ZRINIT) {
                phase = Phase.WAIT_ZRPOS
                useCrc32 = (f.hdr[3] and CANFC32) != 0
                events.add(Event.Ready)
            } else if (fileData.isNotEmpty() && offset >= fileData.size) {
                // rz re-advertises ZRINIT once the file is complete: wrap up
                send.write(ZmodemReceiver.hexFrame(ZFIN))
                send.write("OO".toByteArray())
                finish()
            }
            ZRPOS -> if (fileData.isNotEmpty()) {
                offset = hdrToLongLE(f.hdr).toInt()
                streamAll()
            }
            ZACK -> {} // mid-blast acks are ignored, matching sz's windowed blast
            ZSKIP -> {
                events.add(Event.Skipped)
                finish()
            }
            ZEOF -> {}
            ZFIN -> {
                send.write(ZmodemReceiver.hexFrame(ZFIN))
                send.write("OO".toByteArray())
                finish()
            }
            ZNAK -> {}
            ZABORT, ZCAN -> fail("remote aborted")
        }
    }

    /** True when the receiver's ZRINIT advertised 32-bit CRCs (CANFC32). */
    private var useCrc32 = false

    /** 16-bit bin frame ('A' marker): raw header bytes + 2-byte CRC16. */
    private fun binFrame(type: Int, hdr: IntArray): ByteArray {
        val body = ByteArray(5)
        body[0] = type.toByte()
        for (i in 0 until 4) body[1 + i] = hdr[i].toByte()
        val crc = ZmodemReceiver.crc16(body)
        return byteArrayOf(ZmodemReceiver.ZPAD.toByte(), ZDLE.toByte(), 0x41.toByte()) +
            body + byteArrayOf(((crc shr 8) and 0xFF).toByte(), (crc and 0xFF).toByte())
    }

    /** 32-bit bin frame ('C' marker): escaped header + little-endian escaped CRC32. */
    private fun bin32Frame(type: Int, hdr: IntArray): ByteArray {
        val body = ByteArray(5)
        body[0] = type.toByte()
        for (i in 0 until 4) body[1 + i] = hdr[i].toByte()
        val crc = crc32(body)
        val out = ByteArrayOutputStream()
        out.write(ZmodemReceiver.ZPAD)
        out.write(ZDLE)
        out.write(0x43) // 'C'
        for (b in body) writeMaybeEscaped(out, b.toInt() and 0xFF)
        for (shift in 0..24 step 8) writeMaybeEscaped(out, ((crc ushr shift) and 0xFF).toInt())
        return out.toByteArray()
    }

    private fun writeMaybeEscaped(out: ByteArrayOutputStream, v: Int) {
        if (escape32(v)) {
            out.write(ZDLE)
            out.write(v xor 0x40)
        } else {
            out.write(v)
        }
    }

    /**
     * Subpacket with a 4-byte little-endian escaped CRC32 (CANFC32 mode).
     * Like the 16-bit variant, the CRC covers the RAW payload + terminator
     * (verified: crc32(rawData+term) reproduces sz's emitted bytes).
     */
    private fun subpacket32(payload: ByteArray, terminator: Int): ByteArray {
        val escaped = escape(payload)
        val crc = crc32(payload + byteArrayOf(terminator.toByte()))
        val out = ByteArrayOutputStream()
        out.write(escaped)
        out.write(ZDLE)
        out.write(terminator)
        for (shift in 0..24 step 8) writeMaybeEscaped(out, ((crc ushr shift) and 0xFF).toInt())
        return out.toByteArray()
    }

    private fun frameFor(type: Int, hdr: IntArray): ByteArray =
        if (useCrc32) bin32Frame(type, hdr) else binFrame(type, hdr)

    private fun subpacketFor(payload: ByteArray, terminator: Int): ByteArray =
        if (useCrc32) subpacket32(payload, terminator) else subpacket(payload, terminator)

    /**
     * sz-style full blast (verified against a live 50KB sz-rz exchange):
     * one ZDATA frame, then ZCRCG-chained 1024-byte subpackets ending
     * ZCRCE, then ZEOF — no mid-stream waiting. The receiver ignores
     * windowing acks until the end, then re-advertises ZRINIT, which the
     * ZRINIT handler answers with ZFIN. Larger subpackets (single-packet
     * streaming) are rejected by lrzsz's receive buffer — 1024 is the size
     * sz itself uses.
     */
    private fun streamAll() {
        send.write(frameFor(ZDATA, pos4le(offset.toLong())))
        while (offset < fileData.size) {
            val end = minOf(offset + CHUNK, fileData.size)
            val term = if (end == fileData.size) ZCRCE else ZCRCG
            send.write(subpacketFor(fileData.copyOfRange(offset, end), term))
            offset = end
        }
        events.add(Event.Progress(fileData.size.toLong(), fileData.size.toLong()))
        send.write(frameFor(ZEOF, pos4le(fileData.size.toLong())))
    }

    private fun finish() {
        events.add(Event.Complete(fileName))
        phase = Phase.DONE
    }

    private fun hdrToLong(hdr: IntArray): Long =
        ((hdr[0].toLong() and 0xFF) shl 24) or ((hdr[1].toLong() and 0xFF) shl 16) or
            ((hdr[2].toLong() and 0xFF) shl 8) or (hdr[3].toLong() and 0xFF)

    private fun hdrToLongLE(hdr: IntArray): Long =
        ((hdr[3].toLong() and 0xFF) shl 24) or ((hdr[2].toLong() and 0xFF) shl 16) or
            ((hdr[1].toLong() and 0xFF) shl 8) or (hdr[0].toLong() and 0xFF)
}
