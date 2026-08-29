package at.least.conch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Connection health shown as the banner: dot style + color per state (see [StatusDot]). */
internal enum class ConnState { CONNECTING, CONNECTED, RECONNECTING, STOPPED }

/** ZMODEM uploads are in-memory (file + escaped copy); SFTP streams, so big files go there. */
const val ZMODEM_UPLOAD_MAX_BYTES = 32 * 1024 * 1024

class TerminalActivity : FragmentActivity() {

    private var reconnector: SessionReconnector? = null
    private var host: Host? = null
    private var terminalView: TerminalView? = null
    private var emulator: TerminalEmulator? = null

    private val statusText = mutableStateOf<String?>(null)
    private val connState = mutableStateOf(ConnState.CONNECTING)
    private val subtitle = mutableStateOf("")
    private val keyboardRowVisible = mutableStateOf(true)
    private val ctrlArmed = mutableStateOf(false)
    private val altArmed = mutableStateOf(false)
    private val finishRequested = mutableStateOf(false)
    private val keyPromptState = mutableStateOf<Pair<KeyPromptRequest, (Boolean) -> Unit>?>(null)
    private val snippetsSheetVisible = mutableStateOf(false)
    private val paletteSheetVisible = mutableStateOf(false)
    private val tunnelConfirmVisible = mutableStateOf(false)
    private val liveTunnelCount = mutableIntStateOf(0)
    private val connectionGen = mutableIntStateOf(0)

    /**
     * SFTP transfers for this session (Files tab + Transfers sheet). Each
     * runs on its own channel from the live connection, so it survives
     * leaving the Files tab; the queue dies with the Activity.
     */
    private val transfers: TransferQueue by lazy {
        TransferQueue(
            downloadsDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: filesDir,
            sftpProvider = { reconnector?.sftpClient() },
        )
    }
    private val scrollOffset = mutableIntStateOf(0)

    /** One-shot user-facing message, rendered as a Snackbar (was Toast). */
    private val message = mutableStateOf<String?>(null)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Token scoping the reconnector's pending retry on [mainHandler]. */
    private val retryToken = Any()

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

    /** Unique id for this terminal instance in the live-sessions registry. */
    private val sessionId = java.util.UUID.randomUUID().toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val hostId = intent.getStringExtra("hostId") ?: return finish()
        val host = HostStore(this).load().firstOrNull { it.id == hostId } ?: return finish()
        this.host = host
        subtitle.value = "${host.username}@${host.hostname}:${host.port}"

        if (SettingsStore.commandHistory(this)) {
            historyAssembler = InputLineAssembler { line ->
                historyExecutor.execute { historyStore.record(hostId, line) }
            }
        }

        emulator = TerminalEmulator(80, 24).also { emu ->
            emu.bellListener = { }
            emu.titleListener = { title -> runOnUiThread { subtitle.value = title } }
        }

