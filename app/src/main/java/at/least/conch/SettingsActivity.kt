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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crashEnabled.value = CrashReporting.isEnabled()
        keepScreenOn.value = getSharedPreferences("conchapp_settings", MODE_PRIVATE)
            .getBoolean("keepScreenOn", false)
        commandHistory.value = getSharedPreferences("conchapp_settings", MODE_PRIVATE)
            .getBoolean("commandHistory", true)
        appLock.value = AppLock.isEnabled(this) && AppLock.canAuthenticate(this)
        setContent {
            SettingsScreen(
                exportLauncher = exportLauncher,
                importLauncher = importLauncher,
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
                    ?: throw IllegalStateException("Cannot write file")
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
                    ?: throw IllegalStateException("Cannot read file")
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
                val wrongPass = e is javax.crypto.AEADBadTagException || e.cause is javax.crypto.AEADBadTagException
                if (!wrongPass) CrashReporting.report(e)   // wrong passphrases are user input, not bugs
                runOnUiThread {
                    busy.value = false
                    val msg = if (wrongPass)
                        "Wrong passphrase or corrupted file"
                    else "Import failed: ${e.message}"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
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
    ) {
        val exportPicker = rememberLauncherForActivityResult(exportLauncher) { uri -> uri?.let { onExportTarget(it) } }
        val importPicker = rememberLauncherForActivityResult(importLauncher) { uri -> uri?.let { onImportTarget(it) } }

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
                                    if (CrashReporting.isAvailable())
                                        "Send anonymous crash reports to the developer's self-hosted server. Host addresses, usernames and credentials are never included. Off by default."
                                    else
                                        "Not available in this build (no reporting endpoint compiled in).",
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
                                    getSharedPreferences("conchapp_settings", MODE_PRIVATE)
                                        .edit().putBoolean("keepScreenOn", on).apply()
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
                                    getSharedPreferences("conchapp_settings", MODE_PRIVATE)
                                        .edit().putBoolean("commandHistory", on).apply()
                                }
                            )
                        }
                        TextButton(
                            onClick = {
                                CommandHistoryStore(this@SettingsActivity).clear()
                                Toast.makeText(this@SettingsActivity, "Command history cleared", Toast.LENGTH_SHORT).show()
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
                                    if (AppLock.canAuthenticate(this@SettingsActivity))
                                        "Require fingerprint, face or screen lock to open Conch."
                                    else
                                        "No screen lock or biometrics set up on this device.",
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
                                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                                    Text("Export")
                                }
                                OutlinedButton(onClick = { importPicker.launch(arrayOf("*/*")) }) {
                                    Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                                    Text("Import")
                                }
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
                            if (passphraseModeExport)
                                "Choose a passphrase to encrypt the backup (min 6 characters). You will need it to restore."
                            else
                                "Enter the passphrase this backup was encrypted with."
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
                dismissButton = { TextButton(onClick = {
                    showPassphrase.value = false
                    passphraseText.value = ""
                    pendingExport = null
                    pendingImport = null
                }) { Text("Cancel") } }
            )
        }
    }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun TerminalThemePicker() {
        var selected by remember {
            mutableStateOf(
                TerminalTheme.byName(
                    getSharedPreferences("conchapp_settings", MODE_PRIVATE)
                        .getString(TerminalTheme.PREF_KEY, null)
                ).name
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
                        getSharedPreferences("conchapp_settings", MODE_PRIVATE)
                            .edit().putString(TerminalTheme.PREF_KEY, theme.name).apply()
                    },
                    label = { Text(theme.name) }
                )
            }
        }
    }
}
