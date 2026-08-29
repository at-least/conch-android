package at.least.conch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

class EditHostActivity : ComponentActivity() {

    private lateinit var store: HostStore
    private var existing: Host? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = HostStore(this)
        existing = intent.getStringExtra("hostId")?.let { id ->
            store.load().firstOrNull { it.id == id }
        }
        val initial = existing
        setContent {
            ConchTheme {
                EditHostScreen(
                    isEdit = initial != null,
                    initial = initial,
                    otherHosts = store.load().filter { it.id != initial?.id },
                    onBack = { finish() },
                    onSave = { host, password -> save(host, password) }
                )
            }
        }
    }

    private fun save(host: Host, password: String) {
        if (password.isNotEmpty()) {
            SecretsStore.put("host-pw:${host.id}", password)
        }
        if (host.authType == Host.AUTH_KEY) {
            SecretsStore.delete("host-pw:${host.id}")
        }

        val hosts = store.load()
        val idx = hosts.indexOfFirst { it.id == host.id }
        if (idx >= 0) hosts[idx] = host else hosts.add(0, host)
        store.save(hosts)
        finish()
    }
}

/** A settings-style group heading — the Material pattern for sectioned forms. */
@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp),
    )
}

/**
 * A boolean host option. `ListItem` + `Switch` is the native Material row:
 * the whole row toggles, the label and explanation get the right type
 * styles, and the touch target is a full list-item high.
 */
