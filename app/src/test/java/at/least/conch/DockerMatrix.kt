package at.least.conch

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Shared plumbing for conch-android's opt-in Docker OpenSSH test matrix
 * (tools/sshd-matrix — deliberately independent of the conch-ios harness:
 * own image/container/ports). Same flag and skip semantics for every matrix
 * test: without the flag it skips (CI without Docker stays green); with the
 * flag but the matrix down it FAILS — opt-in means the setup was demanded.
 */
object DockerMatrix {
    /** host port → container 2223: password + pubkey auth. */
    const val PW_AND_KEY_PORT = 2233

    /** host port → container 2224: pubkey-only auth. */
    const val KEY_ONLY_PORT = 2234

    /** host port → container 2225: same auth as 2233 but AllowTcpForwarding yes. */
    const val FORWARDING_PORT = 2235

    /** host port → container 2226: keyboard-interactive (PAM) only, no "password" method. */
    const val KBD_INTERACTIVE_PORT = 2236

    /**
     * host port → container 2228: hardened server — SSH banner, MaxSessions 2,
     * PermitOpen 127.0.0.1:2223 only, a CA-trusted [certuser][CERT_PRINCIPAL],
     * authorized_keys option users ([cmduser][CMD_USER] forced command,
     * [restrictuser][RESTRICT_USER] restrict,pty, [noptyuser][NOPTY_USER]
     * no-pty) and a chrooted SFTP-only account ([sftponly][SFTP_ONLY_USER]).
     */
    const val HARDENED_PORT = 2238

    /** host port → container 2229: MaxAuthTries 1, idle shells reaped after 12 s (ChannelTimeout). */
    const val STRICT_PORT = 2239

    /** host port → container 2230: only an ECDSA host key is offered. */
    const val ECDSA_HOST_PORT = 2240

    /** host port → container 2231: only an RSA host key is offered (SHA-2 rsa-sha2-* signatures). */
    const val RSA_HOST_PORT = 2241

    /** host port → container 2232: legacy appliance — SHA-1 kex, CBC ciphers, ssh-rsa. May be absent on OpenSSH 10. */
    const val LEGACY_PORT = 2242

    /** host port → container 2270: accepts TCP and never sends a byte (connect/handshake timeout fixture). */
    const val SILENT_ACCEPT_PORT = 2270

    /** host port → container 2271: sends an SSH banner, then stalls forever (handshake timeout fixture). */
    const val BANNER_STALL_PORT = 2271

    /** sshd port INSIDE the container (targets of direct-tcpip / inner ssh). */
    const val CONTAINER_SSH_PORT = 2223

    /** Forwarding-enabled sshd port INSIDE the container. */
    const val CONTAINER_FWD_PORT = 2225

    /** The password account every base instance carries (also on the variants and alt servers). */
    const val PW_USER = "pwuser"
    const val PW_PASSWORD = "conch-pw-1"

    /** authorized_keys-option / certificate accounts on the hardened instance (:2238). */
    const val CERT_PRINCIPAL = "certuser"
    const val CMD_USER = "cmduser"
    const val RESTRICT_USER = "restrictuser"
    const val NOPTY_USER = "noptyuser"
    const val SFTP_ONLY_USER = "sftponly"
    const val SFTP_ONLY_PASSWORD = "conch-pw-3"

    /** The forced command cmduser's authorized_keys pins (every exec/shell runs this). */
    const val FORCED_COMMAND_OUTPUT = "FORCED_COMMAND_ONLY"

    /** Default matrix container, as named by tools/sshd-matrix/run.sh. */
    const val CONTAINER_NAME = "conch-android-sshd"

    const val FLAG = "conch.localSshdTest"

    /** Second flag: with it, every distro variant must be up (CI); without it, missing variants skip. */
    const val DISTRO_FLAG = "conch.distroMatrix"

    /**
     * One row of the distro matrix (tools/sshd-matrix/run.sh --variants):
     * the same recipe on another base image, three instances on
     * [pwPort]..[pwPort]+2 mirroring 2233..2235 of the default container.
     */
    data class Variant(
        val name: String,
        val base: String,
        val pwPort: Int,
        val container: String = "$CONTAINER_NAME-$name",
        /** run.sh mounts the host's docker socket into this container (Docker tab tests). */
        val dockerSocket: Boolean = false,
    ) {
        val keyOnlyPort: Int get() = pwPort + 1
        val forwardingPort: Int get() = pwPort + 2
        override fun toString() = "$name ($base)"
    }

    /** The default container as a matrix row (bookworm, ports 2233..2235). */
    val DEFAULT_VARIANT =
        Variant("bookworm", "debian:bookworm-slim", PW_AND_KEY_PORT, CONTAINER_NAME, dockerSocket = true)

    /** Keep in step with VARIANTS in run.sh. */
    val VARIANTS = listOf(
        Variant("ubuntu2004", "ubuntu:20.04", 2243),
        Variant("ubuntu2404", "ubuntu:24.04", 2246),
        Variant("alpine", "alpine:3.20", 2249),
        Variant("trixie", "debian:trixie-slim", 2252),
        Variant("rocky9", "rockylinux:9", 2255),
    )

