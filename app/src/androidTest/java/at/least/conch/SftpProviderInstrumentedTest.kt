package at.least.conch

import android.content.Context
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

/**
 * The SAF provider the way the system's file picker drives it: through
 * ContentResolver, across the real provider IPC (same-UID caller, so the
 * MANAGE_DOCUMENTS gate is satisfied), with a real SFTP backend against
 * the Docker matrix — roots, listing, create/write/read/rename/delete,
 * using the platform's ParcelFileDescriptor pipes that Robolectric only
 * emulates.
 */
@RunWith(AndroidJUnit4::class)
class SftpProviderInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver get() = context.contentResolver
    private lateinit var host: Host

    @Before
    fun setUp() {
        host = MatrixDevice.passwordHost(alias = "matrix-saf", safExpose = true)
        MatrixDevice.seedHost(context, host)
        // the provider connects promptless: pin the host key first
        MatrixDevice.requireMatrix()
        SshConnectionFactory.connect(context, host, MatrixDevice.acceptPrompt).disconnect()
    }

    @After
    fun tearDown() {
        MatrixDevice.removeHosts(context) { it.id == host.id }
    }

    private fun childrenOf(docId: String): Map<String, String> {
        val uri = DocumentsContract.buildChildDocumentsUri(SftpDocumentsProvider.AUTHORITY, docId)
        resolver.query(uri, null, null, null, null).use { c ->
            checkNotNull(c) { "no cursor for $uri" }
            val out = mutableMapOf<String, String>()
            val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (c.moveToNext()) out[c.getString(nameCol)] = c.getString(idCol)
            return out
        }
    }

    @Test
    fun `picker_flow_over_content_resolver_round-trips_a_file_on_real_openssh`() {
        val rootsUri = DocumentsContract.buildRootsUri(SftpDocumentsProvider.AUTHORITY)
        val rootDoc = resolver.query(rootsUri, null, null, null, null).use { c ->
            checkNotNull(c)
            var found: String? = null
            val idCol = c.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_ROOT_ID)
            val docCol = c.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
            while (c.moveToNext()) if (c.getString(idCol) == host.id) found = c.getString(docCol)
            checkNotNull(found) { "host not exposed as a SAF root" }
        }
        val home = childrenOf(rootDoc)
        // /home/pwuser is the root; create a folder + file exactly as a picker would
        val rootUri = DocumentsContract.buildDocumentUri(SftpDocumentsProvider.AUTHORITY, rootDoc)
        val dirUri = DocumentsContract.createDocument(resolver, rootUri, DocumentsContract.Document.MIME_TYPE_DIR, "saf-device")
        checkNotNull(dirUri)
        val fileUri = DocumentsContract.createDocument(resolver, dirUri, "application/octet-stream", "d.bin")
        checkNotNull(fileUri)
        val payload = ByteArray(200_000) { i -> ((i * 71 + 13) and 0xFF).toByte() }
        resolver.openOutputStream(fileUri, "w")!!.use { it.write(payload) }
        val back = resolver.openInputStream(fileUri)!!.use { it.readBytes() }
        assertArrayEquals(payload, back)

        val renamed = DocumentsContract.renameDocument(resolver, fileUri, "e.bin")
        checkNotNull(renamed)
        val dirId = DocumentsContract.getDocumentId(dirUri)
        assertEquals(setOf("e.bin"), childrenOf(dirId).keys)

        assertTrue(DocumentsContract.deleteDocument(resolver, renamed))
        assertTrue(DocumentsContract.deleteDocument(resolver, dirUri))
        assertEquals(home.keys, childrenOf(rootDoc).keys)
    }
}
