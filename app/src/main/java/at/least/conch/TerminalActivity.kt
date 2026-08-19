package at.least.conch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class TerminalActivity : FragmentActivity(), SshSession.Callbacks {

    private var session: SshSession? = null
    private var host: Host? = null
    private var terminalView: TerminalView? = null
    private var emulator: TerminalEmulator? = null

    private val statusText = mutableStateOf<String?>(null)
    private val statusColor = mutableStateOf(Color(0xFFFFB74D))
    private val subtitle = mutableStateOf("")
    private val keyboardRowVisible = mutableStateOf(true)
    private val ctrlArmed = mutableStateOf(false)
    private val finishRequested = mutableStateOf(false)
    private val keyPromptState = mutableStateOf<Pair<KeyPromptRequest, (Boolean) -> Unit>?>(null)
    private val snippetsSheetVisible = mutableStateOf(false)
    private val scrollOffset = mutableStateOf(0)

    /** Command history capture (null when disabled in Settings). */
    private var historyAssembler: InputLineAssembler? = null
    private val historySheetVisible = mutableStateOf(false)
    private val historyStore by lazy { CommandHistoryStore(this) }
    private val historyExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "conch-history").apply { isDaemon = true }
    }

    /** TOFU prompt wired into the SSH handshake (runs on the reader thread). */
    private val tofuPrompt: KeyPrompt = { request, answer ->
        keyPromptState.value = request to answer
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hostId = intent.getStringExtra("hostId") ?: return finish()
        val host = HostStore(this).load().firstOrNull { it.id == hostId } ?: return finish()
        this.host = host
        subtitle.value = "${host.username}@${host.hostname}:${host.port}"

        if (getSharedPreferences("conchapp_settings", MODE_PRIVATE).getBoolean("commandHistory", true)) {
            historyAssembler = InputLineAssembler { line ->
                historyExecutor.execute { historyStore.record(hostId, line) }
            }
        }

        emulator = TerminalEmulator(80, 24).also { emu ->
            emu.bellListener = { }
            emu.titleListener = { title -> runOnUiThread { subtitle.value = title } }
        }

        setContent { TerminalScreen() }
        connect(host)
    }

    override fun onStart() {
        super.onStart()
        AppLock.lockIfNeeded(this)
    }

    override fun onStop() {
        super.onStop()
        AppLock.onWentToBackground()
    }

    override fun onResume() {
        super.onResume()
        if (getSharedPreferences("conchapp_settings", MODE_PRIVATE).getBoolean("keepScreenOn", false)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun connect(host: Host) {
        statusText.value = "Connecting to ${host.hostname}:${host.port} …"
        val emu = emulator
        session = SshSession(
            context = this,
            host = host,
            initialCols = emu?.cols ?: 80,
            initialRows = emu?.rows ?: 24,
            callbacks = this,
            tofuPrompt = tofuPrompt,
        ).also { it.connect() }
    }

    // ------------------------------------------------------- session callbacks

    override fun onConnected() {
        statusText.value = "Connected ${host?.username}@${host?.hostname}"
        statusColor.value = Color(0xFF4CAF50)
        host?.let { h ->
            val name = if (h.alias.isNotBlank()) h.alias else h.hostname
            // Only start the foreground service while we're in the foreground;
            // Android 14+ throws ForegroundServiceStartNotAllowedException
            // for background starts. onConnected can fire after the user has
            // already left the activity.
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.POST_NOTIFICATIONS
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                runCatching { SessionService.start(this, h.id, name) }
            }
        }
        terminalView?.post { terminalView?.showSoftKeyboard() }
    }

    override fun onData(data: ByteArray) {
        terminalView?.feedAndInvalidate(data) ?: emulator?.feed(data)
    }

    override fun onDisconnected(reason: String) {
        if (isFinishing) return
        SessionService.stop(this)
        statusText.value = "Disconnected: $reason"
        statusColor.value = Color(0xFFE53935)
        emulator?.feed("\r\u001b[90m── Connection closed: $reason ──\u001b[0m\r\n")
        terminalView?.invalidate()
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        session = null
    }

    override fun onDestroy() {
        session?.disconnect()
        session = null
        SessionService.stop(this)
        historyExecutor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------- clipboard

    private fun copyScreen() {
        val text = emulator?.getScreenText() ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        Toast.makeText(this, "Screen copied", Toast.LENGTH_SHORT).show()
    }

    private fun pasteIntoTerminal() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        if (text.isNotEmpty()) terminalView?.pasteText(text)
    }

    private fun openSftp() {
        val host = this.host ?: return
        startActivity(android.content.Intent(this, SftpActivity::class.java).putExtra("hostId", host.id))
    }

    private fun openMonitor() {
        val host = this.host ?: return
        startActivity(android.content.Intent(this, MonitorActivity::class.java).putExtra("hostId", host.id))
    }

    // -------------------------------------------------------------- compose UI

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TerminalScreen() {
        val host = this.host
        var menuOpen by remember { mutableStateOf(false) }
        val snippets = remember { SnippetStore(this).load() }

        Scaffold(
            containerColor = Color(0xFF1A1B26),
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF10151E),
                        titleContentColor = Color(0xFFE0E0E0),
                        navigationIconContentColor = Color(0xFFE0E0E0),
                        actionIconContentColor = Color(0xFFE0E0E0),
                    ),
                    navigationIcon = {
                        IconButton(onClick = { disconnectAndFinish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                host?.let { if (it.alias.isNotBlank()) it.alias else it.hostname } ?: "",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    subtitle.value,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (scrollOffset.value > 0) {
                                    Text(
                                        "  ↕${scrollOffset.value}",
                                        fontSize = 12.sp,
                                        color = Color(0xFF80DEEA)
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { openSftp() }) {
                            Icon(Icons.Filled.Storage, contentDescription = "Files")
                        }
                        IconButton(onClick = { openMonitor() }) {
                            Icon(Icons.Filled.Monitor, contentDescription = "Monitor")
                        }
                        IconButton(onClick = {
                            val host = this@TerminalActivity.host ?: return@IconButton
                            startActivity(android.content.Intent(this@TerminalActivity, DockerActivity::class.java).putExtra("hostId", host.id))
                        }) {
                            Icon(Icons.Filled.Storage, contentDescription = "Docker")
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Snippets") },
                                leadingIcon = { Icon(Icons.Filled.Code, contentDescription = null) },
                                onClick = { menuOpen = false; snippetsSheetVisible.value = true }
                            )
                            DropdownMenuItem(
                                text = { Text("History") },
                                leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                                onClick = { menuOpen = false; historySheetVisible.value = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy screen") },
                                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                onClick = { menuOpen = false; copyScreen() }
                            )
                            DropdownMenuItem(
                                text = { Text("Paste") },
                                leadingIcon = { Icon(Icons.Filled.ContentPaste, contentDescription = null) },
                                onClick = { menuOpen = false; pasteIntoTerminal() }
                            )
                            DropdownMenuItem(
                                text = { Text(if (keyboardRowVisible.value) "Hide extra keys" else "Show extra keys") },
                                leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    keyboardRowVisible.value = !keyboardRowVisible.value
                                    val tv = terminalView
                                    if (tv != null) {
                                        if (keyboardRowVisible.value) tv.showSoftKeyboard() else tv.hideSoftKeyboard()
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Font size up") },
                                onClick = { menuOpen = false; terminalView?.fontSizePx = (terminalView?.fontSizePx ?: 0f) + 2f }
                            )
                            DropdownMenuItem(
                                text = { Text("Font size down") },
                                onClick = { menuOpen = false; terminalView?.fontSizePx = (terminalView?.fontSizePx ?: 0f) - 2f }
                            )
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                onClick = { menuOpen = false; finishRequested.value = true }
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color(0xFF1A1B26))
                    .imePadding()
            ) {
                statusText.value?.let { text ->
                    Text(
                        text,
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(statusColor.value)
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
                AndroidView(
                    factory = { ctx ->
                        TerminalView(ctx).apply {
                            terminalView = this
                            this.emulator = this@TerminalActivity.emulator
                            onData = { data ->
                                session?.write(data)
                                historyAssembler?.feed(data)
                            }
                            onPtyResize = { c, r -> session?.resizePty(c, r) }
                            onCtrlStateChanged = { armed -> this@TerminalActivity.ctrlArmed.value = armed }
                            onScrollOffsetChanged = { off -> this@TerminalActivity.scrollOffset.value = off }
                            if (host?.fontSizeSp ?: 0f > 0f) {
                                fontSizePx = host!!.fontSizeSp * resources.displayMetrics.scaledDensity
                            }
                            theme = TerminalTheme.byName(
                                getSharedPreferences("conchapp_settings", MODE_PRIVATE)
                                    .getString(TerminalTheme.PREF_KEY, null)
                            )
                            setOnClickListener { showSoftKeyboard() }
                            post { requestFocus() }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                if (keyboardRowVisible.value) {
                    ExtraKeysRow()
                }
            }
        }

        keyPromptState.value?.let { (request, answer) ->
            AlertDialog(
                onDismissRequest = { },
                title = { Text(if (request.isChange) "⚠ Host key changed!" else "Unknown host key") },
                text = {
                    Column {
                        Text(
                            if (request.isChange)
                                "The key reported by ${request.endpoint} differs from the recorded one. The server may have been reinstalled — or someone is intercepting the connection."
                            else
                                "First connection to ${request.endpoint}. Trust this host?"
                        )
                        Text(
                            "Key type: ${request.keyType}\nFingerprint:\n${request.fingerprint}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 12.dp)
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
                }
            )
        }

        if (finishRequested.value) {
            AlertDialog(
                onDismissRequest = { finishRequested.value = false },
                title = { Text("Disconnect") },
                text = { Text("Disconnect and close this session?") },
                confirmButton = {
                    TextButton(onClick = { disconnectAndFinish() }) { Text("Disconnect") }
                },
                dismissButton = {
                    TextButton(onClick = { finishRequested.value = false }) { Text("Cancel") }
                }
            )
        }

        if (snippetsSheetVisible.value) {
            ModalBottomSheet(onDismissRequest = { snippetsSheetVisible.value = false }) {
                Text(
                    "Snippets",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                if (snippets.isEmpty()) {
                    Text(
                        "No snippets yet. Add them from the main menu → Snippet manager.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
                    items(snippets, key = { it.id }) { snip ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    snippetsSheetVisible.value = false
                                    terminalView?.sendText(snip.command + "\r")
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text(snip.label, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Text(
                                snip.command,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (historySheetVisible.value) {
            HistorySheet()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HistorySheet() {
        val host = this.host ?: return
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
        // Search does file read + AES-GCM decrypt + JSON parse: keep it off
        // the UI thread, debounced 150 ms so keystrokes stay cheap.
        LaunchedEffect(query) {
            delay(150)
            results = withContext(Dispatchers.Default) {
                historyStore.search(host.id, query)
            }
        }
        ModalBottomSheet(onDismissRequest = { historySheetVisible.value = false }) {
            Text(
                "Command history",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
            if (results.isEmpty()) {
                Text(
                    if (query.isBlank()) "No history yet. Commands you run are recorded here (encrypted on this device)."
                    else "Nothing matches \"$query\".",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
                items(results) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                historySheetVisible.value = false
                                // sendRaw, not sendText: a single-char entry must
                                // never be interpreted as an armed Ctrl-letter
                                terminalView?.sendRaw((entry.text + "\r").toByteArray(Charsets.UTF_8))
                            }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            entry.text.lineSequence().firstOrNull() ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { saveAsSnippet(entry.text) }) { Text("Save") }
                    }
                }
            }
        }
    }

    private fun saveAsSnippet(command: String) {
        val store = SnippetStore(this)
        val list = store.load()
        val label = command.lineSequence().firstOrNull { it.isNotBlank() }?.take(40) ?: "snippet"
        list.add(Snippet(label = label, command = command))
        store.save(list)
        Toast.makeText(this, "Saved snippet: $label", Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun ExtraKeysRow() {
        val context = androidx.compose.ui.platform.LocalContext.current
        val keys = remember { mutableStateOf(ExtraKeysConfig.load(context)) }
        var editing by remember { mutableStateOf(false) }

        if (!editing) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF10151A))
                    .horizontalScroll(rememberScrollState())
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                keys.value.forEach { id ->
                    if (id == "CTRL") {
                        KeyButton("CTRL", armed = ctrlArmed.value) { toggleCtrl() }
                    } else {
                        KeyButton(ExtraKeysConfig.labelFor(id)) {
                            ExtraKeysConfig.bytesFor(id)?.let { bytes -> terminalView?.sendRaw(bytes) }
                        }
                    }
                }
                KeyButton("⚙") {
                    editing = true
                }
            }
        } else {
            ExtraKeysEditor(
                current = keys.value,
                onSave = { ids ->
                    ExtraKeysConfig.save(context, ids)
                    keys.value = ids
                    editing = false
                },
                onCancel = { editing = false },
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun ExtraKeysEditor(current: List<String>, onSave: (List<String>) -> Unit, onCancel: () -> Unit) {
        val selected = androidx.compose.runtime.mutableStateListOf(*current.toTypedArray())
        ModalBottomSheet(onDismissRequest = onCancel) {
            Text(
                "Extra keys",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Text(
                "Tap to add or remove. Long-press ⚙ row keys later to reorder (drag support coming).",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            // selected chips (tap to remove)
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                selected.forEach { id ->
                    androidx.compose.material3.FilterChip(
                        selected = true,
                        onClick = { selected.remove(id) },
                        label = { Text(ExtraKeysConfig.labelFor(id)) }
                    )
                }
            }
            // available chips (tap to append)
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ExtraKeysConfig.ALL.forEach { def ->
                    if (def.id !in selected) {
                        androidx.compose.material3.FilterChip(
                            selected = false,
                            onClick = { selected.add(def.id) },
                            label = { Text(def.label) }
                        )
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { onSave(selected.toList()) }) { Text("Save") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }

    private fun toggleCtrl() {
        val tv = terminalView ?: return
        tv.ctrlArmed = !tv.ctrlArmed
        ctrlArmed.value = tv.ctrlArmed
    }

    @Composable
    private fun KeyButton(label: String, armed: Boolean = false, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (armed) Color(0xFF2196F3) else Color(0xFF263238),
                contentColor = Color(0xFFE0E0E0)
            ),
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .height(40.dp)
                .defaultMinSize(minWidth = 48.dp)
        ) {
            Text(label, fontSize = 13.sp)
        }
    }

    private fun disconnectAndFinish() {
        session?.disconnect()
        session = null
        SessionService.stop(this)
        finish()
    }
}
