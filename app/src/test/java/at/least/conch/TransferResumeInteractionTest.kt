package at.least.conch

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resume-from-offset over a REAL SFTP subsystem (in-process Apache MINA
 * sshd): a download interrupted after N bytes continues from its `.part`
 * and the result is byte-exact; an upload whose remote half is shorter is
 * appended from that offset. This is what [SshjTransport]'s offset reads
 * and WRITE|CREAT-without-TRUNC writes have to deliver.
 */
class TransferResumeInteractionTest {

    private lateinit var dir: File
    private lateinit var root: File
    private lateinit var server: TestSshd
    private lateinit var ssh: net.schmizz.sshj.SSHClient

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conch-resume").toFile()
        root = Files.createTempDirectory("conch-resume-root").toFile()
        server = TestSshd(sftpRoot = root).start()
        ssh = connectTrusted(server, KnownHostsStore(dir))
    }

    @After
    fun tearDown() {
        runCatching { ssh.disconnect() }
        server.close()
        dir.deleteRecursively()
        root.deleteRecursively()
    }

    private val payload = ByteArray(1_500_000) { i -> ((i * 31 + 7) and 0xFF).toByte() }

    @Test(timeout = 60_000)
    fun `download cancelled after the first chunk resumes from the partial byte-exact`() {
        File(root, "big.bin").writeBytes(payload)
        val downloads = File(dir, "dl").apply { mkdirs() }
        // interrupt: cancel as soon as the first progress callback lands
        val cancelAt = AtomicBoolean(false)
        var cutAt = 0L
        run {
            val part = File(downloads, "big.bin.part")
            val transport = SshjTransport(ssh.newSFTPClient(), chunk = 64 * 1024)
            try {
                part.outputStream().use { out ->
                    transport.download("/big.bin", 0, out, cancelAt) { n ->
                        cutAt = n
                        cancelAt.set(true)
                    }
                }
                error("expected TransferCancelled")
            } catch (e: TransferCancelled) {
                assertEquals(cutAt, e.at)
            }
            assertEquals(cutAt, part.length())
            assertTrue("should have stopped early", cutAt in 1 until payload.size.toLong())
        }

        // the queue picks the partial up and finishes it
        val offsets = mutableListOf<Long>()
        val queue = TransferQueue(
            downloadsDir = downloads,
            sftpProvider = { ssh.newSFTPClient() },
            transportFactory = { RecordingTransport(SshjTransport(it), offsets) },
            executor = Executors.newSingleThreadExecutor(),
        )
        try {
            val item = queue.enqueueDownload("big.bin", "/big.bin", payload.size.toLong())!!
            awaitTrue("download never finished: ${queue.items.value}", 30_000) {
                queue.items.value.first { it.id == item.id }.state is TransferQueue.State.Done
            }
            val done = queue.items.value.first { it.id == item.id }
            assertEquals("resumed at the partial's length", listOf(cutAt), offsets)
            assertArrayEquals(payload, File(downloads, "big.bin").readBytes())
            assertTrue(!File(downloads, "big.bin.part").exists())
            assertEquals(payload.size.toLong(), done.transferred)
        } finally {
            queue.close()
        }
    }

    @Test(timeout = 60_000)
    fun `upload appends from the remote's current length`() {
        // an earlier upload that died after 700 000 bytes
        File(root, "up.bin").writeBytes(payload.copyOf(700_000))
        val src = File(dir, "src.bin").apply { writeBytes(payload) }
        val offsets = mutableListOf<Long>()
        val queue = TransferQueue(
            downloadsDir = dir,
            sftpProvider = { ssh.newSFTPClient() },
            transportFactory = { RecordingTransport(SshjTransport(it), offsets) },
            executor = Executors.newSingleThreadExecutor(),
        )
        try {
            val item = queue.enqueueUpload(src, "/up.bin")!!
            awaitTrue("upload never finished: ${queue.items.value}", 30_000) {
                queue.items.value.first { it.id == item.id }.state is TransferQueue.State.Done
            }
            assertEquals("appended from the remote's length", listOf(700_000L), offsets)
            assertArrayEquals(payload, File(root, "up.bin").readBytes())
        } finally {
            queue.close()
        }
    }

    @Test(timeout = 60_000)
    fun `fresh download through the queue is byte-exact and lands under the final name`() {
        File(root, "fresh.bin").writeBytes(payload)
        val queue = TransferQueue(
            downloadsDir = dir,
            sftpProvider = { ssh.newSFTPClient() },
            executor = Executors.newSingleThreadExecutor(),
        )
        try {
            val item = queue.enqueueDownload("fresh.bin", "/fresh.bin", null)!!
            awaitTrue("download never finished: ${queue.items.value}", 30_000) {
                queue.items.value.first { it.id == item.id }.state is TransferQueue.State.Done
            }
            assertArrayEquals(payload, File(dir, "fresh.bin").readBytes())
            assertEquals(payload.size.toLong(), queue.items.value.first { it.id == item.id }.totalBytes)
        } finally {
            queue.close()
        }
    }

    /** Records the offset every transfer starts at. */
    private class RecordingTransport(
        private val inner: TransferTransport,
        private val offsets: MutableList<Long>,
    ) : TransferTransport by inner {
        override fun download(
            path: String,
            offset: Long,
            sink: java.io.OutputStream,
            cancelled: AtomicBoolean,
            onProgress: (Long) -> Unit,
        ) {
            offsets += offset
            inner.download(path, offset, sink, cancelled, onProgress)
        }

        override fun upload(
            source: java.io.InputStream,
            path: String,
            offset: Long,
            cancelled: AtomicBoolean,
            onProgress: (Long) -> Unit,
        ) {
            offsets += offset
            inner.upload(source, path, offset, cancelled, onProgress)
        }
    }
}
