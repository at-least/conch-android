package at.least.conch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** SSH key management: generate ed25519 keys, import, inspect, delete. */
class KeysActivity : ComponentActivity() {

    /** An import that needs a passphrase (first prompt or retry). */
    private data class PassphrasePrompt(val name: String, val bytes: ByteArray, val error: String?)

    private val keys = mutableStateListOf<SshKeyInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KeysScreen() }
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
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Writes the stored PEM of [key] to the SAF-picked location. */
    private fun writeExport(uri: android.net.Uri, key: SshKeyInfo) {
        try {
            val pem = KeyManager(this).exportPem(key.id)
                ?: error("${KeyManager.MISSING_KEY_PREFIX} '${key.name}' — re-import it first")
            contentResolver.openOutputStream(uri)?.use { it.write(pem.toByteArray()) }
                ?: error("Cannot open file")
            Toast.makeText(this, "Exported unencrypted — store it safely", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            CrashReporting.report(e)
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
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
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Passphrase required") },
            text = {
                Column {
                    val intro = if (prompt.error!!.startsWith("Wrong")) {
                        prompt.error
                    } else {
                        "\"${prompt.name}\" is passphrase-protected. The key is stored " +
                            "decrypted (device-encrypted at rest); this is only needed once."
                    }
                    Text(intro, fontSize = 13.sp)
                    OutlinedTextField(
                        value = passphraseText,
                        onValueChange = onTextChange,
                        label = { Text("Passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = prompt.error.startsWith("Wrong"),
                        modifier = Modifier.padding(top = 10.dp)
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
            Toast.makeText(this, "Imported", Toast.LENGTH_SHORT).show()
        } catch (e: EncryptedKeyException) {
            passphrasePrompt = PassphrasePrompt(name, bytes, e.message ?: "Passphrase required")
            passphraseText = ""
        } catch (e: Exception) {
            CrashReporting.report(e)
            passphrasePrompt = null
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private var passphrasePrompt: PassphrasePrompt? by mutableStateOf(null)
    private var passphraseText: String by mutableStateOf("")

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    private fun KeysScreen() {
        val context = LocalContext.current
        var showGenerate by remember { mutableStateOf(false) }
        var genName by remember { mutableStateOf("") }
        var detail by remember { mutableStateOf<SshKeyInfo?>(null) }

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
            KeyDetailDialog(
                key = k,
                onCopy = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("pubkey", k.publicLine))
                    Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
                    detail = null
                },
                onDelete = {
                    KeyManager(this@KeysActivity).delete(k.id)
                    keys.removeAll { it.id == k.id }
                    detail = null
                },
                onExport = {
                    exportKey = k
                    detail = null
                    exportLauncher.launch("${k.name}.key")
                },
                onClose = { detail = null },
            )
        }
    }

    /** Empty state + key list. */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun KeyListBody(padding: PaddingValues, onOpen: (SshKeyInfo) -> Unit) {
        if (keys.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Filled.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "No keys yet. Generate an ed25519 key, then add the public key to ~/.ssh/authorized_keys on your server.",
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(keys, key = { it.id }) { k ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .combinedClickable(onClick = { onOpen(k) }, onLongClick = { })
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(k.name, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                            Text(
                                "${k.algorithm} · ${k.fingerprint}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
            title = { Text("Generate Ed25519 key") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Name (e.g. my-phone)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isBlank()) {
                        Toast.makeText(this@KeysActivity, "Enter a name", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    onGenerate()
                }) { Text("Generate") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }

    /** Fingerprint + public-key inspection with copy / export / delete. */
    @Composable
    private fun KeyDetailDialog(
        key: SshKeyInfo,
        onCopy: () -> Unit,
        onDelete: () -> Unit,
        onExport: () -> Unit,
        onClose: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(key.name) },
            text = {
                Column {
                    Text("Fingerprint: ${key.fingerprint}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text(
                        "Public key (authorized_keys):",
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    Text(
                        key.publicLine,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onCopy) { Text("Copy public key") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = onExport) { Text("Export") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        )
    }
}
