package at.least.conch

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class SftpEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
)

/** Remote file browser over SFTP: list, navigate, download, upload, rename, delete, mkdir. */
class SftpActivity : ComponentActivity() {

    private var client: SSHClient? = null
    private var sftp: SFTPClient? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "conch-sftp").apply { isDaemon = true }
    }

    private var host: Host? = null

    private val path = mutableStateOf("/")
    private val entries = mutableStateOf<List<SftpEntry>>(emptyList())
    private val busy = mutableStateOf(false)
    private val status = mutableStateOf<String?>(null)

    /** sortMode index: 0 name, 1 size, 2 time; sign stored separately */
    private val sortMode = mutableStateOf(0)
    private val sortDescending = mutableStateOf(false)
    private val actionEntry = mutableStateOf<SftpEntry?>(null)
    private val showMkdir = mutableStateOf(false)
    private val showNewFile = mutableStateOf(false)
    private val newFileText = mutableStateOf("")
    private val showRename = mutableStateOf<SftpEntry?>(null)
    private val renameText = mutableStateOf("")
    private val mkdirText = mutableStateOf("")

    private val tofuPrompt: KeyPrompt = { request, answer ->
        runOnUiThread {
            status.value = "Unknown host key — trust it from a terminal session first"
        }
        answer(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hostId = intent.getStringExtra("hostId") ?: return finish()
        host = HostStore(this).load().firstOrNull { it.id == hostId } ?: return finish()
        setContent { SftpScreen() }

        status.value = "Connecting…"
        executor.execute {
            try {
                val ssh = SshConnectionFactory.connect(this, host!!, tofuPrompt)
                client = ssh
                sftp = ssh.newSFTPClient()
                val home = try {
                    sftp!!.canonicalize(".")
                } catch (_: Exception) {
                    "/"
                }
                path.value = home
                runOnUiThread {
                    status.value = null
                    refresh()
                }
            } catch (e: Exception) {
                CrashReporting.report(e)
                runOnUiThread { status.value = SshConnectionFactory.describeError(e) }
            }
        }
    }

    override fun onDestroy() {
        executor.execute {
            try { sftp?.close() } catch (_: Exception) {}
            try { client?.disconnect() } catch (_: Exception) {}
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun refresh() {
        val current = path.value
        busy.value = true
        executor.execute {
            try {
                val list = sftp!!.ls(current)
                    .filter { it.name != "." && it.name != ".." }
                    .let { raw -> sortEntries(raw) }
                    .map {
                        SftpEntry(
                            name = it.name,
                            path = it.path,
                            isDir = it.isDirectory,
                            size = it.attributes.size,
                            mtime = it.attributes.mtime * 1000L,
                        )
                    }
                runOnUiThread {
                    entries.value = list
                    busy.value = false
                    status.value = null
                }
            } catch (e: Exception) {
                CrashReporting.report(e)
                runOnUiThread {
                    busy.value = false
                    status.value = "Failed to list: ${e.message}"
                }
            }
        }
    }

    private fun sortEntries(raw: List<RemoteResourceInfo>): List<RemoteResourceInfo> {
        val key: (RemoteResourceInfo) -> Comparable<*> = when (sortMode.value) {
            1 -> { e -> e.attributes.size }
            2 -> { e -> e.attributes.mtime }
            else -> { e -> e.name.lowercase() }
        }
        val base = compareByDescending<RemoteResourceInfo> { it.isDirectory }
            .thenBy(key)
        return if (sortDescending.value) raw.sortedWith(base.reversed()) else raw.sortedWith(base)
    }

    private fun cycleSort() {
        // cycles: name asc -> name desc -> size asc -> size desc -> time asc -> time desc
        val next = when {
            sortMode.value == 0 && !sortDescending.value -> { sortDescending.value = true; "Name ↓" }
            sortMode.value == 0 -> { sortMode.value = 1; sortDescending.value = false; "Size ↑" }
            sortMode.value == 1 && !sortDescending.value -> { sortDescending.value = true; "Size ↓" }
            sortMode.value == 1 -> { sortMode.value = 2; sortDescending.value = false; "Time ↑" }
            sortMode.value == 2 && !sortDescending.value -> { sortDescending.value = true; "Time ↓" }
            else -> { sortMode.value = 0; sortDescending.value = false; "Name ↑" }
        }
        sortLabel.value = next
        refresh()
    }

    private val sortLabel = mutableStateOf("Name ↑")

    private fun navigate(dir: SftpEntry) {
        path.value = dir.path
        refresh()
    }

    private fun goUp() {
        val cur = path.value.trimEnd('/')
        if (cur.isEmpty()) return
        path.value = cur.substringBeforeLast('/').ifEmpty { "/" }
        refresh()
    }

    private fun download(entry: SftpEntry) {
        busy.value = true
        status.value = "Downloading ${entry.name}…"
        executor.execute {
            try {
                val local = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), entry.name)
                sftp!!.get(entry.path, local.absolutePath)
                runOnUiThread {
                    busy.value = false
                    status.value = "Downloaded to: ${local.absolutePath}"
                }
            } catch (e: Exception) {
                CrashReporting.report(e)
                runOnUiThread {
                    busy.value = false
                    status.value = "Download failed: ${e.message}"
                }
            }
        }
    }

    private fun upload(uri: Uri) {
        busy.value = true
        status.value = "Uploading…"
        executor.execute {
            var tmp: File? = null
            try {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
                tmp = File.createTempFile("up", null, cacheDir).also { t ->
                    contentResolver.openInputStream(uri)?.use { input ->
                        t.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalStateException("Cannot read file")
                }
                val remote = path.value.trimEnd('/') + "/" + name
                sftp!!.getFileTransfer().upload(tmp.absolutePath, remote)
                runOnUiThread {
                    busy.value = false
                    status.value = "Uploaded: $name"
                    refresh()
                }
            } catch (e: Exception) {
                CrashReporting.report(e)
                runOnUiThread {
                    busy.value = false
                    status.value = "Upload failed: ${e.message}"
                }
            } finally {
                tmp?.delete()
            }
        }
    }

    private fun delete(entry: SftpEntry) {
        busy.value = true
        executor.execute {
            try {
                if (entry.isDir) sftp!!.rmdir(entry.path) else sftp!!.rm(entry.path)
                runOnUiThread {
                    busy.value = false
                    status.value = "Deleted ${entry.name}"
                    refresh()
                }
            } catch (e: Exception) {
                CrashReporting.report(e)
                runOnUiThread {
                    busy.value = false
                    status.value = "Delete failed: ${e.message}"
                }
            }
        }
    }

    private fun rename(entry: SftpEntry, newName: String) {
        busy.value = true
        executor.execute {
            try {
                val parent = entry.path.trimEnd('/').substringBeforeLast('/')
                sftp!!.rename(entry.path, parent + "/" + newName)
                runOnUiThread {
                    busy.value = false
                    status.value = null
                    refresh()
                }
            } catch (e: Exception) {
                CrashReporting.report(e)
                runOnUiThread {
                    busy.value = false
                    status.value = "Rename failed: ${e.message}"
                }
            }
        }
    }

    private fun newFile(name: String) {
        busy.value = true
        executor.execute {
            try {
                val remote = path.value.trimEnd('/') + "/" + name
                sftp!!.open(
                    remote,
                    java.util.Collections.singleton(net.schmizz.sshj.sftp.OpenMode.CREAT),
                ).close()
                runOnUiThread {
                    busy.value = false
                    status.value = null
                    refresh()
                }
            } catch (e: Exception) {
                CrashReporting.report(e)
                runOnUiThread {
                    busy.value = false
                    status.value = "Create failed: ${e.message}"
                }
            }
        }
    }

    private fun mkdir(name: String) {
        busy.value = true
        executor.execute {
            try {
                sftp!!.mkdir(path.value.trimEnd('/') + "/" + name)
                runOnUiThread {
                    busy.value = false
                    status.value = null
                    refresh()
                }
            } catch (e: Exception) {
                CrashReporting.report(e)
                runOnUiThread {
                    busy.value = false
                    status.value = "Create failed: ${e.message}"
                }
            }
        }
    }

    // ------------------------------------------------------------------ UI

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SftpScreen() {
        val uploadLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { upload(it) } }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            path.value,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        TextButton(onClick = { cycleSort() }) {
                            Text(sortLabel.value, fontSize = 13.sp)
                        }
                        IconButton(onClick = { goUp() }, enabled = path.value != "/") {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "Up one level")
                        }
                        IconButton(onClick = { refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                )
            },
            floatingActionButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FloatingActionButton(onClick = {
                        newFileText.value = ""
                        showNewFile.value = true
                    }) {
                        Icon(Icons.Filled.Description, contentDescription = "New file")
                    }
                    FloatingActionButton(onClick = {
                        mkdirText.value = ""
                        showMkdir.value = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "New folder")
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                status.value?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                if (busy.value) LinearProgressIndicator(Modifier.fillMaxWidth())

                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) { Text("Upload file") }
                }

                LazyColumn(Modifier.weight(1f)) {
                    items(entries.value, key = { it.path }) { entry ->
                        EntryRow(entry)
                    }
                }
            }
        }

        actionEntry.value?.let { entry ->
            AlertDialog(
                onDismissRequest = { actionEntry.value = null },
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
                                actionEntry.value = null
                                download(entry)
                            }) { Text("Download") }
                        }
                        TextButton(onClick = {
                            actionEntry.value = null
                            renameText.value = entry.name
                            showRename.value = entry
                        }) { Text("Rename") }
                        TextButton(onClick = {
                            actionEntry.value = null
                            delete(entry)
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { actionEntry.value = null }) { Text("Cancel") }
                }
            )
        }

        showRename.value?.let { entry ->
            AlertDialog(
                onDismissRequest = { showRename.value = null },
                title = { Text("Rename") },
                text = {
                    OutlinedTextField(value = renameText.value, onValueChange = { renameText.value = it })
                },
                confirmButton = {
                    TextButton(onClick = {
                        showRename.value = null
                        if (renameText.value.isNotBlank()) rename(entry, renameText.value.trim())
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { showRename.value = null }) { Text("Cancel") } }
            )
        }

        if (showNewFile.value) {
            AlertDialog(
                onDismissRequest = { showNewFile.value = false },
                title = { Text("New file") },
                text = {
                    OutlinedTextField(value = newFileText.value, onValueChange = { newFileText.value = it })
                },
                confirmButton = {
                    TextButton(onClick = {
                        showNewFile.value = false
                        if (newFileText.value.isNotBlank()) newFile(newFileText.value.trim())
                    }) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = { showNewFile.value = false }) { Text("Cancel") } }
            )
        }

        if (showMkdir.value) {
            AlertDialog(
                onDismissRequest = { showMkdir.value = false },
                title = { Text("New folder") },
                text = {
                    OutlinedTextField(value = mkdirText.value, onValueChange = { mkdirText.value = it })
                },
                confirmButton = {
                    TextButton(onClick = {
                        showMkdir.value = false
                        if (mkdirText.value.isNotBlank()) mkdir(mkdirText.value.trim())
                    }) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = { showMkdir.value = false }) { Text("Cancel") } }
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun EntryRow(entry: SftpEntry) {
        Card(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 3.dp)
                .combinedClickable(
                    onClick = { if (entry.isDir) navigate(entry) else actionEntry.value = entry },
                    onLongClick = { actionEntry.value = entry }
                )
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Icon(
                    if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                    contentDescription = null,
                    tint = if (entry.isDir) androidx.compose.ui.graphics.Color(0xFF3B8EEA) else MaterialTheme.colorScheme.onSurfaceVariant
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

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
