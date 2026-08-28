package at.least.conch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** CRUD for command snippets usable from the terminal. */
class SnippetsActivity : ComponentActivity() {

    private val snippets = mutableStateListOf<Snippet>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ConchTheme { SnippetsScreen() } }
    }

    override fun onResume() {
        super.onResume()
        snippets.clear()
        snippets.addAll(SnippetStore(this).load())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SnippetsScreen() {
        val store = remember { SnippetStore(this) }
        var editing by remember { mutableStateOf<Snippet?>(null) }
        var editLabel by remember { mutableStateOf("") }
        var editCommand by remember { mutableStateOf("") }
        var showEditor by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Snippet manager") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        editing = null
                        editLabel = ""
                        editCommand = ""
                        showEditor = true
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add snippet") }
                )
            }
        ) { padding ->
            if (snippets.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Code,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "No snippets yet",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        "Save the commands you type often and run them from the terminal's command palette.",
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
                    items(snippets, key = { it.id }) { snip ->
                        ListItem(
                            leadingContent = { Icon(Icons.Filled.Code, contentDescription = null) },
                            headlineContent = { Text(snip.label) },
                            supportingContent = {
                                Text(
                                    snip.command,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.clickable {
                                editing = snip
                                editLabel = snip.label
                                editCommand = snip.command
                                showEditor = true
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }

        if (showEditor) {
            AlertDialog(
                onDismissRequest = { showEditor = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (editing == null) "New snippet" else "Edit snippet",
                            modifier = Modifier.weight(1f),
                        )
                        // Delete belongs next to what it deletes, not stacked
                        // into the dialog's Cancel slot where it competed
                        // with the way out of the dialog.
                        if (editing != null) {
                            IconButton(onClick = {
                                snippets.removeAll { it.id == editing!!.id }
                                store.save(snippets)
                                showEditor = false
                            }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete snippet",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = editLabel,
                            onValueChange = { editLabel = it },
                            label = { Text("Label") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = editCommand,
                            onValueChange = { editCommand = it },
                            label = { Text("Command") },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = editLabel.isNotBlank() && editCommand.isNotBlank(),
                        onClick = {
                            val target = editing
                            if (target != null) {
                                target.label = editLabel.trim()
                                target.command = editCommand
                            } else {
                                snippets.add(0, Snippet(label = editLabel.trim(), command = editCommand))
                            }
                            store.save(snippets)
                            showEditor = false
                        }
                    ) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showEditor = false }) { Text("Cancel") }
                }
            )
        }
    }
}
