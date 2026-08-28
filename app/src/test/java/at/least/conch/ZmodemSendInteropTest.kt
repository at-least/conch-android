package at.least.conch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * ZMODEM sender vs the REAL lrzsz `rz` binary: rz runs in a temp cwd, our
 * ZmodemSender answers it over pipes, and the file rz writes must be
 * byte-identical to the fixture. Covers both a plain text file and a
 * ZDLE/XON/XOFF-hostile 50KB binary that exercises the full ZCRCG
 * subpacket chain, ZEOF and the ZRINIT->ZFIN endgame. Wire contracts
 * pinned by brute-force diffing against live sz traffic: payload escaping
 * = exact 7-bit ZDLE/DLE/XON/XOFF + 0x90/0x91/0x93 (sz.c zsendline table);
 * subpacket CRC covers the RAW payload + terminator, not the escaped form.
 */
class ZmodemSendInteropTest {

    private fun rzAvailable(): Boolean =
        File("/opt/homebrew/bin/rz").exists() || // macOS homebrew
            File("/usr/local/bin/rz").exists() || // macOS intel / manual
            File("/usr/bin/rz").exists() // Linux (apt lrzsz — CI installs it)

    @Test
    fun `rz receives a small text file we send byte-identically`() {
        driveRz(ByteArray(200) { i -> ('a' + (i % 26)).code.toByte() }, "text.txt")
    }

    @Test
    fun `rz receives a hostile 50kb binary we send byte-identically`() {
        driveRz(ByteArray(50_000) { i -> ((i * 37 + 11) and 0xFF).toByte() }, "binary.bin")
    }

    private fun driveRz(bytes: ByteArray, name: String) {
        org.junit.Assume.assumeTrue("rz not available", rzAvailable())
        val dir = java.nio.file.Files.createTempDirectory("zmsend").toFile()
        try {
            val sender = ZmodemSender()
            val proc = ProcessBuilder("rz").directory(dir).start()
            val w = proc.outputStream
            val outcomes = ConcurrentLinkedQueue<ZmodemSender.Event>()
            val t = Thread {
                try {
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = proc.inputStream.read(buf)
                        if (n < 0) break
                        val res = sender.feed(buf.copyOf(n))
                        if (res.send.isNotEmpty()) {
                            w.write(res.send)
                            w.flush()
                        }
                        for (e in res.events) {
                            outcomes.add(e)
                            if (e is ZmodemSender.Event.Ready) {
                                w.write(sender.begin(name, bytes))
                                w.flush()
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            t.start()
            proc.waitFor(30, TimeUnit.SECONDS)
            w.close()
            t.join(5_000)
            proc.destroy()
            assertEquals("expected Complete, got $outcomes", 1, outcomes.count { it is ZmodemSender.Event.Complete })
            assertArrayEquals(bytes, File(dir, name).readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }
}
