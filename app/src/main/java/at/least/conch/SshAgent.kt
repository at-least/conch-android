package at.least.conch

import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateKey

/** One key the forwarded agent offers to the server side. */
class SshAgentIdentity(val blob: ByteArray, val comment: String)

/**
 * Key material for the on-device ssh-agent: what to list and how to sign.
 * [KeyManagerAgentSource][at.least.conch.KeyManagerAgentSource] is the app
 * implementation; tests inject in-memory sources.
 */
interface AgentKeySource {
    fun identities(): List<SshAgentIdentity>

    /**
     * SSH wire-format signature (`string algo, string sig`) for the key whose
     * wire blob matches [blob], or null when this source cannot serve it.
     */
    fun sign(blob: ByteArray, data: ByteArray, flags: Int): ByteArray?
}

/** draft-miller-ssh-agent message and flag constants. */
object SshAgentProtocol {
    const val AGENT_FAILURE = 5
    const val AGENT_IDENTITIES_ANSWER = 12
    const val AGENT_SIGN_RESPONSE = 14
    const val AGENT_REQUEST_IDENTITIES = 11
    const val AGENT_SIGN_REQUEST = 13
    const val FLAG_RSA_SHA2_256 = 2
    const val FLAG_RSA_SHA2_512 = 4
}

/**
 * Server side of the ssh-agent protocol as spoken over a forwarded
 * "auth-agent@openssh.com" channel: each message is a 4-byte big-endian
 * length followed by the payload. Supports listing identities and signing;
 * every other request fails. [serve] runs until the channel closes.
 */
class SshAgentServer(private val source: AgentKeySource) {

    /** Answers one decoded request payload; null to end the conversation. */
    fun respond(msg: ByteArray): ByteArray {
        return when (msg[0].toInt() and 0xFF) {
            SshAgentProtocol.AGENT_REQUEST_IDENTITIES -> identitiesAnswer()
            SshAgentProtocol.AGENT_SIGN_REQUEST -> signResponse(msg)
            else -> failure()
        }
    }

    fun serve(input: InputStream, output: OutputStream) {
        while (true) {
            val msg = readFramed(input, 1 shl 20) ?: return
            val reply = try {
                respond(msg)
            } catch (_: Exception) {
                failure()
            }
            output.write(frame(reply))
            output.flush()
        }
    }

    private fun identitiesAnswer(): ByteArray {
        val ids = source.identities()
        val body = byteArrayOf(SshAgentProtocol.AGENT_IDENTITIES_ANSWER.toByte()) + u32(ids.size)
        val parts = mutableListOf(body)
        for (id in ids) {
            parts += sshString(id.blob)
            parts += sshString(id.comment.toByteArray(Charsets.UTF_8))
        }
        return parts.reduce { a, b -> a + b }
    }

    private fun signResponse(msg: ByteArray): ByteArray {
        val blob = readSshString(msg, 1) ?: return failure()
        val data = readSshString(msg, 1 + 4 + blob.size) ?: return failure()
        val off = 1 + 4 + blob.size + 4 + data.size
        if (off + 4 > msg.size) return failure()
        val flags = u32At(msg, off).toInt()
        val sig = source.sign(blob, data, flags) ?: return failure()
        return byteArrayOf(SshAgentProtocol.AGENT_SIGN_RESPONSE.toByte()) + sshString(sig)
    }

    private fun failure(): ByteArray = byteArrayOf(SshAgentProtocol.AGENT_FAILURE.toByte())

    companion object {
        /** 4-byte length prefix + payload, the agent wire framing. */
        fun frame(payload: ByteArray): ByteArray = u32(payload.size) + payload

        /** Reads one framed message; null on EOF or oversized frame. */
        fun readFramed(input: InputStream, maxLen: Int): ByteArray? {
            val head = ByteArray(4)
            if (!readFully(input, head)) return null
            val len = u32At(head, 0).toInt()
            if (len <= 0 || len > maxLen) return null
            val msg = ByteArray(len)
            if (!readFully(input, msg)) return null
            return msg
        }

        fun readFully(input: InputStream, into: ByteArray): Boolean {
            var got = 0
            while (got < into.size) {
                val n = input.read(into, got, into.size - got)
                if (n < 0) return false
                got += n
            }
            return true
        }

        fun u32(v: Int): ByteArray = byteArrayOf(
            (v ushr 24).toByte(),
            (v ushr 16).toByte(),
            (v ushr 8).toByte(),
            v.toByte(),
        )

        fun u32At(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }

        /** SSH mpint-style string encoding: 4-byte length + bytes. */
        fun sshString(data: ByteArray): ByteArray = u32(data.size) + data

        /** Reads a 4-byte-length-prefixed byte string at [off]; null if truncated. */
        fun readSshString(b: ByteArray, off: Int): ByteArray? {
            if (off + 4 > b.size) return null
            val len = u32At(b, off) // Long — Int arithmetic would overflow the bounds check
            if (off + 4L + len > b.size) return null
            return b.copyOfRange(off + 4, (off + 4L + len).toInt())
        }
    }
}

