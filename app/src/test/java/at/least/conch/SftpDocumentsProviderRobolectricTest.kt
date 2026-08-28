package at.least.conch

import android.content.pm.ProviderInfo
import android.database.Cursor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The SAF provider picker shell under Robolectric, backed by an in-memory
 * [SftpBackend] — roots, child cursors, CRUD, notifications and the pipe
 * plumbing of openDocument run exactly as the system would drive them.
 * Real-SFTP correctness lives in SftpProviderFsTest (MINA) and the Docker
 * OpenSSH test; keeping Robolectric off real SSH also keeps its sandbox
 * classloader away from sshj's JVM-global crypto provider registry.
 */
@RunWith(AndroidJUnit4::class)
class SftpDocumentsProviderRobolectricTest {

    /** In-memory SFTP world: a path set for dirs, a map for file contents. */
    private class FakeBackend : SftpBackend {
        val dirs = mutableSetOf("/")
        val files = mutableMapOf<String, ByteArray>()

        override fun homePath(hostId: String): String = "/"

        override fun list(hostId: String, path: String): List<SftpDocEntry> {
            val prefix = if (path == "/") "/" else "$path/"
            val children = (dirs + files.keys)
                .filter { it != "/" && it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
                .map { full ->
                    SftpDocEntry(
                        displayName = full.removePrefix(prefix),
                        isDir = full in dirs,
                        sizeBytes = files[full]?.size?.toLong() ?: 0,
                        modifiedSec = 1,
                    )
                }
            return children.sortedWith(
                compareByDescending<SftpDocEntry> { it.isDir }.thenBy { it.displayName },
            )
        }

        override fun stat(hostId: String, path: String): SftpDocEntry? {
            if (path in dirs) {
                return SftpDocEntry(path.substringAfterLast('/').ifEmpty { "/" }, true, 0, 1)
            }
            val f = files[path] ?: return null
            return SftpDocEntry(path.substringAfterLast('/'), false, f.size.toLong(), 1)
        }

        override fun mkdir(hostId: String, path: String) {
            dirs += path
        }

        override fun createFile(hostId: String, path: String) {
            files[path] = ByteArray(0)
        }

        override fun delete(hostId: String, path: String) {
            dirs -= path
            files.remove(path)
        }

        override fun rename(hostId: String, from: String, to: String) {
            if (from in dirs) {
                dirs -= from
                dirs += to
            } else {
                files[to] = files.remove(from) ?: ByteArray(0)
            }
        }

        override fun openRead(hostId: String, path: String): InputStream =
            files[path]?.inputStream() ?: error("no such file: $path")

        override fun openWrite(hostId: String, path: String): OutputStream =
            object : OutputStream() {
                val buf = ByteArrayOutputStream()

                override fun write(b: Int) {
                    buf.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    buf.write(b, off, len)
                }

                override fun close() {
                    files[path] = buf.toByteArray()
                }
            }
    }

    private val backend = FakeBackend()
    private lateinit var provider: SftpDocumentsProvider

    private val host = Host(
        id = "saf-host",
        alias = "Matrix",
        hostname = "127.0.0.1",
        username = "testuser",
        authType = Host.AUTH_PASSWORD,
        safExpose = true,
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        HostStore(ctx).save(listOf(host))

        SftpDocumentsProvider.backendOverride = { _ -> backend }
        provider = SftpDocumentsProvider()
        val info = ProviderInfo()
        info.authority = SftpDocumentsProvider.AUTHORITY
        info.exported = true // DocumentsProvider.attachInfo enforces:
        info.grantUriPermissions = true // exported + grantable + guarded by
        info.readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
        info.writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        provider.attachInfo(ctx, info)
        assertTrue(provider.onCreate())
    }

    @After
    fun tearDown() {
        SftpDocumentsProvider.backendOverride = null
    }

    private fun rootDocumentId() = SftpDocIds.encode(host.id, SftpDocIds.ROOT_PATH)

    /** disambiguates the String-sortOrder overload from the Bundle one */
    private fun childrenOf(docId: String): Cursor =
        provider.queryChildDocuments(docId, null as Array<out String>?, null as String?)

    private fun names(cursor: Cursor): List<String> {
        val out = mutableListOf<String>()
        val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        while (cursor.moveToNext()) out += cursor.getString(idx)
        return out
    }

