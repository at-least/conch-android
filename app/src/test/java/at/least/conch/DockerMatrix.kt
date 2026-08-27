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

    /** sshd port INSIDE the container (targets of direct-tcpip / inner ssh). */
    const val CONTAINER_SSH_PORT = 2223

    const val FLAG = "conch.localSshdTest"

    val keysDir: File = File(
        System.getenv("CONCH_ANDROID_MATRIX_KEYS")
            ?: "${System.getProperty("user.home")}/.cache/conch-android/sshd-matrix/keys",
    )

    /** TOFU prompt that accepts any unknown host key (first-connect UX). */
    val acceptPrompt: KeyPrompt = { _, done -> done(true) }

    fun optedIn(): Boolean = System.getProperty(FLAG) == "true"

    fun reachable(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PW_AND_KEY_PORT), 1_000) }
        true
    } catch (_: Exception) {
        false
    }

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
