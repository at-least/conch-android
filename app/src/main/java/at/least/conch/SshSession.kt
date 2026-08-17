package at.least.conch

import android.content.Context
import android.os.Handler
import android.os.Looper
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages one SSH connection with an interactive PTY shell plus optional
 * local port-forward tunnels, backed by sshj. All socket I/O runs on
 * background threads; callbacks arrive on the main thread.
 */
class SshSession(
    private val context: Context,
    private val host: Host,
    initialCols: Int,
    initialRows: Int,
    private val callbacks: Callbacks,
    private val tofuPrompt: KeyPrompt? = null,
) {
    interface Callbacks {
        fun onConnected()
        fun onData(data: ByteArray)
        fun onDisconnected(reason: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var client: SSHClient? = null
    private var session: Session? = null
    private var shell: Session.Shell? = null
    private var shellOut: OutputStream? = null
    private val forwarderSockets = mutableListOf<ServerSocket>()
    private val forwarderThreads = mutableListOf<Thread>()
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
                val ssh = SshConnectionFactory.connect(context, host, tofuPrompt)
                client = ssh

                startTunnels(ssh)

                if (host.socksPort > 0) {
                    val proxy = SocksProxy(ssh)
                    val bound = proxy.start(host.socksPort)
                    socksProxy = proxy
                    mainHandler.post {
                        callbacks.onData(
                            "\r\n\u001b[90m[socks5 listening on 127.0.0.1:$bound]\u001b[0m\r\n".toByteArray()
                        )
                    }
                }

                val s = ssh.startSession()
                session = s
                s.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
                val sh = s.startShell()
                shell = sh
                shellOut = sh.outputStream

                mainHandler.post { callbacks.onConnected() }

                if (host.tmuxAutoAttach) {
                    // -A: attach if the session exists, create it otherwise;
                    // COLORTERM lets remote apps use RGB (truecolor) output
                    synchronized(sh.outputStream) {
                        sh.outputStream.write("COLORTERM=truecolor tmux new -A -s conch\r".toByteArray())
                        sh.outputStream.flush()
                    }
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
                        mainHandler.post { callbacks.onData(copy) }
                    }
                }
                disconnectInner("Connection closed by remote")
            } catch (e: Exception) {
                CrashReporting.report(e)
                disconnectInner(SshConnectionFactory.describeError(e))
            }
        }.also { readerThread = it }.apply {
            name = "ssh-reader"
            isDaemon = true
            start()
        }
    }

    private var readerThread: Thread? = null

    private fun startTunnels(ssh: SSHClient) {
        for (t in host.tunnels) {
            if (t.localPort !in 1..65535 || t.host.isBlank() || t.port !in 1..65535) continue
            try {
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
            } catch (_: Exception) {
                // tunnel port unavailable — skip, shell continues
            }
        }
    }

    fun write(data: ByteArray) {
        val out = shellOut ?: return
        writerExecutor.execute {
            try {
                synchronized(out) { out.write(data); out.flush() }
            } catch (_: IOException) {
            }
        }
    }

    fun resizePty(newCols: Int, newRows: Int) {
        cols = newCols
        rows = newRows
        val sh = shell ?: return
        writerExecutor.execute {
            try {
                sh.changeWindowDimensions(newCols, newRows, 0, 0)
            } catch (_: Exception) {
            }
        }
    }

    fun disconnect(reason: String = "Disconnected") {
        disconnectInner(reason)
    }

    private fun disconnectInner(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        writerExecutor.shutdownNow()
        socksProxy?.stop()
        try { session?.close() } catch (_: Exception) {}
        try { client?.disconnect() } catch (_: Exception) {}
        forwarderSockets.forEach { try { it.close() } catch (_: Exception) {} }
        val c = callbacks
        mainHandler.post { c.onDisconnected(reason) }
    }
}
