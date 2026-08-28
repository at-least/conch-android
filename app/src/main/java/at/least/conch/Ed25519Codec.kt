package at.least.conch

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.security.SecureRandom

/**
 * Pure (JVM-testable) encoding helpers for Ed25519 keys in PKCS#8 / X.509 DER,
 * plus keypair generation via the BouncyCastle lightweight API (works on all
 * Android API levels, independent of JCA provider availability).
 */
object Ed25519Codec {

    /** DER header for a PKCS#8 Ed25519 private key wrapping the raw 32-byte seed. */
    val PKCS8_PREFIX = hexToBytes("302e020100300506032b657004220420")

    /** DER header for an X.509 Ed25519 public key wrapping the raw 32-byte point. */
    val X509_PREFIX = hexToBytes("302a300506032b6570032100")

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(
            hex.length / 2
        ) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

    fun pkcs8FromSeed(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "seed must be 32 bytes" }
        return PKCS8_PREFIX + seed
    }

    /**
     * Seed from a PKCS#8 Ed25519 key. Accepts the minimal v1 shape this
     * codec writes AND RFC 8410 v2 blobs (attributes and/or the embedded
     * public key, as some tools emit): the seed is always the 32-byte
     * OCTET STRING nested in the privateKey OCTET STRING (`04 22 04 20`),
     * and the algorithm must be the Ed25519 OID.
     */
    fun seedFromPkcs8(pkcs8: ByteArray): ByteArray? {
        if (pkcs8.size == 48 && pkcs8.copyOfRange(0, 16).contentEquals(PKCS8_PREFIX)) {
            return pkcs8.copyOfRange(16, 48)
        }
        if (indexOf(pkcs8, ED25519_ALGORITHM) < 0) return null
        val at = indexOf(pkcs8, CURVE_PRIVATE_KEY_TAG)
        if (at < 0 || at + CURVE_PRIVATE_KEY_TAG.size + 32 > pkcs8.size) return null
        return pkcs8.copyOfRange(at + CURVE_PRIVATE_KEY_TAG.size, at + CURVE_PRIVATE_KEY_TAG.size + 32)
    }

    /** AlgorithmIdentifier for id-Ed25519 (1.3.101.112). */
    private val ED25519_ALGORITHM = hexToBytes("300506032b6570")

    /** OCTET STRING(34) { OCTET STRING(32) } — the CurvePrivateKey wrapper. */
    private val CURVE_PRIVATE_KEY_TAG = hexToBytes("04220420")

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    fun x509FromPublic(publicPoint: ByteArray): ByteArray {
        require(publicPoint.size == 32) { "public point must be 32 bytes" }
        return X509_PREFIX + publicPoint
    }

    fun publicFromX509(x509: ByteArray): ByteArray? {
        if (x509.size != 44) return null
        if (!x509.copyOfRange(0, 12).contentEquals(X509_PREFIX)) return null
        return x509.copyOfRange(12, 44)
    }

    /** Generates a fresh Ed25519 keypair: (seed, publicPoint). */
    fun generateKeyPair(random: SecureRandom = SecureRandom()): Pair<ByteArray, ByteArray> {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(random))
        val kp = gen.generateKeyPair()
        val priv = kp.private as Ed25519PrivateKeyParameters
        val pub = kp.public as Ed25519PublicKeyParameters
        return priv.encoded to pub.encoded
    }

    fun derivePublic(seed: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(seed).generatePublicKey().encoded

    // ------------------------------------------------- OpenSSH private key PEM

    private fun u32(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(),
        (v ushr 16).toByte(),
        (v ushr 8).toByte(),
        v.toByte()
    )

    private fun sshStr(b: ByteArray): ByteArray = u32(b.size) + b

    /** ssh wire blob for an ed25519 key: string "ssh-ed25519" + string publicPoint. */
    fun ed25519SshBlob(publicPoint: ByteArray): ByteArray =
        sshStr("ssh-ed25519".toByteArray()) + sshStr(publicPoint)

    /**
     * Builds an unencrypted OpenSSH v1 private key file ("BEGIN OPENSSH PRIVATE KEY")
     * for the given seed — the format sshj's OpenSSHKeyFile loads natively for ed25519.
     */
    fun openSshPrivateKeyPem(seed: ByteArray, publicPoint: ByteArray, comment: String): String {
        require(seed.size == 32 && publicPoint.size == 32)
        // The checkint pair is round-trip integrity, not a secret, but the
        // rest of this file uses SecureRandom — no reason to mix RNGs.
        val check = SecureRandom().nextInt()

        var priv = u32(check) + u32(check) +
            sshStr("ssh-ed25519".toByteArray()) +
            sshStr(publicPoint) +
            sshStr(seed + publicPoint) +
            sshStr(comment.toByteArray())
        var pad = 1
        while (priv.size % 8 != 0) {
            priv += pad.toByte()
            pad++
        }

        val blob = "openssh-key-v1\u0000".toByteArray() +
            sshStr("none".toByteArray()) +
            sshStr("none".toByteArray()) +
            sshStr(ByteArray(0)) +
            u32(1) +
            sshStr(ed25519SshBlob(publicPoint)) +
            sshStr(priv)

        val b64 = java.util.Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(blob)
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
    }
}
