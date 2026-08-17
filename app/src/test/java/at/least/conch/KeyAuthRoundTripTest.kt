package at.least.conch

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Round-trip: generate a key with Ed25519Codec, persist it exactly like
 * KeyManager.persist() does (PKCS#8 PEM), load it back with sshj, and check
 * the ssh wire public key matches the publicLine blob.
 * Requires no network. Run: ./gradlew test --tests '*.KeyAuthRoundTripTest'
 */
class KeyAuthRoundTripTest {

    @Test
    fun `openssh pem loads in sshj and matches public line`() {
        val (seed, publicPoint) = Ed25519Codec.generateKeyPair()
        val pem = Ed25519Codec.openSshPrivateKeyPem(seed, publicPoint, "roundtrip")

        val tmp = File.createTempFile("conch", ".key")
        try {
            tmp.writeText(pem)
            val probe = SSHClient()
            val provider = probe.loadKeys(tmp.absolutePath)

            assertEquals("ssh-ed25519", provider.type.toString())

            // wire-format public key straight from the loaded keypair
            val fromLoaded = net.schmizz.sshj.common.Buffer.PlainBuffer()
                .apply { putPublicKey(provider.public) }
                .getCompactData()

            val expected = Ed25519Codec.ed25519SshBlob(publicPoint)

            assertTrue(
                "public key mismatch",
                fromLoaded.contentEquals(expected)
            )
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `openssh pem accepted by openssh-keygen`() {
        val (seed, publicPoint) = Ed25519Codec.generateKeyPair()
        val pem = Ed25519Codec.openSshPrivateKeyPem(seed, publicPoint, "kg")
        val tmp = File.createTempFile("conch", ".key")
        try {
            tmp.writeText(pem)
            java.nio.file.Files.setPosixFilePermissions(
                tmp.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            )
            val p = ProcessBuilder("ssh-keygen", "-y", "-f", tmp.absolutePath)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.readBytes().decodeToString().trim()
            p.waitFor()
            assertTrue("ssh-keygen output: $out", out.startsWith("ssh-ed25519 "))
            val expected = KeyManager.publicLineFor("ssh-ed25519", publicPoint, "kg")
                .trim().split(" ").take(2).joinToString(" ")
            val actual = out.split(" ").take(2).joinToString(" ")
            assertTrue("derived public differs:\nssh-keygen: [$actual]\nexpected:   [$expected]", actual == expected)
        } finally {
            tmp.delete()
        }
    }
}
