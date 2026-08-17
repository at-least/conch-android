package at.least.conch

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Full auth path against the local test sshd (127.0.0.1:2223).
 * Opt-in only (mutates shared authorized_keys, needs a running test sshd):
 *   ./gradlew testDebugUnitTest -Dconch.localSshdTest=true --tests '*.LocalSshdAuthTest'
 */
class LocalSshdAuthTest {

    @Test
    fun `ed25519 openssh key authenticates against local sshd`() {
        org.junit.Assume.assumeTrue(
            "opt-in test: pass -Dconch.localSshdTest=true",
            System.getProperty("conch.localSshdTest") == "true"
        )
        val (seed, pub) = Ed25519Codec.generateKeyPair()
        val pem = Ed25519Codec.openSshPrivateKeyPem(seed, pub, "localtest")
        val pubLine = KeyManager.publicLineFor("ssh-ed25519", pub, "localtest")

        // register public key for sshtest
        val tmpPub = File.createTempFile("localtest", ".pub")
        tmpPub.writeText(pubLine)
        val add = ProcessBuilder(
            "sudo", "bash", "-c",
            "mkdir -p /tmp/opencode/localtest && cp ${tmpPub.absolutePath} /tmp/opencode/localtest/pub && cat /tmp/opencode/localtest/pub >> /home/sshtest/.ssh/authorized_keys && grep -c '${pubLine.split(" ")[1].take(20)}' /home/sshtest/.ssh/authorized_keys"
        ).redirectErrorStream(true).start()
        val added = add.inputStream.readBytes().decodeToString().trim()
        add.waitFor()
        tmpPub.delete()
        assertTrue("failed to register key: $added", added.isNotEmpty() && added.toIntOrNull() != null)

        val tmpPriv = File.createTempFile("localtest", ".key")
        try {
            tmpPriv.writeText(pem)
            java.nio.file.Files.setPosixFilePermissions(
                tmpPriv.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            )
            val ssh = SSHClient()
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connect("127.0.0.1", 2223)
            try {
                ssh.authPublickey("sshtest", ssh.loadKeys(tmpPriv.absolutePath))
                val s = ssh.startSession()
                val cmd = s.exec("echo KEYAUTH_OK")
                val out = cmd.inputStream.readBytes().decodeToString().trim()
                cmd.close()
                s.close()
                assertTrue("unexpected output: $out", out == "KEYAUTH_OK")
            } finally {
                ssh.disconnect()
            }
        } finally {
            tmpPriv.delete()
            // remove the test key from authorized_keys (unique tail: comment + last 20 chars)
            val uniqueTail = pubLine.split(" ")[1].takeLast(20)
            ProcessBuilder(
                "sudo", "bash", "-c",
                "sed -i '/$uniqueTail/d' /home/sshtest/.ssh/authorized_keys"
            ).start().waitFor()
        }
    }
}
