package at.least.conch

import net.schmizz.sshj.SSHClient
import org.apache.sshd.server.SshServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * RSA / ECDSA key matrix (improvement plan 1.1 / 1.2): these algorithms
 * flow through import() via sshj and are persisted as PKCS#8 PEM exactly
 * like KeyManager.persist()'s non-Ed25519 branch — these tests pin that
 * whole path (parse, public-line derivation, fingerprint, real wire auth
 * against the in-process sshd) for keys the app has never explicitly
 * supported in its README.
 *
 * Parity drivers: JuiceSSH lost users over ed25519/key-format gaps;
 * ConnectBot's top historical issues were pubkey-related.
 */
class KeyAlgorithmMatrixTest {

    private fun pkcs8Pem(pair: KeyPair): String =
        "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"

    private fun rsa(bits: Int): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    private fun ec(curve: String): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    /** import-path mirror + real wire auth for one generated keypair. */
    private fun roundTripAndAuth(expectedAlgorithm: String, pair: KeyPair) {
        val pem = pkcs8Pem(pair)
        val tmp = File.createTempFile("matrix", ".key")
        try {
            tmp.writeText(pem)
            // mirrors import(): sshj parses, public line derived from the
            // loaded key (persist()'s fallbackPublicKey branch)
            val probe = SSHClient()
            val provider = probe.loadKeys(tmp.absolutePath)
            provider.private // force the lazy parse
            val publicLine = KeyManager.publicLineFor(provider.public!!, expectedAlgorithm)
            assertTrue(
                "expected $expectedAlgorithm, derived: ${publicLine.take(30)}",
                publicLine.startsWith("$expectedAlgorithm ")
            )
            val fingerprint = KeyManager.fingerprintOf(publicLine)
            assertTrue(fingerprint.startsWith("SHA256:"))

            TestSshd(authorizedKeys = listOf(pair.public), password = null).use { sshd ->
                sshd.start()
                val store = KnownHostsStore(Files.createTempDirectory("conch-matrix").toFile())
                val ssh = connectTrusted(
                    sshd,
                    store,
                    host = Host(
                        hostname = "127.0.0.1",
                        username = sshd.user,
                        authType = Host.AUTH_KEY,
                        keyId = "matrix-key",
                    ),
                    keyProvider = { client, _ -> client.loadKeys(tmp.absolutePath) },
                )
                try {
                    val session = ssh.startSession()
                    val cmd = session.exec("echo MATRIX_OK")
                    val out = cmd.inputStream.readBytes().decodeToString().trim()
                    cmd.close()
                    session.close()
                    assertEquals("echo MATRIX_OK", out)
                } finally {
                    ssh.disconnect()
                }
            }
        } finally {
            tmp.delete()
        }
    }

