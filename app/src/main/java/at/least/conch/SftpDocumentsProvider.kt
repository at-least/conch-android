package at.least.conch

import android.content.Context
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Exposes SAF-enabled hosts to Android's system file pickers and every
 * app that can OPEN_DOCUMENT / OPEN_DOCUMENT_TREE (improvement-plan 3.1,
 * the iOS Secure ShellFish gap on Android).
 *
 * Deliberately thin: all SSH/SFTP work lives in [SftpProviderFs] (plain
 * JVM, tested against MINA and real OpenSSH); this class only maps cursor
 * rows and pipes streams. Roots are per-host and OPT-IN (Host.safExpose):
 * a root is visible in every picker UI on the device, so nothing is
 * exposed without an explicit user decision.
 */
class SftpDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "at.least.conch.sftp"

        /** Robolectric swaps the backend in before attachInfo. */
        @Volatile
        var backendOverride: ((Context) -> SftpBackend)? = null
    }

    private lateinit var fs: SftpBackend

    private val ctx: Context get() = context!!

    override fun onCreate(): Boolean {
        fs = backendOverride?.invoke(ctx)
            ?: SftpProviderFs(
                loadHost = { id -> HostStore(ctx).load().firstOrNull { it.id == id } },
                connectHost = { host -> SshConnectionFactory.connect(context = ctx, host = host, prompt = null) },
            )
        return true
    }

    private val defaultProjection: Array<String>
        get() = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

    override fun queryRoots(projection: Array<out String>?): MatrixCursor {
        val cursor = MatrixCursor(
            projection ?: arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            ),
        )
        for (host in HostStore(ctx).load().filter { it.safExpose }) {
            cursor.newRow().apply {
                add(DocumentsContract.Root.COLUMN_ROOT_ID, host.id)
                add(DocumentsContract.Root.COLUMN_TITLE, host.alias.ifEmpty { host.hostname })
                add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, SftpDocIds.encode(host.id, SftpDocIds.ROOT_PATH))
                add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, -1)
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): MatrixCursor {
        val cursor = MatrixCursor(projection ?: defaultProjection)
        val docId = documentId ?: return cursor
        val hostId = SftpDocIds.hostOf(docId) ?: return cursor
        if (SftpDocIds.isRoot(docId)) {
            val host = HostStore(ctx).load().firstOrNull { it.id == hostId }
            cursor.newRow().apply {
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
                add(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    host?.alias?.ifEmpty { host.hostname } ?: hostId,
                )
                add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                add(DocumentsContract.Document.COLUMN_FLAGS, rootFlags())
                add(DocumentsContract.Document.COLUMN_SIZE, 0)
            }
            return cursor
        }
        val entry = fs.stat(hostId, SftpDocIds.pathOf(docId)) ?: throw FileNotFoundException(docId)
        addEntryRow(cursor, hostId, SftpDocIds.parentPath(SftpDocIds.pathOf(docId)), entry)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?,
    ): MatrixCursor {
        val cursor = MatrixCursor(projection ?: defaultProjection)
        cursor.setNotificationUri(
            ctx.contentResolver,
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId),
        )
        val docId = parentDocumentId ?: return cursor
        val hostId = SftpDocIds.hostOf(docId) ?: return cursor
        val path = SftpDocIds.pathOf(docId)
        for (entry in fs.list(hostId, path)) {
            addEntryRow(cursor, hostId, path, entry)
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        if (parentDocumentId == null || documentId == null) return false
        val parentHost = SftpDocIds.hostOf(parentDocumentId) ?: return false
        val childHost = SftpDocIds.hostOf(documentId) ?: return false
        if (parentHost != childHost) return false
        val parentPath = SftpDocIds.pathOf(parentDocumentId)
        val childPath = SftpDocIds.pathOf(documentId)
        if (parentPath == SftpDocIds.ROOT_PATH) return true // home roots everything below
        return childPath.startsWith("$parentPath/")
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val docId = documentId ?: throw FileNotFoundException("no document")
        val hostId = SftpDocIds.hostOf(docId) ?: throw FileNotFoundException("bad id: $docId")
        val path = SftpDocIds.pathOf(docId)
        val writable = mode?.contains('w') == true
        val pipe = ParcelFileDescriptor.createPipe() // [0] read end, [1] write end
        if (writable) {
            val target = fs.openWrite(hostId, path)
            Thread {
                // a cancelled write just ends the copy; the file stays
                // partial, exactly like an editor crash mid-save
                runCatching {
                    FileInputStream(pipe[0].fileDescriptor).use { input ->
                        target.use { output -> input.copyTo(output) }
                    }
                }.onFailure { CrashReporting.report(it) }
            }.apply {
                name = "conch-saf-write"
                isDaemon = true
                start()
            }
            return pipe[1]
        }
        val source = fs.openRead(hostId, path)
        Thread {
            runCatching {
                FileOutputStream(pipe[1].fileDescriptor).use { output ->
                    source.use { input -> input.copyTo(output) }
                }
            }.onFailure { CrashReporting.report(it) }
        }.apply {
            name = "conch-saf-read"
            isDaemon = true
            start()
        }
        return pipe[0]
    }

    override fun createDocument(
        parentDocumentId: String?,
        mimeType: String?,
        displayName: String?,
    ): String {
        val docId = parentDocumentId ?: throw FileNotFoundException("no parent")
        val hostId = SftpDocIds.hostOf(docId) ?: throw FileNotFoundException("bad parent: $docId")
        val parentPath = SftpDocIds.pathOf(docId)
        val name = displayName?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("no name")
        val childPath = SftpDocIds.childPath(
            if (parentPath == SftpDocIds.ROOT_PATH) fs.homePath(hostId) else parentPath,
            name,
        )
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            fs.mkdir(hostId, childPath)
        } else {
            fs.createFile(hostId, childPath)
        }
        notifyChildrenChanged(docId)
        return SftpDocIds.encode(hostId, childPath)
    }

    override fun deleteDocument(documentId: String?) {
        val docId = documentId ?: throw FileNotFoundException("no document")
        val hostId = SftpDocIds.hostOf(docId) ?: throw FileNotFoundException("bad id: $docId")
        fs.delete(hostId, SftpDocIds.pathOf(docId))
        notifyParentChanged(docId)
    }

    override fun renameDocument(documentId: String?, displayName: String?): String? {
        val docId = documentId ?: throw FileNotFoundException("no document")
        val name = displayName?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("no name")
        val hostId = SftpDocIds.hostOf(docId) ?: throw FileNotFoundException("bad id: $docId")
        val from = SftpDocIds.pathOf(docId)
        val to = SftpDocIds.childPath(SftpDocIds.parentPath(from), name)
        if (from == to) return docId
        fs.rename(hostId, from, to)
        notifyParentChanged(docId)
        return SftpDocIds.encode(hostId, to)
    }

    // ------------------------------------------------------------- rows

    private fun addEntryRow(cursor: MatrixCursor, hostId: String, parentPath: String, entry: SftpDocEntry) {
        val path = SftpDocIds.childPath(
            if (parentPath == SftpDocIds.ROOT_PATH) fs.homePath(hostId) else parentPath,
            entry.displayName,
        )
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, SftpDocIds.encode(hostId, path))
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, entry.displayName)
            add(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                if (entry.isDir) {
                    DocumentsContract.Document.MIME_TYPE_DIR
                } else {
                    "application/octet-stream"
                },
            )
            add(
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME,
            )
            add(DocumentsContract.Document.COLUMN_SIZE, entry.sizeBytes)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, entry.modifiedSec * 1000)
        }
    }

    private fun rootFlags(): Int =
        DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
            DocumentsContract.Document.FLAG_SUPPORTS_RENAME

    private fun notifyChildrenChanged(parentDocId: String) {
        DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocId).let {
            ctx.contentResolver.notifyChange(it, null)
        }
    }

    private fun notifyParentChanged(docId: String) {
        val parent = DocumentsContract.buildDocumentUri(
            AUTHORITY,
            SftpDocIds.encode(SftpDocIds.hostOf(docId) ?: "", SftpDocIds.parentPath(SftpDocIds.pathOf(docId))),
        )
        val children = DocumentsContract.buildChildDocumentsUri(AUTHORITY, docId)
        ctx.contentResolver.notifyChange(parent, null)
        ctx.contentResolver.notifyChange(children, null)
    }
}