    /**
     * A non-OpenSSH server implementation (tools/sshd-matrix/servers/, brought
     * up by run.sh --servers). Each pins that conch's handshake, auth and
     * channel handling do not secretly assume OpenSSH. [pwPort] is the
     * password/primary port; ports that a given server does not run are 0.
     */
    data class Server(
        val name: String,
        val pwPort: Int,
        val keyOnlyPort: Int = 0,
        val forwardingPort: Int = 0,
        val container: String = "$CONTAINER_NAME-$name",
    ) {
        override fun toString() = name
    }

    /** Keep in step with SERVERS in run.sh. */
    val DROPBEAR = Server("dropbear", pwPort = 2263, keyOnlyPort = 2264, forwardingPort = 2265)
    val TINYSSH = Server("tinyssh", pwPort = 0, keyOnlyPort = 2266)
    val GOSSH = Server("gossh", pwPort = 2267, forwardingPort = 2267)
    val PARAMIKO = Server("paramiko", pwPort = 2268)
    val SERVERS = listOf(DROPBEAR, TINYSSH, GOSSH, PARAMIKO)

    val keysDir: File = File(
        System.getenv("CONCH_ANDROID_MATRIX_KEYS")
            ?: "${System.getProperty("user.home")}/.cache/conch-android/sshd-matrix/keys",
    )

    /** TOFU prompt that accepts any unknown host key (first-connect UX). */
    val acceptPrompt: KeyPrompt = { _, done -> done(true) }

    fun optedIn(): Boolean = System.getProperty(FLAG) == "true"

    fun distroOptedIn(): Boolean = System.getProperty(DISTRO_FLAG) == "true"