/**
 * Produces SSH wire-format signatures with plain JCA (+ i2p eddsa for
 * Ed25519, as sshj itself uses): RSA honors the agent's rsa-sha2 flags
 * (falling back to ssh-rsa/SHA-1 only when no flag is given, matching
 * OpenSSH's agent), ECDSA converts JCA's DER signature to SSH's r||s.
 */
object SshAgentSigner {

    fun sign(privateKey: PrivateKey, data: ByteArray, flags: Int): ByteArray? = runCatching {
        when (privateKey) {
            is RSAPrivateKey -> {
                val (algo, wire) = when {
                    flags and SshAgentProtocol.FLAG_RSA_SHA2_512 != 0 ->
                        "SHA512withRSA" to "rsa-sha2-512"
                    flags and SshAgentProtocol.FLAG_RSA_SHA2_256 != 0 ->
                        "SHA256withRSA" to "rsa-sha2-256"
                    else -> "SHA1withRSA" to "ssh-rsa"
                }
                wireSignature(wire, jcaSign(algo, privateKey, data))
            }
            is ECPrivateKey -> {
                val bits = privateKey.params.curve.field.fieldSize
                val (algo, wire) = when {
                    bits <= 256 -> "SHA256withECDSA" to "ecdsa-sha2-nistp256"
                    bits <= 384 -> "SHA384withECDSA" to "ecdsa-sha2-nistp384"
                    else -> "SHA512withECDSA" to "ecdsa-sha2-nistp521"
                }
                val der = jcaSign(algo, privateKey, data)
                wireSignature(wire, derToRaw(der, (bits + 7) / 8))
            }
            is net.i2p.crypto.eddsa.EdDSAPrivateKey -> {
                val engine = net.i2p.crypto.eddsa.EdDSAEngine(
                    java.security.MessageDigest.getInstance("SHA-512"),
                ).apply { initSign(privateKey) }
                engine.update(data)
                wireSignature("ssh-ed25519", engine.sign())
            }
            else -> return@runCatching null
        }
    }.getOrNull()

    /** SSH signature blob: `string algorithm-name, string signature-bytes`. */
    private fun wireSignature(algorithm: String, signature: ByteArray): ByteArray =
        SshAgentServer.sshString(algorithm.toByteArray(Charsets.US_ASCII)) +
            SshAgentServer.sshString(signature)

    private fun jcaSign(algorithm: String, privateKey: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance(algorithm).apply {
            initSign(privateKey)
            update(data)
        }.sign()

    /** ASN.1 `SEQUENCE { INTEGER r, INTEGER s }` → fixed-width r||s. */
    internal fun derToRaw(der: ByteArray, coordLen: Int): ByteArray {
        // P-256 signatures use a short-form length header; P-384/P-521 content
        // exceeds 127 bytes and DER switches to long form (0x81/0x82 …)
        var i = 1
        val lenByte = der[i].toInt() and 0xFF
        i += if (lenByte and 0x80 == 0) {
            1
        } else {
            1 + (lenByte and 0x7F)
        }
        val r = readDerInt(der, i)
        val s = readDerInt(der, r.second)
        return fixedWidth(r.first, coordLen) + fixedWidth(s.first, coordLen)
    }

    /** (magnitude, next offset) of the DER INTEGER at [off]. */
    private fun readDerInt(der: ByteArray, off: Int): Pair<ByteArray, Int> {
        val len = der[off + 1].toInt() and 0xFF
        return der.copyOfRange(off + 2, off + 2 + len) to off + 2 + len
    }

    private fun fixedWidth(magnitude: ByteArray, len: Int): ByteArray {
        val n = BigInteger(1, magnitude)
        val out = ByteArray(len)
        val b = n.toByteArray()
        val src = if (b.size > len) b.copyOfRange(b.size - len, b.size) else b
        System.arraycopy(src, 0, out, len - src.size, src.size)
        return out
    }
}
