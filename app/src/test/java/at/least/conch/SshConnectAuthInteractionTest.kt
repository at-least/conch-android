package at.least.conch

import net.schmizz.sshj.userauth.UserAuthException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Real connection + authentication against the in-process sshd, through the
 * same SshConnectionFactory code path the app uses (Android storage injected).
 */
class SshConnectAuthInteractionTest {

    private lateinit var dir: File
    private lateinit var server: TestSshd

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conch-test").toFile()
        server = TestSshd().start()
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun host(authType: String) = Host(
        hostname = "127.0.0.1",
        username = server.user,
        authType = authType,
    )

    @Test(timeout = 30_000)
    fun `password auth connects and authenticates`() {
        val ssh = connectTrusted(server, KnownHostsStore(dir), host(Host.AUTH_PASSWORD))
        try {
            assertTrue("client should be authenticated", ssh.isAuthenticated)
            assertTrue("transport should be connected", ssh.isConnected)
        } finally {
            ssh.disconnect()
        }
    }

    @Test(timeout = 30_000)
    fun `wrong password fails with auth error`() {
        try {
            connectTrusted(
                server, KnownHostsStore(dir), host(Host.AUTH_PASSWORD),
                password = "wrong-password",
            )
            fail("expected UserAuthException")
        } catch (e: UserAuthException) {
            val msg = SshConnectionFactory.describeError(e)
            assertTrue("describeError should mention auth failure: $msg", msg.startsWith("Authentication failed"))
        }
    }

    @Test(timeout = 30_000)
    fun `unknown user fails with auth error`() {
        val store = KnownHostsStore(dir)
        store.add("127.0.0.1", server.port, server.hostPublicKey)
        val h = host(Host.AUTH_PASSWORD)
        h.port = server.port
        h.username = "nosuchuser"
        try {
            SshConnectionFactory.connect(
                host = h,
                prompt = null,
                store = store,
                keyProvider = { _, _ -> throw IllegalStateException("no key") },
                password = { server.password ?: "" },
            )
            fail("expected UserAuthException")
        } catch (e: UserAuthException) {
            assertTrue(SshConnectionFactory.describeError(e).startsWith("Authentication failed"))
        }
    }

    @Test(timeout = 30_000)
    fun `ed25519 openssh key authenticates`() {
        newTestKey().use { key ->
            val keyServer = TestSshd(password = null, authorizedKeys = listOf(key.publicKey)).start()
            try {
                val ssh = connectTrusted(
                    keyServer, KnownHostsStore(dir),
                    host(Host.AUTH_KEY).apply { keyId = "k1" },
                    keyProvider = { s, _ -> s.loadKeys(key.file.absolutePath) },
                )
                try {
                    assertTrue(ssh.isAuthenticated)
                } finally {
                    ssh.disconnect()
                }
            } finally {
                keyServer.close()
            }
        }
    }

    @Test(timeout = 30_000)
    fun `ed25519 key not in authorized_keys is rejected`() {
        newTestKey().use { key ->
            newTestKey().use { authorized ->
                val keyServer = TestSshd(
                    password = null,
                    authorizedKeys = listOf(authorized.publicKey),
                ).start()
                try {
                    val store = KnownHostsStore(dir)
                    store.add("127.0.0.1", keyServer.port, keyServer.hostPublicKey)
                    val h = host(Host.AUTH_KEY)
                    h.port = keyServer.port
                    h.keyId = "k1"
                    try {
                        SshConnectionFactory.connect(
                            host = h,
                            prompt = null,
                            store = store,
                            keyProvider = { s, _ -> s.loadKeys(key.file.absolutePath) },
                            password = { "" },
                        )
                        fail("expected UserAuthException")
                    } catch (e: UserAuthException) {
                        assertTrue(SshConnectionFactory.describeError(e).startsWith("Authentication failed"))
                    }
                } finally {
                    keyServer.close()
                }
            }
        }
    }

