package at.least.conch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * ssh-agent protocol core against hand-decoded frames: the wire format the
 * server-side ssh client speaks is whatever draft-miller-ssh-agent says, so
 * byte-level assertions here are the contract. Real end-to-end (OpenSSH
 * ssh-add / ssh sign-through) lives in DockerOpenSshIntegrationTest.
 */
class SshAgentProtocolTest {

    private class FixedSource(
        private val keyBlob: ByteArray?,
        private val privateKey: java.security.PrivateKey?,
    ) : AgentKeySource {
        override fun identities() =
            keyBlob?.let { listOf(SshAgentIdentity(it, "unit-key")) } ?: emptyList()

        override fun sign(blob: ByteArray, data: ByteArray, flags: Int): ByteArray? =
            if (keyBlob != null && blob.contentEquals(keyBlob)) {
                SshAgentSigner.sign(privateKey!!, data, flags)
            } else {
                null
            }
    }

    /** One request/response exchange through the real serve loop. */
    private fun exchange(source: AgentKeySource, request: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        SshAgentServer(source).serve(ByteArrayInputStream(SshAgentServer.frame(request)), output)
        val framed = output.toByteArray()
        assertEquals("one framed reply expected", framed.size - 4, SshAgentServer.u32At(framed, 0).toInt())
        return framed.copyOfRange(4, framed.size)
    }

    @Test
    fun `request identities lists blob and comment`() {
        val blob = byteArrayOf(1, 2, 3, 4)
        val reply = exchange(
            FixedSource(blob, null),
            byteArrayOf(SshAgentProtocol.AGENT_REQUEST_IDENTITIES.toByte()),
        )
        assertEquals(SshAgentProtocol.AGENT_IDENTITIES_ANSWER, reply[0].toInt() and 0xFF)
        assertEquals(1, SshAgentServer.u32At(reply, 1).toInt())
        val gotBlob = SshAgentServer.readSshString(reply, 5)
        assertNotNull(gotBlob)
        assertArrayEquals(blob, gotBlob)
        val comment = SshAgentServer.readSshString(reply, 5 + 4 + blob.size)
        assertEquals("unit-key", String(comment!!, Charsets.UTF_8))
    }

    @Test
    fun `unknown message fails without ending the conversation`() {
        val reply = exchange(
            FixedSource(null, null),
            byteArrayOf(17),
        )
        assertEquals(SshAgentProtocol.AGENT_FAILURE, reply[0].toInt() and 0xFF)
    }

    @Test
    fun `sign request for an unknown key fails`() {
        val reply = exchange(
            FixedSource(null, null),
            signRequest(byteArrayOf(9, 9), byteArrayOf(1)),
        )
        assertEquals(SshAgentProtocol.AGENT_FAILURE, reply[0].toInt() and 0xFF)
    }

