package at.least.conch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ZMODEM receiver vs the REAL lrzsz `sz` binary (same pattern as the
 * ssh-keygen tests): sz runs as a subprocess, our ZmodemReceiver answers it
 * over piped stdin/stdout, and the file it "downloads" must be
 * byte-identical to the fixture. This is interop testing, not
 * self-consistency — the wire format is whatever lrzsz actually emits.
 *
 * Run: ./gradlew test --tests '*.ZmodemInteropTest' (requires `sz` on PATH)
 */
class ZmodemInteropTest {

    private fun szPath(): String =
        ProcessBuilder("sz", "--version").start().let { p ->
            val ok = p.waitFor(5, TimeUnit.SECONDS) && p.inputStream.readBytes().isNotEmpty()
            p.destroy()
            if (!ok) org.junit.Assume.assumeTrue("sz not available", false)
            "sz"
        }

    private fun runSz(vararg files: File): Transfer {
        val engine = ZmodemReceiver()
        val proc = ProcessBuilder(szPath(), *files.map { it.absolutePath }.toTypedArray())
            .redirectErrorStream(false)
            .start()
        val received = mutableListOf<Pair<String, ByteArray>>()
        var current: Pair<String, ByteArrayOutputStream>? = null

        val writer = proc.outputStream
        val reader = Thread {
            try {
                val buf = ByteArray(8192)
                while (true) {
                    val n = proc.inputStream.read(buf)
                    if (n < 0) break
                    val res = engine.feed(buf.copyOf(n))
                    if (res.send.isNotEmpty()) {
                        writer.write(res.send)
                        writer.flush()
                    }
                    for (e in res.events) {
                        when (e) {
                            is ZmodemReceiver.Event.Offered ->
                                current = e.name to ByteArrayOutputStream()
                            is ZmodemReceiver.Event.Data ->
                                current?.second?.write(e.chunk)
                            is ZmodemReceiver.Event.Complete -> {
                                current?.let {
                                    received.add(it.first to it.second.toByteArray())
                                }
                                current = null
                            }
                            else -> {}
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        reader.start()
        proc.waitFor(30, TimeUnit.SECONDS)
        writer.close()
        reader.join(5_000)
        proc.destroy()
        return Transfer(engine, received)
    }

    private class Transfer(val engine: ZmodemReceiver, val files: List<Pair<String, ByteArray>>)

    @Test
    fun `small text file round-trips byte-identically`() {
        val fixture = File.createTempFile("zmsmall", ".txt")
        try {
            fixture.writeText("hello zmodem from lrzsz\nline two\n")
            val t = runSz(fixture)
            assertEquals(1, t.files.size)
            assertEquals(fixture.name, t.files[0].first)
            assertArrayEquals(fixture.readBytes(), t.files[0].second)
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun `binary file with zdle-hostile bytes round-trips`() {
        val fixture = File.createTempFile("zmbin", ".bin")
        try {
            val bytes = ByteArray(70_000) { i ->
                when (i % 7) {
                    0 -> 0x18 // ZDLE
                    1 -> 0x11 // XON
                    2 -> 0x13 // XOFF
                    3 -> 0x7F
                    4 -> 0x0D
                    5 -> 0x0A
                    else -> (i * 31 and 0xFF).toByte().toInt().toByte()
                } as Byte
            }
            fixture.writeBytes(bytes)
            val t = runSz(fixture)
            assertEquals(1, t.files.size)
            assertArrayEquals(bytes, t.files[0].second)
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun `multiple files in one batch all complete`() {
        val f1 = File.createTempFile("zma", ".txt")
        val f2 = File.createTempFile("zmb", ".txt")
        try {
            f1.writeText("first file contents\n")
            f2.writeText("second file, different length, also fine\n")
            val t = runSz(f1, f2)
            assertEquals(2, t.files.size)
            assertEquals(f1.name, t.files[0].first)
            assertEquals(f2.name, t.files[1].first)
            assertArrayEquals(f1.readBytes(), t.files[0].second)
            assertArrayEquals(f2.readBytes(), t.files[1].second)
        } finally {
            f1.delete()
            f2.delete()
        }
    }

    @Test
    fun `crc16 matches known zmodem vector`() {
        // CRC16-CCITT/FALSE of "123456789" is 0x31C3 — the ZMODEM variant.
        assertEquals(0x31C3, ZmodemReceiver.crc16("123456789".toByteArray()))
    }

    @Test
    fun `sniff passes plain terminal bytes through`() {
        val e = ZmodemReceiver()
        val r = e.feed("just a normal ls -la listing\r\n".toByteArray())
        assertTrue(!r.active)
        assertEquals("just a normal ls -la listing\r\n", String(r.display))
    }
}