@Composable
private fun SwitchRow(
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supportingColor: Color = Color.Unspecified,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                supporting,
                color = if (supportingColor == Color.Unspecified) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    supportingColor
                },
            )
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        modifier = Modifier.clickable { onCheckedChange(!checked) },
    )
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditHostScreen(
    isEdit: Boolean,
    initial: Host?,
    otherHosts: List<Host>,
    onBack: () -> Unit,
    onSave: (Host, String) -> Unit,
) {
    // rememberSaveable: the form must survive rotation / dark-mode config
    // changes (ConnectBot ships the exact bug — form collapses and input
    // is lost on rotate). `keys` reloads instead; it is derived data.
    var alias by rememberSaveable { mutableStateOf(initial?.alias.orEmpty()) }
    var hostname by rememberSaveable { mutableStateOf(initial?.hostname.orEmpty()) }
    var portText by rememberSaveable {
        mutableStateOf(if (initial != null && initial.port != 22) initial.port.toString() else "")
    }
    var username by rememberSaveable { mutableStateOf(initial?.username.orEmpty()) }
    var authType by rememberSaveable { mutableStateOf(initial?.authType ?: Host.AUTH_PASSWORD) }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var fontSizeText by rememberSaveable {
        mutableStateOf(if ((initial?.fontSizeSp ?: 0f) > 0f) initial!!.fontSizeSp.toInt().toString() else "")
    }
    var keepAlive by rememberSaveable { mutableStateOf(initial?.keepAlive ?: true) }
    var tmux by rememberSaveable { mutableStateOf(initial?.tmuxAutoAttach ?: true) }
    var forwardAgent by rememberSaveable { mutableStateOf(initial?.forwardAgent ?: false) }
    var safExpose by rememberSaveable { mutableStateOf(initial?.safExpose ?: false) }
    var group by rememberSaveable { mutableStateOf(initial?.group.orEmpty()) }
    var groupMenuOpen by rememberSaveable { mutableStateOf(false) }
    val existingGroups = remember(otherHosts) { HostGrouping.groupNames(otherHosts) }
    var knockText by rememberSaveable { mutableStateOf(PortKnocker.format(initial?.knockPorts.orEmpty())) }
    var socksPortText by rememberSaveable {
        mutableStateOf(if ((initial?.socksPort ?: 0) > 0) initial!!.socksPort.toString() else "")
    }
    val ctx = LocalContext.current
    val keys = remember { KeyManager(ctx).list() }
    var selectedKeyId by rememberSaveable { mutableStateOf(initial?.keyId) }
    var keysMenuOpen by rememberSaveable { mutableStateOf(false) }
    var jumpHostId by rememberSaveable { mutableStateOf(initial?.jumpHostId) }
    var jumpMenuOpen by rememberSaveable { mutableStateOf(false) }
    // saveable like every other field — the tunnel list was the one thing a
    // rotation reset to the stored host
    val tunnels = rememberSaveable(saver = TunnelListSaver) {
        mutableStateListOf<Tunnel>().apply { initial?.tunnels?.let { addAll(it) } }
    }

    // Inline validation (Material: the error belongs on the field, not in a
    // toast that has vanished by the time the user looks for the problem).
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val hostnameError = showErrors && hostname.isBlank()
    val usernameError = showErrors && username.isBlank()
    val port = if (portText.isBlank()) 22 else portText.toIntOrNull()
    val portError = showErrors && (port == null || port !in 1..65535)
    val keyError = showErrors && authType == Host.AUTH_KEY && selectedKeyId == null
    val socksPort = socksPortText.trim().let { if (it.isEmpty()) 0 else it.toIntOrNull() ?: -1 }
    val socksError = showErrors && (socksPort < 0 || socksPort > 65535)
    // SshSession silently skips a tunnel with a bad port or blank host, so
    // the only place the user can learn about it is here
    val badTunnel = tunnels.indexOfFirst { !it.isValid() }

    val snackbarHostState = remember { SnackbarHostState() }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            message = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit host" else "Add host") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // The primary action stays reachable instead of living at the
            // bottom of a long scroll.
            BottomAppBar {
                Button(
                    onClick = {
                        showErrors = true
                        val valid = hostname.isNotBlank() && username.isNotBlank() &&
                            port != null && port in 1..65535 &&
                            (authType != Host.AUTH_KEY || selectedKeyId != null) &&
                            !socksError
                        if (!valid) {
                            message = "Check the highlighted fields"
                            return@Button
                        }
                        if (badTunnel >= 0) {
                            message = "Tunnel ${badTunnel + 1}: ports must be 1–65535 and the host non-empty"
                            return@Button
                        }
                        onSave(
                            Host(
                                id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                                alias = alias.trim(),
                                hostname = hostname.trim(),
                                port = port,
                                username = username.trim(),
                                authType = authType,
                                keyId = selectedKeyId,
                                fontSizeSp = fontSizeText.toFloatOrNull() ?: 0f,
                                keepAlive = keepAlive,
                                tmuxAutoAttach = tmux,
                                socksPort = socksPort,
                                jumpHostId = jumpHostId,
                                forwardAgent = forwardAgent,
                                safExpose = safExpose,
                                group = group.trim(),
                                knockPorts = PortKnocker.parse(knockText),
                                tunnels = tunnels.toMutableList(),
                            ),
                            password
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) { Text("Save") }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ListItem supplies its own 16 dp inset, so the switch rows go
            // full-bleed and everything else is inset to match them.
            val field = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
            SectionHeader("Connection", Modifier.padding(horizontal = 16.dp))
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                label = { Text("Name") },
                supportingText = { Text("Optional — shown instead of user@host") },
                singleLine = true,
                modifier = field
            )
            OutlinedTextField(
                value = hostname,
                onValueChange = { hostname = it },
                label = { Text("Host") },
                isError = hostnameError,
                supportingText = if (hostnameError) {
                    { Text("Enter a host address") }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = field
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    isError = usernameError,
                    supportingText = if (usernameError) {
                        { Text("Required") }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.weight(2f)
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("Port") },
                    placeholder = { Text("22") },
                    isError = portError,
                    supportingText = if (portError) {
                        { Text("1–65535") }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            // Free text plus a picker of existing groups (iOS parity): a
            // typo would otherwise silently create a second, near-duplicate
            // section in the host list.
            val canPickGroup = existingGroups.isNotEmpty()
            val groupMenuExpanded = groupMenuOpen && canPickGroup
            ExposedDropdownMenuBox(
                expanded = groupMenuExpanded,
                onExpandedChange = { groupMenuOpen = it },
            ) {
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("Group") },
                    supportingText = { Text("Optional — hosts with the same group are listed together") },
                    singleLine = true,
                    trailingIcon = if (canPickGroup) {
                        { ExposedDropdownMenuDefaults.TrailingIcon(expanded = groupMenuOpen) }
                    } else {
                        null
                    },
                    modifier = field.menuAnchor(MenuAnchorType.PrimaryEditable)
                )
                ExposedDropdownMenu(
                    expanded = groupMenuExpanded,
                    onDismissRequest = { groupMenuOpen = false },
                ) {
                    existingGroups.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                group = name
                                groupMenuOpen = false
                            }
                        )
                    }
                }
            }

            HorizontalDivider()
            SectionHeader("Authentication", Modifier.padding(horizontal = 16.dp))
            // Exclusive choice → segmented buttons, the Material control for
            // exactly this (two filter chips only look mutually exclusive).
            SingleChoiceSegmentedButtonRow(field) {
                SegmentedButton(
                    selected = authType == Host.AUTH_PASSWORD,
                    onClick = { authType = Host.AUTH_PASSWORD },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Password") }
                SegmentedButton(
                    selected = authType == Host.AUTH_KEY,
                    onClick = { authType = Host.AUTH_KEY },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Key") }
            }

            if (authType == Host.AUTH_PASSWORD) {
                val keepsCurrent = isEdit && SecretsStore.get("host-pw:${initial?.id}") != null
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    supportingText = if (keepsCurrent) {
                        { Text("Leave blank to keep the saved password") }
                    } else {
                        null
                    },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (keys.isEmpty()) {
                Text(
                    "No keys yet. Generate or import one in the main menu → Key manager first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                ExposedDropdownMenuBox(
                    expanded = keysMenuOpen,
                    onExpandedChange = { keysMenuOpen = it },
                ) {
                    OutlinedTextField(
                        value = keys.firstOrNull {
                            it.id == selectedKeyId
                        }?.let { "${it.name} (${it.fingerprint.takeLast(12)})" } ?: "",
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Auth key") },
                        placeholder = { Text("Select key") },
                        isError = keyError,
                        supportingText = if (keyError) {
                            { Text("Pick the key this host authenticates with") }
                        } else {
                            null
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = keysMenuOpen) },
                        modifier = field.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = keysMenuOpen, onDismissRequest = { keysMenuOpen = false }) {
                        keys.forEach { k ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(k.name)
                                        Text(
                                            k.fingerprint,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    selectedKeyId = k.id
                                    keysMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }

            // iOS parity: the picker offers only hosts whose own chain resolves
            // and does not pass through THIS host (that would close a cycle);
            // a multi-hop choice shows the full route it implies.
            val self = remember(initial) { initial ?: Host(id = "") }
            val allHosts = remember(otherHosts, self) { otherHosts + self }
            val jumpCandidates = remember(allHosts) { ProxyJumpResolver.candidates(self, allHosts) }
            val chosenJump = otherHosts.firstOrNull { it.id == jumpHostId }
            val route = chosenJump?.let { ProxyJumpResolver.describeChain(it, allHosts) }
            if (otherHosts.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = jumpMenuOpen,
                    onExpandedChange = { jumpMenuOpen = it }
                ) {
                    OutlinedTextField(
                        value = chosenJump?.let { it.alias.ifBlank { it.hostname } } ?: "Direct (no jump host)",
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Connect via") },
                        supportingText = route?.let { { Text("via $it") } },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = jumpMenuOpen) },
                        modifier = field.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = jumpMenuOpen, onDismissRequest = { jumpMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Direct (no jump host)") },
                            onClick = {
                                jumpHostId = null
                                jumpMenuOpen = false
                            }
                        )
                        jumpCandidates.forEach { h ->
                            val viaRoute = ProxyJumpResolver.describeChain(h, allHosts)
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(h.alias.ifBlank { h.hostname })
                                        if (viaRoute != null) {
                                            Text(
                                                "via $viaRoute",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    jumpHostId = h.id
                                    jumpMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            SectionHeader("Session", Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = "Keep-alive",
                supporting = "Send a heartbeat every 15 seconds so idle firewalls don't drop the connection.",
                checked = keepAlive,
                onCheckedChange = { keepAlive = it },
            )
            SwitchRow(
                title = "Auto-attach tmux",
                supporting = "Runs tmux new -A -s conch on connect, so a dropped session resumes where it left off.",
                checked = tmux,
                onCheckedChange = { tmux = it },
            )
            SwitchRow(
                title = "Agent forwarding",
                supporting = if (forwardAgent) {
                    "This server can ask your device to sign with EVERY stored key — enable only on servers you trust."
                } else {
                    "Offer your stored keys to this server's own ssh/git hops."
                },
                supportingColor = if (forwardAgent) MaterialTheme.colorScheme.error else Color.Unspecified,
                checked = forwardAgent,
                onCheckedChange = { forwardAgent = it },
            )
            SwitchRow(
                title = "Files in system picker",
                supporting = "Shows this host's files in Android's file pickers. Other apps see them only after " +
                    "you grant access to a folder.",
                checked = safExpose,
                onCheckedChange = { safExpose = it },
            )
            OutlinedTextField(
                value = fontSizeText,
                onValueChange = { fontSizeText = it },
                label = { Text("Terminal font size") },
                placeholder = { Text("15") },
                suffix = { Text("sp") },
                supportingText = { Text("Blank uses the default of 15 sp") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = field
            )

            HorizontalDivider()
            SectionHeader("Port knocking (UDP)", Modifier.padding(horizontal = 16.dp))
            OutlinedTextField(
                value = knockText,
                onValueChange = { knockText = it },
                label = { Text("Knock sequence") },
                placeholder = { Text("7000, 8000, 9000") },
                supportingText = {
                    Text(
                        "Sent before connecting, in order. Your firewall/knock daemon opens the SSH port " +
                            "after seeing the sequence. Blank = off."
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = field
            )

            HorizontalDivider()
            SectionHeader("Port forwarding", Modifier.padding(horizontal = 16.dp))
            OutlinedTextField(
                value = socksPortText,
                onValueChange = { socksPortText = it },
                label = { Text("SOCKS5 proxy port") },
                supportingText = {
                    val hint = "Blank = off. Point socks5-aware apps at 127.0.0.1:<port>."
                    Text(if (socksError) "Port must be 1–65535" else hint)
                },
                isError = socksError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = field
            )
            tunnels.forEachIndexed { idx, t ->
                TunnelCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    tunnel = t,
                    onChange = { tunnels[idx] = it },
                    onRemove = { tunnels.removeAt(idx) },
                )
            }
            TextButton(
                onClick = { tunnels.add(Tunnel(0, "", 22)) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Add tunnel", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * One port forward. Stacked, not a horizontally scrolling row: the old
 * layout put four fields on one line behind a side-scroll, which hid
 * inputs on a 375 dp phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TunnelCard(
    tunnel: Tunnel,
    onChange: (Tunnel) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    SegmentedButton(
                        selected = !tunnel.remote,
                        onClick = { onChange(tunnel.copy(remote = false)) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Local (-L)") }
                    SegmentedButton(
                        selected = tunnel.remote,
                        onClick = { onChange(tunnel.copy(remote = true)) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Remote (-R)") }
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove tunnel")
                }
            }
            OutlinedTextField(
                value = if (tunnel.localPort == 0) "" else tunnel.localPort.toString(),
                onValueChange = { v -> onChange(tunnel.copy(localPort = v.toIntOrNull() ?: 0)) },
                label = { Text(if (tunnel.remote) "Listen on server port" else "Listen on phone port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            if (tunnel.remote) {
                OutlinedTextField(
                    value = tunnel.bindHost,
                    onValueChange = { v -> onChange(tunnel.copy(bindHost = v.trim())) },
                    label = { Text("Server bind address") },
                    placeholder = { Text("127.0.0.1") },
                    supportingText = { Text("Blank = loopback only. 0.0.0.0 needs GatewayPorts on the server.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = tunnel.host,
                    onValueChange = { v -> onChange(tunnel.copy(host = v)) },
                    label = { Text(if (tunnel.remote) "Forward to phone host" else "Forward to host") },
                    singleLine = true,
                    modifier = Modifier.weight(2f)
                )
                OutlinedTextField(
                    value = if (tunnel.port == 0) "" else tunnel.port.toString(),
                    onValueChange = { v -> onChange(tunnel.copy(port = v.toIntOrNull() ?: 0)) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** A tunnel SshSession would actually start (its own skip rule, mirrored). */
private fun Tunnel.isValid(): Boolean = localPort in 1..65535 && host.isNotBlank() && port in 1..65535

/**
 * Tunnels as saveable strings — one JSON [TunnelWire] per tunnel, so a new
 * tunnel field is saved as soon as it is on the wire type (defaults cover
 * an older saved state).
 */
private val TunnelListSaver = androidx.compose.runtime.saveable.listSaver<SnapshotStateList<Tunnel>, String>(
    save = { list ->
        // not TunnelWire.from: that drops bindHost on local tunnels, and an
        // in-progress edit must survive rotation exactly as typed
        list.map { t ->
            val wire = TunnelWire(t.localPort, t.host, t.port, t.remote, t.bindHost)
            ConchJson.encodeToString(TunnelWire.serializer(), wire)
        }
    },
    restore = { saved ->
        mutableStateListOf<Tunnel>().apply {
            for (s in saved) {
                runCatching { ConchJson.decodeFromString(TunnelWire.serializer(), s).toTunnel() }
                    .onSuccess { add(it) }
            }
        }
    },
)
