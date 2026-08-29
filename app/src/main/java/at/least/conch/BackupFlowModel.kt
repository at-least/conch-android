package at.least.conch

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import java.util.concurrent.Executors

/**
 * Backup export/import and account-free sync, kept OUT of the Activity:
 * these flows span a file picker, a passphrase dialog and seconds of
 * PBKDF2 + I/O, and a rotation in the middle used to recreate the
 * Activity — dropping the picked URI and the open dialog, and resetting
 * `busy` while the previous instance's thread was still writing
 * hosts.json (so a second import could run concurrently). A ViewModel
 * outlives configuration changes; the worker is shut down only in
 * [onCleared], i.e. when the screen is really gone.
 */
class BackupFlowModel(app: Application) : AndroidViewModel(app) {

    val busy = mutableStateOf(false)
    val syncBusy = mutableStateOf(false)

    /** One-shot user-facing message, rendered as a Snackbar. */
    val message = mutableStateOf<String?>(null)

    // passphrase dialog for export / import
    val showPassphrase = mutableStateOf(false)
    val passphraseText = mutableStateOf("")
    var passphraseModeExport = true
    var pendingExport: Uri? = null
    var pendingImport: Uri? = null

    // account-free sync
    val syncConfigured = mutableStateOf(false)
    val syncLastMs = mutableLongStateOf(0L)
    val showSyncPass = mutableStateOf(false)
    val syncPassText = mutableStateOf("")
    var pendingSyncTree: Uri? = null

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "conch-backup").apply { isDaemon = true }
    }

    private val context: Application get() = getApplication()

    override fun onCleared() {
        executor.shutdownNow()
    }

    fun refreshSync() {
        val sb = ScheduledBackup(context)
        syncConfigured.value = sb.isConfigured()
        syncLastMs.longValue = sb.lastExportMs()
    }

    fun doExport(uri: Uri, pass: String) {
        busy.value = true
        executor.execute {
            try {
                val blob = BackupCodec.encrypt(BackupManager(context).collect(), pass.toCharArray())
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(blob) }
                    ?: error("Cannot write file")
                message.value = "Backup exported (${blob.size} bytes)"
            } catch (e: Exception) {
                CrashReporting.report(e)
                message.value = "Export failed: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    fun doImport(uri: Uri, pass: String) {
        busy.value = true
        executor.execute {
            try {
                val blob = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Cannot read file")
                val payload = BackupCodec.decrypt(blob, pass.toCharArray())
                val result = BackupManager(context).restore(payload)
                message.value =
                    "Imported: ${result.hostsAdded} hosts, ${result.keysAdded} keys, " +
                    "${result.snippetsAdded} snippets" +
                    (if (result.knownHostsMerged) ", known hosts merged" else "") +
                    (if (result.secretsRefilled > 0) ", ${result.secretsRefilled} secrets restored" else "") +
                    (if (result.keysSkipped > 0) ", ${result.keysSkipped} keys skipped (no private key)" else "")
            } catch (e: Exception) {
                val wrongPass = e.isWrongPassphrase()
                if (!wrongPass) CrashReporting.report(e) // wrong passphrases are user input, not bugs
                message.value = if (wrongPass) "Wrong passphrase or corrupted file" else "Import failed: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    fun configureSync(tree: Uri, pass: String) {
        syncBusy.value = true
        executor.execute {
            try {
                val sb = ScheduledBackup(context)
                sb.configure(tree, pass)
                message.value = syncMessage(sb.exportNow())
            } finally {
                syncBusy.value = false
                refreshSync()
            }
        }
    }

    fun syncNow() {
        syncBusy.value = true
        executor.execute {
            try {
                message.value = syncMessage(ScheduledBackup(context).exportNow())
            } finally {
                syncBusy.value = false
                refreshSync()
            }
        }
    }

    fun stopSync() {
        ScheduledBackup(context).disable()
        refreshSync()
        message.value = "Scheduled sync stopped"
    }

    private fun syncMessage(out: ScheduledBackup.Outcome): String = when (out) {
        is ScheduledBackup.Outcome.Exported -> "Synced (${out.bytes} bytes)"
        is ScheduledBackup.Outcome.Failed -> "Sync failed: ${out.reason}"
        else -> "Sync skipped"
    }
}

/** GCM tag failure at either depth = wrong passphrase (user input, not a bug). */
internal fun Throwable.isWrongPassphrase(): Boolean =
    this is javax.crypto.AEADBadTagException || cause is javax.crypto.AEADBadTagException
