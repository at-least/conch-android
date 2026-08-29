package at.least.conch

import io.mockk.mockk
import net.schmizz.sshj.sftp.SFTPClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The queue's decisions (iOS TransferQueue parity), against a fake
 * transport: resume offsets, partials kept on cancel/failure, retry
 * resuming from them, the local-copy freshness skip, dedup, clear.
 */
class TransferQueueTest {

    // ---------------------------------------------------------- pure plan

    @Test
    fun `download plan — same-size local copy is fresh, partial resumes, else scratch`() {
        assertEquals(
            TransferPlan.Action.AlreadyDone,
            TransferPlan.download(finalSize = 100, partSize = null, remoteSize = 100),
        )
        assertEquals(
            TransferPlan.Action.Resume(40),
            TransferPlan.download(finalSize = null, partSize = 40, remoteSize = 100),
        )
        // a stale local copy of another size does not block a fresh download
        assertEquals(
            TransferPlan.Action.Resume(40),
            TransferPlan.download(finalSize = 90, partSize = 40, remoteSize = 100),
        )
        // partial at least as long as the remote is garbage: start over
        assertEquals(
            TransferPlan.Action.FromScratch,
            TransferPlan.download(finalSize = null, partSize = 100, remoteSize = 100),
        )
        assertEquals(
            TransferPlan.Action.FromScratch,
            TransferPlan.download(finalSize = null, partSize = 0, remoteSize = 100),
        )
        assertEquals(
            TransferPlan.Action.FromScratch,
            TransferPlan.download(finalSize = null, partSize = null, remoteSize = 100),
        )
        // unknown remote size: never "fresh", but a partial still resumes
        assertEquals(
            TransferPlan.Action.Resume(40),
            TransferPlan.download(finalSize = 100, partSize = 40, remoteSize = null),
        )
    }

    @Test
    fun `upload plan — shorter remote resumes, equal or longer starts over`() {
        assertEquals(
            TransferPlan.Action.Resume(30),
            TransferPlan.upload(remoteSize = 30, sourceSize = 100),
        )
        assertEquals(
            TransferPlan.Action.FromScratch,
            TransferPlan.upload(remoteSize = 100, sourceSize = 100),
        )
        assertEquals(
            TransferPlan.Action.FromScratch,
            TransferPlan.upload(remoteSize = 120, sourceSize = 100),
        )
        assertEquals(
            TransferPlan.Action.FromScratch,
            TransferPlan.upload(remoteSize = 0, sourceSize = 100),
        )
        assertEquals(
            TransferPlan.Action.FromScratch,
            TransferPlan.upload(remoteSize = null, sourceSize = 100),
        )
    }

    @Test
    fun `safe names are one path component`() {
        assertTrue(TransferPlan.isSafeName("report.pdf"))
        assertFalse(TransferPlan.isSafeName("../etc/passwd"))
        assertFalse(TransferPlan.isSafeName("a/b"))
        assertFalse(TransferPlan.isSafeName(".."))
        assertFalse(TransferPlan.isSafeName(""))
    }

    @Test
    fun `progress labels`() {
        assertEquals("12.3 MB / 45.0 MB", TransferFormat.progressLabel(12_897_485, 47_185_920))
        assertEquals("512 B", TransferFormat.progressLabel(512, null))
        assertEquals(0.5f, TransferFormat.progressFraction(50, 100))
        assertNull(TransferFormat.progressFraction(50, null))
        assertEquals(1f, TransferFormat.progressFraction(150, 100))
    }

    // ---------------------------------------------------------- queue + fake

