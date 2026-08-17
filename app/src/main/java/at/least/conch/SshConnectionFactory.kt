package at.least.conch

import android.content.Context
import android.os.Handler
import android.os.Looper
import net.schmizz.sshj.SSHClient

/**
 * Single place that builds an authenticated [SSHClient] for a stored host:
 * TOFU host-key verification, password or key auth, keep-alive.
 */
object SshConnectionFactory {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * @param prompt optional UI prompt for unknown/changed host keys; when null,
     *                untrusted keys are rejected (for background sessions).
     */
    fun connect(
        context: Context,
        host: Host,
        prompt: KeyPrompt? = null,
    ): SSHClient {
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(TofuHostKeyVerifier(KnownHostsStore(context), prompt, mainHandler))
        ssh.connectTimeout = 10_000
        ssh.timeout = 0
        ssh.useCompression()
        ssh.connect(host.hostname, host.port)

        when (host.authType) {
            Host.AUTH_KEY -> {
                val keyId = host.keyId
                    ?: throw IllegalStateException("Host is set to key auth but no key is selected")
                val provider = KeyManager(context).loadKeyProvider(ssh, keyId)
                ssh.authPublickey(host.username, provider)
            }
            else -> {
                val password = SecretsStore.get("host-pw:${host.id}")
                if (password.isNullOrEmpty()) {
                    throw IllegalStateException("No stored password — edit this host and save a password")
                }
                ssh.authPassword(host.username, password)
            }
        }
        if (host.keepAlive) {
            ssh.connection.keepAlive.setKeepAliveInterval(15)
        }
        return ssh
    }

    fun describeError(e: Exception): String = when {
        e is java.net.UnknownHostException -> "Cannot resolve hostname"
        e.message?.contains("Connection refused", true) == true -> "Connection refused (port closed?)"
        e is java.net.SocketTimeoutException -> "Connection timed out"
        e.message?.contains("timed out", true) == true || e is java.net.SocketTimeoutException -> "Connection timed out"
        e is net.schmizz.sshj.userauth.UserAuthException -> "Authentication failed: ${e.message}"
        e.message?.contains("Auth fail", true) == true -> "Authentication failed (wrong user/password/key?)"
        e is IllegalStateException -> e.message ?: "Error"
        else -> "${e.message ?: "Error"} (${e.javaClass.simpleName})${e.cause?.let { " cause: ${it.message} (${it.javaClass.simpleName})" } ?: ""}"
    }
}
