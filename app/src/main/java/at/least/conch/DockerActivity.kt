package at.least.conch

import android.os.Bundle
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.schmizz.sshj.SSHClient
import java.util.concurrent.Executors

/** Docker container management over SSH (docker CLI): list, start, stop, logs. */
class DockerActivity : androidx.fragment.app.FragmentActivity() {

    private var client: SSHClient? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "conch-docker").apply { isDaemon = true }
    }

    private var host: Host? = null

    private val containers = mutableStateOf<List<DockerParser.Container>>(emptyList())
    private val busy = mutableStateOf(false)
    private val status = mutableStateOf<String?>(null)
    private val logsFor = mutableStateOf<DockerParser.Container?>(null)
    private val logsText = mutableStateOf("")

    private val tofuPrompt: KeyPrompt = { _, answer ->
        runOnUiThread { status.value = "Unknown host key — trust it from a terminal session first" }
        answer(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hostId = intent.getStringExtra("hostId") ?: return finish()
        host = HostStore(this).load().firstOrNull { it.id == hostId } ?: return finish()
        setContent { DockerScreen() }
        connect()
    }

    private fun connect() {
        busy.value = true
        executor.execute {
            try {
                client = SshConnectionFactory.connect(this, host!!, tofuPrompt)
                runOnUiThread { refresh() }
            } catch (e: Exception) {
                CrashReporting.report(e)
                runOnUiThread {
                    busy.value = false
                    status.value = SshConnectionFactory.describeError(e)
                }
            }
        }
    }

    private fun exec(command: String, onDone: (String) -> Unit) {
        val ssh = client ?: return
        executor.execute {
            val out = try {
                val s = ssh.startSession()
                val cmd = s.exec(command)
                val text = cmd.inputStream.readBytes().decodeToString()
                cmd.close()
                s.close()
                text
            } catch (e: Exception) {
                "error: ${e.message}"
            }
            runOnUiThread { onDone(out) }
        }
    }

    private fun refresh() {
        busy.value = true
        exec(DockerParser.LIST_COMMAND) { out ->
            containers.value = DockerParser.parse(out)
            busy.value = false
            status.value = if (containers.value.isEmpty() && out.contains("error", true)) {
                out.trim().take(120)
            } else {
                null
            }
        }
    }

    private fun action(container: DockerParser.Container, dockerCmd: String) {
        busy.value = true
        exec("docker $dockerCmd ${container.id}") { refresh() }
    }

    private fun showLogs(container: DockerParser.Container) {
        logsFor.value = container
        logsText.value = "loading…"
        exec("docker logs --tail 200 ${container.id}") { logsText.value = it }
    }

    override fun onDestroy() {
        val c = client
        executor.execute {
            try { c?.disconnect() } catch (_: Exception) {}
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DockerScreen() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Docker") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
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
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                if (busy.value) LinearProgressIndicator(Modifier.fillMaxWidth())

                LazyColumn(Modifier.weight(1f)) {
                    items(containers.value, key = { it.id }) { c ->
                        ContainerRow(c)
                    }
                }
            }
        }

        logsFor.value?.let { c ->
            AlertDialog(
                onDismissRequest = { logsFor.value = null },
                title = { Text("logs: ${c.names}") },
                text = {
                    Text(
                        logsText.value,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 20,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                confirmButton = { TextButton(onClick = { logsFor.value = null }) { Text("Close") } }
            )
        }
    }

    @Composable
    private fun ContainerRow(c: DockerParser.Container) {
        var menuOpen by remember { mutableStateOf(false) }
        val running = c.state == "running"
        Card(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 3.dp)
                .clickable { menuOpen = true }
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "●",
                        color = if (running) Color(0xFF23D18B) else Color(0xFF666666),
                    )
                    Text(c.names, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                }
                Text(
                    c.image,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    c.status,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Logs") },
                onClick = {
                    menuOpen = false
                    showLogs(c)
                }
            )
            if (running) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Stop") },
                    onClick = {
                        menuOpen = false
                        action(c, "stop")
                    }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Restart") },
                    onClick = {
                        menuOpen = false
                        action(c, "restart")
                    }
                )
            } else {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Start") },
                    onClick = {
                        menuOpen = false
                        action(c, "start")
                    }
                )
            }
        }
    }
}
