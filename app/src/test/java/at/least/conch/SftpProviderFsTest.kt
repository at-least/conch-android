package at.least.conch

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * SftpProviderFs against the in-process MINA SFTP server: real wire
 * operations (ls/stat/mkdir/rename/rm/open) plus the two provider-specific
 * contracts — dirs-first listing and one pooled connection per host no
 * matter how many operations run.
 */
class SftpProviderFsTest {

    private lateinit var scratch: File
    private lateinit var sftpRoot: File
    private lateinit var server: TestSshd
    private lateinit var store: KnownHostsStore
    private lateinit var fs: SftpProviderFs

    private val connects = AtomicInteger(0)

    @Before
    fun setUp() {
        scratch = Files.createTempDirectory("conch-saf").toFile()
        sftpRoot = Files.createTempDirectory("conch-saf-root").toFile()
        server = TestSshd(sftpRoot = sftpRoot).start()
        store = KnownHostsStore(scratch)
        val host = Host(
            id = "saf-1",
            hostname = "127.0.0.1",
            username = server.user,
            authType = Host.AUTH_PASSWORD,
        )
        fs = SftpProviderFs(
            loadHost = { if (it == host.id) host else null },
            connectHost = {
                connects.incrementAndGet()
                connectTrusted(server, store, host = it)
            },
        )
    }

    @After
    fun tearDown() {
        runCatching { fs.close() }
        server.close()
        scratch.deleteRecursively()
        sftpRoot.deleteRecursively()
    }

    @Test(timeout = 30_000)
    fun `home resolves once and lists sort dirs first then name`() {
        val home = fs.homePath("saf-1")
        assertTrue("home should be absolute: $home", home.startsWith("/"))

        fs.mkdir("saf-1", "$home/z-dir")
        fs.mkdir("saf-1", "$home/a-dir")
        fs.createFile("saf-1", "$home/b.txt")
        fs.createFile("saf-1", "$home/a.txt")

        val names = fs.list("saf-1", SftpDocIds.ROOT_PATH).map { it.displayName }
        assertEquals(listOf("a-dir", "z-dir", "a.txt", "b.txt"), names)
    }

    @Test(timeout = 30_000)
    fun `write then read round-trips through leased streams`() {
        val payload = ByteArray(70_000) { i -> ((i * 41 + 3) and 0xFF).toByte() }
        fs.openWrite("saf-1", "bin.dat").use { it.write(payload) }
        assertArrayEquals(payload, fs.openRead("saf-1", "bin.dat").use { it.readBytes() })

        val entry = fs.stat("saf-1", "bin.dat")
        assertEquals("bin.dat", entry?.displayName)
        assertEquals(payload.size.toLong(), entry?.sizeBytes)
        assertFalse(entry?.isDir ?: true)
    }

    @Test(timeout = 30_000)
    fun `rename moves, delete removes files and directories`() {
        fs.mkdir("saf-1", "d")
        fs.openWrite("saf-1", "d/f.txt").use { it.write("x".toByteArray()) }
        fs.rename("saf-1", "d/f.txt", "d/g.txt")
        assertEquals(listOf("g.txt"), fs.list("saf-1", "d").map { it.displayName })

        fs.delete("saf-1", "d/g.txt")
        assertEquals(emptyList<String>(), fs.list("saf-1", "d").map { it.displayName })

        fs.delete("saf-1", "d")
        assertNull(fs.stat("saf-1", "d"))
    }

    @Test(timeout = 30_000)
    fun `many operations reuse one pooled connection`() {
        repeat(5) { i ->
            fs.createFile("saf-1", "f$i.txt")
            fs.list("saf-1", SftpDocIds.ROOT_PATH)
        }
        assertEquals("pool must open exactly one connection per host", 1, connects.get())
    }

    @Test(timeout = 30_000)
    fun `unknown host fails instead of connecting`() {
        // an unknown host is a config error, not a missing file — it throws
        org.junit.Assert.assertThrows(net.schmizz.sshj.sftp.SFTPException::class.java) {
            fs.stat("no-such-host", "/")
        }
        assertEquals(0, connects.get())
    }
}

/** docId codec is the whole navigation model — pin it exactly. */
class SftpDocIdsTest {

    @Test
    fun `encode and decode round-trip any path`() {
        val id = SftpDocIds.encode("uuid-1", "/deep/dir with spaces/файл.txt")
        assertEquals("uuid-1", SftpDocIds.hostOf(id))
        assertEquals("/deep/dir with spaces/файл.txt", SftpDocIds.pathOf(id))
    }

    @Test
    fun `root document id is host-colon`() {
        val root = SftpDocIds.encode("uuid-1", SftpDocIds.ROOT_PATH)
        assertEquals("uuid-1:", root)
        assertTrue(SftpDocIds.isRoot(root))
        assertFalse(SftpDocIds.isRoot("uuid-1:/x"))
        assertNull(SftpDocIds.hostOf("no-colon-here"))
    }

    @Test
    fun `parents walk up and stop at root`() {
        assertEquals("/a", SftpDocIds.parentPath("/a/b"))
        assertEquals("/", SftpDocIds.parentPath("/a"))
        assertEquals(SftpDocIds.ROOT_PATH, SftpDocIds.parentPath("/"))
        assertEquals(SftpDocIds.ROOT_PATH, SftpDocIds.parentPath(""))
    }

    @Test
    fun `children append without doubling slashes`() {
        assertEquals("/name", SftpDocIds.childPath("/", "name"))
        assertEquals("/name", SftpDocIds.childPath("", "name"))
        assertEquals("/a/b", SftpDocIds.childPath("/a", "b"))
    }
}
