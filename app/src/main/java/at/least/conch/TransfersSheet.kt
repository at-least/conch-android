package at.least.conch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The Files tab's "Transfers" sheet (iOS parity): every queued / running /
 * finished item with live progress, Cancel while active, Retry after a
 * failure or cancel (downloads resume from their partial), Clear finished.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersSheet(queue: TransferQueue, onDismiss: () -> Unit) {
    val items by queue.items.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Transfers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (items.any { it.state is TransferQueue.State.Done }) {
                    TextButton(onClick = { queue.clearFinished() }) { Text("Clear finished") }
                }
            }
            if (items.isEmpty()) {
                Text(
                    "Downloads and uploads show their progress here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn {
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        TransferRow(item, queue)
                        if (index != items.lastIndex) {
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
}

@Composable
private fun TransferRow(item: TransferQueue.Item, queue: TransferQueue) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(
                if (item.direction == TransferQueue.Direction.DOWNLOAD) Icons.Filled.Download else Icons.Filled.Upload,
                contentDescription = if (item.direction == TransferQueue.Direction.DOWNLOAD) "Download" else "Upload",
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                when (val s = item.state) {
                    TransferQueue.State.Queued -> Text("Queued")
                    TransferQueue.State.Running -> {
                        val fraction = TransferFormat.progressFraction(item.transferred, item.totalBytes)
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        }
                        Text(TransferFormat.progressLabel(item.transferred, item.totalBytes))
                    }
                    TransferQueue.State.Done -> Text(
                        if (item.direction == TransferQueue.Direction.DOWNLOAD) {
                            "Saved to ${item.localFile.parentFile?.name ?: "Downloads"}"
                        } else {
                            "Done"
                        },
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is TransferQueue.State.Failed -> Text(
                        s.reason,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TransferQueue.State.Cancelled -> Text(
                        if (item.resumeOffset > 0) {
                            "Cancelled at ${TransferFormat.bytesLabel(item.resumeOffset)}"
                        } else {
                            "Cancelled"
                        },
                    )
                }
            }
        },
        trailingContent = {
            when {
                item.state.isActive -> TextButton(onClick = { queue.cancel(item.id) }) { Text("Cancel") }
                item.state.isRetryable -> TextButton(onClick = { queue.retry(item.id) }) { Text("Retry") }
            }
        },
    )
}
