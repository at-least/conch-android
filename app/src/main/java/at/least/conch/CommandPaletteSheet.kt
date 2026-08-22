package at.least.conch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                label = { Text("Search commands & snippets") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (results.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (query.isBlank()) "Type to search commands and snippets."
                        else "Nothing matches \"$query\".",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                items(results, key = { it.id }) { entry ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                runCommand(entry.text + "\r")
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            entry.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        entry.label?.let { label ->
                            Text(
                                label,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