    @Test
    fun `roots list only saf-exposed hosts`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        provider.queryRoots(null).use { roots ->
            assertEquals(1, roots.count)
            roots.moveToFirst()
            assertEquals(
                host.id,
                roots.getString(roots.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)),
            )
            assertEquals("Matrix", roots.getString(roots.getColumnIndex(DocumentsContract.Root.COLUMN_TITLE)))
        }

        // opt-out host disappears from the picker entirely
        HostStore(ctx).save(listOf(host.copy(safExpose = false)))
        provider.queryRoots(null).use { assertEquals(0, it.count) }
        HostStore(ctx).save(listOf(host))
    }

    @Test
    fun `root document is a writable dir and children list through`() {
        provider.queryDocument(rootDocumentId(), null).use { root ->
            root.moveToFirst()
            assertEquals(
                DocumentsContract.Document.MIME_TYPE_DIR,
                root.getString(root.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)),
            )
        }

        provider.createDocument(rootDocumentId(), DocumentsContract.Document.MIME_TYPE_DIR, "docs")
        val txtId = provider.createDocument(rootDocumentId(), "text/plain", "hello.txt")

        childrenOf(rootDocumentId()).use { children ->
            assertEquals(listOf("docs", "hello.txt"), names(children))
        }

        provider.queryDocument(txtId, null).use { row ->
            row.moveToFirst()
            assertEquals(
                "hello.txt",
                row.getString(row.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)),
            )
        }
        assertTrue(SftpDocIds.pathOf(txtId).endsWith("/hello.txt"))
    }

    @Test
    fun `openDocument write direction moves bytes through the pipe`() {
        val txtId = provider.createDocument(rootDocumentId(), "text/plain", "data.bin")
        val payload = ByteArray(50_000) { i -> ((i * 7 + 1) and 0xFF).toByte() }

        provider.openDocument(txtId, "w", null).use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { it.write(payload) }
        }

        // the write lands on a copier thread — wait until it does
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (backend.files[SftpDocIds.pathOf(txtId)]?.size == payload.size) break
            Thread.sleep(20)
        }
        assertArrayEquals(payload, backend.files[SftpDocIds.pathOf(txtId)])

        // Read direction: Robolectric's pipe emulation can discard buffered
        // data when the copier closes its write end before the test drains
        // it — an artifact real platforms don't have (AOSP's own providers
        // rely on it). We still exercise that openDocument("r") returns a
        // usable descriptor; content correctness of openRead is pinned by
        // SftpProviderFsTest and the Docker OpenSSH test.
        provider.openDocument(txtId, "r", null).use { pfd ->
            assertTrue(pfd.fileDescriptor.valid())
        }
    }

    @Test
    fun `rename and delete keep the tree consistent`() {
        val dirId = provider.createDocument(rootDocumentId(), DocumentsContract.Document.MIME_TYPE_DIR, "dir")
        val fileId = provider.createDocument(dirId, "text/plain", "a.txt")

        val renamed = provider.renameDocument(fileId, "b.txt")
        assertTrue(SftpDocIds.pathOf(renamed!!).endsWith("/b.txt"))
        childrenOf(dirId).use { assertEquals(listOf("b.txt"), names(it)) }

        provider.deleteDocument(renamed)
        provider.deleteDocument(dirId)
        childrenOf(rootDocumentId()).use { assertEquals(emptyList<String>(), names(it)) }
    }

    @Test
    fun `isChildDocument is a host-scoped prefix check`() {
        val dirId = provider.createDocument(rootDocumentId(), DocumentsContract.Document.MIME_TYPE_DIR, "dir")
        val fileId = provider.createDocument(dirId, "text/plain", "x.txt")
        assertTrue(provider.isChildDocument(rootDocumentId(), dirId))
        assertTrue(provider.isChildDocument(dirId, fileId))
        assertTrue(provider.isChildDocument(rootDocumentId(), fileId))
        assertTrue(!provider.isChildDocument("other-host:", fileId))
    }

    @Test
    fun `mutations notify the picker`() {
        var notified = 0
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val uri = DocumentsContract.buildChildDocumentsUri(SftpDocumentsProvider.AUTHORITY, rootDocumentId())
        val observer = object : android.database.ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                notified++
            }
        }
        ctx.contentResolver.registerContentObserver(uri, false, observer)
        try {
            provider.createDocument(rootDocumentId(), "text/plain", "n.txt")
            assertTrue("createDocument must notify child cursor observers", notified > 0)
        } finally {
            ctx.contentResolver.unregisterContentObserver(observer)
        }
    }
}
