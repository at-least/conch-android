package at.least.conch

import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Mirrors KeyManager.import(): sshj loads a PEM -> we take provider.private.encoded
 * (claimed PKCS#8) and re-derive the seed. Verifies the round-trip that real
 * imports depend on for ed25519 keys stored in OpenSSH v1 format.
 */
class KeyImportPathTest {

    @Test
    fun `sshj-loaded ed25519 private key encodes back to usable pkcs8 seed`() {
        val (seed, pub) = Ed25519Codec.generateKeyPair()
        val pem = Ed25519Codec.openSshPrivateKeyPem(seed, pub, "import-test")

        val tmp = File.createTempFile("conchimp", ".key")
        try {
            tmp.writeText(pem)
            val provider = SSHClient().loadKeys(tmp.absolutePath)

            val priv = provider.private
            val encoded = priv.encoded
            checkNotNull(encoded) { "private.getEncoded() returned null for ${priv.javaClass}" }

            val extracted = Ed25519Codec.seedFromPkcs8(encoded)
            assertArrayEquals("seed must round-trip via getEncoded()", seed, extracted)

            // and the re-serialized OpenSSH PEM must still authenticate-parse
            val rePem = Ed25519Codec.openSshPrivateKeyPem(extracted!!, pub, "re")
            val tmp2 = File.createTempFile("reimport", ".key")
            try {
                tmp2.writeText(rePem)
                val p2 = SSHClient().loadKeys(tmp2.absolutePath)
                assertEquals("ssh-ed25519", p2.type.toString())
            } finally {
                tmp2.delete()
            }
        } finally {
            tmp.delete()
        }
    }
}
