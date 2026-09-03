package at.least.conch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
                        // The primary action lives in the nav bar, not a
                        // floating button — consistent with the rest of the
                        // app's Apple-style chrome.
                        IconButton(onClick = { startNew() }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Add snippet",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
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
                    Button(
                        onClick = { startNew() },
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.padding(top = 24.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Add snippet", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    item(key = "snippets-card") {
                        GroupedCard(count = snippets.size, dividerInset = 60.dp) { index ->
                            val snip = snippets[index]
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                leadingContent = {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(MaterialTheme.colorScheme.secondary),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Code,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(17.dp),
                                        )
                                    }
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
