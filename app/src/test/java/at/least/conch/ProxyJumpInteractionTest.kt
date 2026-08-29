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

    // ---- multi-hop (iOS parity) -------------------------------------------

    private companion object {
        fun pwHost(alias: String, user: String, port: Int, jump: String? = null) = Host(
            alias = alias,
            hostname = "127.0.0.1",
            username = user,
            authType = Host.AUTH_PASSWORD,
            jumpHostId = jump,
        ).apply { this.port = port }
    }

    private class Chain : AutoCloseable {
        val hop1 = TestSshd(user = "u1", password = "pw1", hostKeyPair = TestSshd.hostKeyRsa()).start()
        val hop2 = TestSshd(user = "u2", password = "pw2", hostKeyPair = TestSshd.hostKeyEc()).start()
        val target = TestSshd(
            user = "u3",
            password = "pw3",
            execHandler = { cmd -> ExecResult("target-reached: $cmd\n".toByteArray()) },
        ).start()
        val h1 = pwHost("bastion", "u1", hop1.port)
        val h2 = pwHost("dmz", "u2", hop2.port, jump = h1.id)
        val h3 = pwHost("web", "u3", target.port, jump = h2.id)
        val store = KnownHostsStore(Files.createTempDirectory("conch-chain").toFile())

        val goodPasswords: Map<String, String?> get() = mapOf(h1.id to "pw1", h2.id to "pw2", h3.id to "pw3")

        fun connect(passwords: Map<String, String?> = goodPasswords): SSHClient {
            val jumps = ProxyJumpResolver.chain(h3, listOf(h1, h2, h3))!!
            return SshConnectionFactory.connect(
                host = h3,
                prompt = { _, done -> done(true) }, // TOFU-accept every hop
                store = store,
                keyProvider = { _, _ -> throw IllegalStateException("no key in this test") },
                password = { h -> passwords[h.id] },
                jumps = jumps,
            )
        }

        override fun close() {
            hop1.close()
            hop2.close()
            target.close()
        }
    }

    @Test(timeout = 60_000)
    fun `two-hop chain reaches the target and TOFU-pins every hop's own key`() {
        Chain().use { c ->
            val ssh = c.connect()
            try {
                val session = ssh.startSession()
                val cmd = session.exec("echo CHAIN_OK")
                assertEquals("target-reached: echo CHAIN_OK", cmd.inputStream.readBytes().decodeToString().trim())
                cmd.close()
                session.close()
            } finally {
                ssh.disconnect()
            }
            // one entry per endpoint, each with THAT hop's key type
            val byPort = c.store.file.readLines().filter { it.isNotBlank() }.associate { line ->
                val (hostField, alg) = line.split(" ")
                hostField.substringAfterLast(':').toInt() to alg
            }
            assertEquals(setOf(c.hop1.port, c.hop2.port, c.target.port), byPort.keys)
            assertEquals("ssh-rsa", byPort[c.hop1.port])
            assertEquals("ecdsa-sha2-nistp256", byPort[c.hop2.port])
            assertEquals("ssh-ed25519", byPort[c.target.port])
            // disconnecting the target tore down both jump transports
            awaitNoSessions(c.hop1)
            awaitNoSessions(c.hop2)
        }
    }

    @Test(timeout = 60_000)
    fun `an auth failure on the middle hop names that hop and leaks nothing`() {
        Chain().use { c ->
            val e = runCatching { c.connect(mapOf(c.h1.id to "pw1", c.h2.id to "WRONG", c.h3.id to "pw3")) }
                .exceptionOrNull()
            assertTrue("expected UserAuthException, got $e", e is net.schmizz.sshj.userauth.UserAuthException)
            val msg = SshConnectionFactory.describeError(e as Exception)
            assertTrue("hop not named: $msg", msg.contains("jump host 'dmz'"))
            assertTrue(SshSession.isTerminalFailure(msg))
            // hop1 was authenticated before hop2 failed: it must be closed again
            awaitNoSessions(c.hop1)
            awaitNoSessions(c.hop2)
        }
    }

    @Test
    fun `a missing password on a hop is a host-config error naming the hop`() {
        Chain().use { c ->
            val e = runCatching { c.connect(mapOf(c.h1.id to null, c.h2.id to "pw2", c.h3.id to "pw3")) }
                .exceptionOrNull()
            val msg = SshConnectionFactory.describeError(e as Exception)
            assertTrue(msg, msg.startsWith(SshConnectionFactory.HOST_CONFIG_PREFIX))
            assertTrue(msg, msg.contains("jump host 'bastion'"))
            assertTrue(SshSession.isTerminalFailure(msg))
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
