package at.least.conch

import java.util.Base64

private object TestResources

/** Reads a classpath test resource (`/fixtures/…`, `/ppk/…`, …) or fails with its path. */
fun testResource(path: String): ByteArray =
    checkNotNull(TestResources::class.java.getResourceAsStream(path)) { "missing test resource $path" }.readBytes()

/** Wraps an openssh-key-v1 blob in the PEM armor `ssh-keygen` writes (70-column MIME base64). */
fun opensshArmor(blob: ByteArray): String {
    val b64 = Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(blob)
    return "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
}