    @Test
    fun `rsa signing honors the sha2-256 flag and produces a verifiable signature`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertSignsAndVerifies(pair, rsaBlob(), SshAgentProtocol.FLAG_RSA_SHA2_256, "SHA256withRSA", "rsa-sha2-256", 0)
    }

    @Test
    fun `ed25519 signing produces a verifiable signature`() {
        val gen = net.i2p.crypto.eddsa.KeyPairGenerator()
        gen.initialize(
            net.i2p.crypto.eddsa.spec.EdDSAGenParameterSpec("Ed25519"),
            java.security.SecureRandom(),
        )
        val pair = gen.generateKeyPair()
        assertSignsAndVerifies(pair, edBlob(), 0, null, "ssh-ed25519", 64)
    }

    @Test
    fun `ecdsa signing converts der to fixed-width r and s`() {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val pair = gen.generateKeyPair()
        assertSignsAndVerifies(pair, ecBlob(), 0, "SHA256withECDSA", "ecdsa-sha2-nistp256", 64)
    }

    @Test
    fun `ecdsa p384 and p521 signatures parse their long-form der headers`() {
        // P-384/P-521 SEQUENCE content exceeds 127 bytes → DER long-form
        // length (0x81 …) — the exact case a short-form-only parser breaks on
        fun ecPair(curve: String): KeyPair =
            KeyPairGenerator.getInstance("EC")
                .apply { initialize(ECGenParameterSpec(curve)) }
                .generateKeyPair()
        assertSignsAndVerifies(
            ecPair("secp384r1"),
            ecBlob(),
            0,
            "SHA384withECDSA",
            "ecdsa-sha2-nistp384",
            96,
        )
        assertSignsAndVerifies(
            ecPair("secp521r1"),
            ecBlob(),
            0,
            "SHA512withECDSA",
            "ecdsa-sha2-nistp521",
            132,
        )
    }

    /** Sends a sign request for [pair] and verifies the returned signature. */
    private fun assertSignsAndVerifies(
        pair: KeyPair,
        blob: ByteArray,
        flags: Int,
        jcaVerify: String?,
        wireAlgo: String,
        rawLen: Int,
    ) {
        val data = "agent-sign-test".toByteArray()
        val reply = exchange(FixedSource(blob, pair.private), signRequest(blob, data, flags))
        assertEquals(SshAgentProtocol.AGENT_SIGN_RESPONSE, reply[0].toInt() and 0xFF)
        val sigBlob = SshAgentServer.readSshString(reply, 1)
        assertNotNull(sigBlob)
        // sig blob = string algo, string sig bytes
        val algo = SshAgentServer.readSshString(sigBlob!!, 0)
        assertEquals(wireAlgo, String(algo!!, Charsets.US_ASCII))
        val sig = SshAgentServer.readSshString(sigBlob, 4 + algo.size)!!
        if (jcaVerify != null) {
            val jcaSig = if (wireAlgo.startsWith("ecdsa")) rawToDer(sig) else sig
            val verifier = Signature.getInstance(jcaVerify)
            verifier.initVerify(pair.public)
            verifier.update(data)
            assertTrue("$wireAlgo signature did not verify", verifier.verify(jcaSig))
        } else {
            val engine = net.i2p.crypto.eddsa.EdDSAEngine(
                java.security.MessageDigest.getInstance("SHA-512"),
            )
            engine.initVerify(pair.public)
            engine.update(data)
            assertTrue("ed25519 signature did not verify", engine.verify(sig))
        }
        if (wireAlgo.startsWith("ecdsa")) assertEquals("r||s must be 2×32 bytes", rawLen, sig.size)
    }

    private fun signRequest(blob: ByteArray, data: ByteArray, flags: Int = 0): ByteArray =
        byteArrayOf(SshAgentProtocol.AGENT_SIGN_REQUEST.toByte()) +
            SshAgentServer.sshString(blob) +
            SshAgentServer.sshString(data) +
            SshAgentServer.u32(flags)

    /** SSH wire blobs as the signer will see them from identities(). */
    private fun rsaBlob(): ByteArray {
        // content does not matter to FixedSource (it echoes what it matches);
        // a realistic ssh-rsa blob for shape
        return SshAgentServer.sshString("ssh-rsa".toByteArray()) + SshAgentServer.sshString(ByteArray(270))
    }

    private fun edBlob(): ByteArray =
        SshAgentServer.sshString("ssh-ed25519".toByteArray()) + SshAgentServer.sshString(ByteArray(32))

    private fun ecBlob(): ByteArray =
        SshAgentServer.sshString("ecdsa-sha2-nistp256".toByteArray()) + SshAgentServer.sshString(ByteArray(65))

    /** r||s → ASN.1 SEQUENCE for JCA verification in this test. */
    private fun rawToDer(raw: ByteArray): ByteArray {
        val half = raw.size / 2
        val r = BigInteger(1, raw.copyOfRange(0, half))
        val s = BigInteger(1, raw.copyOfRange(half, raw.size))
        fun derInt(n: BigInteger): ByteArray {
            var b = n.toByteArray()
            if (b.size == 33) b = b.copyOfRange(1, b.size)
            if (b[0].toInt() and 0x80 != 0) b = byteArrayOf(0) + b
            return byteArrayOf(2, b.size.toByte()) + b
        }
        val body = derInt(r) + derInt(s)
        val header = when {
            body.size < 128 -> byteArrayOf(0x30, body.size.toByte())
            body.size < 256 -> byteArrayOf(0x30, 0x81.toByte(), body.size.toByte())
            else ->
                byteArrayOf(0x30, 0x82.toByte(), (body.size shr 8).toByte(), body.size.toByte())
        }
        return header + body
    }

    @Test
    fun `empty source lists zero identities`() {
        val reply = exchange(
            FixedSource(null, null),
            byteArrayOf(SshAgentProtocol.AGENT_REQUEST_IDENTITIES.toByte()),
        )
        assertEquals(0, SshAgentServer.u32At(reply, 1).toInt())
        assertFalse(reply.isEmpty())
    }
}
