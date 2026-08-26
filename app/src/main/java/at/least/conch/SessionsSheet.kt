package at.least.conch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.TimeUnit

/**
 * C52 sessions switcher — ModalBottomSheet listing every live terminal
 * session (Android analogue of iOS's `SessionsView`). Tap a row to bring
 * the owning Activity's task to the foreground; swipe to disconnect that
 * session only (the Activity finishes and tears down its SSH connection).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsSheet(
    onOpen: (LiveSessions.Live) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var sessions by remember { mutableStateOf(LiveSessions.all()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "Sessions",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (sessions.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No live sessions. Connect to a host to start a session.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(sessions, key = { it.id }) { live ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = {
                            if (it == SwipeToDismissBoxValue.EndToStart) {
                                live.disconnect()
                                sessions = LiveSessions.all()
                                true
                            } else false
                        }
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            Box(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
                        },
                        enableDismissFromStartToEnd = false,
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onOpen(live)
                                    onDismiss()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Filled.Terminal, contentDescription = null, tint = Color(0xFF23D18B))
                            Column {
                                Text(live.displayName, fontSize = 15.sp)
                                Text(
                                    "started ${relativeAgo(live.startedAt)} ago",
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
}

private fun relativeAgo(startedAt: Long): String {
    val secs = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startedAt)
    return when {
        secs < 60 -> "${secs}s"
        secs < 3600 -> "${secs / 60}m"
        else -> "${secs / 3600}h"
    }
}
