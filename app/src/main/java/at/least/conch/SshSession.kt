package at.least.conch

import android.content.Context
import android.os.Handler
import android.os.Looper
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.sftp.SFTPClient
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages one SSH connection with an interactive PTY shell plus optional
 * local port-forward tunnels, backed by sshj. All socket I/O runs on
 * background threads; callbacks arrive on the main thread.
 *
 * [post] and [connector] are seams for JVM tests: the app uses the defaults
 * (main-thread Handler + [SshConnectionFactory] with Android storage).
 */
@Suppress("TooManyFunctions") // one connection's whole lifecycle; splitting it would only scatter the locking
class SshSession(
    private val context: Context?,
    private val host: Host,
    initialCols: Int,
    initialRows: Int,
    private val callbacks: Callbacks,
    private val tofuPrompt: KeyPrompt? = null,
    post: ((Runnable) -> Unit)? = null,
    connector: ((Host, KeyPrompt?) -> SSHClient)? = null,
) {
    companion object {
        /** Raw exec output for one command; closes its channel on every path. */
        internal fun execChannelOutput(ssh: SSHClient, command: String): String {
            val s = ssh.startSession()
            try {
                val cmd = s.exec(command)
                val out = cmd.inputStream.readBytes().decodeToString()
                cmd.close()
                return out
            } finally {
                try { s.close() } catch (_: Exception) {}
            }
        }

        /**
         * Wire contract: the inner command is byte-identical to the iOS
         * suffix (InteractionStringTests.swift); Android carries the same
         * `command -v tmux` guard (graceful no-tmux hint). The attach line
         * itself is pinned by InteractionStringContractTest.
         */
        const val TMUX_ATTACH_LINE = "COLORTERM=truecolor tmux new -A -s conch\r"

        /**
         * Reader-loop EOF after the shell lived at least [MIN_SESSION_MS]
         * means the remote side closed a working session — the user typed
         * `exit` / CTRL+D. Reported as [REASON_SESSION_ENDED] so the
         * reconnector stops instead of looping back into a fresh shell
         * (ConnectBot open issue: "Mark session as cleanly closed when
         * exiting with CTRL+D").
         */
        const val REASON_SESSION_ENDED = "Session ended"
        const val MIN_SESSION_MS = 10_000L

        fun cleanCloseReason(uptimeMs: Long): String =
            if (uptimeMs >= MIN_SESSION_MS) REASON_SESSION_ENDED else "Connection closed by remote"

        /**
         * Reasons that must NOT trigger a reconnect: the user ended the
         * session, authentication was rejected (retrying a bad password
         * forever spams the server and can trip lockouts — let the user fix
         * the credentials instead), or the stored key material is gone
         * (no retry can restore it — re-import is the only fix), or the host
         * record itself is unusable (no password saved, key auth with no key,
         * jump host deleted — only editing the host fixes it). Matches the
         * prefixes SshConnectionFactory.describeError emits for those cases.
         */
        fun isTerminalFailure(reason: String): Boolean =
            reason == REASON_SESSION_ENDED ||
                reason.startsWith("Authentication failed") ||
                reason.startsWith(KeyManager.MISSING_KEY_PREFIX) ||
                reason.startsWith(SshConnectionFactory.HOST_CONFIG_PREFIX)
    }

    interface Callbacks {
        fun onConnected()
        fun onData(data: ByteArray)
        fun onDisconnected(reason: String)
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val post: (Runnable) -> Unit = post ?: { r: Runnable -> mainHandler.post(r) }
    private val connector: (Host, KeyPrompt?) -> SSHClient = connector
        ?: { h, p ->
            SshConnectionFactory.connect(
                context ?: error("context required for default connector"),
                h,
                p,
            )
        }

    @Volatile
    private var client: SSHClient? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var shell: Session.Shell? = null

    @Volatile
    private var shellOut: OutputStream? = null
    private val forwarderSockets = java.util.Collections.synchronizedList(mutableListOf<ServerSocket>())
    private val forwarderThreads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
    private val remoteForwards = java.util.Collections.synchronizedList(
        mutableListOf<RemotePortForwarder.Forward>(),
    )

    @Volatile
    private var socksProxy: SocksProxy? = null
    private val closed = AtomicBoolean(false)

    /**
     * Single-threaded writer: keystrokes must reach the PTY in the exact order
     * they were typed. Spawning a thread per write could reorder them.
     */
    private val writerExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ssh-writer").apply { isDaemon = true }
    }

    @Volatile
    private var cols = initialCols

    @Volatile
    private var rows = initialRows

    fun connect() {
        Thread {
            try {
                val ssh = connector(host, tofuPrompt)
                if (closed.get()) {
                    // disconnect() raced us while the connection was building —
                    // nobody will close this client anymore, so do it here
                    try { ssh.disconnect() } catch (_: Exception) {}
                    return@Thread
                }
                client = ssh

                startTunnels(ssh)

                if (host.socksPort > 0) {
                    // A bind failure (port already used, likely by another
                    // concurrent session) must not kill an otherwise healthy
                    // shell connection — same tolerance as per-tunnel binds.
                    try {
                        val proxy = SocksProxy(ssh)
                        val bound = proxy.start(host.socksPort)
                        socksProxy = proxy
                        post {
                            callbacks.onData(
                                "\r\n\u001b[90m[socks5 listening on 127.0.0.1:$bound]\u001b[0m\r\n".toByteArray()
                            )
                        }
                    } catch (e: Exception) {
                        CrashReporting.report(e)
                        post {
                            val msg = "\r\n\u001b[90m[socks5 127.0.0.1:${host.socksPort}" +
                                " unavailable — port in use?]\u001b[0m\r\n"
                            callbacks.onData(msg.toByteArray())
                        }
                    }
                }

                val s = ssh.startSession()
                session = s
                s.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
                val sh = s.startShell()
                shell = sh
                shellOut = sh.outputStream
                establishedAtMs = System.currentTimeMillis()

                if (!closed.get()) post { callbacks.onConnected() }

                if (host.tmuxAutoAttach) {
                    attachTmuxOrHint(ssh, sh)
                }

                val input = sh.inputStream
                // 64KB read buffer (aligned with Termux): bursts like cat/seq
                // no longer force many tiny main-thread callbacks
                val buf = ByteArray(64 * 1024)
                while (!closed.get()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        val copy = buf.copyOf(n)
                        post { callbacks.onData(copy) }
                    }
                }
                val established = establishedAtMs
                val uptime = if (established == 0L) 0L else System.currentTimeMillis() - established
                disconnectInner(cleanCloseReason(uptime), teardown = false)
            } catch (e: Exception) {
                CrashReporting.report(e)
                disconnectInner(SshConnectionFactory.describeError(e), teardown = false)
            } finally {
                // disconnect() is one-shot: if it ran while this thread was
                // still binding tunnel ports / starting the SOCKS proxy /
                // opening the shell, its teardown saw none of that and the
                // ports would stay bound (and the client leaked) for the
                // life of the process. Whatever this thread set up, this
                // thread releases — every step is idempotent, so on the
                // normal path (teardown already done) this is a no-op.
                releaseTransport()
            }
        }.apply {
            name = "ssh-reader"
            isDaemon = true
            start()
        }
    }

    @Volatile
    private var establishedAtMs = 0L

    /**
     * tmux auto-attach with graceful degradation (iOS `command -v tmux`
     * guard parity): when tmux is missing, print a dim one-time hint with
     * the install command instead of a bare "command not found".
     */
    private fun attachTmuxOrHint(ssh: SSHClient, sh: Session.Shell) {
        val hasTmux = try {
            execChannelOutput(ssh, "command -v tmux").isNotEmpty()
        } catch (_: Exception) {
            true // probe failed — let the attach line speak for itself
        }
        if (hasTmux) {
            // -A: attach if the session exists, create it otherwise;
            // COLORTERM lets remote apps use RGB (truecolor) output
            synchronized(sh.outputStream) {
                sh.outputStream.write(TMUX_ATTACH_LINE.toByteArray())
                sh.outputStream.flush()
            }
        } else {
            val hint = "\r\n\u001b[90m[no tmux on this host — install it " +
                "(e.g. apt install tmux) to keep sessions across drops. " +
                "Disable Auto-attach tmux in host settings to hide this.]\u001b[0m\r\n"
            post { callbacks.onData(hint.toByteArray()) }
        }
    }

    private fun startTunnels(ssh: SSHClient) {
        for (t in host.tunnels) {
            if (t.localPort !in 1..65535 || t.host.isBlank() || t.port !in 1..65535) continue
            try {
                if (t.remote) {
                    // -R: server binds the port; arriving connections are
                    // bridged to host:port resolved on THIS device
                    val bound = ssh.remotePortForwarder.bind(
                        RemotePortForwarder.Forward(t.bindHost.ifBlank { "127.0.0.1" }, t.localPort),
                        SocketForwardingConnectListener(InetSocketAddress(t.host, t.port)),
                    )
                    remoteForwards.add(bound)
                } else {
                    val serverSocket = ServerSocket(t.localPort, 50, InetAddress.getByName("127.0.0.1"))
                    forwarderSockets.add(serverSocket)
                    val params = Parameters("127.0.0.1", t.localPort, t.host, t.port)
                    val lpf = ssh.newLocalPortForwarder(params, serverSocket)
                    val thread = Thread {
                        try {
                            lpf.listen()
                        } catch (_: Exception) {
                        }
                    }
                    forwarderThreads.add(thread)
                    thread.isDaemon = true
                    thread.start()
                }
            } catch (_: Exception) {
                // tunnel port unavailable — skip, shell continues
            }
        }
    }

    fun write(data: ByteArray) {
        val out = shellOut ?: return
        try {
            writerExecutor.execute {
                try {
                    synchronized(out) {
                        out.write(data)
                        out.flush()
                    }
                } catch (_: IOException) {
                }
            }
        } catch (_: RejectedExecutionException) {
            // session already disconnected — drop the keystrokes
        }
    }

    fun resizePty(newCols: Int, newRows: Int) {
        cols = newCols
        rows = newRows
        val sh = shell ?: return
        try {
            writerExecutor.execute {
                try {
                    sh.changeWindowDimensions(newCols, newRows, 0, 0)
                } catch (_: Exception) {
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /**
     * True iff the SSH transport is connected and this session has not been
     * torn down. Pure display signal (e.g. the connection-health dot) —
     * never gates interaction.
     */
    val isConnected: Boolean
        get() = !closed.get() && client?.isConnected == true

    /**
     * Run a one-shot command on a NEW exec channel over the SAME connection
     * as the live PTY shell (H1: sshj multiplexes both). Blocks the caller
     * until the command's stdout is fully drained; call from a background
     * thread/coroutine. Returns null if no connection is live or the exec
     * failed. The shell channel is never touched here.
     */
    fun exec(command: String): String? {
        val ssh = client ?: return null
        if (closed.get()) return null
        // Close the channel on every path: the Monitor tab polls this every
        // few seconds, and leaked channels eventually exhaust the server's
        // channel limit — which would kill the interactive shell's connection.
        var s: Session? = null
        return try {
            s = ssh.startSession()
            val cmd = s.exec(command)
            val out = cmd.inputStream.readBytes().decodeToString()
            cmd.close()
            out
        } catch (e: Exception) {
            CrashReporting.report(e)
            null
        } finally {
            try { s?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Open an SFTP client over the SAME connection as the live PTY shell.
     * Caller owns the returned client's lifecycle (close it when the file
     * browser tab is done). Returns null if no connection is live.
     */
    fun sftpClient(): SFTPClient? {
        val ssh = client ?: return null
        if (closed.get()) return null
        return try {
            ssh.newSFTPClient()
        } catch (e: Exception) {
            CrashReporting.report(e)
            null
        }
    }

    /**
     * Tear down all local-port-forward tunnels AND the SOCKS5 proxy WITHOUT
     * closing the shell or the SSH transport. The shell channel and any
     * exec/SFTP channels stay alive (C52 tunnel capsule parity: user taps
     * "⇅ N" → stop all tunnels). Safe to call from the main thread: local
     * listeners close immediately; the `-R` cancels are server round-trips
     * (sshj blocks up to the 30 s connection timeout on a dead link) and
     * run on a background thread.
     */
    fun stopTunnels() {
        closeLocalListeners()
        if (synchronized(remoteForwards) { remoteForwards.isNotEmpty() }) {
            Thread(::cancelRemoteForwards, "ssh-cancel-forwards").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun closeLocalListeners() {
        synchronized(forwarderSockets) {
            forwarderSockets.forEach { try { it.close() } catch (_: Exception) {} }
            forwarderSockets.clear()
            forwarderThreads.forEach { it.interrupt() }
            forwarderThreads.clear()
        }
        socksProxy?.stop()
        socksProxy = null
    }

    /** Takes every `-R` forward off the list (so a second caller finds none) and cancels it on the server. */
    private fun cancelRemoteForwards() {
        val forwards = synchronized(remoteForwards) { remoteForwards.toList().also { remoteForwards.clear() } }
        val ssh = client ?: return
        forwards.forEach { f ->
            try {
                ssh.remotePortForwarder.cancel(f)
            } catch (_: Exception) {
            }
        }
    }

    /** Number of active local-port-forward tunnels + SOCKS proxy (display signal). */
    val tunnelCount: Int
        get() {
            val fw = synchronized(forwarderSockets) { forwarderSockets.count { !it.isClosed } }
            val rf = synchronized(remoteForwards) { remoteForwards.size }
            return fw + rf + (if (socksProxy != null) 1 else 0)
        }

    fun disconnect(reason: String = "Disconnected") {
        disconnectInner(reason)
    }

    /**
     * @param teardown false from the reader thread, whose `finally` releases
     *                 the transport itself; true from a disconnect() caller.
     */
    private fun disconnectInner(reason: String, teardown: Boolean = true) {
        if (!closed.compareAndSet(false, true)) return
        writerExecutor.shutdownNow()
        // Local listeners go now (a reconnect rebinds the same ports within
        // a second); the sshj side goes on a background thread because
        // Session.close() and RemotePortForwarder.cancel() each await a
        // server reply for up to the 30 s connection timeout — on a dead
        // link that is an ANR when the user taps the banner or backs out.
        closeLocalListeners()
        if (teardown) {
            Thread(::releaseTransport, "ssh-teardown").apply {
                isDaemon = true
                start()
            }
        }
        val c = callbacks
        post { c.onDisconnected(reason) }
    }

    /**
     * Releases everything the transport owns. Idempotent and safe to run
     * concurrently from the reader thread's `finally` and a disconnect()
     * caller — closed sockets, cleared lists and sshj's own close locks make
     * a second pass a no-op.
     */
    private fun releaseTransport() {
        closeLocalListeners()
        cancelRemoteForwards()
        try { session?.close() } catch (_: Exception) {}
        try { client?.disconnect() } catch (_: Exception) {}
    }
}
