package at.least.conch

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri

/**
 * Account-free sync (improvement-plan 3.3): keeps a TILDBAK1 backup
 * materialized in a user-chosen SAF folder so Syncthing/Dropbox/rsync can
 * move it between devices — no accounts, no cloud, no new permissions.
 * Restore side is the existing merge-only import.
 *
 * No WorkManager on purpose: this app's data only changes while the app is
 * running, so exports piggyback on foreground moments (MainActivity.onResume
 * plus the Settings "Export now" button) instead of holding background
 * wakeups. Gate rules live in [actionFor] (pure, unit-tested):
 *   - data unchanged since the last export → never rewrite the file
 *   - data changed → export at most once per hour (sync engines collide
 *     badly with rapid rewrites of one file)
 *
 * The backup passphrase is stored in the SecretsStore vault — the same
 * at-rest protection as host passwords and private keys — because the file
 * must stay passphrase-encrypted: whatever leaves the device via the sync
 * folder still needs the passphrase to open.
 */
class ScheduledBackup(private val context: Context) {

    sealed class Outcome {
        data class Exported(val bytes: Int) : Outcome()
        data object SkippedUnchanged : Outcome()
        data object SkippedTooSoon : Outcome()
        data object NotConfigured : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    enum class Action { EXPORT, SKIP_UNCHANGED, SKIP_TOO_SOON, NOT_CONFIGURED }

    /** The entire scheduling policy; every branch pinned by ScheduledBackupTest. */
    fun actionFor(configured: Boolean, changed: Boolean, sinceLastMs: Long): Action = when {
        !configured -> Action.NOT_CONFIGURED
        !changed -> Action.SKIP_UNCHANGED
        sinceLastMs < MIN_INTERVAL_MS -> Action.SKIP_TOO_SOON
        else -> Action.EXPORT
    }

    fun isConfigured(): Boolean = prefs().getString(KEY_TREE, null) != null

    /** ms of the last successful export, 0 when none yet. */
    fun lastExportMs(): Long = prefs().getLong(KEY_MS, 0L)

    /** Enable: remember the folder, keep its grant across reboots. */
    fun configure(treeUri: Uri, passphrase: String) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        SecretsStore.put(PASS_KEY, passphrase)
        prefs().edit()
            .putString(KEY_TREE, treeUri.toString())
            .putLong(KEY_MS, 0L)
            .putString(KEY_FP, null)
            .remove(KEY_FILE)
            .apply()
    }

    fun disable() {
        prefs().edit().clear().apply()
        SecretsStore.delete(PASS_KEY)
    }

    /** Foreground hook: export only if the gate rules say so. Never throws. */
    fun maybeExport(nowMs: Long = System.currentTimeMillis()): Outcome {
        val tree = prefs().getString(KEY_TREE, null)?.toUri() ?: return Outcome.NotConfigured
        val payload = BackupManager(context).collect()
        val fp = BackupCodec.fingerprint(payload)
        val p = prefs()
        val changed = fp != p.getString(KEY_FP, null)
        return when (actionFor(true, changed, nowMs - p.getLong(KEY_MS, 0L))) {
            Action.NOT_CONFIGURED -> Outcome.NotConfigured // unreachable here
            Action.SKIP_UNCHANGED -> Outcome.SkippedUnchanged
            Action.SKIP_TOO_SOON -> Outcome.SkippedTooSoon
            Action.EXPORT -> writeBackup(tree, payload, fp, nowMs)
        }
    }

    /** Settings button path: ignores the gates, still needs configuration. */
    fun exportNow(nowMs: Long = System.currentTimeMillis()): Outcome {
        val tree = prefs().getString(KEY_TREE, null)?.toUri() ?: return Outcome.NotConfigured
        val payload = BackupManager(context).collect()
        return writeBackup(tree, payload, BackupCodec.fingerprint(payload), nowMs)
    }

    /**
     * Writes conch-backup.til into the tree, creating the document on first
     * export and overwriting in place afterwards (fixed name = the file
     * sync engines converge on; rotating names would pile up). If the stored
     * document vanished (folder cleaned, other device deleted it), one
     * retry recreates it.
     */
    private fun writeBackup(
        tree: Uri,
        payload: BackupCodec.BackupPayload,
        fingerprint: String,
        nowMs: Long,
    ): Outcome {
        val pass = SecretsStore.get(PASS_KEY)?.toCharArray()
            ?: return Outcome.Failed("no stored passphrase — re-enable sync")
        return try {
            val blob = BackupCodec.encrypt(payload, pass)
            val fileUri = resolveFileUri(tree)
            context.contentResolver.openOutputStream(fileUri, "wt")?.use { it.write(blob) }
                ?: error("cannot open output stream")
            prefs().edit()
                .putString(KEY_FP, fingerprint)
                .putLong(KEY_MS, nowMs)
                .putString(KEY_FILE, fileUri.toString())
                .apply()
            Outcome.Exported(blob.size)
        } catch (e: Exception) {
            CrashReporting.report(e)
            // stale document (deleted underneath us): forget and let the
            // next attempt recreate it
            prefs().edit().remove(KEY_FILE).apply()
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun resolveFileUri(tree: Uri): Uri {
        val stored = prefs().getString(KEY_FILE, null)
        if (stored != null) {
            val uri = stored.toUri()
            if (uri.toString().startsWith(tree.toString())) return uri
            // configured folder changed: the old document belongs to another tree
            prefs().edit().remove(KEY_FILE).apply()
        }
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        return DocumentsContract.createDocument(
            context.contentResolver,
            root,
            "application/octet-stream",
            FILE_NAME,
        ) ?: error("cannot create $FILE_NAME in the sync folder")
    }

    private fun prefs() = context.getSharedPreferences("scheduled_backup", Context.MODE_PRIVATE)

    companion object {
        /** At most one export per hour; unchanged data never rewrites. */
        const val MIN_INTERVAL_MS = 60L * 60 * 1000

        const val FILE_NAME = "conch-backup.til"
        private const val KEY_TREE = "tree"
        private const val KEY_FILE = "file"
        private const val KEY_FP = "fp"
        private const val KEY_MS = "ms"
        private const val PASS_KEY = "scheduled-backup-passphrase"
    }
}
