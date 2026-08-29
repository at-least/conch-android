package at.least.conch

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * SFTP edge cases through the app's real SAF backend ([SftpProviderFs]) and
 * real internal-sftp — the cases a file manager actually hits and the
 * in-process MINA server cannot stress: a large transfer, hundreds of
 * entries in one directory, non-ASCII filenames, symlinks, a permission
 * denial, and a write that runs the filesystem out of space (a 1 MB tmpfs
 * mounted at /mnt/tiny). Each must fail cleanly (an IOException the SAF layer
 * can surface) rather than hang, corrupt, or crash.
 *
 * Same opt-in as [DockerSshdAuthTest] (see [DockerMatrix]).
 */
class DockerSftpStressTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fs: SftpProviderFs
    private lateinit var host: Host
    private lateinit var home: String
    private lateinit var work: String

    @Before
    fun setUp() {
        DockerMatrix.requireMatrix()
        val store = KnownHostsStore(tmp.newFolder())
        host = DockerMatrix.pwHost().copy(id = "sftp-stress")
        fs = SftpProviderFs(
            loadHost = { if (it == host.id) host else null },
            connectHost = { h -> DockerMatrix.connect(store, h) },
        )
        home = fs.homePath(host.id)
        work = "$home/stress-${System.nanoTime()}"
        fs.mkdir(host.id, work)
    }

    @After
    fun tearDown() {
        if (this::fs.isInitialized) {
            DockerMatrix.dockerExec("rm -rf '$work' /mnt/tiny/*", allowFailure = true)
            fs.close()
        }
    }

    @Test(timeout = 180_000)
    fun `a large file round-trips byte-exact through the saf backend`() {
        val payload = ByteArray(16 * 1024 * 1024) { i -> ((i * 2654435761L.toInt()) ushr 13).toByte() }
        val path = "$work/large.bin"
        fs.openWrite(host.id, path).use { out ->
            var off = 0
            val chunk = 64 * 1024
            while (off < payload.size) {
                out.write(payload, off, minOf(chunk, payload.size - off))
                off += chunk
            }
        }
        val back = fs.openRead(host.id, path).use { it.readBytes() }
        assertEquals("size mismatch", payload.size, back.size)
        assertEquals(sha256Hex(payload), sha256Hex(back))
        // server-side hash agrees (no silent truncation on the wire)
        val remote = DockerMatrix.dockerExec("sha256sum '$path' | cut -d' ' -f1").trim()
        assertEquals(sha256Hex(payload), remote)
    }

    @Test(timeout = 120_000)
    fun `hundreds of entries in one directory all list`() {
        val n = 400
        repeat(n) { i -> fs.createFile(host.id, "$work/f_%04d.txt".format(i)) }
        val listed = fs.list(host.id, work).map { it.displayName }.filter { it.startsWith("f_") }
        assertEquals("not every entry listed", n, listed.size)
        assertTrue("listing lost an entry", listed.contains("f_0000.txt") && listed.contains("f_0399.txt"))
    }

    @Test(timeout = 60_000)
    fun `non-ASCII filenames round-trip`() {
        val names = listOf("café.txt", "日本語のファイル.bin", "emoji-😀-name.dat", "naïve—dash.log")
        for (name in names) {
            val payload = name.toByteArray(Charsets.UTF_8) + ByteArray(200) { it.toByte() }
            fs.openWrite(host.id, "$work/$name").use { it.write(payload) }
            val back = fs.openRead(host.id, "$work/$name").use { it.readBytes() }
            assertArrayEquals("round-trip corrupted for $name", payload, back)
        }
        val listed = fs.list(host.id, work).map { it.displayName }.toSet()
        assertTrue("a non-ASCII name went missing: $listed", listed.containsAll(names))
    }

    @Test(timeout = 60_000)
    fun `a symlink is listed and its target is readable`() {
        fs.openWrite(host.id, "$work/target.txt").use { it.write("SYMLINK_TARGET".toByteArray()) }
        DockerMatrix.dockerExec("ln -s target.txt '$work/link.txt'")
        val entries = fs.list(host.id, work).map { it.displayName }
        assertTrue("symlink not listed: $entries", entries.contains("link.txt"))
        // following the link reads the target's bytes
        val viaLink = fs.openRead(host.id, "$work/link.txt").use { it.readBytes() }
        assertEquals("SYMLINK_TARGET", String(viaLink))
    }

    @Test(timeout = 60_000)
    fun `writing into a directory owned by another user is denied cleanly`() {
        val e = runCatching {
            fs.openWrite(host.id, "/root/conch-should-not-write").use { it.write(byteArrayOf(1, 2, 3)) }
        }.exceptionOrNull()
        assertTrue(
            "expected a clean IOException for permission denial, got: $e",
            e is IOException || e is net.schmizz.sshj.sftp.SFTPException
        )
    }

    @Test(timeout = 60_000)
    fun `a write that fills the filesystem fails cleanly rather than hanging`() {
        // /mnt/tiny is a 1 MB tmpfs; a 4 MB write must run it out of space
        val path = "/mnt/tiny/toobig.bin"
        val e = runCatching {
            fs.openWrite(host.id, path).use { out ->
                val chunk = ByteArray(64 * 1024) { 0x5A }
                repeat(64) { out.write(chunk) } // 4 MB total
            }
        }.exceptionOrNull()
        assertTrue("expected an out-of-space failure, got: $e", e != null)
        // the SFTP connection is still healthy afterwards — one bad write did
        // not poison the lease (a follow-up op over the same backend works)
        fs.openWrite(host.id, "$work/after-full.txt").use { it.write("STILL_OK".toByteArray()) }
        assertEquals("STILL_OK", String(fs.openRead(host.id, "$work/after-full.txt").use { it.readBytes() }))
    }
}
