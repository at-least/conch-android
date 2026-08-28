package at.least.conch

import net.schmizz.sshj.SSHClient
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

    /** host port → container 2227: only listens for 8 s after the UDP knock sequence. */
    const val GATED_PORT = 2237

    /** UDP knock sequence knockd watches for (same numbers on host and in the container). */
    val KNOCK_PORTS = listOf(2260, 2261, 2262)

    /** sshd port INSIDE the container (targets of direct-tcpip / inner ssh). */
    const val CONTAINER_SSH_PORT = 2223

    /** Forwarding-enabled sshd port INSIDE the container. */
    const val CONTAINER_FWD_PORT = 2225

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
    ) {
        val keyOnlyPort: Int get() = pwPort + 1
        val forwardingPort: Int get() = pwPort + 2
        override fun toString() = "$name ($base)"
    }

    /** The default container as a matrix row (bookworm, ports 2233..2235). */
    val DEFAULT_VARIANT = Variant("bookworm", "debian:bookworm-slim", PW_AND_KEY_PORT, CONTAINER_NAME)

    /** Keep in step with VARIANTS in run.sh. */
    val VARIANTS = listOf(
        Variant("ubuntu2004", "ubuntu:20.04", 2243),
        Variant("ubuntu2404", "ubuntu:24.04", 2246),
        Variant("alpine", "alpine:3.20", 2249),
        Variant("trixie", "debian:trixie-slim", 2252),
        Variant("rocky9", "rockylinux:9", 2255),
    )

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
            val buf = ByteArray(8)
            var got = 0
            while (got < buf.size) {
                val n = s.getInputStream().read(buf, got, buf.size - got)
                if (n < 0) break
                got += n
            }
            String(buf, 0, got, Charsets.US_ASCII).startsWith("SSH-2.0")
        }
    } catch (_: Exception) {
        false
    }

    fun waitForSshd(port: Int, timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (sshdAnswers(port)) return
            Thread.sleep(250)
        }
        throw AssertionError("sshd on 127.0.0.1:$port did not come back within ${timeoutMs}ms")
    }

    /**
     * Distro-variant gate: skips unless the default matrix is opted in; with
     * [DISTRO_FLAG] a missing variant FAILS (CI started them all), otherwise
     * a variant that is not running just skips — a developer may bring up
     * one base at a time (run.sh --variant NAME).
     */
    fun requireVariant(v: Variant) {
        assumeTrue("opt-in test: pass -D$FLAG=true", optedIn())
        if (distroOptedIn()) {
            assertTrue(
                "variant $v not reachable on 127.0.0.1:${v.pwPort} — start it: tools/sshd-matrix/run.sh --variants",
                reachable(v.pwPort),
            )
        } else {
            assumeTrue("variant $v not running (tools/sshd-matrix/run.sh --variant ${v.name})", reachable(v.pwPort))
        }
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
        return SshConnectionFactory.connect(
            host = host,
            prompt = prompt,
            store = store,
            keyProvider = { ssh, _ -> ssh.loadKeys(keyFile!!.absolutePath) },
            password = { password },
        )
    }

    /** One-shot exec over an authenticated connection; returns full stdout. */
    fun exec(
        ssh: SSHClient,
        command: String,
        timeoutMs: Long = 15_000,
        forwardAgent: Boolean = false,
    ): String {
        ssh.startSession().use { session ->
            if (forwardAgent) AgentForwarding.requestOn(session)
            val cmd = session.exec(command)
            val holder = java.util.concurrent.atomic.AtomicReference("")
            val reader = Thread {
                val out = java.io.ByteArrayOutputStream()
                try {
                    val buf = ByteArray(4096)
                    val deadline = System.currentTimeMillis() + 120_000
                    // poll with available(): blocking read() on sshj channel
                    // streams deadlocks when an agent-forwarding request is
                    // pending on the same channel
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
