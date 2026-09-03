package at.least.conch

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-session tab composables for the C52 redesign: Monitor / Docker / Files
 * ride the SAME SSH connection as the live PTY shell (via [SessionReconnector]
 * .exec / .sftpClient — H1 multiplex). Parsers and command strings are reused
 * verbatim from the standalone Activities; only the connection source changes.
 *
 * [SftpTab] additionally takes a `connectionGen` that increments on every
 * successful (re)connect — the tab closes its SFTPClient and reopens on a
 * generation change so Files never rides a dead client after a reconnect.
 *
 * UI is Material3 / Android-native. These composables are stateless re: the
 * connection — they take the reconnector and a coroutine scope is implied by
 * LaunchedEffect. Each is embedded inside TerminalActivity's TabRow (E3).
 */

// =====================================================================
// In-session tabs + tunnel capsule labels
// =====================================================================

/**
 * In-session tabs (C52 redesign): Terminal / Monitor / Docker / Files —
 * exactly four, each with a title and icon (iOS SessionTab parity). Lives
 * here (not inside TerminalActivity) so the model is JVM-testable.
 */
internal enum class SessionTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    TERMINAL("Terminal", Icons.Filled.Code),
    MONITOR("Monitor", Icons.Filled.Monitor),
    DOCKER("Docker", Icons.Filled.Storage),
    FILES("Files", Icons.Filled.Folder),
}

/**
 * Pure label derivations for the tunnel capsule (iOS TunnelStatus
 * analogue — count semantics live on SshSession.tunnelCount). Extracted
 * from TerminalActivity so the user-facing strings are pinned.
 */
object TunnelCapsule {
    /** Capsule shows only when at least one tunnel is live. */
    fun visible(count: Int): Boolean = count > 0

    /** The chip pairs this with a SyncAlt icon, so the count is the whole label. */
    fun chipText(count: Int): String = "$count"

    fun stopDialogTitle(count: Int): String = "Stop $count tunnel(s)?"
}

// =====================================================================
// Monitor
// =====================================================================

/**
 * Pure decision kernel of the MonitorTab poll loop (iOS
 * LogAndContinueTests "parse failure leaves prior snapshot usable"
 * parity): a good parse refreshes; a failed parse or dead exec NEVER
 * overwrites a good prior snapshot — errors surface only when there is
 * nothing better to show. Extracted from the Composable so the policy is
 * JVM-testable.
 */
object MonitorPoll {
    data class State(
        val snapshot: MonitorParser.Snapshot?,
        val error: String?,
        /** Last unparsable raw output, shown under the error so users can
         * self-diagnose (e.g. busybox `free`/`df` variants). Null when the
         * parse is healthy. */
        val raw: String? = null,
    )

    fun reduce(state: State, out: String?): State {
        if (out != null) {
            val parsed = MonitorParser.parse(out)
            if (parsed != null) return State(parsed, null)
            if (state.snapshot == null) return State(null, "Failed to read metrics", out.take(RAW_CAP))
            return state
        }
        if (state.snapshot == null) return State(null, "Failed to read metrics", state.raw)
        return state
    }

    const val RAW_CAP = 2000
}