    fun reachable(port: Int = PW_AND_KEY_PORT): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * True once [port] accepts a TCP connection AND greets with an SSH
     * banner — a Docker port proxy accepts connections before the container
     * process behind it is back, so a bare connect() is not "ready".
     */
    fun sshdAnswers(port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 1_000)
            s.soTimeout = 2_000
            String(s.getInputStream().readNBytes(8), Charsets.US_ASCII).startsWith("SSH-2.0")
        }
    } catch (_: Exception) {
        false
    }

    fun waitForSshd(port: Int, timeoutMs: Long = 30_000) {
        awaitTrue("sshd on 127.0.0.1:$port did not come back within ${timeoutMs}ms", timeoutMs) { sshdAnswers(port) }
    }

    /**
     * Opt-in gate shared by the distro variants and the alternate servers:
     * skips unless the default matrix is opted in; with [DISTRO_FLAG] a
     * missing instance FAILS (CI started them all), otherwise one that is
     * not running just skips — a developer may bring up one at a time.
     */
    private fun requireInstance(label: String, port: Int, startAll: String, startOne: String) {
        assumeTrue("opt-in test: pass -D$FLAG=true", optedIn())
        if (distroOptedIn()) {
            assertTrue("$label not reachable on 127.0.0.1:$port — start it: $startAll", reachable(port))
        } else {
            assumeTrue("$label not running ($startOne)", reachable(port))
        }
    }

    /** Distro-variant gate (tools/sshd-matrix/run.sh --variant NAME). */
    fun requireVariant(v: Variant) = requireInstance(
        "variant $v",
        v.pwPort,
        "tools/sshd-matrix/run.sh --variants",
        "tools/sshd-matrix/run.sh --variant ${v.name}",
    )

    /**
     * Alternate-server gate (tools/sshd-matrix/run.sh --server NAME). [port]
     * is the server port under test (a server does not run every role — 0
     * means "this server has no such instance", which always skips).
     */
    fun requireServer(s: Server, port: Int) {
        assumeTrue("server ${s.name} has no instance for this role", port != 0)
        requireInstance(
            "server ${s.name}",
            port,
            "tools/sshd-matrix/run.sh --servers",
            "tools/sshd-matrix/run.sh --server ${s.name}",
        )
    }

    /**
     * A matrix port that is optional on some bases (the legacy SHA-1 instance
     * is refused to start by OpenSSH 10; the PAM instance is absent where the
     * package is): opted-in but unreachable SKIPS rather than fails,
     * because the fixture itself documents "not available on this base".
     */
    fun requireOptionalInstance(port: Int, why: String) {
        requireMatrix()
        assumeTrue("optional instance on :$port not up ($why)", reachable(port))
    }

    /**
     * Runs the docker CLI on the host that runs the tests (the same daemon
     * that started the matrix) — used to restart/pause the container, add
     * tc netem qdiscs and spawn throwaway containers. Fails loudly on a
     * non-zero exit so a broken fixture never masquerades as an app bug.
     */
    fun docker(vararg args: String, timeoutMs: Long = 60_000, allowFailure: Boolean = false): String {
        val proc = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            throw AssertionError("docker ${args.joinToString(" ")} timed out: $out")
        }
        if (proc.exitValue() != 0 && !allowFailure) {
            throw AssertionError("docker ${args.joinToString(" ")} failed (${proc.exitValue()}): $out")
        }
        return out
    }

    /** `docker exec` in the default container (root). */
    fun dockerExec(command: String, container: String = CONTAINER_NAME, allowFailure: Boolean = false): String =
        docker("exec", container, "sh", "-c", command, allowFailure = allowFailure)

    /** The remote's OpenSSH version string, e.g. "OpenSSH_9.2p1 Debian-2+deb12u3". */
    fun sshVersion(ssh: SSHClient): String = exec(ssh, "ssh -V 2>&1").trim()

    fun requireMatrix() {
        assumeTrue("opt-in test: pass -D$FLAG=true", optedIn())
        assertTrue(
            "test sshd matrix not reachable on 127.0.0.1:$PW_AND_KEY_PORT — start it: tools/sshd-matrix/run.sh",
            reachable(),
        )
    }

    fun keyFile(name: String): File {
        val f = File(keysDir, name)
        assertTrue("missing $f — start the matrix: tools/sshd-matrix/run.sh", f.isFile)
        return f
    }

    /** The [PW_USER] password account on a matrix port, with any extra [Host] fields a test needs. */
    fun pwHost(port: Int = PW_AND_KEY_PORT, hostname: String = "127.0.0.1", configure: Host.() -> Unit = {}): Host =
        Host(hostname = hostname, username = PW_USER, authType = Host.AUTH_PASSWORD)
            .apply { this.port = port }
            .apply(configure)

    /** App-path connection (SshConnectionFactory) for an arbitrary [host] with matrix credentials. */
    fun connect(
        store: KnownHostsStore,
        host: Host,
        password: String? = PW_PASSWORD,
        keyFile: File? = null,
        prompt: KeyPrompt? = acceptPrompt,
    ): SSHClient = SshConnectionFactory.connect(
        host = host,
        prompt = prompt,
        store = store,
        keyProvider = { ssh, _ -> ssh.loadKeys(checkNotNull(keyFile) { "password auth in this test" }.absolutePath) },
        password = { password },
    )

    /** App-path connection (SshConnectionFactory) against a matrix port. */
    fun connect(
        store: KnownHostsStore,
        port: Int,
        user: String,
        authType: String = Host.AUTH_PASSWORD,
        password: String? = null,
        keyFile: File? = null,
        prompt: KeyPrompt? = acceptPrompt,
    ): SSHClient {
        val host = Host(
            hostname = "127.0.0.1",
            username = user,
            authType = authType,
            keyId = if (authType == Host.AUTH_KEY) "matrix-key" else null,
        ).apply { this.port = port }
        return connect(store, host, password, keyFile, prompt)
    }

    /** [PW_USER] / [PW_PASSWORD] on [port] — the connection most matrix tests start from. */
    fun connectPw(store: KnownHostsStore, port: Int = PW_AND_KEY_PORT, prompt: KeyPrompt? = acceptPrompt): SSHClient =
        connect(store, port, PW_USER, password = PW_PASSWORD, prompt = prompt)

    /** TOFU once (interactive) so later promptless, background-shaped connects through [store] succeed. */
    fun pinHostKey(store: KnownHostsStore, port: Int = PW_AND_KEY_PORT) {
        connectPw(store, port).use { }
    }

    /** Runs [block] on a fresh PTY shell of [ssh]; the session is closed afterwards. */
    fun <T> withPtyShell(
        ssh: SSHClient,
        term: String = "xterm-256color",
        cols: Int = 80,
        rows: Int = 24,
        block: (Session.Shell) -> T,
    ): T {
        val session = ssh.startSession()
        return try {
            session.allocatePTY(term, cols, rows, 0, 0, emptyMap())
            block(session.startShell())
        } finally {
            runCatching { session.close() }
        }
    }

    /** One-shot exec over an authenticated connection; returns full stdout. */
    fun exec(
        ssh: SSHClient,
        command: String,
        timeoutMs: Long = 15_000,
    ): String {
        ssh.startSession().use { session ->
            val cmd = session.exec(command)
            val holder = java.util.concurrent.atomic.AtomicReference("")
            val reader = Thread {
                val out = java.io.ByteArrayOutputStream()
                try {
                    val buf = ByteArray(4096)
                    val deadline = System.currentTimeMillis() + 120_000
                    // poll with available(): a blocking read() on sshj
                    // channel streams can hang when the channel closes
                    while (System.currentTimeMillis() < deadline) {
                        var progressed = false
                        for (stream in listOf(cmd.inputStream, cmd.errorStream)) {
                            while (stream.available() > 0) {
                                val n = stream.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                progressed = true
                            }
                        }
                        if (!cmd.isOpen) break
                        if (!progressed) Thread.sleep(20)
                    }
                } catch (_: Exception) {
                }
                holder.set(out.toString("UTF-8"))
            }
            reader.isDaemon = true
            reader.start()
            reader.join(timeoutMs)
            // close failure must not discard what was collected — the
            // captured output is the diagnostic for exactly this situation
            runCatching { cmd.close() }
            reader.join(2_000)
            return holder.get()
        }
    }
}
