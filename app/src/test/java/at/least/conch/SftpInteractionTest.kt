package at.least.conch

import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SFTP subsystem interaction — the exact SFTPClient calls the Files tab (SessionTabs) makes:
 * canonicalize/ls, upload/download, mkdir/rm/rmdir/rename, open(CREAT).
 */
class SftpInteractionTest {

    private lateinit var dir: File
    private lateinit var root: File
    private lateinit var server: TestSshd
    private lateinit var ssh: net.schmizz.sshj.SSHClient
    private lateinit var sftp: SFTPClient

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conch-sftp").toFile()
        root = Files.createTempDirectory("conch-sftp-root").toFile()
        server = TestSshd(sftpRoot = root).start()
        ssh = connectTrusted(server, KnownHostsStore(dir))
        sftp = ssh.newSFTPClient()
    }

    @After
    fun tearDown() {
        runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
        server.close()
        dir.deleteRecursively()
        root.deleteRecursively()
    }

    @Test(timeout = 30_000)
    fun `canonicalize dot returns the virtual home`() {
        val home = sftp.canonicalize(".")
        assertNotNull(home)
        assertTrue("home should be an absolute virtual path: $home", home.startsWith("/"))
    }

    @Test(timeout = 30_000)
    fun `mkdir then ls lists the new directory`() {
        sftp.mkdir("/docs")
        val names = sftp.ls("/").filter { it.name != "." && it.name != ".." }.map { it.name }
        assertEquals(listOf("docs"), names)
        val entry = sftp.ls("/").first { it.name == "docs" }
        assertTrue(entry.isDirectory)
    }

    @Test(timeout = 30_000)
    fun `upload and get round-trip file content`() {
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        val local = File.createTempFile("conch-up", ".bin")
        local.writeBytes(payload)
        try {
            // Files tab upload(): getFileTransfer().upload(local, remote)
            sftp.getFileTransfer().upload(local.absolutePath, "/blob.bin")

            val listed = sftp.ls("/").first { it.name == "blob.bin" }
            assertFalse(listed.isDirectory)
            assertEquals(payload.size.toLong(), listed.attributes.size)

            // Files tab download(): sftp.get(remote, localPath)
            val down = File.createTempFile("conch-down", ".bin")
            try {
                sftp.get("/blob.bin", down.absolutePath)
                assertArrayEquals(payload, down.readBytes())
            } finally {
                down.delete()
            }

            // file really lives in the server's backing directory
            assertArrayEquals(payload, File(root, "blob.bin").readBytes())
        } finally {
            local.delete()
        }
    }

    @Test(timeout = 30_000)
    fun `ls reports mtime and size attributes`() {
        File(root, "stats.txt").writeText("hello world")
        val entry: RemoteResourceInfo = sftp.ls("/").first { it.name == "stats.txt" }
        assertEquals(11L, entry.attributes.size)
        val ageMs = System.currentTimeMillis() - entry.attributes.mtime * 1000L
        assertTrue("mtime should be recent, was ${ageMs}ms old", ageMs in 0..120_000)
    }

    @Test(timeout = 30_000)
    fun `rm deletes a file`() {
        File(root, "junk.txt").writeText("x")
        sftp.rm("/junk.txt")
        assertTrue(sftp.ls("/").none { it.name == "junk.txt" })
        assertFalse(File(root, "junk.txt").exists())
    }

    @Test(timeout = 30_000)
    fun `rmdir deletes an empty directory`() {
        File(root, "emptydir").mkdirs()
        sftp.rmdir("/emptydir")
        assertFalse(File(root, "emptydir").exists())
    }

    @Test(timeout = 30_000)
    fun `rename moves a file within the same directory`() {
        File(root, "old-name.txt").writeText("payload")
        // Files tab rename(): parent + "/" + newName
        sftp.rename("/old-name.txt", "/new-name.txt")
        assertFalse(File(root, "old-name.txt").exists())
        assertEquals("payload", File(root, "new-name.txt").readText())
    }

    @Test(timeout = 30_000)
    fun `open with CREAT creates an empty file`() {
        // Files tab newFile(): open(remote, CREAT).close()
        sftp.open(
            "/fresh.txt",
            java.util.Collections.singleton(net.schmizz.sshj.sftp.OpenMode.CREAT),
        ).close()
        val f = File(root, "fresh.txt")
        assertTrue("file should exist in backing dir", f.exists())
        assertEquals(0L, f.length())
    }

    @Test(timeout = 30_000)
    fun `get on a missing file fails with SFTP error`() {
        val local = File.createTempFile("conch-x", null)
        try {
            sftp.get("/definitely-missing.bin", local.absolutePath)
            fail("expected SFTPException")
        } catch (e: SFTPException) {
            assertNotNull(e.statusCode)
        } finally {
            local.delete()
        }
    }

    @Test(timeout = 30_000)
    fun `nested directories list their own contents`() {
        File(root, "a/b").mkdirs()
        File(root, "a/b/c.txt").writeText("deep")
        val names = sftp.ls("/a/b").filter { it.name != "." && it.name != ".." }.map { it.name }
        assertEquals(listOf("c.txt"), names)
    }

    // ------------------------------------------------------ odd boundaries
    // paramiko's test_sftp_big reads on odd chunk sizes (629/793) to catch
    // byte scrambling; ours does the same through RemoteFile.

    @Test(timeout = 60_000)
    fun `odd-offset odd-length reads return unscrambled bytes`() {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        File(root, "odd.bin").writeBytes(payload)
        sftp.open("/odd.bin", java.util.EnumSet.of(net.schmizz.sshj.sftp.OpenMode.READ)).use { rf ->
            var offset = 0L
            val chunk = 629
            while (offset < payload.size) {
                val len = minOf(chunk, (payload.size - offset).toInt())
                val data = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = rf.read(offset + got, data, got, len - got)
                    if (n < 0) throw AssertionError("EOF at ${offset + got}")
                    got += n
                }
                org.junit.Assert.assertArrayEquals(
                    "scrambled at offset $offset",
                    payload.copyOfRange(offset.toInt(), (offset + len).toInt()),
                    data,
                )
                offset += len
            }
        }
    }

    @Test(timeout = 60_000)
    fun `odd-length writes at offsets land exactly`() {
        sftp.open(
            "/odd-writes.bin",
            java.util.EnumSet.of(net.schmizz.sshj.sftp.OpenMode.CREAT, net.schmizz.sshj.sftp.OpenMode.WRITE),
        ).use { rf ->
            var offset = 0L
            val chunk = ByteArray(793) { ((it * 7 + 3) % 256).toByte() }
            repeat(20) {
                rf.write(offset, chunk, 0, chunk.size)
                offset += chunk.size
            }
        }
        val written = File(root, "odd-writes.bin").readBytes()
        assertEquals(20 * 793, written.size)
        val expected = ByteArray(20 * 793) { ((it % 793) * 7 + 3).let { v -> (v % 256).toByte() } }
        assertArrayEquals(expected, written)
    }

    @Test(timeout = 120_000)
    fun `hundred small files survive create and list`() {
        // paramiko's test_lots_of_files: many small files over one session
        val locals = (1..100).map { i ->
            File.createTempFile("conch-many", ".txt").apply { writeText("this is file #$i.\n") }
        }
        try {
            locals.forEachIndexed { i, f -> sftp.getFileTransfer().upload(f.absolutePath, "/many${i + 1}.txt") }
            val listed = sftp.ls("/").filter { it.name.startsWith("many") }.map { it.name }
            assertEquals(
                (1..100).map { "many$it.txt" }.toSet(),
                listed.toSet(),
            )
            assertEquals(100, listed.size)
            // spot-check random-order contents
            for (i in listOf(37, 4, 91, 68, 15)) {
                val check = File.createTempFile("conch-check", ".txt")
                try {
                    sftp.get("/many$i.txt", check.absolutePath)
                    assertEquals("this is file #$i.\n", check.readText())
                } finally {
                    check.delete()
                }
            }
        } finally {
            locals.forEach { it.delete() }
        }
    }
}
