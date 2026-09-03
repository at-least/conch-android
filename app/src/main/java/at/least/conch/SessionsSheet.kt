package at.least.conch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    // keyed on the registry version: a session ending elsewhere (its own
    // task, the widget) refreshes the sheet instead of leaving a stale row
    val registryVersion = LiveSessions.version.intValue
    var sessions by remember(registryVersion) { mutableStateOf(LiveSessions.all()) }

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
            if (sessions.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                    GroupedCard(count = sessions.size, dividerInset = 60.dp) { index ->
                        val live = sessions[index]
                        // Keyed on the session id, not the row's position: a
                        // swipe removes a row from the middle of the list, and
                        // without this a plain positional `remember` would
                        // hand the next session that slot's stale dismiss state.
                        key(live.id) {
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = {
                                    if (it == SwipeToDismissBoxValue.EndToStart) {
                                        live.disconnect()
                                        // disconnect() only posts the teardown; the
                                        // registry drops the id later in onDestroy —
                                        // remove the row now or it stays swiped-open
                                        sessions = sessions.filter { s -> s.id != live.id }
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
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.conch.success),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
        },
        headlineContent = { Text(live.displayName) },
        supportingContent = { Text("Started ${relativeAgo(live.startedAt)} ago") },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}
