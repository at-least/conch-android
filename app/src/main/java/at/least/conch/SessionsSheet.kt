package at.least.conch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.unit.dp
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
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No live sessions. Connect to a host to start a session.",
                        style = MaterialTheme.typography.bodyMedium,
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
                            } else {
                                false
                            }
                        }
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = { DisconnectSwipeBackground() },
                        enableDismissFromStartToEnd = false,
                    ) {
                        SessionRow(
                            live = live,
                            onOpen = {
                                onOpen(live)
                                onDismiss()
                            },
                        )
                    }
                    HorizontalDivider()
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

/**
 * The swipe target reads as destructive before it completes: error
 * container plus an icon, not red text floating on the sheet background.
 */
@Composable
private fun DisconnectSwipeBackground() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.LinkOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "Disconnect",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** One live session. */
@Composable
private fun SessionRow(live: LiveSessions.Live, onOpen: () -> Unit) {
    ListItem(
        leadingContent = {
            Icon(
                Icons.Filled.Terminal,
                contentDescription = null,
                tint = MaterialTheme.conch.success,
            )
        },
        headlineContent = { Text(live.displayName) },
        supportingContent = { Text("Started ${relativeAgo(live.startedAt)} ago") },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}
