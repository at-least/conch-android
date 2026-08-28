package at.least.conch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class Ed25519CodecTest {

    @Test
    fun `generate returns 32-byte seed and public point`() {
        val (seed, pub) = Ed25519Codec.generateKeyPair()
        assertEquals(32, seed.size)
        assertEquals(32, pub.size)
    }

    @Test
    fun `pkcs8 roundtrip`() {
        val (seed, _) = Ed25519Codec.generateKeyPair()
        val pkcs8 = Ed25519Codec.pkcs8FromSeed(seed)
        assertEquals(48, pkcs8.size)
        assertArrayEquals(seed, Ed25519Codec.seedFromPkcs8(pkcs8))
    }

    @Test
    fun `x509 roundtrip`() {
        val (_, pub) = Ed25519Codec.generateKeyPair()
        val x509 = Ed25519Codec.x509FromPublic(pub)
        assertEquals(44, x509.size)
        assertArrayEquals(pub, Ed25519Codec.publicFromX509(x509))
    }

    @Test
    fun `derivePublic matches generated public key`() {
        val fixed = SecureRandom(byteArrayOf(1, 2, 3, 4))
        val (seed, pub) = Ed25519Codec.generateKeyPair(fixed)
        assertArrayEquals(pub, Ed25519Codec.derivePublic(seed))
    }

    @Test
    fun `garbage input returns null not exception`() {
        assertEquals(null, Ed25519Codec.seedFromPkcs8(ByteArray(48)))
        assertEquals(null, Ed25519Codec.publicFromX509(ByteArray(44)))
    }

    @Test
    fun `ssh blob is length-prefixed`() {
        val blob = KeyManager.sshBlob("ssh-ed25519", ByteArray(32) { 0x0A })
        // 4-byte length + "ssh-ed25519" + 4-byte length + 32
        assertEquals(4 + 11 + 4 + 32, blob.size)
        assertEquals(11, readU32(blob, 0))
        assertEquals("ssh-ed25519", String(blob, 4, 11))
        assertEquals(32, readU32(blob, 15))
    }

    @Test
    fun `public line and fingerprint roundtrip`() {
        val (_, pub) = Ed25519Codec.generateKeyPair()
        val line = KeyManager.publicLineFor("ssh-ed25519", pub, "test-key")
        val parts = line.trim().split(" ")
        assertEquals("ssh-ed25519", parts[0])
        assertEquals("test-key", parts[2])
        val fp = KeyManager.fingerprintOf(line)
        assertTrue(fp.startsWith("SHA256:"))
        assertEquals(43, fp.removePrefix("SHA256:").length) // 32 bytes base64 unpadded
    }

    private fun readU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    @Test
    fun `pkcs8 v2 with embedded public key yields the same seed`() {
        val (seed, pub) = Ed25519Codec.generateKeyPair()
        // RFC 8410 OneAsymmetricKey: version 1, attributes absent, publicKey [1]
        val v2 = org.bouncycastle.asn1.pkcs.PrivateKeyInfo(
            org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                org.bouncycastle.asn1.ASN1ObjectIdentifier("1.3.101.112"),
            ),
            org.bouncycastle.asn1.DEROctetString(seed),
            null,
            pub,
        ).encoded
        assertTrue("must not be the minimal v1 shape", v2.size != 48)
        assertArrayEquals(seed, Ed25519Codec.seedFromPkcs8(v2))
    }

    @Test
    fun `pkcs8 of another algorithm is rejected even when it contains an octet string`() {
        // X25519 (1.3.101.110) has the same CurvePrivateKey shape but is not an Ed25519 key
        val x = org.bouncycastle.asn1.pkcs.PrivateKeyInfo(
            org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                org.bouncycastle.asn1.ASN1ObjectIdentifier("1.3.101.110"),
            ),
            org.bouncycastle.asn1.DEROctetString(ByteArray(32) { 7 }),
        ).encoded
        assertEquals(null, Ed25519Codec.seedFromPkcs8(x))
    }
}
