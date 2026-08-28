package at.least.conch

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {

    private lateinit var store: HostStore
    private val hosts: SnapshotStateList<Host> = mutableStateListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = HostStore(this)
        hosts.addAll(store.load())
        setContent { ConchTheme { HostListScreen() } }
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
        // account-free sync: data only changes while the app runs, so a
        // foreground moment is the honest (and only needed) export trigger
        Thread {
            runCatching { ScheduledBackup(this).maybeExport() }
                .onFailure { CrashReporting.report(it) }
        }.apply {
            isDaemon = true
            name = "conch-sync-backup"
            start()
        }
    }

    override fun onStop() {
        super.onStop()
        AppLock.onWentToBackground()
    }

    /** Parses an OpenSSH config file into hosts; returns the user-facing summary. */
    private fun importOpenSshConfig(uri: android.net.Uri): String = try {
        val text = contentResolver.openInputStream(uri)?.use {
            it.readBytes().decodeToString()
        } ?: error("Cannot read file")
        val parsed = OpenSshConfigParser.parse(text)
        if (parsed.isEmpty()) "No importable hosts found" else applyImport(parsed)
    } catch (e: Exception) {
        "Import failed: ${e.message}"
    }

    /** Appends [parsed] to the store, links ProxyJump hops, returns the summary. */
    private fun applyImport(parsed: List<OpenSshConfigParser.ParsedHost>): String {
        val all = store.load()
        val imported = parsed
            .filter { it.hostname.isNotBlank() }
            .map { p ->
                p to Host(
                    alias = p.alias,
                    hostname = p.hostname,
                    port = p.port,
                    username = p.user,
                    forwardAgent = p.forwardAgent,
                )
            }
        all.addAll(imported.map { it.second })
        val jumpLinks = linkProxyJumps(imported)
        store.save(all)
        hosts.clear()
        hosts.addAll(all)

        val identityRefs = parsed
            .mapNotNull { p -> p.identityFile.takeIf { it.isNotBlank() } }
            .distinct()
        return buildString {
            append("Imported ${imported.size} hosts")
            if (jumpLinks > 0) append(". $jumpLinks host(s) auto-linked via ProxyJump")
            if (identityRefs.isNotEmpty()) {
                append(". ${identityRefs.size} host(s) use identity files ")
                append("(${identityRefs.joinToString(", ")}) — import those keys in Key manager")
            }
        }
    }

    /**
     * Auto-links ProxyJump aliases that refer to hosts in this same import
     * (first hop only); returns how many were linked.
     */
    private fun linkProxyJumps(imported: List<Pair<OpenSshConfigParser.ParsedHost, Host>>): Int =
        imported
            .filter { (p, _) -> p.proxyJump.isNotBlank() }
            .count { (p, host) ->
                val firstHop = p.proxyJump.split(",").first().trim()
                val jump = imported.firstOrNull { it.first.alias == firstHop }
                jump?.also { host.jumpHostId = it.second.id } != null
            }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HostListScreen() {
        var confirmDelete by remember { mutableStateOf<Host?>(null) }
        var showAbout by remember { mutableStateOf(false) }
        var mainMenuOpen by remember { mutableStateOf(false) }
        var showSessions by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        val snackbarHostState = remember { SnackbarHostState() }
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

        // Snackbar, not a dialog: an import summary is feedback on a finished
        // action, and it must not block the list it just changed.
        LaunchedEffect(message) {
            message?.let {
                snackbarHostState.showSnackbar(it)
                message = null
            }
        }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) message = importOpenSshConfig(uri) }

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = { Text("Conch") },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        // The sessions switcher was buried in the overflow;
                        // a badged action shows at a glance that sessions are
                        // live and opens them in one tap.
                        if (!LiveSessions.isEmpty()) {
                            IconButton(onClick = { showSessions = true }) {
                                BadgedBox(
                                    badge = { Badge { Text(LiveSessions.all().size.toString()) } }
                                ) {
                                    Icon(Icons.Filled.Terminal, contentDescription = "Live sessions")
                                }
                            }
                        }
                        IconButton(onClick = { mainMenuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = mainMenuOpen,
                            onDismissRequest = { mainMenuOpen = false }
                        ) {
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
                                leadingIcon = { Icon(Icons.Filled.Code, contentDescription = null) },
                                onClick = {
                                    mainMenuOpen = false
                                    startActivity(Intent(this@MainActivity, SnippetsActivity::class.java))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Import OpenSSH config") },
                                leadingIcon = { Icon(Icons.Filled.FileOpen, contentDescription = null) },
                                onClick = {
                                    mainMenuOpen = false
                                    importLauncher.launch(arrayOf("*/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                onClick = {
                                    mainMenuOpen = false
                                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("About") },
                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                onClick = {
                                    mainMenuOpen = false
                                    showAbout = true
                                }
                            )
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                // Extended while the list is empty — the only action on an
                // empty screen deserves its label; it shrinks once hosts
                // exist and the icon alone is unambiguous.
                if (hosts.isEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = { startActivity(Intent(this, EditHostActivity::class.java)) },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("Add host") },
                    )
                } else {
                    FloatingActionButton(
                        onClick = { startActivity(Intent(this, EditHostActivity::class.java)) }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add host")
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (hosts.isEmpty()) {
                    EmptyHosts(Modifier.weight(1f))
                } else {
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(hosts, key = { it.id }) { host ->
                            HostCard(
                                host = host,
                                onClick = { openTerminal(host) },
                                onNewSession = { openTerminal(host) },
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
                icon = { Icon(Icons.Filled.Dns, contentDescription = null) },
                title = { Text("Delete host") },
                text = { Text("\"${host.displayName()}\" and its saved password are removed from this device.") },
                confirmButton = {
                    TextButton(onClick = {
                        hosts.remove(host)
                        store.save(hosts)
                        store.deleteSecrets(host.id)
                        confirmDelete = null
                        message = "Deleted ${host.displayName()}"
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
                }
            )
        }

        if (showSessions) {
            SessionsSheet(
                // Focus the live session's own task — an intent with
                // REORDER_TO_FRONT cannot target a specific session's
                // activity and would surface an arbitrary terminal.
                onOpen = { live -> live.focus() },
                onDismiss = { showSessions = false },
            )
        }

        if (showAbout) {
            AlertDialog(
                onDismissRequest = { showAbout = false },
                icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                title = { Text("Conch ${BuildConfig.VERSION_NAME}") },
                text = {
                    Text(
                        "Android SSH client — free & open-source\n\n" +
                            "sshj + built-in VT terminal + Jetpack Compose\n" +
                            "Key auth · TOFU · tunnels · SFTP · monitor · snippets · tmux"
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showAbout = false }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showAbout = false
                        startActivity(Intent(this@MainActivity, LicensesActivity::class.java))
                    }) { Text("Licenses") }
                }
            )
        }
    }

    private fun openTerminal(host: Host) {
        // Own task per session (LiveSessions design): the sessions switcher
        // moves tasks to the front, so each terminal must be individually
        // addressable.
        val intent = Intent(this, TerminalActivity::class.java)
        intent.putExtra("hostId", host.id)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        startActivity(intent)
    }

    private fun editHost(host: Host) {
        val intent = Intent(this, EditHostActivity::class.java)
        intent.putExtra("hostId", host.id)
        startActivity(intent)
    }
}

/** Empty state: says what this screen is for and how to fill it. */
@Composable
private fun EmptyHosts(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Dns,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "No hosts yet",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Add an SSH host to connect, or import an existing OpenSSH config from the menu.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * One host. A native [ListItem] inside the card does the layout, so
 * heights, text styles and the leading/trailing slots match every other
 * Material list in the app instead of being hand-spaced.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostCard(
    host: Host,
    onClick: () -> Unit,
    onNewSession: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val status = HostCardStatus(liveSessionCount = LiveSessions.countForHost(host.id))
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Icon(
                    Icons.Filled.Dns,
                    contentDescription = null,
                    tint = if (status.isLive) {
                        MaterialTheme.conch.success
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        host.displayName(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    status.badgeText?.let { badge ->
                        Badge(
                            containerColor = MaterialTheme.conch.successContainer,
                            contentColor = MaterialTheme.conch.onSuccessContainer,
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text(badge) }
                    }
                }
            },
            supportingContent = { HostSummaryLine(host) },
            trailingContent = {
                HostActionsMenu(
                    host = host,
                    onNewSession = onNewSession,
                    onEdit = onEdit,
                    onDelete = onDelete,
                )
            },
        )
    }
}

private fun Host.displayName(): String =
    if (alias.isNotBlank()) alias else "$username@$hostname"

/** user@host:port plus the at-a-glance capability icons. */
@Composable
private fun HostSummaryLine(host: Host) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${host.username}@${host.hostname}:${host.port}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        // Icons, not emoji: emoji render per-font and cannot be tinted or
        // sized from the theme.
        if (host.authType == Host.AUTH_KEY) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.Key,
                contentDescription = "Key authentication",
                modifier = Modifier.size(14.dp),
            )
        }
        if (host.tunnels.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.SyncAlt,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                "${host.tunnels.size}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

/**
 * Per-host overflow. This used to be long-press only, which is invisible:
 * the same actions now have a real, reachable affordance.
 */
@Composable
private fun HostActionsMenu(
    host: Host,
    onNewSession: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${host.displayName()}")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Connect (new session)") },
                leadingIcon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onNewSession()
                }
            )
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onEdit()
                }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    menuOpen = false
                    onDelete()
                }
            )
        }
    }
}
