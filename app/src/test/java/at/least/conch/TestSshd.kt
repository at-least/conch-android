package at.least.conch

import net.schmizz.sshj.SSHClient
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.Signal
import org.apache.sshd.server.SignalListener
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.shell.ShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertTrue
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Canned response of the in-process sshd to one exec command. */
class ExecResult(
    val stdout: ByteArray,
    val stderr: ByteArray = ByteArray(0),
    val exit: Int = 0,
)

/**
 * In-process SSH server (Apache MINA SSHD) for JVM unit tests: real wire
 * handshake, auth, PTY/shell, exec, SFTP and forwarding against the app's
 * sshj-based code. One instance per test, [close] stops the server.
 */
class TestSshd(
    val user: String = "testuser",
    val password: String? = "testpw",
    authorizedKeys: List<PublicKey> = emptyList(),
    private val hostKeyPair: KeyPair = hostKeyEd25519(),
    sftpRoot: File? = null,
    fixedPort: Int? = null,
    private val execHandler: (String) -> ExecResult = { cmd -> ExecResult("$cmd\n".toByteArray()) },
    /** Extra server tuning, applied after defaults — e.g. restricted algorithm factories for hardened-sshd tests. */
    private val configure: (SshServer) -> Unit = {},
) : AutoCloseable {

    companion object {
        init {
            // Robolectric runs test classes in a sandbox classloader whose
            // own TestSshd also registers "BC" into the JVM-global provider
            // table. Plain-JVM classes later in the same test JVM would then
            // get crypto objects from the WRONG classloader and die mid-
            // handshake ("Broken transport; encountered EOF") — so each
            // classloader swaps BC in for itself on first use.
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }

        private fun generate(algorithm: String, bits: Int?): KeyPair {
            val gen = KeyPairGenerator.getInstance(algorithm)
            if (bits != null) gen.initialize(bits)
            return gen.generateKeyPair()
        }

        private fun generateEc(): KeyPair {
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1"))
            return gen.generateKeyPair()
        }

        /**
         * Ed25519 must come from the i2p eddsa implementation: both sshj and
         * this MINA version only understand net.i2p.crypto.eddsa key types
         * (JDK EdDSA keys fail with ClassCastException / server-side crash).
         */
        private fun generateEd25519(): KeyPair {
            val gen = net.i2p.crypto.eddsa.KeyPairGenerator()
            gen.initialize(
                net.i2p.crypto.eddsa.spec.EdDSAGenParameterSpec("Ed25519"),
                java.security.SecureRandom(),
            )
            return gen.generateKeyPair()
        }

        private val ed25519HostKey: KeyPair by lazy { generateEd25519() }
        private val rsaHostKey: KeyPair by lazy { generate("RSA", 2048) }
        private val ecHostKey: KeyPair by lazy { generateEc() }

        fun hostKeyEd25519(): KeyPair = ed25519HostKey
        fun hostKeyRsa(): KeyPair = rsaHostKey
        fun hostKeyEc(): KeyPair = ecHostKey
    }

    val recordedCommands = ConcurrentLinkedQueue<String>()
    val shells = ConcurrentLinkedQueue<RecordedShell>()

    val hostPublicKey: PublicKey
        get() = hostKeyPair.public

    internal val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = fixedPort ?: 0
        keyPairProvider = KeyPairProvider.wrap(hostKeyPair)
        if (password != null) {
            passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == user && p == password }
        }
        publickeyAuthenticator = PublickeyAuthenticator { u, key, _ ->
            u == user && authorizedKeys.any { KeyUtils.compareKeys(it, key) }
        }
        forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        commandFactory = CommandFactory { _, command -> ExecCommand(command) }
        shellFactory = ShellFactory { _ -> RecordedShell().also { shells.add(it) } }
        if (sftpRoot != null) {
            fileSystemFactory = VirtualFileSystemFactory(sftpRoot.toPath())
            subsystemFactories = listOf(SftpSubsystemFactory())
        }
        configure(this)
    }

    fun start(): TestSshd {
        server.start()
        return this
    }

    val port: Int
        get() = (server.boundAddresses.first() as InetSocketAddress).port

    override fun close() {
        stopForcibly()
    }

    /** Hard server kill: drop all sessions without SSH_MSG_DISCONNECT. */
    fun stopForcibly() {
        try {
            server.stop(true)
        } catch (_: Exception) {
        }
    }

    /** Server-side "command" channel: records the command, replies with canned output. */
    private inner class ExecCommand(private val command: String) : Command {
        private var out: OutputStream? = null
        private var err: OutputStream? = null
        private var exitCb: ExitCallback? = null

        override fun setInputStream(input: InputStream) {}
        override fun setOutputStream(output: OutputStream) {
            out = output
        }

        override fun setErrorStream(error: OutputStream) {
            err = error
        }

        override fun setExitCallback(callback: ExitCallback) {
            exitCb = callback
        }

        override fun start(channel: ChannelSession, env: Environment) {
            recordedCommands.add(command)
            val res = try {
                execHandler(command)
            } catch (e: Exception) {
                ExecResult(ByteArray(0), (e.message ?: "error").toByteArray(), 1)
            }
            out?.let {
                it.write(res.stdout)
                it.flush()
            }
            err?.let {
                it.write(res.stderr)
                it.flush()
            }
            exitCb?.onExit(res.exit)
        }

        override fun destroy(channel: ChannelSession) {}
    }

    /**
     * Server-side shell: records the environment handed to it (TERM / COLUMNS /
     * LINES from the PTY request), every window-size change, and all received
     * bytes; echoes every byte back to the client.
     */
    inner class RecordedShell : Command {
        val started = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val windowSizes = ConcurrentLinkedQueue<Pair<String, String>>()
        private val received = ConcurrentLinkedQueue<ByteArray>()

        @Volatile
        var envAtStart: Map<String, String> = emptyMap()
            private set

        @Volatile
        var ptyModesAtStart: Map<Any, Any> = emptyMap()
            private set

        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var exitCb: ExitCallback? = null

        override fun setInputStream(inputStream: InputStream) {
            input = inputStream
        }

        override fun setOutputStream(outputStream: OutputStream) {
            output = outputStream
        }

        override fun setErrorStream(errorStream: OutputStream) {}

        override fun setExitCallback(callback: ExitCallback) {
            exitCb = callback
        }

        override fun start(channel: ChannelSession, env: Environment) {
            envAtStart = HashMap(env.env)
            ptyModesAtStart = HashMap(env.ptyModes)
            env.addSignalListener(
                SignalListener { _, _ ->
                    windowSizes.add(
                        (env.env[Environment.ENV_COLUMNS] ?: "?") to (env.env[Environment.ENV_LINES] ?: "?")
                    )
                },
                Signal.WINCH,
            )
            started.countDown()
            thread(isDaemon = true, name = "test-echo-shell") {
                val buf = ByteArray(4096)
                val inStream = input
                val outStream = output
                try {
                    while (true) {
                        val n = inStream!!.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            received.add(buf.copyOf(n))
                            outStream!!.write(buf, 0, n)
                            outStream!!.flush()
                        }
                    }
                } catch (_: IOException) {
                }
                exited.countDown()
                try {
                    exitCb?.onExit(0)
                } catch (_: Exception) {
                }
            }
        }

        override fun destroy(channel: ChannelSession) {}

        /** Server-side channel close: exit-status + channel teardown. */
        fun serverExit(status: Int = 0) {
            try {
                input?.close()
            } catch (_: Exception) {
            }
            try {
                exitCb?.onExit(status)
            } catch (_: Exception) {
            }
        }

        fun awaitStarted() {
            assertTrue("shell channel never started", started.await(10, TimeUnit.SECONDS))
        }

        fun awaitExited() {
            assertTrue("shell channel never exited", exited.await(10, TimeUnit.SECONDS))
        }

        fun receivedBytes(): ByteArray {
            var size = 0
            for (b in received) size += b.size
            val all = ByteArray(size)
            var off = 0
            for (b in received) {
                b.copyInto(all, off)
                off += b.size
            }
            return all
        }

        fun awaitReceived(expected: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                var size = 0
                for (b in received) size += b.size
                if (size >= expected) return
                Thread.sleep(20)
            }
            assertTrue("expected $expected bytes, got ${receivedBytes().size}", false)
        }
    }
}