    /**
     * Policy shared with iOS: RSA parses (sshj can), but import refuses it
     * and a stored RSA key (backup restore) is never usable for login.
     */
    @Test
    fun `rsa parses but the import policy rejects it`() {
        val tmp = File.createTempFile("matrix", ".key")
        try {
            tmp.writeText(pkcs8Pem(rsa(2048)))
            val provider = SSHClient().loadKeys(tmp.absolutePath)
            val algorithm = net.schmizz.sshj.common.KeyType.fromKey(provider.public).toString()
            assertEquals("ssh-rsa", algorithm)
            assertFalse(KeyPolicy.isLoginSupported(algorithm))
            val e = runCatching { KeyPolicy.requireLoginSupported(algorithm) }.exceptionOrNull()
            assertTrue(e is IllegalArgumentException)
            assertEquals(KeyPolicy.RSA_NOT_FOR_LOGIN, e!!.message)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `an rsa key chosen for a host is a terminal connect failure like a missing key`() {
        val reason = "${KeyManager.MISSING_KEY_PREFIX} 'old' is an RSA key — ${KeyPolicy.RSA_NOT_FOR_LOGIN}"
        assertTrue(SshSession.isTerminalFailure(reason))
        assertTrue(KeyPolicy.isLoginSupported("ssh-ed25519"))
        assertTrue(KeyPolicy.isLoginSupported("ecdsa-sha2-nistp256"))
    }

    @Test
    fun `ecdsa p-256 imports and authenticates`() =
        roundTripAndAuth("ecdsa-sha2-nistp256", ec("secp256r1"))

    @Test
    fun `ecdsa p-384 imports and authenticates`() =
        roundTripAndAuth("ecdsa-sha2-nistp384", ec("secp384r1"))

    /** RSA host key server (Dropbear-style parity: host key type mismatch). */
    @Test
    fun `ed25519 user key authenticates against an rsa host key server`() {
        val key = newTestKey()
        try {
            TestSshd(
                authorizedKeys = listOf(key.publicKey),
                password = null,
                hostKeyPair = TestSshd.hostKeyRsa(),
            ).use { sshd ->
                sshd.start()
                val store = KnownHostsStore(Files.createTempDirectory("conch-matrix").toFile())
                val ssh = connectTrusted(
                    sshd,
                    store,
                    host = Host(
                        hostname = "127.0.0.1",
                        username = sshd.user,
                        authType = Host.AUTH_KEY,
                        keyId = "k",
                    ),
                    keyProvider = { client, _ -> client.loadKeys(key.file.absolutePath) },
                )
                try {
                    val session = ssh.startSession()
                    val cmd = session.exec("echo RSAHOST_OK")
                    assertEquals("echo RSAHOST_OK", cmd.inputStream.readBytes().decodeToString().trim())
                    cmd.close()
                    session.close()
                } finally {
                    ssh.disconnect()
                }
            }
        } finally {
            key.close()
        }
    }

    // ------------------------------------------------- hardened sshd (1.2)

    private fun execPasswordOk(sshd: TestSshd, marker: String) {
        val store = KnownHostsStore(Files.createTempDirectory("conch-hardened").toFile())
        val ssh = connectTrusted(sshd, store)
        try {
            val session = ssh.startSession()
            val cmd = session.exec("echo $marker")
            assertEquals("echo $marker", cmd.inputStream.readBytes().decodeToString().trim())
            cmd.close()
            session.close()
        } finally {
            ssh.disconnect()
        }
    }

    @Test
    fun `connects when server only offers hmac-sha2-512-etm mac`() {
        TestSshd(
            configure = { srv: SshServer ->
                val etm = srv.macFactories.filter { it.name == "hmac-sha2-512-etm@openssh.com" }
                check(etm.isNotEmpty()) {
                    "MINA has no hmac-sha2-512-etm: ${srv.macFactories.map { it.name }}"
                }
                srv.macFactories = etm
            },
        ).use { sshd ->
            sshd.start()
            execPasswordOk(sshd, "ETM_OK")
        }
    }

    @Test
    fun `connects when server only offers curve25519 kex`() {
        TestSshd(
            configure = { srv: SshServer ->
                val kex = srv.keyExchangeFactories.filter { it.name.contains("curve25519") }
                check(kex.isNotEmpty()) {
                    "MINA has no curve25519 kex: ${srv.keyExchangeFactories.map { it.name }}"
                }
                srv.keyExchangeFactories = kex
            },
        ).use { sshd ->
            sshd.start()
            execPasswordOk(sshd, "KEX_OK")
        }
    }

    @Test
    fun `connects when server only offers aes256-gcm cipher`() {
        TestSshd(
            configure = { srv: SshServer ->
                val gcm = srv.cipherFactories.filter { it.name.contains("aes256-gcm") }
                check(gcm.isNotEmpty()) {
                    "MINA has no aes256-gcm cipher: ${srv.cipherFactories.map { it.name }}"
                }
                srv.cipherFactories = gcm
            },
        ).use { sshd ->
            sshd.start()
            execPasswordOk(sshd, "GCM_OK")
        }
    }
}
