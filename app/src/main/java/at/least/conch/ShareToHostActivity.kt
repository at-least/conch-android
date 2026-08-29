package at.least.conch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient

/**
 * Share-sheet target: "Upload to SSH host" (iOS parity: drag-and-drop onto
 * the terminal uploads into the shell's cwd). Pick a host — live sessions
 * first, they upload into the shell's OSC 7 directory over the existing
 * connection; a saved host without a session connects like the Files tab
 * and uploads into the remote home. Decisions live in [ShareUpload].
 */
class ShareToHostActivity : ComponentActivity() {

    private val uris = mutableListOf<Uri>()
    private val keyPromptState = mutableStateOf<Pair<KeyPromptRequest, (Boolean) -> Unit>?>(null)
    private val tofuPrompt: KeyPrompt = { request, answer -> keyPromptState.value = request to answer }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        uris.addAll(sharedUris(intent))
        if (uris.isEmpty()) return finish()
        setContent { ConchTheme { PickerScreen() } }
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    /** One choice in the picker: a live session (uploads to its cwd) or a saved host. */
    private data class Target(val host: Host, val live: LiveSessions.Live?)

    private fun targets(): List<Target> {
        val hosts = HostStore(this).load()
        val byId = hosts.associateBy { it.id }
        val live = LiveSessions.all().mapNotNull { l -> byId[l.hostId]?.let { Target(it, l) } }
        val liveHostIds = live.map { it.host.id }.toSet()
        return live + hosts.filter { it.id !in liveHostIds }.map { Target(it, null) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PickerScreen() {
        val snackbar = remember { SnackbarHostState() }
        var picked by remember { mutableStateOf<Target?>(null) }
        var busy by remember { mutableStateOf(false) }
        val targets = remember { targets() }
        val count = uris.size

        // one transfer per Activity: the pick starts it, the snackbar ends it
        LaunchedEffect(picked) {
            val target = picked ?: return@LaunchedEffect
            busy = true
            val message = withContext(Dispatchers.IO) { upload(target) }
            busy = false
            val result = snackbar.showSnackbar(message, actionLabel = "Done", duration = SnackbarDuration.Indefinite)
            if (result == SnackbarResult.ActionPerformed) finish()
        }

        Scaffold(
            topBar = {
                TopAppBar(title = { Text(if (count == 1) "Upload 1 file to…" else "Upload $count files to…") })
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            when {
                busy -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                    CircularProgressIndicator()
                    Text("Uploading to ${picked?.host?.hostname}…", Modifier.padding(top = 16.dp))
                }
                targets.isEmpty() ->
                    Text("No saved hosts — add one in Conch first.", Modifier.padding(padding).padding(24.dp))
                else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    items(targets, key = { (it.live?.id ?: "") + it.host.id }) { t ->
                        TargetRow(t, enabled = picked == null) { picked = t }
                    }
                }
            }
        }
        HostKeyPrompt()
    }

    @Composable
    private fun TargetRow(t: Target, enabled: Boolean, onPick: () -> Unit) {
        ListItem(
            leadingContent = {
                Icon(
                    Icons.Filled.Dns,
                    contentDescription = null,
                    tint = if (t.live != null) {
                        MaterialTheme.conch.success
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            headlineContent = { Text(if (t.host.alias.isNotBlank()) t.host.alias else t.host.hostname) },
            supportingContent = {
                Text(
                    if (t.live != null) {
                        "connected — uploads to ${t.live.cwd() ?: "home"}"
                    } else {
                        "${t.host.username}@${t.host.hostname}:${t.host.port} — uploads to home"
                    },
                )
            },
            trailingContent = {
                if (t.live != null) {
                    Badge(
                        containerColor = MaterialTheme.conch.successContainer,
                        contentColor = MaterialTheme.conch.onSuccessContainer,
                    ) { Text("connected") }
                }
            },
            modifier = Modifier.clickable(enabled = enabled, onClick = onPick),
        )
    }

    /**
     * The whole transfer, off the main thread. A live session lends one SFTP
     * channel on its connection; a saved host gets its own connection that
     * is closed afterwards. Errors are returned as the message, not swallowed.
     */
    private fun upload(t: Target): String {
        var ownClient: SSHClient? = null
        var sftp: SFTPClient? = null
        return try {
            sftp = t.live?.openSftp() ?: run {
                val client = SshConnectionFactory.connect(context = this, host = t.host, prompt = tofuPrompt)
                ownClient = client
                client.newSFTPClient()
            }
            val home = runCatching { sftp.canonicalize(".") }.getOrNull()
            val dir = ShareUpload.destinationDir(t.live?.cwd(), home)
            val existing = runCatching { sftp.ls(dir).map { it.name }.toMutableSet() }.getOrDefault(mutableSetOf())
            val uploaded = mutableListOf<String>()
            for (uri in uris) {
                val name = ShareUpload.uniqueName(ShareUpload.safeName(displayNameOf(this, uri)), existing)
                uploadUri(this, sftp, uri, ShareUpload.remotePath(dir, name))
                existing.add(name)
                uploaded.add(name)
            }
            ShareUpload.summary(uploaded, dir)
        } catch (e: Exception) {
            CrashReporting.report(e)
            "Upload failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            runCatching { sftp?.close() }
            runCatching { ownClient?.disconnect() }
        }
    }

    /** Same TOFU dialog as the terminal; declining cancels the upload. */
    @Composable
    private fun HostKeyPrompt() {
        keyPromptState.value?.let { (request, answer) ->
            AlertDialog(
                onDismissRequest = { },
                title = { Text(if (request.isChange) "Host key changed" else "Unknown host key") },
                text = {
                    Column {
                        Text(
                            if (request.isChange) {
                                "The key reported by ${request.endpoint} differs from the recorded one."
                            } else {
                                "First connection to ${request.endpoint}. Trust this host?"
                            },
                        )
                        Text(
                            "Key type: ${request.keyType}\nFingerprint:\n${request.fingerprint}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        keyPromptState.value = null
                        answer(true)
                    }) { Text(if (request.isChange) "Trust anyway" else "Trust") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        keyPromptState.value = null
                        answer(false)
                    }) { Text("Cancel") }
                },
            )
        }
    }
}
