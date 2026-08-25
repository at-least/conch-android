package at.least.conch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Lightweight server dashboard: CPU / memory / swap / disk / load / uptime. */
class MonitorActivity : ComponentActivity() {

    private var client: SSHClient? = null
    private var session: Session? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "conch-monitor").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    /** Incremented on every toggle so stale poll loops terminate. */
    private val pollGeneration = AtomicInteger(0)

    private var host: Host? = null

    private val snapshot = mutableStateOf<MonitorParser.Snapshot?>(null)
    private val error = mutableStateOf<String?>(null)
    private val autoRefresh = mutableStateOf(true)

    private val tofuPrompt: KeyPrompt = { _, answer ->
        runOnUiThread { error.value = "Unknown host key — trust it from a terminal session first" }
        answer(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hostId = intent.getStringExtra("hostId") ?: return finish()
        host = HostStore(this).load().firstOrNull { it.id == hostId } ?: return finish()
        setContent { MonitorScreen() }

        executor.execute {
            try {
                val ssh = SshConnectionFactory.connect(this, host!!, tofuPrompt)
                client = ssh
                running.set(true)
                pollLoop()
            } catch (e: Exception) {
                CrashReporting.report(e)
                runOnUiThread { error.value = SshConnectionFactory.describeError(e) }
            }
        }
    }

    private fun pollLoop() {
        val gen = pollGeneration.incrementAndGet()
        while (running.get() && !isFinishing && gen == pollGeneration.get()) {
            val result = probe()
            runOnUiThread {
                if (result != null) {
                    snapshot.value = result
                    error.value = null
                } else if (snapshot.value == null) {
                    error.value = "Failed to read metrics"
                }
            }
            if (!autoRefresh.value) break
            try {
                Thread.sleep(5000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun probe(): MonitorParser.Snapshot? {
        val ssh = client ?: return null
        return try {
            val s = ssh.startSession()
            session = s
            val cmd = s.exec(MonitorParser.PROBE)
            val out = cmd.inputStream.readBytes().decodeToString()
            cmd.close()
            s.close()
            MonitorParser.parse(out)
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        running.set(false)
        pollGeneration.incrementAndGet()
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
    private fun MonitorScreen() {
        val snap = snapshot.value
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(host?.let { if (it.alias.isNotBlank()) it.alias else it.hostname } ?: "Monitor") },
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
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto refresh (5s)", modifier = Modifier.weight(1f))
                    Switch(
                        checked = autoRefresh.value,
                        onCheckedChange = { on ->
                            autoRefresh.value = on
                            if (on) executor.execute { pollLoop() }
                        }
                    )
                }

                error.value?.let {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                if (snap == null && error.value == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                snap?.let { s ->
                    MetricCard(
                        "CPU", "%.1f%%".format(s.cpuPercent), s.cpuPercent / 100.0,
                        "load %.2f %.2f %.2f".format(s.load1, s.load5, s.load15)
                    )
                    MetricCard(
                        "Memory",
                        "${formatBytes(s.memUsedBytes)} / ${formatBytes(s.memTotalBytes)}",
                        ratio(s.memUsedBytes, s.memTotalBytes),
                        if (s.swapTotalBytes > 0)
                            "swap ${formatBytes(s.swapUsedBytes)} / ${formatBytes(s.swapTotalBytes)}"
                        else "no swap"
                    )
                    MetricCard(
                        "Disk (/)",
                        "${formatBytes(s.diskUsedBytes)} / ${formatBytes(s.diskTotalBytes)}",
                        ratio(s.diskUsedBytes, s.diskTotalBytes),
                        "%.1f%% used".format(100.0 * s.diskUsedBytes / s.diskTotalBytes.coerceAtLeast(1))
                    )
                    MetricCard(
                        "Uptime",
                        formatUptime(s.uptimeSeconds),
                        null,
                        "since boot"
                    )
                }
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
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

    private fun ratio(used: Long, total: Long): Double =
        if (total > 0) used.toDouble() / total else 0.0

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
}
