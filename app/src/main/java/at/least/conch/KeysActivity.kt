package at.least.conch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** SSH key management: generate ed25519 keys, import, inspect, delete. */
class KeysActivity : ComponentActivity() {

    /** An import that needs a passphrase (first prompt or retry). */
    private data class PassphrasePrompt(val name: String, val bytes: ByteArray, val error: String?)

    private val keys = mutableStateListOf<SshKeyInfo>()

    /** One-shot user-facing message, shown as a Snackbar (was Toast). */
    private var message: String? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ConchTheme { KeysScreen() } }
    }

    override fun onResume() {
        super.onResume()
        keys.clear()
        keys.addAll(KeyManager(this).list())
    }

    /** Reads the picked file and runs the first import attempt. */
    private fun readAndImport(uri: android.net.Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Cannot read file")
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported"
            attemptImport(name, bytes, null)
        } catch (e: Exception) {
            CrashReporting.report(e)
            message = "Import failed: ${e.message}"
        }
    }

    /** Writes the stored PEM of [key] to the SAF-picked location. */
    private fun writeExport(uri: android.net.Uri, key: SshKeyInfo) {
        try {
            val pem = KeyManager(this).exportPem(key.id)
                ?: error("${KeyManager.MISSING_KEY_PREFIX} '${key.name}' — re-import it first")
            contentResolver.openOutputStream(uri)?.use { it.write(pem.toByteArray()) }
                ?: error("Cannot open file")
            message = "Exported unencrypted — store it safely"
        } catch (e: Exception) {
            CrashReporting.report(e)
            message = "Export failed: ${e.message}"
        }
    }

    /** First prompt / re-prompt for an encrypted key import. */
    @Composable
    private fun PassphrasePromptDialog(
        prompt: PassphrasePrompt,
        passphraseText: String,
        onTextChange: (String) -> Unit,
        onUnlock: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        val wrong = prompt.error!!.startsWith("Wrong")
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Filled.Key, contentDescription = null) },
            title = { Text("Passphrase required") },
            text = {
                Column {
                    Text(
                        if (wrong) {
                            prompt.error
                        } else {
                            "\"${prompt.name}\" is passphrase-protected. The key is stored " +
                                "decrypted (device-encrypted at rest); this is only needed once."
                        }
                    )
                    OutlinedTextField(
                        value = passphraseText,
                        onValueChange = onTextChange,
                        label = { Text("Passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = wrong,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onUnlock) { Text("Unlock") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        )
    }

    /**
     * Runs an import attempt; an encrypted key flips the UI into the
     * passphrase prompt (re-prompting on a wrong passphrase) instead of
     * failing out to the file picker. Parity driver: ConnectBot "wrong key
     * passphrase gives no retry".
     */
    private fun attemptImport(name: String, bytes: ByteArray, passphrase: CharArray?) {
        try {
            KeyManager(this).import(name, bytes, passphrase)
            keys.clear()
            keys.addAll(KeyManager(this).list())
            passphrasePrompt = null
            passphraseText = ""
            message = "Imported $name"
        } catch (e: EncryptedKeyException) {
            passphrasePrompt = PassphrasePrompt(name, bytes, e.message ?: "Passphrase required")
            passphraseText = ""
        } catch (e: Exception) {
            CrashReporting.report(e)
            passphrasePrompt = null
            message = "Import failed: ${e.message}"
        }
    }

    private var passphrasePrompt: PassphrasePrompt? by mutableStateOf(null)
    private var passphraseText: String by mutableStateOf("")

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun KeysScreen() {
        val context = LocalContext.current
        var showGenerate by remember { mutableStateOf(false) }
        var genName by remember { mutableStateOf("") }
        var detail by remember { mutableStateOf<SshKeyInfo?>(null) }
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(message) {
            message?.let {
                snackbarHostState.showSnackbar(it)
                message = null
            }
        }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) readAndImport(uri)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Key manager") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            importLauncher.launch(arrayOf("*/*"))
                        }) {
                            Icon(Icons.Filled.Upload, contentDescription = "Import private key")
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        genName = ""
                        showGenerate = true
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Generate key") }
                )
            }
        ) { padding ->
            KeyListBody(padding = padding, onOpen = { detail = it })
        }

        if (showGenerate) {
            GenerateKeyDialog(
                name = genName,
                onNameChange = { genName = it },
                onGenerate = {
                    KeyManager(this).generate(genName.trim())
                    keys.clear()
                    keys.addAll(KeyManager(this).list())
                    showGenerate = false
                    message = "Generated ${genName.trim()}"
                },
                onDismiss = { showGenerate = false },
            )
        }

        var exportKey by remember { mutableStateOf<SshKeyInfo?>(null) }
        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain")
        ) { uri ->
            val k = exportKey
            exportKey = null
            if (uri != null && k != null) writeExport(uri, k)
        }

        passphrasePrompt?.let { prompt ->
            PassphrasePromptDialog(
                prompt = prompt,
                passphraseText = passphraseText,
                onTextChange = { passphraseText = it },
                onUnlock = {
                    if (passphraseText.isNotEmpty()) {
                        attemptImport(prompt.name, prompt.bytes, passphraseText.toCharArray())
                    }
                },
                onDismiss = {
                    passphrasePrompt = null
                    passphraseText = ""
                },
            )
        }

        detail?.let { k ->
            KeyDetailSheet(
                key = k,
                onCopy = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("pubkey", k.publicLine))
                    detail = null
                    message = "Public key copied"
                },
                onDelete = {
                    KeyManager(this@KeysActivity).delete(k.id)
                    keys.removeAll { it.id == k.id }
                    detail = null
                    message = "Deleted ${k.name}"
                },
                onExport = {
                    exportKey = k
                    detail = null
                    exportLauncher.launch("${k.name}.key")
                },
                onDismiss = { detail = null },
            )
        }
    }

    /** Empty state + key list. */
    @Composable
    private fun KeyListBody(padding: PaddingValues, onOpen: (SshKeyInfo) -> Unit) {
        if (keys.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Key,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "No keys yet",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    "Generate an Ed25519 key, then add its public key to ~/.ssh/authorized_keys on your server.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(keys, key = { it.id }) { k ->
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Key, contentDescription = null) },
                        headlineContent = { Text(k.name) },
                        supportingContent = {
                            Text(
                                "${k.algorithm} · ${k.fingerprint}",
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.clickable { onOpen(k) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    /** Ed25519 generation dialog. */
    @Composable
    private fun GenerateKeyDialog(
        name: String,
        onNameChange: (String) -> Unit,
        onGenerate: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Filled.Key, contentDescription = null) },
            title = { Text("Generate Ed25519 key") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    placeholder = { Text("my-phone") },
                    isError = name.isBlank(),
                    supportingText = { Text("Names the key in the picker and in authorized_keys") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = onGenerate) { Text("Generate") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }

    /**
     * Fingerprint + public key with copy / export / delete.
     *
     * A bottom sheet, not a dialog: the old version crammed three actions
     * into the dialog's dismiss slot, where they overflowed narrow screens
     * and gave "Delete" the visual weight of "Cancel".
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun KeyDetailSheet(
        key: SshKeyInfo,
        onCopy: () -> Unit,
        onDelete: () -> Unit,
        onExport: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(Modifier.padding(bottom = 24.dp)) {
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Key, contentDescription = null) },
                    headlineContent = { Text(key.name, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(key.fingerprint, fontFamily = FontFamily.Monospace)
                    },
                )
                Text(
                    "Public key (authorized_keys)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
                Text(
                    key.publicLine,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ListItem(
                    leadingContent = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                    headlineContent = { Text("Copy public key") },
                    modifier = Modifier.clickable(onClick = onCopy),
                )
                ListItem(
                    leadingContent = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                    headlineContent = { Text("Export private key") },
                    supportingContent = { Text("Unencrypted PEM — store it somewhere safe") },
                    modifier = Modifier.clickable(onClick = onExport),
                )
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    headlineContent = { Text("Delete key", color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable(onClick = onDelete),
                )
            }
        }
    }
}
