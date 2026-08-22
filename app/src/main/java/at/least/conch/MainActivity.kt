package at.least.conch

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : FragmentActivity() {

    private lateinit var store: HostStore
    private val hosts: SnapshotStateList<Host> = mutableStateListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = HostStore(this)
        hosts.addAll(store.load())
        setContent { HostListScreen() }
    }

    override fun onStart() {
        super.onStart()
        AppLock.lockIfNeeded(this)
    }

    override fun onResume() {
        super.onResume()
        hosts.clear()
        hosts.addAll(store.load())
        HostsWidget.update(this)
    }

    override fun onStop() {
        super.onStop()
        AppLock.onWentToBackground()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HostListScreen() {
        var menuHost by remember { mutableStateOf<Host?>(null) }
        var confirmDelete by remember { mutableStateOf<Host?>(null) }
        var showAbout by remember { mutableStateOf(false) }
        var mainMenuOpen by remember { mutableStateOf(false) }
        var showSessions by remember { mutableStateOf(false) }
        var importResult by remember { mutableStateOf<String?>(null) }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                try {
                    val text = contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    } ?: throw IllegalStateException("Cannot read file")
                    val parsed = OpenSshConfigParser.parse(text)
                    if (parsed.isEmpty()) {
                        importResult = "No importable hosts found"
                    } else {
                        val all = store.load()
                        var added = 0
                        for (p in parsed) {
                            if (p.hostname.isBlank()) continue
                            val host = Host(
                                alias = p.alias,
                                hostname = p.hostname,
                                port = p.port,
                                username = p.user,
                            )
                            all.add(host)
                            added++
                        }
                        store.save(all)
                        hosts.clear()
                        hosts.addAll(all)
                        importResult = "Imported $added hosts"
                    }
                } catch (e: Exception) {
                    importResult = "Import failed: ${e.message}"
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Conch") },
                    actions = {
                        IconButton(onClick = { mainMenuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = mainMenuOpen, onDismissRequest = { mainMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Key manager") },
                                leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                                onClick = {
                                    mainMenuOpen = false
                                    startActivity(Intent(this@MainActivity, KeysActivity::class.java))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Snippet manager") },
                                onClick = {
                                    mainMenuOpen = false
                                    startActivity(Intent(this@MainActivity, SnippetsActivity::class.java))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Import OpenSSH config") },
                                onClick = {
                                    mainMenuOpen = false
                                    importLauncher.launch(arrayOf("*/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    mainMenuOpen = false
                                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                                }
                            )
                            if (!LiveSessions.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Sessions (${LiveSessions.all().size})") },
                                    leadingIcon = { Icon(Icons.Filled.MoreVert, contentDescription = null) },
                                    onClick = {
                                        mainMenuOpen = false
                                        showSessions = true
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("About") },
                                onClick = { mainMenuOpen = false; showAbout = true }
                            )
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { startActivity(Intent(this, EditHostActivity::class.java)) }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add host")
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (hosts.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No hosts yet\nTap + to add an SSH host",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(hosts, key = { it.id }) { host ->
                            HostCard(
                                host = host,
                                onClick = { openTerminal(host) },
                                onEdit = { editHost(host) },
                                onDelete = { confirmDelete = host }
                            )
                        }
                    }
                }
                // Ad slot: no-op in FOSS builds; Play build shows a banner
                // (once wired) unless the remove-ads purchase is active.
                Ads.Banner()
            }
        }

        confirmDelete?.let { host ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("Delete host") },
                text = { Text("Delete \"${host.displayName()}\"?") },
                confirmButton = {
                    TextButton(onClick = {
                        hosts.remove(host)
                        store.save(hosts)
                        store.deleteSecrets(host.id)
                        confirmDelete = null
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
                }
            )
        }

        if (showSessions) {
            SessionsSheet(
                onOpen = { live ->
                    val intent = Intent(this@MainActivity, TerminalActivity::class.java)
                    intent.putExtra("hostId", live.hostId)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    startActivity(intent)
                },
                onDismiss = { showSessions = false },
            )
        }

        if (showAbout) {
            AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text("Conch 0.8.1") },
                text = { Text("Android SSH client — free & open-source\nsshj + built-in VT terminal + Jetpack Compose\nKey auth / TOFU / tunnels / SFTP / monitor / snippets / tmux") },
                confirmButton = {
                    TextButton(onClick = { showAbout = false }) { Text("OK") }
                }
            )
        }

        importResult?.let { msg ->
            AlertDialog(
                onDismissRequest = { importResult = null },
                text = { Text(msg) },
                confirmButton = { TextButton(onClick = { importResult = null }) { Text("OK") } }
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun HostCard(host: Host, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
        var menuOpen by remember { mutableStateOf(false) }
        val status = HostCardStatus(liveSessionCount = LiveSessions.countForHost(host.id))
        Box {
            Card(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { menuOpen = true }
                    )
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(host.displayName(), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        if (status.showsDot) {
                            Box(
                                Modifier
                                    .padding(start = 6.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF23D18B))
                            )
                            status.badgeText?.let { badge ->
                                Text(
                                    badge,
                                    fontSize = 11.sp,
                                    color = Color(0xFF23D18B),
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                    Text(
                        buildString {
                            append("${host.username}@${host.hostname}:${host.port}")
                            if (host.authType == Host.AUTH_KEY) append(" · 🔑")
                            if (host.tunnels.isNotEmpty()) append(" · ⧉${host.tunnels.size}")
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Connect (new session)") },
                    onClick = {
                        menuOpen = false
                        val intent = Intent(this@MainActivity, TerminalActivity::class.java)
                        intent.putExtra("hostId", host.id)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        startActivity(intent)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = { menuOpen = false; onEdit() }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { menuOpen = false; onDelete() }
                )
            }
        }
    }

    private fun Host.displayName(): String =
        if (alias.isNotBlank()) alias else "$username@$hostname"

    private fun openTerminal(host: Host) {
        val intent = Intent(this, TerminalActivity::class.java)
        intent.putExtra("hostId", host.id)
        startActivity(intent)
    }

    private fun editHost(host: Host) {
        val intent = Intent(this, EditHostActivity::class.java)
        intent.putExtra("hostId", host.id)
        startActivity(intent)
    }
}
