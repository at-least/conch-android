package at.least.conch

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.Executors

class SettingsActivity : ComponentActivity() {

    private val crashEnabled = mutableStateOf(false)
    private val keepScreenOn = mutableStateOf(false)
    private val appLock = mutableStateOf(false)
    private val busy = mutableStateOf(false)
    private val commandHistory = mutableStateOf(true)

    // account-free sync (ScheduledBackup)
    private val syncConfigured = mutableStateOf(false)
    private val syncLastMs = mutableLongStateOf(0L)
    private val syncBusy = mutableStateOf(false)
    private val showSyncPass = mutableStateOf(false)
    private val syncPassText = mutableStateOf("")
    private var pendingSyncTree: Uri? = null

    /** Pending SAF target once the user confirms the passphrase. */
    private var pendingExport: Uri? = null
    private var pendingImport: Uri? = null

    private val showPassphrase = mutableStateOf(false)
    private var passphraseText = mutableStateOf("")
    private var passphraseModeExport = true

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "conch-backup").apply { isDaemon = true }
    }

    private val exportLauncher =
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    private val importLauncher =
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    private val syncFolderLauncher =
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crashEnabled.value = CrashReporting.isEnabled()
        keepScreenOn.value = SettingsStore.keepScreenOn(this)
        commandHistory.value = SettingsStore.commandHistory(this)
        appLock.value = AppLock.isEnabled(this) && AppLock.canAuthenticate(this)
        refreshSync()
        setContent {
            SettingsScreen(
                exportLauncher = exportLauncher,
                importLauncher = importLauncher,
                syncFolderLauncher = syncFolderLauncher,
            )
        }
    }

    // ------------------------------------------------------------ actions

    private fun onExportTarget(uri: Uri) {
        passphraseModeExport = true
        passphraseText.value = ""
        pendingExport = uri
        showPassphrase.value = true
    }

    private fun onImportTarget(uri: Uri) {
        passphraseModeExport = false
        passphraseText.value = ""
        pendingImport = uri
        showPassphrase.value = true
    }

    private fun confirmPassphrase() {
        val pass = passphraseText.value
        showPassphrase.value = false
        if (pass.length < 6) {
            Toast.makeText(this, "Passphrase must be at least 6 characters", Toast.LENGTH_SHORT).show()
            return
        }
        if (passphraseModeExport) {
            val uri = pendingExport ?: return
            pendingExport = null
            doExport(uri, pass)
        } else {
            val uri = pendingImport ?: return
            pendingImport = null
            doImport(uri, pass)
        }
        passphraseText.value = ""
    }

    private fun doExport(uri: Uri, pass: String) {
        busy.value = true
        executor.execute {
            try {
                val blob = BackupCodec.encrypt(BackupManager(this).collect(), pass.toCharArray())
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(blob) }
                    ?: error("Cannot write file")
                runOnUiThread {
                    busy.value = false
                    Toast.makeText(this, "Backup exported (${blob.size} bytes)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                CrashReporting.report(e)
                runOnUiThread {
                    busy.value = false
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun doImport(uri: Uri, pass: String) {
        busy.value = true
        executor.execute {
            try {
                val blob = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Cannot read file")
                val payload = BackupCodec.decrypt(blob, pass.toCharArray())
                val result = BackupManager(this).restore(payload)
                runOnUiThread {
                    busy.value = false
                    Toast.makeText(
                        this,
                        "Imported: ${result.hostsAdded} hosts, ${result.keysAdded} keys, ${result.snippetsAdded} snippets" +
                            if (result.knownHostsMerged) ", known hosts merged" else "",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                val wrongPass = e.isWrongPassphrase()
                if (!wrongPass) CrashReporting.report(e) // wrong passphrases are user input, not bugs
                runOnUiThread {
                    busy.value = false
                    val msg = if (wrongPass) {
                        "Wrong passphrase or corrupted file"
                    } else {
                        "Import failed: ${e.message}"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ------------------------------------------------- account-free sync

    private fun refreshSync() {
        val sb = ScheduledBackup(this)
        syncConfigured.value = sb.isConfigured()
        syncLastMs.longValue = sb.lastExportMs()
    }

    private fun onSyncTarget(uri: Uri) {
        pendingSyncTree = uri
        syncPassText.value = ""
        showSyncPass.value = true
    }

    private fun confirmSyncPass() {
        val pass = syncPassText.value
        showSyncPass.value = false
        if (pass.length < 6) {
            pendingSyncTree = null
            Toast.makeText(this, "Passphrase must be at least 6 characters", Toast.LENGTH_SHORT).show()
            return
        }
        val tree = pendingSyncTree ?: return
        pendingSyncTree = null
        syncPassText.value = ""
        syncBusy.value = true
        executor.execute {
            val sb = ScheduledBackup(this)
            sb.configure(tree, pass)
            val out = sb.exportNow()
            runOnUiThread {
                syncBusy.value = false
                refreshSync()
                syncToast(out)
            }
        }
    }

    private fun doSyncNow() {
        syncBusy.value = true
        executor.execute {
            val out = ScheduledBackup(this).exportNow()
            runOnUiThread {
                syncBusy.value = false
                refreshSync()
                syncToast(out)
            }
        }
    }

    private fun doSyncStop() {
        ScheduledBackup(this).disable()
        refreshSync()
        Toast.makeText(this, "Scheduled sync stopped", Toast.LENGTH_SHORT).show()
    }

    private fun syncToast(out: ScheduledBackup.Outcome) {
        val msg = when (out) {
            is ScheduledBackup.Outcome.Exported -> "Synced (${out.bytes} bytes)"
            is ScheduledBackup.Outcome.Failed -> "Sync failed: ${out.reason}"
            else -> "Sync skipped"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsScreen(
        exportLauncher: androidx.activity.result.contract.ActivityResultContracts.CreateDocument,
        importLauncher: androidx.activity.result.contract.ActivityResultContracts.OpenDocument,
        syncFolderLauncher: androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree,
    ) {
        val exportPicker = rememberLauncherForActivityResult(exportLauncher) { uri -> uri?.let { onExportTarget(it) } }
        val importPicker = rememberLauncherForActivityResult(importLauncher) { uri -> uri?.let { onImportTarget(it) } }
        val syncFolderPicker =
            rememberLauncherForActivityResult(syncFolderLauncher) { uri -> uri?.let { onSyncTarget(it) } }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Crash reports", fontSize = 16.sp)
                                Text(
                                    if (CrashReporting.isAvailable()) {
                                        "Send anonymous crash reports to the developer's self-hosted server. Host addresses, usernames and credentials are never included. Off by default."
                                    } else {
                                        "Not available in this build (no reporting endpoint compiled in)."
                                    },
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Switch(
                                checked = crashEnabled.value,
                                enabled = CrashReporting.isAvailable(),
                                onCheckedChange = { on ->
                                    crashEnabled.value = on
                                    CrashReporting.setEnabled(on)
                                }
                            )
                        }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Keep screen on", fontSize = 16.sp)
                                Text(
                                    "Keep the screen awake while the terminal is open.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Switch(
                                checked = keepScreenOn.value,
                                onCheckedChange = { on ->
                                    keepScreenOn.value = on
                                    SettingsStore.setKeepScreenOn(this@SettingsActivity, on)
                                }
                            )
                        }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Command history", fontSize = 16.sp)
                                Text(
                                    "Remember the commands you run, per host, encrypted on this device. Search and re-run them from the terminal menu, or save them as snippets.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Switch(
                                checked = commandHistory.value,
                                onCheckedChange = { on ->
                                    commandHistory.value = on
                                    SettingsStore.setCommandHistory(this@SettingsActivity, on)
                                }
                            )
                        }
                        TextButton(
                            onClick = {
                                CommandHistoryStore(this@SettingsActivity).clear()
                                Toast.makeText(
                                    this@SettingsActivity,
                                    "Command history cleared",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.padding(top = 4.dp)
                        ) { Text("Clear history") }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Terminal theme", fontSize = 16.sp)
                        Text(
                            "Color scheme for the terminal (applied to new sessions).",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                        )
                        TerminalThemePicker()
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Lock app", fontSize = 16.sp)
                                Text(
                                    if (AppLock.canAuthenticate(this@SettingsActivity)) {
                                        "Require fingerprint, face or screen lock to open Conch."
                                    } else {
                                        "No screen lock or biometrics set up on this device."
                                    },
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Switch(
                                checked = appLock.value,
                                enabled = AppLock.canAuthenticate(this@SettingsActivity),
                                onCheckedChange = { on ->
                                    appLock.value = on
                                    AppLock.setEnabled(this@SettingsActivity, on)
                                }
                            )
                        }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Backup & restore", fontSize = 16.sp)
                        Text(
                            "Export hosts (with passwords), keys, snippets and known hosts into a single encrypted file. The file is protected by your passphrase — it can be imported on another device.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                        )
                        if (busy.value) {
                            Text("Working…", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { exportPicker.launch("conchapp-backup.bin") }) {
                                    Icon(
                                        Icons.Filled.FileDownload,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 6.dp)
                                    )
                                    Text("Export")
                                }
                                OutlinedButton(onClick = { importPicker.launch(arrayOf("*/*")) }) {
                                    Icon(
                                        Icons.Filled.FileUpload,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 6.dp)
                                    )
                                    Text("Import")
                                }
                            }
                        }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Account-free sync", fontSize = 16.sp)
                        Text(
                            "Keeps an encrypted backup (conch-backup.til) in a folder you pick — " +
                                "sync it with Syncthing, Dropbox or a cable. Refreshes while the app " +
                                "is open, at most hourly and only when data changed; restore on any " +
                                "device with Import (merge-only, never overwrites).",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                        )
                        if (syncConfigured.value) {
                            Text(
                                if (syncLastMs.longValue > 0) {
                                    "Last export " + android.text.format.DateUtils
                                        .getRelativeTimeSpanString(syncLastMs.longValue)
                                } else {
                                    "Will export on next app open"
                                },
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { doSyncNow() },
                                    enabled = !syncBusy.value
                                ) { Text("Export now") }
                                OutlinedButton(onClick = { doSyncStop() }) { Text("Stop") }
                            }
                        } else {
                            OutlinedButton(onClick = { syncFolderPicker.launch(null) }) {
                                Icon(
                                    Icons.Filled.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Text("Choose folder")
                            }
                        }
                    }
                }
            }
        }

        if (showPassphrase.value) {
            AlertDialog(
                onDismissRequest = {
                    showPassphrase.value = false
                    passphraseText.value = ""
                    pendingExport = null
                    pendingImport = null
                },
                title = { Text(if (passphraseModeExport) "Backup passphrase" else "Enter passphrase") },
                text = {
                    Column {
                        Text(
                            if (passphraseModeExport) {
                                "Choose a passphrase to encrypt the backup (min 6 characters). You will need it to restore."
                            } else {
                                "Enter the passphrase this backup was encrypted with."
                            }
                        )
                        OutlinedTextField(
                            value = passphraseText.value,
                            onValueChange = { passphraseText.value = it },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { confirmPassphrase() }) { Text("OK") } },
                dismissButton = {
                    TextButton(onClick = {
                        showPassphrase.value = false
                        passphraseText.value = ""
                        pendingExport = null
                        pendingImport = null
                    }) { Text("Cancel") }
                }
            )
        }

        if (showSyncPass.value) {
            AlertDialog(
                onDismissRequest = {
                    showSyncPass.value = false
                    syncPassText.value = ""
                    pendingSyncTree = null
                },
                title = { Text("Sync passphrase") },
                text = {
                    Column {
                        Text(
                            "Encrypts every synced backup. Stored in this device's " +
                                "keystore vault; needed to restore on any device."
                        )
                        OutlinedTextField(
                            value = syncPassText.value,
                            onValueChange = { syncPassText.value = it },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { confirmSyncPass() }) { Text("OK") } },
                dismissButton = {
                    TextButton(onClick = {
                        showSyncPass.value = false
                        syncPassText.value = ""
                        pendingSyncTree = null
                    }) { Text("Cancel") }
                }
            )
        }
    }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun TerminalThemePicker() {
        var selected by remember {
            mutableStateOf(
                TerminalTheme.byName(SettingsStore.terminalTheme(this@SettingsActivity)).name
            )
        }
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (theme in TerminalTheme.ALL) {
                androidx.compose.material3.FilterChip(
                    selected = theme.name == selected,
                    onClick = {
                        selected = theme.name
                        SettingsStore.setTerminalTheme(this@SettingsActivity, theme.name)
                    },
                    label = { Text(theme.name) }
                )
            }
        }
    }

    private companion object {
        /** GCM tag failure at either depth = wrong passphrase (user input, not a bug). */
        fun Throwable.isWrongPassphrase(): Boolean =
            this is javax.crypto.AEADBadTagException || cause is javax.crypto.AEADBadTagException
    }
}
