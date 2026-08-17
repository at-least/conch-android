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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** SSH key management: generate ed25519 keys, import, inspect, delete. */
class KeysActivity : ComponentActivity() {

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
            if (uri != null) {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Cannot read file")
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported"
                    KeyManager(this).import(name, bytes)
                    keys.clear()
                    keys.addAll(KeyManager(this).list())
                    Toast.makeText(this, "Imported", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    CrashReporting.report(e)
                    Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
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
                    onClick = { genName = ""; showGenerate = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Generate key") }
                )
            }
        ) { padding ->
            if (keys.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding).padding(24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Filled.Key, contentDescription = null,
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
                                .combinedClickable(onClick = { detail = k }, onLongClick = { })
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

        if (showGenerate) {
            AlertDialog(
                onDismissRequest = { showGenerate = false },
                title = { Text("Generate Ed25519 key") },
                text = {
                    OutlinedTextField(
                        value = genName,
                        onValueChange = { genName = it },
                        label = { Text("Name (e.g. my-phone)") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (genName.isBlank()) {
                            Toast.makeText(context, "Enter a name", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        KeyManager(this).generate(genName.trim())
                        keys.clear()
                        keys.addAll(KeyManager(this).list())
                        showGenerate = false
                    }) { Text("Generate") }
                },
                dismissButton = { TextButton(onClick = { showGenerate = false }) { Text("Cancel") } }
            )
        }

        detail?.let { k ->
            AlertDialog(
                onDismissRequest = { detail = null },
                title = { Text(k.name) },
                text = {
                    Column {
                        Text("Fingerprint: ${k.fingerprint}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        Text(
                            "Public key (authorized_keys):",
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        Text(
                            k.publicLine,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("pubkey", k.publicLine))
                        Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
                        detail = null
                    }) { Text("Copy public key") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            KeyManager(this@KeysActivity).delete(k.id)
                            keys.removeAll { it.id == k.id }
                            detail = null
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { detail = null }) { Text("Close") }
                    }
                }
            )
        }
    }
}
