package at.least.conch

import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * ProxyJump through a real second server (improvement plan 2.1): the target
 * handshake and auth run inside a direct channel of the jump server; both
 * host keys are TOFU-verified; tearing the returned client down must close
 * the jump transport too. Parity driver: Termius paywalls host chaining,
 * ConnectBot only added ProxyJump in Dec 2025.
 */
class ProxyJumpInteractionTest {

    private fun connect(
        target: TestSshd,
        jump: TestSshd,
        host: Host,
        jumpHost: Host,
        store: KnownHostsStore,
        jumpPw: String? = jump.password,
    ): SSHClient {
        host.port = target.port
        jumpHost.port = jump.port
        return SshConnectionFactory.connect(
            host = host,
            prompt = null,
            store = store,
            keyProvider = { _, _ -> throw IllegalStateException("no key in this test") },
            password = { h -> if (h === jumpHost) jumpPw else target.password },
            jumpHost = jumpHost,
        )
    }

    @Test
    fun `target authenticates through the jump server`() {
        val jump = TestSshd(
            user = "jumper",
            password = "jumppw",
            execHandler = { cmd -> ExecResult("jump-only: $cmd\n".toByteArray()) },
        )
        val target = TestSshd(
            user = "target",
            password = "targetpw",
            execHandler = { cmd -> ExecResult("target-reached: $cmd\n".toByteArray()) },
        )
        jump.use { t ->
            target.use { g ->
                t.start()
                g.start()
                val store = KnownHostsStore(Files.createTempDirectory("conch-jump").toFile())
                store.add("127.0.0.1", t.port, t.hostPublicKey)
                store.add("127.0.0.1", g.port, g.hostPublicKey)

                val host = Host(hostname = "127.0.0.1", username = "target", authType = Host.AUTH_PASSWORD)
                val jumpHost = Host(hostname = "127.0.0.1", username = "jumper", authType = Host.AUTH_PASSWORD)
                val ssh = connect(g, t, host, jumpHost, store)
                try {
                    val session = ssh.startSession()
                    val cmd = session.exec("echo JUMP_OK")
                    assertEquals("target-reached: echo JUMP_OK", cmd.inputStream.readBytes().decodeToString().trim())
                    cmd.close()
                    session.close()
                } finally {
                    ssh.disconnect()
                }
            }
        }
    }

    @Test
    fun `jump auth failure surfaces as an error`() {
        val jump = TestSshd(user = "jumper", password = "jumppw")
        val target = TestSshd(user = "target", password = "targetpw")
        jump.use { t ->
            target.use { g ->
                t.start()
                g.start()
                val store = KnownHostsStore(Files.createTempDirectory("conch-jump").toFile())
                store.add("127.0.0.1", t.port, t.hostPublicKey)
                store.add("127.0.0.1", g.port, g.hostPublicKey)

                val host = Host(hostname = "127.0.0.1", username = "target", authType = Host.AUTH_PASSWORD)
                val jumpHost = Host(hostname = "127.0.0.1", username = "jumper", authType = Host.AUTH_PASSWORD)
                var threw = false
                try {
                    connect(g, t, host, jumpHost, store, jumpPw = "wrong-password")
                } catch (_: Exception) {
                    threw = true
                }
                assertTrue(threw)
            }
        }
    }

    /** The returned client's disconnect() must also close the jump client. */
    @Test
    fun `disconnecting the target closes the jump transport`() {
        val jump = TestSshd(user = "jumper", password = "jumppw")
        val target = TestSshd(user = "target", password = "targetpw")
        jump.use { t ->
            target.use { g ->
                t.start()
                g.start()
                val store = KnownHostsStore(Files.createTempDirectory("conch-jump").toFile())
                store.add("127.0.0.1", t.port, t.hostPublicKey)
                store.add("127.0.0.1", g.port, g.hostPublicKey)

                val host = Host(hostname = "127.0.0.1", username = "target", authType = Host.AUTH_PASSWORD)
                val jumpHost = Host(hostname = "127.0.0.1", username = "jumper", authType = Host.AUTH_PASSWORD)
                val ssh = connect(g, t, host, jumpHost, store)
                ssh.disconnect()
                awaitNoSessions(t)
            }
        }
    }

    /** Polls until the server reports no sessions, or fails after 10s. */
    private fun awaitNoSessions(server: TestSshd) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (server.server.activeSessions.isEmpty()) return
            Thread.sleep(50)
        }
        assertTrue(
            "jump-client session leaked after target disconnect",
            server.server.activeSessions.isEmpty(),
        )
    }
}