/** Trivial TCP echo server on 127.0.0.1, for forwarding/SOCKS tests. */
class EchoServer : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

    val port: Int
        get() = serverSocket.localPort

    init {
        thread(isDaemon = true, name = "test-echo-server") {
            while (!stopped.get()) {
                val sock = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true, name = "test-echo-conn") {
                    try {
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = sock.getInputStream().read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                sock.getOutputStream().write(buf, 0, n)
                                sock.getOutputStream().flush()
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        try {
                            sock.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        stopped.set(true)
        try {
            serverSocket.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Connects through the real [SshConnectionFactory] core (same code path as the
 * app minus Android storage), with the host key pre-trusted so no prompt is
 * needed — like a background session against an already-known host.
 */
fun connectTrusted(
    server: TestSshd,
    store: KnownHostsStore,
    host: Host = Host(
        hostname = "127.0.0.1",
        username = server.user,
        authType = Host.AUTH_PASSWORD,
    ),
    password: String = server.password ?: "",
    keyProvider: (SSHClient, String) -> net.schmizz.sshj.userauth.keyprovider.KeyProvider =
        { _, _ -> throw IllegalStateException("no key in this test") },
): SSHClient {
    store.add("127.0.0.1", server.port, server.hostPublicKey)
    host.port = server.port
    return SshConnectionFactory.connect(
        host = host,
        prompt = null,
        store = store,
        keyProvider = keyProvider,
        password = { password },
    )
}

/** Reads from a stream until the accumulated output contains [expected]. */
fun readUntil(input: InputStream, expected: String, timeoutMs: Long = 10_000): String {
    val deadline = System.currentTimeMillis() + timeoutMs
    val acc = StringBuilder()
    val buf = ByteArray(4096)
    while (System.currentTimeMillis() < deadline) {
        while (input.available() > 0) {
            val n = input.read(buf)
            if (n < 0) break
            acc.append(String(buf, 0, n, Charsets.UTF_8))
        }
        if (acc.contains(expected)) return acc.toString()
        Thread.sleep(20)
    }
    throw AssertionError("timed out waiting for \"$expected\", got: \"$acc\"")
}

/** Temp file holding an Ed25519 OpenSSH PEM, plus the sshj-loaded public key. */
class TestKey(pem: String) : AutoCloseable {
    val file: File = File.createTempFile("conch-test", ".key")

    init {
        file.writeText(pem)
        java.nio.file.Files.setPosixFilePermissions(
            file.toPath(),
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
        )
    }

    val publicKey: PublicKey by lazy {
        SSHClient().use { ssh -> ssh.loadKeys(file.absolutePath).public }
    }

    override fun close() {
        file.delete()
    }
}

fun newTestKey(): TestKey {
    val (seed, pub) = Ed25519Codec.generateKeyPair()
    return TestKey(Ed25519Codec.openSshPrivateKeyPem(seed, pub, "unittest"))
}
