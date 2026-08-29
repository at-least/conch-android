package at.least.conch

import android.content.Context
import android.net.Uri
import net.schmizz.sshj.sftp.SFTPClient
import java.io.File

/**
 * One local `content://` (or `file://`) Uri → one remote SFTP path. Shared
 * by the Files tab's Upload button and share-to-host so the staging rule
 * lives once: the stream is copied to a temp file in cacheDir (sshj
 * uploads from a path, and a content Uri has none), then removed.
 * Blocking — call off the main thread.
 */
internal fun uploadUri(context: Context, sftp: SFTPClient, uri: Uri, remotePath: String) {
    var tmp: File? = null
    try {
        tmp = File.createTempFile("up", null, context.cacheDir).also { t ->
            context.contentResolver.openInputStream(uri)?.use { input ->
                t.outputStream().use { input.copyTo(it) }
            } ?: error("Cannot read file")
        }
        sftp.fileTransfer.upload(tmp.absolutePath, remotePath)
    } finally {
        tmp?.delete()
    }
}
