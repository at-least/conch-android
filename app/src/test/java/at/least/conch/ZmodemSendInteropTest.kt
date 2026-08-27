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
 * byte-identical to the fixture.
 *
 * KNOWN LIMITATION: binary/multi-KB transfers stall in the endgame handshake
 * (data delivered, rz re-issues ZRPOS). Plain small files complete — that
 * path is active here; do not widen scope until verified against lrzsz.
 */
class ZmodemSendInteropTest {

    @Test
    fun `rz receives a small text file we send byte-identically`() {
        org.junit.Assume.assumeTrue(
            "rz not available",
            File("/opt/homebrew/bin/rz").exists() || File("/usr/local/bin/rz").exists(),
        )
        val dir = java.nio.file.Files.createTempDirectory("zmsend").toFile()
        try {
            val bytes = ByteArray(200) { i -> ('a' + (i % 26)).code.toByte() }
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
                                w.write(sender.begin("sent.txt", bytes))
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
            assertArrayEquals(bytes, File(dir, "sent.txt").readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }
}