@Composable
fun MonitorTab(session: SessionReconnector, hostId: String, modifier: Modifier = Modifier) {
    var snapshot by remember { mutableStateOf<MonitorParser.Snapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var rawOut by remember { mutableStateOf<String?>(null) }
    var autoRefresh by remember { mutableStateOf(true) }
    // per host for the life of the process: re-entering the tab continues the line
    val history = remember(hostId) { MetricHistoryStore.forHost(hostId) }
    // successful samples reach the Server-stats widget (throttled; iOS parity)
    val sharedStats = SharedStats(LocalContext.current.applicationContext)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(MonitorCardSpacing)
    ) {
        ListItem(
            colors = groupedRowColors(),
            headlineContent = { Text("Auto refresh") },
            supportingContent = { Text("Re-reads the host's metrics every 5 seconds") },
            trailingContent = {
                Switch(checked = autoRefresh, onCheckedChange = { autoRefresh = it })
            },
            modifier = Modifier.clickable { autoRefresh = !autoRefresh },
        )

        error?.let {
            FlatCard {
                Column(Modifier.padding(14.dp)) {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    rawOut?.let { raw ->
                        Text(
                            raw,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }

        if (snapshot == null && error == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        snapshot?.let { s -> MetricCards(s, history) }
    }

    // Poll loop: runs while mounted + autoRefresh; cancels automatically on
    // leave-composition. Re-attaches when autoRefresh flips back on.
    LaunchedEffect(autoRefresh) {
        if (!autoRefresh) return@LaunchedEffect
        if (isWindowsHost(session)) {
            error = "Windows host: Monitor is not supported. Terminal, Files and Docker still work."
            return@LaunchedEffect
        }
        while (true) {
            val out = withContext(Dispatchers.IO) { session.exec(MonitorParser.PROBE) }
            val next = MonitorPoll.reduce(MonitorPoll.State(snapshot, error, rawOut), out)
            snapshot = next.snapshot
            error = next.error
            rawOut = next.raw
            next.snapshot?.let {
                val now = System.currentTimeMillis()
                history.push(now, it)
                if (hostId.isNotEmpty()) {
                    withContext(Dispatchers.IO) { sharedStats.set(hostId, StatsSnapshot.from(it, now), now) }
                }
            }
            delay(5_000)
        }
    }
}

/** Gap between the Monitor cards, shared by the tab's own column and [MetricCards]. */
private val MonitorCardSpacing = 10.dp

/**
 * The four live cards, in their own Column rather than as four siblings
 * emitted straight into the caller's: a Composable that emits more than one
 * thing at its top level cannot be reused inside another layout without
 * surprises (compose-lints ComposeMultipleContentEmitters).
 */
@Composable
private fun MetricCards(
    s: MonitorParser.Snapshot,
    history: MetricHistory,
) = Column(verticalArrangement = Arrangement.spacedBy(MonitorCardSpacing)) {
    MetricCard(
        "CPU",
        "%.1f%%".format(s.cpuPercent),
        s.cpuPercent / 100.0,
        "load %.2f %.2f %.2f".format(s.load1, s.load5, s.load15),
        history = history.cpuSeries(),
        historyMax = 100.0,
    )
    MetricCard(
        "Memory",
        "${formatBytes(s.memUsedBytes)} / ${formatBytes(s.memTotalBytes)}",
        ratio(s.memUsedBytes, s.memTotalBytes),
        if (s.swapTotalBytes > 0) {
            "swap ${formatBytes(s.swapUsedBytes)} / ${formatBytes(s.swapTotalBytes)}"
        } else {
            "no swap"
        },
        history = history.memSeries(),
        historyMax = 1.0,
    )
    MetricCard(
        "Disk (/)",
        if (s.diskTotalBytes > 0) "${formatBytes(s.diskUsedBytes)} / ${formatBytes(s.diskTotalBytes)}" else "n/a",
        ratio(s.diskUsedBytes, s.diskTotalBytes),
        if (s.diskTotalBytes > 0) {
            "%.1f%% used".format(100.0 * s.diskUsedBytes / s.diskTotalBytes)
        } else {
            "df -B1 unavailable on this host (busybox?)"
        }
    )
    MetricCard("Uptime", formatUptime(s.uptimeSeconds), null, "since boot")
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    progress: Double?,
    footnote: String,
    history: DoubleArray? = null,
    historyMax: Double = 1.0,
) {
    FlatCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Tabular monospace figures: a changing CPU percentage must not
            // reflow the row it sits in.
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
            )
            progress?.let {
                LinearProgressIndicator(
                    progress = { it.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
            history?.let { series ->
                Sparkline(
                    series,
                    historyMax,
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(36.dp),
                )
            }
            Text(
                footnote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * One-pixel-per-ambition line chart: oldest→newest [values] clamped to
 * [max], drawn as a single polyline. Geometry lives in [SparklineGeometry]
 * (pure, unit-tested); this is the thin Canvas shell.
 */
@Composable
private fun Sparkline(values: DoubleArray, max: Double, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val pts = SparklineGeometry.points(values, size.width, size.height, max)
        if (pts.size < 2) return@Canvas
        val path = Path().apply {
            moveTo(pts.first().first, pts.first().second)
            for (i in 1 until pts.size) lineTo(pts[i].first, pts[i].second)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun ratio(used: Long, total: Long): Double = if (total > 0) used.toDouble() / total else 0.0

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1fG".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.1fM".format(bytes / 1048576.0)
    bytes >= 1L shl 10 -> "%.1fK".format(bytes / 1024.0)
    else -> "${bytes}B"
}

private fun formatUptime(seconds: Long): String {
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

// =====================================================================
// Docker
// =====================================================================

@Composable
fun DockerTab(session: SessionReconnector, modifier: Modifier = Modifier) {
    val containers = remember { mutableStateListOf<DockerParser.Container>() }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var logsFor by remember { mutableStateOf<DockerParser.Container?>(null) }
    var logsText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun refresh() {
        busy = true
        scope.launch {
            // exec() returns stdout only; "docker: command not found" and
            // daemon errors are stderr — merge them or the fallback below
            // never has anything to show
            val out = withContext(Dispatchers.IO) {
                session.exec("${DockerParser.LIST_COMMAND} 2>&1")
            } ?: ""
            containers.clear()
            containers.addAll(DockerParser.parse(out))
            busy = false
            // Raw fallback: no containers parsed but the host said SOMETHING
            // ("docker: command not found", daemon errors, snap PATH issues) —
            // surface it instead of an empty list. ServerBox parity driver.
            status = if (containers.isEmpty() && out.isNotBlank()) out.trim().take(2000) else null
        }
    }

    fun action(container: DockerParser.Container, dockerCmd: String) {
        busy = true
        scope.launch {
            val out = withContext(Dispatchers.IO) { session.exec("docker $dockerCmd ${container.id} 2>&1") }
            // success prints the id; anything longer is the daemon's error
            status = out?.trim()?.takeIf { it.isNotEmpty() && it != container.id }?.take(2000)
            refresh()
        }
    }

    fun showLogs(container: DockerParser.Container) {
        logsFor = container
        logsText = "loading…"
        scope.launch {
            logsText = withContext(Dispatchers.IO) {
                // most services log to stderr; without 2>&1 the dialog is empty
                session.exec("docker logs --tail 200 ${container.id} 2>&1")
            } ?: "error: exec failed"
        }
    }

    Column(modifier.fillMaxSize()) {
        status?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f)) {
            items(containers, key = { it.id }) { c ->
                ContainerRow(
                    c,
                    onStop = { action(c, "stop") },
                    onStart = { action(c, "start") },
                    onRestart = { action(c, "restart") },
                    onLogs = { showLogs(c) }
                )
            }
        }
    }

    // First load on mount.
    LaunchedEffect(Unit) { refresh() }

    logsFor?.let { c ->
        AlertDialog(
            onDismissRequest = { logsFor = null },
            title = { Text("Logs — ${c.names}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                // Scrollable: 200 lines of logs never fit a dialog, and
                // clipping them at 20 hid the end the user came for.
                Text(
                    logsText,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { logsFor = null }) { Text("Close") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContainerRow(
    c: DockerParser.Container,
    onStop: () -> Unit,
    onStart: () -> Unit,
    onRestart: () -> Unit,
    onLogs: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val running = c.state == "running"
    Box {
        ListItem(
            leadingContent = {
                // A tinted icon, not a "●" glyph: it scales with the type
                // system and carries a content description for the state.
                Icon(
                    if (running) Icons.Filled.PlayCircle else Icons.Filled.StopCircle,
                    contentDescription = if (running) "Running" else "Stopped",
                    tint = if (running) {
                        MaterialTheme.conch.success
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            headlineContent = {
                Text(c.names, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Column {
                    Text(c.image, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.status, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            trailingContent = {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${c.names}")
                }
            },
            modifier = Modifier.combinedClickable(
                onClick = { menuOpen = true },
                onLongClick = { menuOpen = true },
            ),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Logs") },
                leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onLogs()
                }
            )
            if (running) {
                DropdownMenuItem(
                    text = { Text("Stop") },
                    leadingIcon = { Icon(Icons.Filled.StopCircle, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onStop()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Restart") },
                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRestart()
                    }
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Start") },
                    leadingIcon = { Icon(Icons.Filled.PlayCircle, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onStart()
                    }
                )
            }
        }
    }
    HorizontalDivider()
}

// =====================================================================
// SFTP (Files)
// =====================================================================

/** One remote directory entry as shown by the Files tab. */
data class SftpEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpTab(
    session: SessionReconnector,
    connectionGen: Int,
    transfers: TransferQueue,
    modifier: Modifier = Modifier,
    startPath: String = "/",
) {
    val context = LocalContext.current
    var sftp by remember { mutableStateOf<SFTPClient?>(null) }
    val transferItems by transfers.items.collectAsState()
    var showTransfers by remember { mutableStateOf(false) }
    var sftpFailed by remember { mutableStateOf(false) }
    // bumped by "Retry": the open effect is keyed on it, otherwise the button
    // only cleared the flag and the tab sat on "Opening SFTP…" forever
    var retryGen by remember { mutableIntStateOf(0) }
    var path by remember { mutableStateOf(startPath) }
    val entries = remember { mutableStateListOf<SftpEntry>() }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    // sortMode: 0 name, 1 size, 2 time; sign stored separately
    var sortMode by remember { mutableIntStateOf(0) }
    var sortDescending by remember { mutableStateOf(false) }
    var sortLabel by remember { mutableStateOf(SORT_OPTIONS.first()) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    var actionEntry by remember { mutableStateOf<SftpEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<SftpEntry?>(null) }
    var showMkdir by remember { mutableStateOf(false) }
    var showNewFile by remember { mutableStateOf(false) }
    var newFileText by remember { mutableStateOf("") }
    var showRename by remember { mutableStateOf<SftpEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var mkdirText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // (Re)open SFTP on mount AND on every reconnect (connectionGen change).
    // The previous client is closed first so we never leak a dead SFTPClient
    // after the underlying SSHClient is replaced by SessionReconnector.
    LaunchedEffect(connectionGen, retryGen) {
        // close() sends CHANNEL_CLOSE — a socket write, which on the main
        // thread throws NetworkOnMainThreadException (swallowed) and leaves
        // the server-side channel open: the very leak this is here to stop
        sftp?.let { old -> withContext(Dispatchers.IO) { runCatching { old.close() } } }
        sftp = null
        sftpFailed = false
        entries.clear()
        // NonCancellable so a cancellation mid-open still hands us the client
        // (a cancelled withContext discards its result — and the channel)
        val client = withContext(NonCancellable + Dispatchers.IO) { session.sftpClient() }
        if (!isActive) {
            // tab left / reconnected while opening: give the channel back
            client?.let { c -> withContext(NonCancellable + Dispatchers.IO) { runCatching { c.close() } } }
            return@LaunchedEffect
        }
        if (client == null) {
            sftpFailed = true
        } else {
            sftp = client
            val home = withContext(Dispatchers.IO) {
                try { client.canonicalize(".") } catch (_: Exception) { "/" }
            }
            path = home
        }
    }

    // Leaving the Files tab discards this state — without closing here every
    // visit leaked one SFTPClient (a channel on the shared connection), until
    // the server's channel limit killed the interactive shell too.
    DisposableEffect(Unit) {
        onDispose {
            // off the main thread (see above); a plain thread, since the
            // composition's scope is already cancelled here
            sftp?.let { old ->
                Thread({ runCatching { old.close() } }, "sftp-close").apply { isDaemon = true }.start()
            }
        }
    }

    fun refresh() {
        val sftp = sftp ?: return
        busy = true
        val target = path
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    sftp.ls(target)
                        .filter { it.name != "." && it.name != ".." }
                        .let { raw -> sortEntries(raw, sortMode, sortDescending) }
                        .map {
                            SftpEntry(
                                name = it.name,
                                path = it.path,
                                isDir = it.isDirectory,
                                size = it.attributes.size,
                                mtime = it.attributes.mtime * 1000L,
                            )
                        }
                } catch (e: Exception) {
                    CrashReporting.report(e)
                    status = "Failed to list: ${e.message}"
                    emptyList()
                }
            }
            // a slow listing that returns after the user already navigated
            // away must not overwrite the current directory's contents
            if (target != path) return@launch
            entries.clear()
            entries.addAll(list)
            busy = false
        }
    }

    // Refresh whenever path or sort changes (after SFTP is ready).
    LaunchedEffect(sftp, path, sortMode, sortDescending) {
        if (sftp != null) refresh()
    }

    fun navigate(dir: SftpEntry) { path = dir.path }
    fun goUp() {
        val cur = path.trimEnd('/')
        if (cur.isEmpty()) return
        path = cur.substringBeforeLast('/').ifEmpty { "/" }
    }

    // Transfers run in the queue (their own SFTP channel each, so they
    // outlive this tab); the sheet shows progress, Cancel and Retry.
    fun download(entry: SftpEntry) {
        status = try {
            val item = transfers.enqueueDownload(entry.name, entry.path, entry.size)
            if (item == null) "Already downloading ${entry.name}" else "Queued download: ${entry.name}"
        } catch (e: IllegalArgumentException) {
            "Download failed: ${e.message}"
        }
    }

    fun upload(uri: Uri) {
        if (sftp == null) return
        val target = path
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val name = displayNameOf(context, uri) ?: ShareUpload.FALLBACK_NAME
                    val staged = stageUri(context, uri)
                    val item = transfers.enqueueUpload(
                        staged,
                        ShareUpload.remotePath(target, name),
                        deleteSourceWhenDone = true,
                    )
                    if (item == null) "Already uploading $name" else "Queued upload: $name"
                } catch (e: Exception) {
                    CrashReporting.report(e)
                    "Upload failed: ${e.message}"
                }
            }
            status = msg
        }
    }

    // A finished upload changes the listing; a finished download changes
    // nothing here but the status line tells the user where it landed.
    val doneIds = transferItems.filter { it.state is TransferQueue.State.Done }.map { it.id }.toSet()
    var seenDone by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(doneIds) {
        val fresh = transferItems.filter { it.id in doneIds && it.id !in seenDone }
        seenDone = doneIds
        if (fresh.any { it.direction == TransferQueue.Direction.UPLOAD }) refresh()
        fresh.lastOrNull()?.let {
            status = if (it.direction == TransferQueue.Direction.DOWNLOAD) {
                "Downloaded to: ${it.localFile.absolutePath}"
            } else {
                "Uploaded: ${it.name}"
            }
        }
    }

    fun delete(entry: SftpEntry) {
        val sftp = sftp ?: return
        busy = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    if (entry.isDir) sftp.rmdir(entry.path) else sftp.rm(entry.path)
                    "Deleted ${entry.name}"
                } catch (e: Exception) {
                    CrashReporting.report(e)
                    "Delete failed: ${e.message}"
                }
            }
            busy = false
            status = msg
            refresh()
        }
    }

    fun rename(entry: SftpEntry, newName: String) {
        val sftp = sftp ?: return
        busy = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val parent = entry.path.trimEnd('/').substringBeforeLast('/')
                    sftp.rename(entry.path, "$parent/$newName")
                    null
                } catch (e: Exception) {
                    CrashReporting.report(e)
                    "Rename failed: ${e.message}"
                }
            }
            busy = false
            status = msg
            refresh()
        }
    }

    fun newFile(name: String) {
        val sftp = sftp ?: return
        busy = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val remote = path.trimEnd('/') + "/" + name
                    sftp.open(remote, java.util.Collections.singleton(net.schmizz.sshj.sftp.OpenMode.CREAT)).close()
                    null
                } catch (e: Exception) {
                    CrashReporting.report(e)
                    "Create failed: ${e.message}"
                }
            }
            busy = false
            status = msg
            refresh()
        }
    }

    fun mkdir(name: String) {
        val sftp = sftp ?: return
        busy = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    sftp.mkdir(path.trimEnd('/') + "/" + name)
                    null
                } catch (e: Exception) {
                    CrashReporting.report(e)
                    "Create failed: ${e.message}"
                }
            }
            busy = false
            status = msg
            refresh()
        }
    }

    if (sftpFailed) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.FolderOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(
                "SFTP unavailable",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "This server refused the SFTP subsystem, or it is not enabled.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = { retryGen++ }, modifier = Modifier.padding(top = 16.dp)) {
                Text("Retry")
            }
        }
        return
    }

    if (sftp == null) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(
                "Opening SFTP…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        return
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { upload(it) }
    }

    Column(modifier.fillMaxSize()) {
        // Path + navigation. The sort control is an explicit menu now: the
        // old single button cycled blindly through six states, so picking
        // "Time ↓" could take five taps and the options were never listed.
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                path,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { goUp() }, enabled = path != "/") {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Up one level")
            }
            Box {
                IconButton(onClick = { sortMenuOpen = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort ($sortLabel)")
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    SORT_OPTIONS.forEachIndexed { index, label ->
                        val mode = index / 2
                        val descending = index % 2 == 1
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = {
                                if (sortMode == mode && sortDescending == descending) {
                                    Icon(Icons.Filled.Check, contentDescription = "Selected")
                                }
                            },
                            onClick = {
                                sortMode = mode
                                sortDescending = descending
                                sortLabel = label
                                sortMenuOpen = false
                            }
                        )
                    }
                }
            }
            val active = transferItems.count { it.state.isActive }
            IconButton(onClick = { showTransfers = true }) {
                BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                    Icon(
                        Icons.Filled.SwapVert,
                        contentDescription = if (active == 0) "Transfers" else "Transfers, $active active",
                    )
                }
            }
            IconButton(onClick = { refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }
        if (showTransfers) {
            TransfersSheet(transfers, onDismiss = { showTransfers = false })
        }
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        // Reserved height: the bar appearing and vanishing used to shove the
        // whole list up and down on every refresh.
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Row(
            Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Upload", Modifier.padding(start = 6.dp))
            }
            TextButton(onClick = {
                newFileText = ""
                showNewFile = true
            }) {
                Icon(Icons.Filled.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("File", Modifier.padding(start = 6.dp))
            }
            TextButton(onClick = {
                mkdirText = ""
                showMkdir = true
            }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Folder", Modifier.padding(start = 6.dp))
            }
        }
        if (entries.isEmpty() && !busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Empty folder",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(entries, key = { it.path }) { entry ->
                    EntryRow(
                        entry,
                        onClick = { if (entry.isDir) navigate(entry) else actionEntry = entry },
                        onLongClick = { actionEntry = entry }
                    )
                }
            }
        }
    }

    actionEntry?.let { entry ->
        // A sheet, not a dialog: three actions crammed into a dialog's
        // confirm slot overflow a narrow screen and gave Delete the same
        // weight as Download.
        ModalBottomSheet(onDismissRequest = { actionEntry = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                ListItem(
                    leadingContent = {
                        Icon(
                            if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                            contentDescription = null,
                        )
                    },
                    headlineContent = {
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            (if (entry.isDir) "Folder" else formatSize(entry.size)) + " · " +
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                    .format(Date(entry.mtime))
                        )
                    },
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (!entry.isDir) {
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
                        headlineContent = { Text("Download") },
                        modifier = Modifier.clickable {
                            actionEntry = null
                            download(entry)
                        },
                    )
                }
                ListItem(
                    leadingContent = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                    headlineContent = { Text("Rename") },
                    modifier = Modifier.clickable {
                        actionEntry = null
                        renameText = entry.name
                        showRename = entry
                    },
                )
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    headlineContent = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable {
                        actionEntry = null
                        confirmDelete = entry
                    },
                )
            }
        }
    }

    // Deleting a remote file is not undoable over SFTP: confirm first.
    confirmDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Delete ${entry.name}?") },
            text = {
                Text(
                    if (entry.isDir) {
                        "The folder is removed from the server. This cannot be undone."
                    } else {
                        "The file is removed from the server. This cannot be undone."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = entry
                    confirmDelete = null
                    delete(target)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }

    showRename?.let { entry ->
        AlertDialog(
            onDismissRequest = { showRename = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("New name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRename = null
                    if (renameText.isNotBlank()) rename(entry, renameText.trim())
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showRename = null }) { Text("Cancel") } }
        )
    }

    if (showNewFile) {
        AlertDialog(
            onDismissRequest = { showNewFile = false },
            title = { Text("New file") },
            text = {
                OutlinedTextField(
                    value = newFileText,
                    onValueChange = { newFileText = it },
                    label = { Text("File name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNewFile = false
                    if (newFileText.isNotBlank()) newFile(newFileText.trim())
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewFile = false }) { Text("Cancel") } }
        )
    }

    if (showMkdir) {
        AlertDialog(
            onDismissRequest = { showMkdir = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = mkdirText,
                    onValueChange = { mkdirText = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showMkdir = false
                    if (mkdirText.isNotBlank()) mkdir(mkdirText.trim())
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showMkdir = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(entry: SftpEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    ListItem(
        leadingContent = {
            Icon(
                if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                contentDescription = if (entry.isDir) "Folder" else "File",
                tint = if (entry.isDir) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        },
        headlineContent = {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(if (entry.isDir) "Directory" else formatSize(entry.size))
        },
        trailingContent = if (entry.isDir) {
            { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
        } else {
            null
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
    HorizontalDivider()
}

/** Sort menu entries; index/2 is the sort key, index%2 the direction. */
private val SORT_OPTIONS = listOf(
    "Name (A–Z)",
    "Name (Z–A)",
    "Size (smallest)",
    "Size (largest)",
    "Modified (oldest)",
    "Modified (newest)",
)

private fun sortEntries(raw: List<RemoteResourceInfo>, sortMode: Int, descending: Boolean): List<RemoteResourceInfo> {
    val key: (RemoteResourceInfo) -> Comparable<*> = when (sortMode) {
        1 -> { e -> e.attributes.size }
        2 -> { e -> e.attributes.mtime }
        else -> { e -> e.name.lowercase() }
    }
    val base = compareByDescending<RemoteResourceInfo> { it.isDirectory }.thenBy(key)
    return if (descending) raw.sortedWith(base.reversed()) else raw.sortedWith(base)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** Bottom navigation between the four in-session tabs. */
@Composable
internal fun SessionTabBar(tab: SessionTab, onTab: (SessionTab) -> Unit) {
    // Plain Material NavigationBar: the theme supplies the container, the
    // selected indicator and the unselected contrast, all of which were
    // hardcoded before.
    NavigationBar {
        SessionTab.entries.forEach { t ->
            NavigationBarItem(
                selected = tab == t,
                onClick = { onTab(t) },
                icon = { Icon(t.icon, contentDescription = null) },
                label = { Text(t.title) },
                alwaysShowLabel = true,
            )
        }
    }
}

/** Placeholder while a tool tab waits for the shared connection. */
@Composable
internal fun LoadingTab(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                "$label — connecting…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/** The Monitor probe reads /proc — a clear message beats a generic error on Windows OpenSSH hosts (ServerBox #491 parity). */
private suspend fun isWindowsHost(session: SessionReconnector): Boolean {
    val uname = withContext(Dispatchers.IO) { session.exec("uname -s") }
    return uname?.contains("NT", true) == true
}

/**
 * The user-visible file name of a SAF document. `lastPathSegment` is an
 * opaque document id for most providers (Downloads: "msf:123", Drive:
 * "acc=1;doc=…"), so only the local-storage provider happened to work.
 */
internal fun displayNameOf(context: android.content.Context, uri: Uri): String? {
    val fromQuery = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
    val name = fromQuery?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: return null
    // one path component, never a traversal
    return name.replace('/', '_').takeIf { it != ".." && it != "." }
}
