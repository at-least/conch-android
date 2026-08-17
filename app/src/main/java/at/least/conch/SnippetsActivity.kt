package at.least.conch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** CRUD for command snippets usable from the terminal. */
class SnippetsActivity : ComponentActivity() {

    private val snippets = mutableStateListOf<Snippet>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SnippetsScreen() }
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
                    text = { Text("Add") }
                )
            }
        ) { padding ->
            if (snippets.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding).padding(24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "No snippets yet. Add frequently used commands and run them from the terminal menu.",
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
                    items(snippets, key = { it.id }) { snip ->
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .clickable {
                                    editing = snip
                                    editLabel = snip.label
                                    editCommand = snip.command
                                    showEditor = true
                                }
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(snip.label, fontWeight = FontWeight.Bold)
                                Text(
                                    snip.command,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showEditor) {
            AlertDialog(
                onDismissRequest = { showEditor = false },
                title = { Text(if (editing == null) "New snippet" else "Edit snippet") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = editLabel,
                            onValueChange = { editLabel = it },
                            label = { Text("Label") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = editCommand,
                            onValueChange = { editCommand = it },
                            label = { Text("Command") },
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (editLabel.isBlank() || editCommand.isBlank()) return@TextButton
                        val target = editing
                        if (target != null) {
                            target.label = editLabel.trim()
                            target.command = editCommand
                        } else {
                            snippets.add(0, Snippet(label = editLabel.trim(), command = editCommand))
                        }
                        store.save(snippets)
                        showEditor = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    Row {
                        if (editing != null) {
                            TextButton(onClick = {
                                snippets.removeAll { it.id == editing!!.id }
                                store.save(snippets)
                                showEditor = false
                            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                        TextButton(onClick = { showEditor = false }) { Text("Cancel") }
                    }
                }
            )
        }
    }
}