    @Test(timeout = 30_000)
    fun `key auth without keyId fails fast`() {
        val store = KnownHostsStore(dir)
        store.add("127.0.0.1", server.port, server.hostPublicKey)
        val h = host(Host.AUTH_KEY)
        h.port = server.port
        h.keyId = null
        try {
            SshConnectionFactory.connect(
                host = h,
                prompt = null,
                store = store,
                keyProvider = { _, _ -> throw IllegalStateException("should not be called") },
                password = { "" },
            )
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Host is set to key auth but no key is selected", e.message)
        }
    }

    @Test(timeout = 30_000)
    fun `password auth without stored password fails fast`() {
        val store = KnownHostsStore(dir)
        store.add("127.0.0.1", server.port, server.hostPublicKey)
        val h = host(Host.AUTH_PASSWORD)
        h.port = server.port
        try {
            SshConnectionFactory.connect(
                host = h,
                prompt = null,
                store = store,
                keyProvider = { _, _ -> throw IllegalStateException("no key") },
                password = { null },
            )
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("No stored password — edit this host and save a password", e.message)
        }
    }

    @Test(timeout = 30_000)
    fun `keepAlive enabled sets 15 second interval`() {
        val h = host(Host.AUTH_PASSWORD).apply { keepAlive = true }
        val ssh = connectTrusted(server, KnownHostsStore(dir), h)
        try {
            assertEquals(15, ssh.connection.keepAlive.keepAliveInterval)
        } finally {
            ssh.disconnect()
        }
    }

    @Test(timeout = 30_000)
    fun `keepAlive disabled leaves interval unset`() {
        val h = host(Host.AUTH_PASSWORD).apply { keepAlive = false }
        val ssh = connectTrusted(server, KnownHostsStore(dir), h)
        try {
            assertEquals(0, ssh.connection.keepAlive.keepAliveInterval)
        } finally {
            ssh.disconnect()
        }
    }

    // ---------------------------------------- host-key algorithm matrix

    @Test(timeout = 30_000)
    fun `rsa host key completes handshake auth and exec`() {
        val rsaServer = TestSshd(hostKeyPair = TestSshd.hostKeyRsa()).start()
        try {
            val ssh = connectTrusted(rsaServer, KnownHostsStore(dir))
            try {
                assertTrue(ssh.isAuthenticated)
                val s = ssh.startSession()
                val cmd = s.exec("kex-check")
                assertEquals("kex-check\n", cmd.inputStream.readBytes().decodeToString())
                cmd.close()
                s.close()
            } finally {
                ssh.disconnect()
            }
        } finally {
            rsaServer.close()
        }
    }

    @Test(timeout = 30_000)
    fun `ecdsa host key completes handshake auth and exec`() {
        val ecServer = TestSshd(hostKeyPair = TestSshd.hostKeyEc()).start()
        try {
            val ssh = connectTrusted(ecServer, KnownHostsStore(dir))
            try {
                assertTrue(ssh.isAuthenticated)
                val s = ssh.startSession()
                val cmd = s.exec("kex-check")
                assertEquals("kex-check\n", cmd.inputStream.readBytes().decodeToString())
                cmd.close()
                s.close()
            } finally {
                ssh.disconnect()
            }
        } finally {
            ecServer.close()
        }
    }

    // ---------------------------------------------- connection soak test

    @Test(timeout = 300_000)
    fun `fifty connect auth exec disconnect cycles hold up`() {
        // sshj's LoadsOfConnects idea: catches thread/socket leaks in the
        // connection path (app background sessions, widget, SessionService)
        val store = KnownHostsStore(dir)
        for (i in 1..50) {
            val ssh = connectTrusted(server, store)
            try {
                assertTrue("cycle $i not authenticated", ssh.isAuthenticated)
                val s = ssh.startSession()
                val cmd = s.exec("cycle $i")
                assertEquals("cycle $i\n", cmd.inputStream.readBytes().decodeToString())
                cmd.close()
                s.close()
            } finally {
                ssh.disconnect()
            }
        }
        assertEquals(50, server.recordedCommands.count { it.startsWith("cycle") })
    }
}
