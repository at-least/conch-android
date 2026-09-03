package at.least.conch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * C50/C52 command palette — pull-down search over history + snippets, tap to
 * run in the terminal. Two taps instead of ten characters on glass. The
 * Snippets & History management sheets open from here (the terminal toolbar
 * slimmed to dot + palette + disconnect on iOS; here the overflow menu's
 * Snippets/History items are replaced by a single "Command palette" entry).
 *
 * `runCommand` sends the line into the terminal (caller uses sendRaw so a
 * single-char entry is never interpreted as an armed Ctrl-letter).
 * `onOpenSnippets` / `onOpenHistory` open the management sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPaletteSheet(
    hostId: String,
    historyStore: CommandHistoryStore,
    snippetStore: SnippetStore,
    runCommand: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenSnippets: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CommandPaletteModel.Entry>>(emptyList()) }

    // Debounced search: file read + AES-GCM decrypt + JSON parse off the UI
    // thread, 150ms so keystrokes stay cheap (mirrors the HistorySheet).
    LaunchedEffect(query) {
        delay(150)
        val (history, snippets) = withContext(Dispatchers.Default) {
            // iOS parity: history is per-host (CommandPaletteView.swift:15-17).
            val h = historyStore.load().filter { it.hostId == hostId }.map { it.text }
            val s = snippetStore.load().map { it.label to it.command }
            h to s
        }
        results = CommandPaletteModel.filter(query, history, snippets)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Command Palette",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenSnippets) {
                    Icon(Icons.Filled.Code, contentDescription = "Snippets")
                }
                IconButton(onClick = onOpenHistory) {
                    Icon(Icons.Filled.History, contentDescription = "History")
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                placeholder = {
                    Text("Search commands & snippets", color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (results.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (query.isBlank()) {
                            "Type to search commands and snippets."
                        } else {
                            "Nothing matches \"$query\"."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                itemsIndexed(results, key = { _, entry -> entry.id }) { index, entry ->
                    ListItem(
                        leadingContent = {
                            // Which list a hit came from, at a glance.
                            Icon(
                                if (entry.label != null) Icons.Filled.Code else Icons.Filled.History,
                                contentDescription = if (entry.label != null) "Snippet" else "History",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        headlineContent = {
                            Text(
                                entry.text,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = entry.label?.let { label -> { Text(label) } },
                        modifier = Modifier.clickable {
                            onDismiss()
                            runCommand(entry.text + "\r")
                        },
                    )
                    if (index != results.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 56.dp),
                        )
                    }
                }
            }
        }
    }
}