    private lateinit var dir: File
    private lateinit var fake: FakeTransport
    private lateinit var queue: TransferQueue

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conch-transfers").toFile()
        fake = FakeTransport()
        queue = TransferQueue(
            downloadsDir = dir,
            sftpProvider = { mockk<SFTPClient>(relaxed = true) },
            transportFactory = { fake },
            executor = Executors.newSingleThreadExecutor(),
        )
    }

    @After
    fun tearDown() {
        queue.close()
        dir.deleteRecursively()
    }

    private val payload = ByteArray(1000) { (it % 251).toByte() }

    private fun awaitState(id: String, pred: (TransferQueue.State) -> Boolean): TransferQueue.Item {
        awaitTrue("item $id never reached the expected state: ${queue.items.value}", 10_000) {
            queue.items.value.firstOrNull { it.id == id }?.let { pred(it.state) } == true
        }
        return queue.items.value.first { it.id == id }
    }

    @Test
    fun `download that fails mid-way keeps the partial and retry resumes from it byte-exact`() {
        fake.remote["/r/big.bin"] = payload
        fake.failDownloadAfter = 400
        val item = queue.enqueueDownload("big.bin", "/r/big.bin", payload.size.toLong())!!
        val failed = awaitState(item.id) { it is TransferQueue.State.Failed }
        assertEquals(400L, failed.resumeOffset)
        assertEquals(400L, File(dir, "big.bin.part").length())
        assertFalse(File(dir, "big.bin").exists())

        fake.failDownloadAfter = null
        queue.retry(item.id)
        val done = awaitState(item.id) { it is TransferQueue.State.Done }
        assertEquals(listOf(0L, 400L), fake.downloadOffsets)
        assertArrayEquals(payload, File(dir, "big.bin").readBytes())
        assertFalse(File(dir, "big.bin.part").exists())
        assertEquals(1000L, done.transferred)
        assertEquals(1000L, done.totalBytes)
    }

    @Test
    fun `cancel mid-way keeps the partial, retry resumes`() {
        fake.remote["/r/c.bin"] = payload
        fake.blockAfter = 300
        val item = queue.enqueueDownload("c.bin", "/r/c.bin", 1000)!!
        assertTrue(fake.blocked.await(5, TimeUnit.SECONDS))
        queue.cancel(item.id)
        fake.release.countDown()
        val cancelled = awaitState(item.id) { it is TransferQueue.State.Cancelled }
        assertTrue(cancelled.resumeOffset >= 300)
        assertEquals(cancelled.resumeOffset, File(dir, "c.bin.part").length())

        fake.blockAfter = null
        queue.retry(item.id)
        awaitState(item.id) { it is TransferQueue.State.Done }
        assertEquals(cancelled.resumeOffset, fake.downloadOffsets.last())
        assertArrayEquals(payload, File(dir, "c.bin").readBytes())
    }

    @Test
    fun `a local copy with the remote's size is already downloaded — no transfer`() {
        fake.remote["/r/same.bin"] = payload
        File(dir, "same.bin").writeBytes(payload)
        val item = queue.enqueueDownload("same.bin", "/r/same.bin", 1000)!!
        val done = awaitState(item.id) { it is TransferQueue.State.Done }
        assertTrue(fake.downloadOffsets.isEmpty())
        assertEquals(1000L, done.transferred)
    }

    @Test
    fun `stale partial longer than the remote is discarded and the download starts over`() {
        fake.remote["/r/s.bin"] = payload
        File(dir, "s.bin.part").writeBytes(ByteArray(1200))
        val item = queue.enqueueDownload("s.bin", "/r/s.bin", 1000)!!
        awaitState(item.id) { it is TransferQueue.State.Done }
        assertEquals(listOf(0L), fake.downloadOffsets)
        assertArrayEquals(payload, File(dir, "s.bin").readBytes())
    }

    @Test
    fun `upload resumes from a shorter remote file and deletes its staged source when asked`() {
        val src = File(dir, "staged.tmp").apply { writeBytes(payload) }
        fake.remote["/r/up.bin"] = payload.copyOf(250) // interrupted earlier upload
        val item = queue.enqueueUpload(src, "/r/up.bin", deleteSourceWhenDone = true)!!
        awaitState(item.id) { it is TransferQueue.State.Done }
        assertEquals(listOf(250L), fake.uploadOffsets)
        assertArrayEquals(payload, fake.remote["/r/up.bin"])
        assertFalse(src.exists())
    }

    @Test
    fun `upload over an equal-size remote starts over`() {
        val src = File(dir, "staged2.tmp").apply { writeBytes(payload) }
        fake.remote["/r/eq.bin"] = ByteArray(1000) { 9 }
        val item = queue.enqueueUpload(src, "/r/eq.bin")!!
        awaitState(item.id) { it is TransferQueue.State.Done }
        assertEquals(listOf(0L), fake.uploadOffsets)
        assertArrayEquals(payload, fake.remote["/r/eq.bin"])
        assertTrue(src.exists())
    }

    @Test
    fun `an active download of the same remote path is not duplicated, a finished one is replaced`() {
        fake.remote["/r/d.bin"] = payload
        fake.blockAfter = 100
        val first = queue.enqueueDownload("d.bin", "/r/d.bin", 1000)!!
        assertTrue(fake.blocked.await(5, TimeUnit.SECONDS))
        assertNull(queue.enqueueDownload("d.bin", "/r/d.bin", 1000))
        assertEquals(1, queue.activeCount)
        fake.release.countDown()
        awaitState(first.id) { it is TransferQueue.State.Done }
        fake.blockAfter = null
        val second = queue.enqueueDownload("d.bin", "/r/d.bin", 1000)
        assertNotNull(second)
        assertEquals(listOf(second!!.id), queue.items.value.map { it.id })
    }

    @Test
    fun `queued item cancelled before it runs never touches the transport, clear removes finished only`() {
        fake.remote["/r/a.bin"] = payload
        fake.remote["/r/b.bin"] = payload
        fake.blockAfter = 100
        val a = queue.enqueueDownload("a.bin", "/r/a.bin", 1000)!!
        assertTrue(fake.blocked.await(5, TimeUnit.SECONDS))
        val b = queue.enqueueDownload("b.bin", "/r/b.bin", 1000)!!
        queue.cancel(b.id)
        assertTrue(queue.items.value.first { it.id == b.id }.state is TransferQueue.State.Cancelled)
        fake.release.countDown()
        awaitState(a.id) { it is TransferQueue.State.Done }
        assertEquals(listOf("/r/a.bin"), fake.downloadedPaths)

        queue.clearFinished()
        assertEquals(listOf(b.id), queue.items.value.map { it.id })
        // and the cancelled one can still be retried
        queue.retry(b.id)
        awaitState(b.id) { it is TransferQueue.State.Done }
        assertArrayEquals(payload, File(dir, "b.bin").readBytes())
    }

    @Test
    fun `unsafe remote names are refused before anything is queued`() {
        try {
            queue.enqueueDownload("../x", "/r/x", 1)
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(queue.items.value.isEmpty())
    }

    /** In-memory remote: records offsets, can fail or block after N bytes. */
    private class FakeTransport : TransferTransport {
        val remote = HashMap<String, ByteArray>()
        val downloadOffsets = mutableListOf<Long>()
        val uploadOffsets = mutableListOf<Long>()
        val downloadedPaths = mutableListOf<String>()

        @Volatile
        var failDownloadAfter: Int? = null

        @Volatile
        var blockAfter: Int? = null

        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val step = 100

        override fun remoteSize(path: String): Long? = remote[path]?.size?.toLong()

        override fun download(
            path: String,
            offset: Long,
            sink: OutputStream,
            cancelled: AtomicBoolean,
            onProgress: (Long) -> Unit,
        ) {
            downloadOffsets += offset
            downloadedPaths += path
            val data = remote.getValue(path)
            var pos = offset.toInt()
            val sent = AtomicInteger(0)
            while (pos < data.size) {
                if (cancelled.get()) throw TransferCancelled(pos.toLong())
                val n = minOf(step, data.size - pos)
                sink.write(data, pos, n)
                sink.flush()
                pos += n
                sent.addAndGet(n)
                onProgress(pos.toLong())
                failDownloadAfter?.let { if (sent.get() >= it) throw java.io.IOException("connection reset") }
                blockAfter?.let {
                    if (sent.get() >= it) {
                        blocked.countDown()
                        release.await(10, TimeUnit.SECONDS)
                        blockAfter = null
                    }
                }
            }
        }

        override fun upload(
            source: InputStream,
            path: String,
            offset: Long,
            cancelled: AtomicBoolean,
            onProgress: (Long) -> Unit,
        ) {
            uploadOffsets += offset
            val existing = if (offset > 0) remote.getValue(path).copyOf(offset.toInt()) else ByteArray(0)
            val out = java.io.ByteArrayOutputStream().apply { write(existing) }
            val buf = ByteArray(step)
            var pos = offset
            while (true) {
                if (cancelled.get()) throw TransferCancelled(pos)
                val n = source.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                pos += n
                onProgress(pos)
            }
            remote[path] = out.toByteArray()
        }
    }
}