        setContent { ConchTheme(darkTheme = true) { TerminalScreen() } }
        connect(host)
    }

    override fun onStart() {
        super.onStart()
        AppLock.lockIfNeeded(this)
    }

    override fun onResume() {
        super.onResume()
        if (SettingsStore.keepScreenOn(this)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun connect(host: Host) {
        connState.value = ConnState.CONNECTING
        statusText.value = "Connecting to ${host.hostname}:${host.port} …"
        val emu = emulator
        reconnector = SessionReconnector(
            newSession = { cb ->
                SshSession(
                    context = this,
                    host = host,
                    initialCols = emu?.cols ?: 80,
                    initialRows = emu?.rows ?: 24,
                    callbacks = cb,
                    tofuPrompt = tofuPrompt,
                )
            },
            listener = object : SessionReconnector.Listener {
                override fun onSessionConnected() = this@TerminalActivity.onSessionConnected()
                override fun onSessionData(data: ByteArray) = this@TerminalActivity.onSessionData(data)
                override fun onReconnecting(attempt: Int, delayMs: Long, reason: String) =
                    this@TerminalActivity.onReconnecting(attempt, delayMs, reason)

                override fun onSessionStopped(reason: String) = showStopped(reason)
            },
            // Token-scoped so cancelling a retry never drops unrelated work
            // someone later posts on the same handler.
            postDelayed = { delayMs, action ->
                mainHandler.postAtTime(action, retryToken, SystemClock.uptimeMillis() + delayMs)
            },
            cancelScheduled = { mainHandler.removeCallbacksAndMessages(retryToken) },
        ).also { it.start() }
    }

    // ------------------------------------------------------- session callbacks

    private fun onSessionConnected() {
        connState.value = ConnState.CONNECTED
        statusText.value = "Connected ${host?.username}@${host?.hostname}"
        liveTunnelCount.intValue = reconnector?.tunnelCount ?: 0
        connectionGen.intValue += 1
        val h = host
        if (h != null) {
            LiveSessions.register(
                LiveSessions.Live(
                    id = sessionId,
                    hostId = h.id,
                    displayName = if (h.alias.isNotBlank()) h.alias else h.hostname,
                    startedAt = System.currentTimeMillis(),
                    disconnectFn = { runOnUiThread { disconnectAndFinish() } },
                    focusFn = { runOnUiThread { bringToFront() } },
                    cwdFn = { emulator?.cwd },
                    sftpFn = { reconnector?.sftpClient() },
                )
            )
        }
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
                runCatching { SessionService.start(this, sessionId, name) }
            }
        }
        terminalView?.post { terminalView?.showSoftKeyboard() }
    }

    private fun onSessionData(data: ByteArray) {
        terminalView?.feedAndInvalidate(data) ?: emulator?.feed(data)
    }

    private fun onReconnecting(attempt: Int, delayMs: Long, reason: String) {
        if (isFinishing) return
        connState.value = ConnState.RECONNECTING
        statusText.value = "Reconnecting ($attempt) in ${delayMs / 1000}s — $reason · tap to stop"
        emulator?.feed("\r\u001b[90m── Connection lost: $reason — reconnecting ──\u001b[0m\r\n")
        terminalView?.invalidate()
    }

    private fun showStopped(reason: String) {
        if (isFinishing) return
        if (connState.value == ConnState.STOPPED) return
        connState.value = ConnState.STOPPED
        statusText.value = "Disconnected: $reason"
        emulator?.feed("\r\u001b[90m── Connection closed: $reason ──\u001b[0m\r\n")
        terminalView?.invalidate()
        SessionService.stop(this, sessionId)
        message.value = reason
    }

    /** User tapped the reconnecting banner: give up and show the stopped state. */
    private fun stopReconnecting() {
        reconnector?.stop("stopped by user")
    }

    override fun onDestroy() {
        LiveSessions.unregister(sessionId)
        transfers.close()
        reconnector?.stop()
        reconnector = null
        SessionService.stop(this, sessionId)
        historyExecutor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------- clipboard

    private fun copyScreen() {
        val text = emulator?.getScreenText() ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        message.value = "Screen copied"
    }

    private fun pasteIntoTerminal() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        if (text.isNotEmpty()) terminalView?.pasteText(text)
    }

    // -------------------------------------------------------------- compose UI

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TerminalScreen() {
        val host = this.host
        var menuOpen by remember { mutableStateOf(false) }
        val zmodemPickLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { readBounded(it, ZMODEM_UPLOAD_MAX_BYTES) }
                        ?: error("Cannot read file")
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
                    terminalView?.beginZmodemUpload(name, bytes)
                } catch (e: Exception) {
                    CrashReporting.report(e)
                    message.value = "Upload failed: ${e.message}"
                    terminalView?.cancelZmodem()
                }
            } else {
                terminalView?.cancelZmodem()
            }
        }
        zmodemPickLauncherRef = { zmodemPickLauncher }
        var snippets by remember { mutableStateOf(listOf<Snippet>()) }
        var tab by remember { mutableStateOf(initialTab(intent.getStringExtra(EXTRA_TAB))) }
        val snackbarHostState = remember { SnackbarHostState() }

        // Transient feedback (copied, saved, disconnect reason) as a
        // Snackbar: it belongs to this screen, respects insets and does not
        // paint over the terminal the way a system Toast does.
        LaunchedEffect(message.value) {
            message.value?.let {
                snackbarHostState.showSnackbar(it)
                message.value = null
            }
        }

        // Snippets load off-main whenever the sheet opens — newly saved
        // snippets (history sheet → Save) appear on the next open too.
        LaunchedEffect(snippetsSheetVisible.value) {
            if (snippetsSheetVisible.value) {
                snippets = withContext(Dispatchers.IO) { SnippetStore(this@TerminalActivity).load() }
            }
        }

        // The hidden terminal must not eat hardware keys while another tab
        // is showing; coming back re-focuses it for hardware-keyboard users.
        LaunchedEffect(tab) {
            val tv = terminalView ?: return@LaunchedEffect
            if (tab == SessionTab.TERMINAL) tv.requestFocus() else tv.clearFocus()
        }

        // The chrome takes its background from the user's terminal theme, so
        // the bar, the terminal and the key row read as one surface instead
        // of three hardcoded near-blacks.
        // remembered: reading it per recomposition would hit SharedPreferences
        // on every frame the terminal repaints.
        val terminalBg = remember {
            Color(TerminalTheme.byName(SettingsStore.terminalTheme(this)).bg or 0xFF000000.toInt())
        }
        Scaffold(
            containerColor = terminalBg,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                // Scrolled-back-by-N: an icon plus the count,
                                // where a bare "↕12" used to sit.
                                if (scrollOffset.intValue > 0) {
                                    Icon(
                                        Icons.Filled.UnfoldMore,
                                        contentDescription = "Scrolled back",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .padding(start = 8.dp)
                                            .size(14.dp),
                                    )
                                    Text(
                                        "${scrollOffset.intValue}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        if (TunnelCapsule.visible(liveTunnelCount.intValue)) {
                            AssistChip(
                                onClick = { tunnelConfirmVisible.value = true },
                                label = { Text(TunnelCapsule.chipText(liveTunnelCount.intValue)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.SyncAlt,
                                        contentDescription = "Live tunnels",
                                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.conch.successContainer,
                                    labelColor = MaterialTheme.conch.onSuccessContainer,
                                    leadingIconContentColor = MaterialTheme.conch.onSuccessContainer,
                                ),
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Command palette") },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    paletteSheetVisible.value = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Snippets") },
                                leadingIcon = { Icon(Icons.Filled.Code, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    snippetsSheetVisible.value = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("History") },
                                leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    historySheetVisible.value = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy screen") },
                                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    copyScreen()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Paste") },
                                leadingIcon = { Icon(Icons.Filled.ContentPaste, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    pasteIntoTerminal()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Cancel file transfer") },
                                leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    terminalView?.cancelZmodem()
                                    transfers.cancelAll()
                                }
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
                            HorizontalDivider()
                            // A stepper, not two menu entries: resizing text
                            // is a repeated adjustment, and each old tap shut
                            // the menu and made you reopen it.
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Text size", Modifier.weight(1f))
                                        IconButton(onClick = {
                                            terminalView?.fontSizePx =
                                                (terminalView?.fontSizePx ?: 0f) - 2f
                                        }) {
                                            Icon(
                                                Icons.Filled.Remove,
                                                contentDescription = "Smaller text",
                                            )
                                        }
                                        IconButton(onClick = {
                                            terminalView?.fontSizePx =
                                                (terminalView?.fontSizePx ?: 0f) + 2f
                                        }) {
                                            Icon(Icons.Filled.Add, contentDescription = "Larger text")
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.FormatSize, contentDescription = null)
                                },
                                onClick = { },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text("Disconnect", color = MaterialTheme.colorScheme.error)
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.LinkOff,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    finishRequested.value = true
                                }
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
                    // consume first: without it the IME inset would be added
                    // on top of the navigation-bar inset Scaffold already paid.
                    .consumeWindowInsets(padding)
                    .background(terminalBg)
                    .imePadding()
            ) {
                // Health banner lives on the terminal tab only (iOS parity:
                // the dot+banner is terminal context, not Monitor/Docker/Files).
                if (tab == SessionTab.TERMINAL) {
                    statusText.value?.let { text ->
                        ConnectionBanner(
                            state = connState.value,
                            text = text,
                            keepAlive = host?.keepAlive ?: false,
                            onStopRetrying = { stopReconnecting() },
                        )
                    }
                }
                // Terminal stays mounted under everything (opacity swap, never
                // removed from composition) so buffer + PTY + scrollback survive
                // every tab switch — the invariant iOS enforces via ZStack.
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .then(if (tab == SessionTab.TERMINAL) Modifier else Modifier.alpha(0f))
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                TerminalView(ctx).apply {
                                    terminalView = this
                                    this.emulator = this@TerminalActivity.emulator
                                    onData = { data ->
                                        reconnector?.write(data)
                                        historyAssembler?.feed(data)
                                    }
                                    // ZMODEM frames / cancels: SSH-bound, but not
                                    // keystrokes — keep them out of command history
                                    onProtocol = { data -> reconnector?.write(data) }
                                    // Engine device replies (CPR/DA/...) go to the SSH channel too.
                                    this@TerminalActivity.emulator?.onResponse = { data ->
                                        reconnector?.write(data)
                                    }
                                    onPtyResize = { c, r -> reconnector?.resizePty(c, r) }
                                    onCtrlStateChanged = { armed -> this@TerminalActivity.ctrlArmed.value = armed }
                                    onAltStateChanged = { armed -> this@TerminalActivity.altArmed.value = armed }
                                    onScrollOffsetChanged = { off -> this@TerminalActivity.scrollOffset.intValue = off }
                                    if (host?.fontSizeSp ?: 0f > 0f) {
                                        fontSizePx = host!!.fontSizeSp * resources.displayMetrics.scaledDensity
                                    }
                                    theme = TerminalTheme.byName(SettingsStore.terminalTheme(this@TerminalActivity))
                                    val fontId = SettingsStore.terminalFontFamily(this@TerminalActivity)
                                    typeface = TerminalFont.byId(fontId).typeface(ctx)
                                    zmodemSink = zmodemDownloadSink()
                                    setOnClickListener { showSoftKeyboard() }
                                    post { requestFocus() }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                        if (keyboardRowVisible.value && tab == SessionTab.TERMINAL) {
                            ExtraKeysRow()
                        }
                    }
                    // Non-terminal tabs render ON TOP of the (hidden) terminal.
                    val rc = reconnector
                    when (tab) {
                        SessionTab.TERMINAL -> {}
                        SessionTab.MONITOR ->
                            if (rc != null) MonitorTab(rc, host?.id.orEmpty()) else LoadingTab("Monitor")
                        SessionTab.DOCKER -> if (rc != null) DockerTab(rc) else LoadingTab("Docker")
                        SessionTab.FILES ->
                            if (rc != null) SftpTab(rc, connectionGen.intValue, transfers) else LoadingTab("Files")
                    }
                }
                SessionTabBar(tab = tab, onTab = { tab = it })
            }
        }

        keyPromptState.value?.let { (request, answer) ->
            AlertDialog(
                onDismissRequest = { },
                icon = {
                    Icon(
                        if (request.isChange) Icons.Filled.Warning else Icons.Filled.Lock,
                        contentDescription = null,
                        tint = if (request.isChange) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                title = { Text(if (request.isChange) "Host key changed" else "Unknown host key") },
                text = {
                    Column {
                        Text(
                            if (request.isChange) {
                                "The key reported by ${request.endpoint} differs from the recorded one. The server may have been reinstalled — or someone is intercepting the connection."
                            } else {
                                "First connection to ${request.endpoint}. Trust this host?"
                            }
                        )
                        Text(
                            "Key type: ${request.keyType}\nFingerprint:\n${request.fingerprint}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
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
                        // Declining trust is a decision, not a network blip:
                        // stop the reconnect loop instead of re-prompting
                        // on every backoff interval.
                        stopReconnecting()
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

        if (tunnelConfirmVisible.value) {
            AlertDialog(
                onDismissRequest = { tunnelConfirmVisible.value = false },
                title = { Text(TunnelCapsule.stopDialogTitle(liveTunnelCount.intValue)) },
                text = { Text("This tears down all local port forwards. The SSH session stays connected.") },
                confirmButton = {
                    TextButton(onClick = {
                        reconnector?.stopTunnels()
                        liveTunnelCount.intValue = 0
                        tunnelConfirmVisible.value = false
                    }) { Text("Stop all tunnels", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { tunnelConfirmVisible.value = false }) { Text("Cancel") }
                }
            )
        }

        if (paletteSheetVisible.value) {
            val hostId = this.host?.id ?: return@TerminalScreen
            CommandPaletteSheet(
                hostId = hostId,
                historyStore = historyStore,
                snippetStore = SnippetStore(this),
                runCommand = { line -> terminalView?.sendRaw(line.toByteArray(Charsets.UTF_8)) },
                onDismiss = { paletteSheetVisible.value = false },
                onOpenSnippets = {
                    paletteSheetVisible.value = false
                    snippetsSheetVisible.value = true
                },
                onOpenHistory = {
                    paletteSheetVisible.value = false
                    historySheetVisible.value = true
                },
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
                        ListItem(
                            leadingContent = { Icon(Icons.Filled.Code, contentDescription = null) },
                            headlineContent = { Text(snip.label) },
                            supportingContent = {
                                Text(
                                    snip.command,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.clickable {
                                snippetsSheetVisible.value = false
                                // sendRaw, not sendText: a single-char
                                // command must never be interpreted as
                                // an armed Ctrl-letter (parity with the
                                // palette + history sheet paths).
                                terminalView?.sendRaw((snip.command + "\r").toByteArray(Charsets.UTF_8))
                            },
                        )
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
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search history") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
            if (results.isEmpty()) {
                Text(
                    if (query.isBlank()) {
                        "No history yet. Commands you run are recorded here (encrypted on this device)."
                    } else {
                        "Nothing matches \"$query\"."
                    },
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
                items(results) { entry ->
                    ListItem(
                        headlineContent = {
                            Text(
                                entry.text.lineSequence().firstOrNull() ?: "",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { saveAsSnippet(entry.text) }) {
                                Icon(
                                    Icons.Filled.BookmarkAdd,
                                    contentDescription = "Save as snippet",
                                )
                            }
                        },
                        modifier = Modifier.clickable {
                            historySheetVisible.value = false
                            // sendRaw, not sendText: a single-char entry must
                            // never be interpreted as an armed Ctrl-letter
                            terminalView?.sendRaw((entry.text + "\r").toByteArray(Charsets.UTF_8))
                        },
                    )
                }
            }
        }
    }

    private fun saveAsSnippet(command: String) {
        val label = command.lineSequence().firstOrNull { it.isNotBlank() }?.take(40) ?: "snippet"
        historyExecutor.execute {
            val store = SnippetStore(this)
            val list = store.load()
            list.add(Snippet(label = label, command = command))
            store.save(list)
        }
        message.value = "Saved snippet: $label"
    }

    @OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun ExtraKeysRow() {
        val context = androidx.compose.ui.platform.LocalContext.current
        val keys = remember { mutableStateOf(ExtraKeysConfig.load(context)) }
        var editing by remember { mutableStateOf(false) }

        if (!editing) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                keys.value.forEach { id ->
                    if (id == "CTRL") {
                        KeyButton("CTRL", armed = ctrlArmed.value) { toggleCtrl() }
                    } else if (id == "ALT") {
                        KeyButton("ALT", armed = altArmed.value) { toggleAlt() }
                    } else {
                        KeyButton(ExtraKeysConfig.labelFor(id)) {
                            ExtraKeysConfig.bytesFor(id)?.let { bytes -> terminalView?.sendRaw(bytes) }
                        }
                    }
                }
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Filled.Tune, contentDescription = "Edit extra keys")
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

    private fun toggleCtrl() {
        val tv = terminalView ?: return
        tv.ctrlArmed = !tv.ctrlArmed
        ctrlArmed.value = tv.ctrlArmed
    }

    /**
     * Reads at most [max] bytes, failing clearly beyond that. The ZMODEM
     * sender holds the whole file plus its escaped form in memory, so an
     * unbounded pick (a 400 MB tarball) was an OOM crash mid-upload.
     */
    private fun readBounded(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > max) {
                error("File is larger than ${max / (1024 * 1024)} MB — use the Files tab (SFTP) for big uploads")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun toggleAlt() {
        val tv = terminalView ?: return
        tv.altArmed = !tv.altArmed
        altArmed.value = tv.altArmed
    }

    /**
     * ZMODEM download sink: saves to the system Downloads collection
     * (MediaStore on API 29+, no permission needed); older devices fall
     * back to the app's external files dir.
     */
    /** Set from composition so the non-composable ZMODEM sink can launch the SAF picker. */
    private var zmodemPickLauncherRef: (() -> androidx.activity.result.ActivityResultLauncher<Array<String>>)? = null

    private fun zmodemDownloadSink(): TerminalView.ZmodemSink {
        var uri: android.net.Uri? = null
        var out: java.io.OutputStream? = null
        return object : TerminalView.ZmodemSink {
            // The download stream deliberately outlives this call: ZMODEM
            // delivers a file as offer → many data chunks → complete, and
            // every exit path (complete, failure, a restarted offer) closes
            // it. Lint cannot follow a stream held in a field across
            // callbacks.
            @android.annotation.SuppressLint("Recycle")
            override fun onZmodemOffer(name: String, size: Long) {
                // A restarted transfer can offer a new file without ever
                // closing the last one; dropping the reference would leak the
                // stream and strand its MediaStore row as permanently pending
                // (invisible in Downloads).
                closePartial()
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                        }
                        uri = contentResolver.insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            values,
                        )
                        out = uri?.let { contentResolver.openOutputStream(it) }
                    } else {
                        val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                            ?: filesDir
                        val f = java.io.File(dir, name)
                        uri = android.net.Uri.fromFile(f)
                        out = f.outputStream()
                    }
                    if (out == null) throw IllegalStateException("cannot open download stream")
                } catch (e: Exception) {
                    CrashReporting.report(e)
                    runOnUiThread { message.value = "Cannot save ${'$'}{e.message}" }
                }
            }

            override fun onZmodemData(chunk: ByteArray) {
                try {
                    out?.write(chunk)
                } catch (e: Exception) {
                    CrashReporting.report(e)
                }
            }

            override fun onZmodemComplete(name: String, size: Long) {
                try {
                    out?.close()
                    if (android.os.Build.VERSION.SDK_INT >= 29 && uri != null) {
                        contentResolver.update(
                            uri!!,
                            android.content.ContentValues().apply {
                                put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                            },
                            null,
                            null,
                        )
                    }
                    runOnUiThread { message.value = "Saved $name ($size bytes) to Downloads" }
                } catch (e: Exception) {
                    CrashReporting.report(e)
                } finally {
                    out = null
                    uri = null
                }
            }

            override fun onZmodemUploadRequested() {
                zmodemPickLauncherRef?.invoke()?.launch(arrayOf("*/*"))
            }

            override fun onZmodemFailed(reason: String) {
                closePartial()
            }

            /** Close and discard a half-written download; safe to call twice. */
            fun closePartial() {
                try {
                    out?.close()
                    if (uri != null) {
                        contentResolver.delete(uri!!, null, null)
                    }
                } catch (_: Exception) {
                }
                out = null
                uri = null
            }
        }
    }

    /** Sessions-switcher target: bring this terminal's own task to the front. */
    private fun bringToFront() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        runCatching { am.moveTaskToFront(taskId, 0) }
    }

    private fun disconnectAndFinish() {
        reconnector?.stop()
        reconnector = null
        SessionService.stop(this, sessionId)
        finish()
    }

    /** In-session tabs moved to SessionTabs.kt (SessionTab) so they are unit-testable. */

    companion object {
        /** Intent extra: which [SessionTab] to open first (the stats widget opens Monitor). */
        const val EXTRA_TAB = "tab"
        const val TAB_MONITOR = "monitor"

        internal fun initialTab(extra: String?): SessionTab =
            if (extra == TAB_MONITOR) SessionTab.MONITOR else SessionTab.TERMINAL
    }
}

/**
 * Connection health banner. Each state gets a Material container/on-container
 * pair — success, warning, error — instead of the three raw hexes the states
 * used to be painted with, so the text keeps its contrast in either mode and
 * the amber "reconnecting" state is legible rather than white-on-orange.
 */
@Composable
private fun ConnectionBanner(
    state: ConnState,
    text: String,
    keepAlive: Boolean,
    onStopRetrying: () -> Unit,
) {
    val container = when (state) {
        ConnState.CONNECTED -> MaterialTheme.conch.successContainer
        ConnState.CONNECTING, ConnState.RECONNECTING -> MaterialTheme.conch.warningContainer
        ConnState.STOPPED -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (state) {
        ConnState.CONNECTED -> MaterialTheme.conch.onSuccessContainer
        ConnState.CONNECTING, ConnState.RECONNECTING -> MaterialTheme.conch.onWarningContainer
        ConnState.STOPPED -> MaterialTheme.colorScheme.onErrorContainer
    }
    val retrying = state == ConnState.RECONNECTING
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(container)
            .then(
                if (retrying) {
                    Modifier.clickable(
                        onClickLabel = "Stop reconnecting",
                        role = Role.Button,
                        onClick = onStopRetrying,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        StatusDot(state = state, keepAlive = keepAlive, color = content)
        Text(
            text,
            color = content,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        )
    }
}
