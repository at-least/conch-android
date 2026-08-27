package at.least.conch

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class EditHostActivity : ComponentActivity() {

    private lateinit var store: HostStore
    private var existing: Host? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = HostStore(this)
        existing = intent.getStringExtra("hostId")?.let { id ->
            store.load().firstOrNull { it.id == id }
        }
        val initial = existing
        setContent {
            EditHostScreen(
                isEdit = initial != null,
                initial = initial,
                otherHosts = store.load().filter { it.id != initial?.id },
                onBack = { finish() },
                onSave = { host, password -> save(host, password) }
            )
        }
    }

    private fun save(host: Host, password: String) {
        if (host.hostname.isBlank()) return toast("Enter a host address")
        if (host.username.isBlank()) return toast("Enter a username")
        if (host.port !in 1..65535) return toast("Invalid port")
        if (host.authType == Host.AUTH_KEY && host.keyId == null) return toast("Select an auth key")
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

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
    var fontSizeText by rememberSaveable {
        mutableStateOf(if ((initial?.fontSizeSp ?: 0f) > 0f) initial!!.fontSizeSp.toInt().toString() else "")
    }
    var keepAlive by rememberSaveable { mutableStateOf(initial?.keepAlive ?: true) }
    var tmux by rememberSaveable { mutableStateOf(initial?.tmuxAutoAttach ?: true) }
    var forwardAgent by rememberSaveable { mutableStateOf(initial?.forwardAgent ?: false) }
    var socksPortText by rememberSaveable {
        mutableStateOf(if ((initial?.socksPort ?: 0) > 0) initial!!.socksPort.toString() else "")
    }
    val ctx = LocalContext.current
    val keys = remember { KeyManager(ctx).list() }
    var selectedKeyId by rememberSaveable { mutableStateOf(initial?.keyId) }
    var keysMenuOpen by rememberSaveable { mutableStateOf(false) }
    var jumpHostId by rememberSaveable { mutableStateOf(initial?.jumpHostId) }
    var jumpMenuOpen by rememberSaveable { mutableStateOf(false) }
    val tunnels = remember { mutableStateListOf<Tunnel>().apply { initial?.tunnels?.let { addAll(it) } } }

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
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                label = { Text("Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = hostname,
                onValueChange = { hostname = it },
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text("Port (default 22)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = authType == Host.AUTH_PASSWORD,
                    onClick = { authType = Host.AUTH_PASSWORD },
                    label = { Text("Password") }
                )
                FilterChip(
                    selected = authType == Host.AUTH_KEY,
                    onClick = { authType = Host.AUTH_KEY },
                    label = { Text("Key") }
                )
            }

            if (authType == Host.AUTH_PASSWORD) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = {
                        Text(
                            if (isEdit && SecretsStore.get("host-pw:${initial?.id}") != null) "Password (blank = keep current)" else "Password"
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                if (keys.isEmpty()) {
                    Text(
                        "No keys yet. Generate or import one from the main menu → Key manager first.",
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = keysMenuOpen,
                        onExpandedChange = { keysMenuOpen = it }
                    ) {
                        OutlinedTextField(
                            value = keys.firstOrNull {
                                it.id == selectedKeyId
                            }?.let { "${it.name} (${it.fingerprint.takeLast(12)})" } ?: "Select key",
                            onValueChange = { },
                            readOnly = true,
                            label = { Text("Auth key") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = keysMenuOpen) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(expanded = keysMenuOpen, onDismissRequest = { keysMenuOpen = false }) {
                            keys.forEach { k ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(k.name)
                                            Text(k.fingerprint, fontSize = 11.sp)
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
            }

            if (otherHosts.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = jumpMenuOpen,
                    onExpandedChange = { jumpMenuOpen = it }
                ) {
                    OutlinedTextField(
                        value = otherHosts.firstOrNull {
                            it.id == jumpHostId
                        }?.let { "${it.alias.ifBlank { it.hostname }} (jump)" } ?: "Direct (no jump host)",
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Connect via") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = jumpMenuOpen) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = jumpMenuOpen, onDismissRequest = { jumpMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Direct (no jump host)") },
                            onClick = {
                                jumpHostId = null
                                jumpMenuOpen = false
                            }
                        )
                        otherHosts.forEach { h ->
                            DropdownMenuItem(
                                text = { Text(h.alias.ifBlank { h.hostname }) },
                                onClick = {
                                    jumpHostId = h.id
                                    jumpMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = fontSizeText,
                onValueChange = { fontSizeText = it },
                label = { Text("Terminal font size sp (blank = 15)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = keepAlive,
                    onClick = { keepAlive = !keepAlive },
                    label = { Text("Keep-alive (15s)") }
                )
                FilterChip(
                    selected = tmux,
                    onClick = { tmux = !tmux },
                    label = { Text("Auto-attach tmux") }
                )
                FilterChip(
                    selected = forwardAgent,
                    onClick = { forwardAgent = !forwardAgent },
                    label = { Text("Agent forwarding") }
                )
            }
            if (forwardAgent) {
                Text(
                    "Agent forwarding lets this server ask your device to sign " +
                        "with EVERY stored key — enable only on servers you trust.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = socksPortText,
                onValueChange = { socksPortText = it },
                label = { Text("SOCKS5 proxy port (blank = off)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Text("Port forwarding", fontSize = 15.sp)
            tunnels.forEachIndexed { idx, t ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = t.remote,
                        onClick = { tunnels[idx] = t.copy(remote = !t.remote) },
                        label = { Text(if (t.remote) "-R" else "-L") }
                    )
                    OutlinedTextField(
                        value = if (t.localPort == 0) "" else t.localPort.toString(),
                        onValueChange = { v -> tunnels[idx] = t.copy(localPort = v.toIntOrNull() ?: 0) },
                        label = { Text(if (t.remote) "Server port" else "Phone port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.padding(vertical = 8.dp).weight(1f, fill = false)
                    )
                    Text(if (t.remote) "⇤" else "→")
                    OutlinedTextField(
                        value = t.host,
                        onValueChange = { v -> tunnels[idx] = t.copy(host = v) },
                        label = { Text(if (t.remote) "Phone host" else "Target host") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f, fill = false)
                    )
                    OutlinedTextField(
                        value = if (t.port == 0) "" else t.port.toString(),
                        onValueChange = { v -> tunnels[idx] = t.copy(port = v.toIntOrNull() ?: 0) },
                        label = { Text(if (t.remote) "Phone port" else "Target port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    IconButton(onClick = { tunnels.removeAt(idx) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove")
                    }
                }
            }
            Button(onClick = { tunnels.add(Tunnel(0, "", 22)) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(" Add tunnel")
            }

            Button(
                onClick = {
                    val port = if (portText.isBlank()) 22 else portText.toIntOrNull()
                    val fs = fontSizeText.toFloatOrNull() ?: 0f
                    if (port == null || port !in 1..65535) {
                        android.widget.Toast.makeText(
                            ctx,
                            "Invalid port",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
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
                            fontSizeSp = fs,
                            keepAlive = keepAlive,
                            tmuxAutoAttach = tmux,
                            socksPort = socksPortText.toIntOrNull() ?: 0,
                            jumpHostId = jumpHostId,
                            forwardAgent = forwardAgent,
                            tunnels = tunnels.toMutableList(),
                        ),
                        password
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("Save", fontSize = 16.sp)
            }
        }
    }
}
