package at.least.conch

import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import java.io.File
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

    fun chipText(count: Int): String = "⇅ $count"

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
fun MonitorTab(session: SessionReconnector, modifier: Modifier = Modifier) {
    var snapshot by remember { mutableStateOf<MonitorParser.Snapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var rawOut by remember { mutableStateOf<String?>(null) }
    var autoRefresh by remember { mutableStateOf(true) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto refresh (5s)", modifier = Modifier.weight(1f))
            Switch(checked = autoRefresh, onCheckedChange = { autoRefresh = it })
        }

        error?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    rawOut?.let { raw ->
                        Text(
                            raw,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
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

        snapshot?.let { s ->
            MetricCard(
                "CPU",
                "%.1f%%".format(s.cpuPercent),
                s.cpuPercent / 100.0,
                "load %.2f %.2f %.2f".format(s.load1, s.load5, s.load15)
            )
            MetricCard(
                "Memory",
                "${formatBytes(s.memUsedBytes)} / ${formatBytes(s.memTotalBytes)}",
                ratio(s.memUsedBytes, s.memTotalBytes),
                if (s.swapTotalBytes > 0) {
                    "swap ${formatBytes(s.swapUsedBytes)} / ${formatBytes(s.swapTotalBytes)}"
                } else {
                    "no swap"
                }
            )
            MetricCard(
                "Disk (/)",
                "${formatBytes(s.diskUsedBytes)} / ${formatBytes(s.diskTotalBytes)}",
                ratio(s.diskUsedBytes, s.diskTotalBytes),
                "%.1f%% used".format(100.0 * s.diskUsedBytes / s.diskTotalBytes.coerceAtLeast(1))
            )
            MetricCard("Uptime", formatUptime(s.uptimeSeconds), null, "since boot")
        }
    }

    // Poll loop: runs while mounted + autoRefresh; cancels automatically on
    // leave-composition. Re-attaches when autoRefresh flips back on.
    LaunchedEffect(autoRefresh) {
        if (!autoRefresh) return@LaunchedEffect
        while (true) {
            val out = withContext(Dispatchers.IO) { session.exec(MonitorParser.PROBE) }
            val next = MonitorPoll.reduce(MonitorPoll.State(snapshot, error, rawOut), out)
            snapshot = next.snapshot
            error = next.error
            rawOut = next.raw
            delay(5_000)
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, progress: Double?, footnote: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
            progress?.let {
                LinearProgressIndicator(
                    progress = { it.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
            Text(
                footnote,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
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
            val out = withContext(Dispatchers.IO) {
                session.exec(DockerParser.LIST_COMMAND)
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
            withContext(Dispatchers.IO) { session.exec("docker $dockerCmd ${container.id}") }
            refresh()
        }
    }

    fun showLogs(container: DockerParser.Container) {
        logsFor = container
        logsText = "loading…"
        scope.launch {
            logsText = withContext(Dispatchers.IO) {
                session.exec("docker logs --tail 200 ${container.id}")
            } ?: "error: exec failed"
        }
    }

    Column(modifier.fillMaxSize()) {
        status?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
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
            title = { Text("logs: ${c.names}") },
            text = {
                Text(
                    logsText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 20,
                    overflow = TextOverflow.Ellipsis,
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
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .combinedClickable(onClick = { menuOpen = true }, onLongClick = { menuOpen = true })
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("●", color = if (running) Color(0xFF23D18B) else Color(0xFF666666))
                Text(c.names, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
            Text(c.image, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(c.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        androidx.compose.material3.DropdownMenuItem(text = { Text("Logs") }, onClick = {
            menuOpen = false
            onLogs()
        })
        if (running) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Stop") },
                onClick = {
                    menuOpen = false
                    onStop()
                }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Restart") },
                onClick = {
                    menuOpen = false
                    onRestart()
                }
            )
        } else {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Start") },
                onClick = {
                    menuOpen = false
                    onStart()
                }
            )
        }
    }
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

@Composable
fun SftpTab(
    session: SessionReconnector,
    connectionGen: Int,
    modifier: Modifier = Modifier,
    startPath: String = "/",
) {
    val context = LocalContext.current
    var sftp by remember { mutableStateOf<SFTPClient?>(null) }
    var sftpFailed by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf(startPath) }
    val entries = remember { mutableStateListOf<SftpEntry>() }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    // sortMode: 0 name, 1 size, 2 time; sign stored separately
    var sortMode by remember { mutableStateOf(0) }
    var sortDescending by remember { mutableStateOf(false) }
    var sortLabel by remember { mutableStateOf("Name ↑") }

    var actionEntry by remember { mutableStateOf<SftpEntry?>(null) }
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
    LaunchedEffect(connectionGen) {
        sftp?.let { runCatching { it.close() } }
        sftp = null
        sftpFailed = false
        entries.clear()
        val client = withContext(Dispatchers.IO) { session.sftpClient() }
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
        onDispose { sftp?.let { runCatching { it.close() } } }
    }

    fun refresh() {
        val sftp = sftp ?: return
        busy = true
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    sftp.ls(path)
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

    fun cycleSort() {
        val next = when {
            sortMode == 0 && !sortDescending -> {
                sortDescending = true
                "Name ↓"
            }
            sortMode == 0 -> {
                sortMode = 1
                sortDescending = false
                "Size ↑"
            }
            sortMode == 1 && !sortDescending -> {
                sortDescending = true
                "Size ↓"
            }
            sortMode == 1 -> {
                sortMode = 2
                sortDescending = false
                "Time ↑"
            }
            sortMode == 2 && !sortDescending -> {
                sortDescending = true
                "Time ↓"
            }
            else -> {
                sortMode = 0
                sortDescending = false
                "Name ↑"
            }
        }
        sortLabel = next
    }

    fun download(entry: SftpEntry) {
        val sftp = sftp ?: return
        busy = true
        status = "Downloading ${entry.name}…"
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val local = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), entry.name)
                    sftp.get(entry.path, local.absolutePath)
                    "Downloaded to: ${local.absolutePath}"
                } catch (e: Exception) {
                    CrashReporting.report(e)
                    "Download failed: ${e.message}"
                }
            }
            busy = false
            status = msg
        }
    }

    fun upload(uri: Uri) {
        val sftp = sftp ?: return
        busy = true
        status = "Uploading…"
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                var tmp: File? = null
                try {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
                    tmp = File.createTempFile("up", null, context.cacheDir).also { t ->
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            t.outputStream().use { input.copyTo(it) }
                        } ?: error("Cannot read file")
                    }
                    val remote = path.trimEnd('/') + "/" + name
                    sftp.getFileTransfer().upload(tmp.absolutePath, remote)
                    "Uploaded: $name"
                } catch (e: Exception) {
                    CrashReporting.report(e)
                    "Upload failed: ${e.message}"
                } finally {
                    tmp?.delete()
                }
            }
            busy = false
            status = msg
            refresh()
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
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SFTP unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { sftpFailed = false }) { Text("Retry") }
        }
        return
    }

    if (sftp == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Opening SFTP…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { upload(it) }
    }

    Column(modifier.fillMaxSize()) {
        // breadcrumb / sort / up / refresh
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                path,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { cycleSort() }) { Text(sortLabel, fontSize = 13.sp) }
            androidx.compose.material3.IconButton(onClick = { goUp() }, enabled = path != "/") {
                androidx.compose.material3.Icon(Icons.Filled.ArrowUpward, contentDescription = "Up one level")
            }
            androidx.compose.material3.IconButton(onClick = { refresh() }) {
                androidx.compose.material3.Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }
        status?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
            TextButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) { Text("Upload file") }
            TextButton(onClick = {
                newFileText = ""
                showNewFile = true
            }) { Text("New file") }
            TextButton(onClick = {
                mkdirText = ""
                showMkdir = true
            }) { Text("New folder") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.path }) { entry ->
                EntryRow(
                    entry,
                    onClick = { if (entry.isDir) navigate(entry) else { actionEntry = entry } },
                    onLongClick = { actionEntry = entry }
                )
            }
        }
    }

    actionEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { actionEntry = null },
            title = { Text(entry.name) },
            text = {
                Text(
                    buildString {
                        append(if (entry.isDir) "Folder" else formatSize(entry.size))
                        append("\nModified: ")
                        append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.mtime)))
                    }
                )
            },
            confirmButton = {
                Row {
                    if (!entry.isDir) {
                        TextButton(onClick = {
                            actionEntry = null
                            download(entry)
                        }) { Text("Download") }
                    }
                    TextButton(
                        onClick = {
                            actionEntry = null
                            renameText = entry.name
                            showRename = entry
                        }
                    ) { Text("Rename") }
                    TextButton(onClick = {
                        actionEntry = null
                        delete(entry)
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { actionEntry = null }) { Text("Cancel") } }
        )
    }

    showRename?.let { entry ->
        AlertDialog(
            onDismissRequest = { showRename = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }) },
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
            text = { OutlinedTextField(value = newFileText, onValueChange = { newFileText = it }) },
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
            text = { OutlinedTextField(value = mkdirText, onValueChange = { mkdirText = it }) },
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
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                contentDescription = null,
                tint = if (entry.isDir) Color(0xFF3B8EEA) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text(entry.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (entry.isDir) "Directory" else formatSize(entry.size),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

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
    NavigationBar(containerColor = Color(0xFF10151E)) {
        SessionTab.entries.forEach { t ->
            NavigationBarItem(
                selected = tab == t,
                onClick = { onTab(t) },
                icon = { Icon(t.icon, contentDescription = t.title) },
                label = { Text(t.title, fontSize = 11.sp) },
                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFFE0E0E0),
                    selectedTextColor = Color(0xFFE0E0E0),
                    indicatorColor = Color(0xFF1E62B4),
                    unselectedIconColor = Color(0xFF9E9E9E),
                    unselectedTextColor = Color(0xFF9E9E9E),
                ),
            )
        }
    }
}

/** Placeholder while a tool tab waits for the shared connection. */
@Composable
internal fun LoadingTab(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LinearProgressIndicator(Modifier.fillMaxWidth(0.6f))
            Text(
                "$label — connecting…",
                fontSize = 13.sp,
                color = Color(0xFF9E9E9E),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
