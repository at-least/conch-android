package at.least.conch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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

        fun startNew() {
            editing = null
            editLabel = ""
            editCommand = ""
            showEditor = true
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Snippet manager") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = flatTopAppBarColors(),
                    actions = {
                        TopBarAddButton("Add snippet") { startNew() }
                    }
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            if (snippets.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Code,
                    title = "No snippets yet",
                    body = "Save the commands you type often and run them from the terminal's command palette.",
                    actionLabel = "Add snippet",
                    onAction = { startNew() },
                    modifier = Modifier.padding(padding),
                )
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = GroupedListDefaults.PagePadding,
                ) {
                    groupedItems(
                        count = snippets.size,
                        key = { index -> snippets[index].id },
                        dividerInset = GroupedListDefaults.IconRowDividerInset,
                    ) { index ->
                        val snip = snippets[index]
                        ListItem(
                            colors = groupedRowColors(),
                            leadingContent = {
                                IconTile(Icons.Filled.Code)
                            },
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
                            val idx = target?.let { t -> snippets.indexOfFirst { it.id == t.id } } ?: -1
                            if (target != null && idx >= 0) {
                                // replace, don't mutate: the list only
                                // recomposes on element change, so an in-place
                                // edit kept showing the old label until resume
                                snippets[idx] = target.copy(label = editLabel.trim(), command = editCommand)
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
