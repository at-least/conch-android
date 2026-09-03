package at.least.conch

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class SettingsActivity : ComponentActivity() {

    private val crashEnabled = mutableStateOf(false)
    private val keepScreenOn = mutableStateOf(false)
    private val appLock = mutableStateOf(false)
    private val commandHistory = mutableStateOf(true)

    /**
     * Backup/sync flow state lives in a ViewModel so a rotation mid-flow
     * keeps the picked file, the open dialog and the running worker.
     */
    private val vm: BackupFlowModel by viewModels()

    private val busy get() = vm.busy
    private val message get() = vm.message
    private val syncConfigured get() = vm.syncConfigured
    private val syncLastMs get() = vm.syncLastMs
    private val syncBusy get() = vm.syncBusy
    private val showSyncPass get() = vm.showSyncPass
    private val syncPassText get() = vm.syncPassText
    private var pendingSyncTree: Uri?
        get() = vm.pendingSyncTree
        set(value) { vm.pendingSyncTree = value }
    private var pendingExport: Uri?
        get() = vm.pendingExport
        set(value) { vm.pendingExport = value }
    private var pendingImport: Uri?
        get() = vm.pendingImport
        set(value) { vm.pendingImport = value }
    private val showPassphrase get() = vm.showPassphrase
    private val passphraseText get() = vm.passphraseText
    private var passphraseModeExport: Boolean
        get() = vm.passphraseModeExport
        set(value) { vm.passphraseModeExport = value }

    private val exportLauncher =
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    private val importLauncher =
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    private val syncFolderLauncher =
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        crashEnabled.value = CrashReporting.isEnabled()
        keepScreenOn.value = SettingsStore.keepScreenOn(this)
        commandHistory.value = SettingsStore.commandHistory(this)
        appLock.value = AppLock.isEnabled(this) && AppLock.canAuthenticate(this)
        refreshSync()
        setContent {
            ConchTheme {
                SettingsScreen(
                    exportLauncher = exportLauncher,
                    importLauncher = importLauncher,
                    syncFolderLauncher = syncFolderLauncher,
                )
            }
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
        if (pass.length < MIN_PASSPHRASE) {
            // keep the dialog and the picked target: closing here orphaned
            // the URI (and, for export, the SAF-created empty file)
            message.value = "Passphrase must be at least $MIN_PASSPHRASE characters"
            return
        }
        showPassphrase.value = false
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

    private fun doExport(uri: Uri, pass: String) = vm.doExport(uri, pass)

    private fun doImport(uri: Uri, pass: String) = vm.doImport(uri, pass)

    // ------------------------------------------------- account-free sync

    private fun refreshSync() = vm.refreshSync()

    private fun onSyncTarget(uri: Uri) {
        pendingSyncTree = uri
        syncPassText.value = ""
        showSyncPass.value = true
    }

    private fun confirmSyncPass() {
        val pass = syncPassText.value
        showSyncPass.value = false
        if (pass.length < MIN_PASSPHRASE) {
            pendingSyncTree = null
            message.value = "Passphrase must be at least $MIN_PASSPHRASE characters"
            return
        }
        val tree = pendingSyncTree ?: return
        pendingSyncTree = null
        syncPassText.value = ""
        vm.configureSync(tree, pass)
    }

    private fun doSyncNow() = vm.syncNow()

    private fun doSyncStop() = vm.stopSync()

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

        val snackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(message.value) {
            message.value?.let {
                snackbarHostState.showSnackbar(it)
                message.value = null
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = flatTopAppBarColors(),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                TerminalSection()
                PrivacySecuritySection()
                BackupSection(exportPicker, importPicker, syncFolderPicker)
            }
        }

        if (showPassphrase.value) {
            PassphraseDialog(
                title = if (passphraseModeExport) "Backup passphrase" else "Enter passphrase",
                explanation = if (passphraseModeExport) {
                    "Choose a passphrase to encrypt the backup (min $MIN_PASSPHRASE characters). " +
                        "You will need it to restore."
                } else {
                    "Enter the passphrase this backup was encrypted with."
                },
                value = passphraseText.value,
                onValueChange = { passphraseText.value = it },
                onConfirm = { confirmPassphrase() },
                onDismiss = {
                    showPassphrase.value = false
                    passphraseText.value = ""
                    pendingExport = null
                    pendingImport = null
                },
            )
        }

        if (showSyncPass.value) {
            PassphraseDialog(
                title = "Sync passphrase",
                explanation = "Encrypts every synced backup. Stored in this device's " +
                    "keystore vault; needed to restore on any device.",
                value = syncPassText.value,
                onValueChange = { syncPassText.value = it },
                onConfirm = { confirmSyncPass() },
                onDismiss = {
                    showSyncPass.value = false
                    syncPassText.value = ""
                    pendingSyncTree = null
                },
            )
        }
    }

    @Composable
    private fun TerminalSection() {
        Column {
            GroupHeader("Terminal")
            GroupedCard(count = 3) { index ->
                when (index) {
                    0 -> SettingsSwitch(
                        icon = Icons.Filled.Lightbulb,
                        title = "Keep screen on",
                        supporting = "Keep the screen awake while the terminal is open.",
                        checked = keepScreenOn.value,
                        onCheckedChange = { on ->
                            keepScreenOn.value = on
                            SettingsStore.setKeepScreenOn(this@SettingsActivity, on)
                        },
                    )
                    1 -> SettingsRow(
                        icon = Icons.Filled.Palette,
                        title = "Terminal theme",
                    ) {
                        Text("Color scheme for the terminal (applied to new sessions).")
                        TerminalThemePicker(Modifier.padding(top = 8.dp))
                    }
                    else -> SettingsRow(
                        icon = Icons.Filled.TextFields,
                        title = "Terminal font",
                    ) {
                        Text(
                            "JetBrains Mono Nerd Font ships with the app: box-drawing and powerline " +
                                "glyphs for tmux, htop, starship and friends (applied to new sessions)."
                        )
                        TerminalFontPicker(Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun PrivacySecuritySection() {
        Column {
            GroupHeader("Privacy & security")
            GroupedCard(count = 4) { index ->
                when (index) {
                    0 -> SettingsSwitch(
                        icon = Icons.Filled.Fingerprint,
                        title = "Lock app",
                        supporting = if (AppLock.canAuthenticate(this@SettingsActivity)) {
                            "Require fingerprint, face or screen lock to open Conch."
                        } else {
                            "No screen lock or biometrics set up on this device."
                        },
                        enabled = AppLock.canAuthenticate(this@SettingsActivity),
                        checked = appLock.value,
                        onCheckedChange = { on ->
                            appLock.value = on
                            AppLock.setEnabled(this@SettingsActivity, on)
                        },
                    )
                    1 -> SettingsSwitch(
                        icon = Icons.Filled.History,
                        title = "Command history",
                        supporting = "Remember the commands you run, per host, encrypted on this device. " +
                            "Search and re-run them from the terminal menu, or save them as snippets.",
                        checked = commandHistory.value,
                        onCheckedChange = { on ->
                            commandHistory.value = on
                            SettingsStore.setCommandHistory(this@SettingsActivity, on)
                        },
                    )
                    // Destructive, so it's tinted red — Apple's convention for
                    // an irreversible local action, kept apart from a plain row.
                    2 -> ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        headlineContent = {
                            Text("Clear history", color = MaterialTheme.colorScheme.error)
                        },
                        modifier = Modifier.clickable {
                            CommandHistoryStore(this@SettingsActivity).clear()
                            message.value = "Command history cleared"
                        },
                    )
                    else -> SettingsSwitch(
                        icon = Icons.Filled.BugReport,
                        title = "Crash reports",
                        supporting = if (CrashReporting.isAvailable()) {
                            "Send anonymous crash reports to the developer's self-hosted server. Host " +
                                "addresses, usernames and credentials are never included. Off by default."
                        } else {
                            "Not available in this build (no reporting endpoint compiled in)."
                        },
                        enabled = CrashReporting.isAvailable(),
                        checked = crashEnabled.value,
                        onCheckedChange = { on ->
                            crashEnabled.value = on
                            CrashReporting.setEnabled(on)
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun BackupSection(
        exportPicker: androidx.activity.compose.ManagedActivityResultLauncher<String, android.net.Uri?>,
        importPicker: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, android.net.Uri?>,
        syncFolderPicker: androidx.activity.compose.ManagedActivityResultLauncher<android.net.Uri?, android.net.Uri?>,
    ) {
        Column {
            GroupHeader("Backup")
            GroupedCard(count = 2) { index ->
                if (index == 0) {
                    BackupRestoreRow(exportPicker, importPicker)
                } else {
                    AccountFreeSyncRow(syncFolderPicker)
                }
            }
        }
    }

    @Composable
    private fun BackupRestoreRow(
        exportPicker: androidx.activity.compose.ManagedActivityResultLauncher<String, android.net.Uri?>,
        importPicker: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, android.net.Uri?>,
    ) {
        SettingsRow(icon = Icons.Filled.FileDownload, title = "Backup & restore") {
            Text(
                "Export hosts (with passwords), keys, snippets and known hosts into a " +
                    "single encrypted file, protected by your passphrase — import it on " +
                    "another device."
            )
            if (busy.value) {
                Row(
                    Modifier.padding(top = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text("Working…")
                }
            } else {
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { exportPicker.launch(ScheduledBackup.FILE_NAME) }) {
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

    @Composable
    private fun AccountFreeSyncRow(
        syncFolderPicker: androidx.activity.compose.ManagedActivityResultLauncher<android.net.Uri?, android.net.Uri?>,
    ) {
        SettingsRow(icon = Icons.Filled.CloudSync, title = "Account-free sync") {
            Text(
                "Keeps an encrypted backup (conch-backup.conchbak) in a folder you pick — " +
                    "sync it with Syncthing, Dropbox or a cable. Refreshes while the app " +
                    "is open, at most hourly and only when data changed; restore on any " +
                    "device with Import (merge-only, never overwrites)."
            )
            if (syncConfigured.value) {
                Text(
                    if (syncLastMs.longValue > 0) {
                        "Last export " + android.text.format.DateUtils
                            .getRelativeTimeSpanString(syncLastMs.longValue)
                    } else {
                        "Will export on next app open"
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    OutlinedButton(onClick = { doSyncNow() }, enabled = !syncBusy.value) { Text("Export now") }
                    OutlinedButton(onClick = { doSyncStop() }) { Text("Stop") }
                }
            } else {
                OutlinedButton(
                    onClick = { syncFolderPicker.launch(null) },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
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

    /**
     * A settings row with a leading icon and free-form supporting content
     * below the title (a picker, a status line, action buttons) — the
     * richer sibling of [SettingsSwitch] for rows that aren't a toggle.
     */
    @Composable
    private fun SettingsRow(
        icon: ImageVector,
        title: String,
        supporting: @Composable ColumnScope.() -> Unit,
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(icon, contentDescription = null) },
            headlineContent = { Text(title) },
            supportingContent = { Column(content = supporting) },
        )
    }

    /**
     * The native settings row: `ListItem` with a leading icon and a trailing
     * `Switch`, tappable across its full width. Replaces the previous
     * one-Card-per-setting stack, which spent a card of elevation on every
     * toggle and left the label and its explanation in ad-hoc `sp` sizes.
     */
    @Composable
    private fun SettingsSwitch(
        icon: ImageVector,
        title: String,
        supporting: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        enabled: Boolean = true,
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(icon, contentDescription = null) },
            headlineContent = { Text(title) },
            supportingContent = { Text(supporting) },
            trailingContent = {
                Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            },
            modifier = Modifier.clickable(enabled = enabled) { onCheckedChange(!checked) },
        )
    }

    /** Shared passphrase prompt for backup / restore / sync. */
    @Composable
    private fun PassphraseDialog(
        title: String,
        explanation: String,
        value: String,
        onValueChange: (String) -> Unit,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        var visible by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column {
                    Text(explanation)
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        label = { Text("Passphrase") },
                        visualTransformation = if (visible) {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { visible = !visible }) {
                                Icon(
                                    if (visible) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (visible) "Hide passphrase" else "Show passphrase",
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )
                }
            },
            confirmButton = { TextButton(onClick = onConfirm) { Text("OK") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun TerminalThemePicker(modifier: Modifier = Modifier) {
        var selected by remember {
            mutableStateOf(
                TerminalTheme.byName(SettingsStore.terminalTheme(this@SettingsActivity)).name
            )
        }
        androidx.compose.foundation.layout.FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (theme in TerminalTheme.ALL) {
                FilterChip(
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

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun TerminalFontPicker(modifier: Modifier = Modifier) {
        var selected by remember {
            mutableStateOf(TerminalFont.byId(SettingsStore.terminalFontFamily(this@SettingsActivity)))
        }
        androidx.compose.foundation.layout.FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (font in TerminalFont.ALL) {
                FilterChip(
                    selected = font == selected,
                    onClick = {
                        selected = font
                        SettingsStore.setTerminalFontFamily(this@SettingsActivity, font.id)
                    },
                    label = { Text(font.displayName) }
                )
            }
        }
    }

    private companion object {
        const val MIN_PASSPHRASE = 6
    }
}
