package at.least.conch

import android.content.Context
import android.os.Handler
import android.os.Looper
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.Config
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.keyprovider.KeyProvider

/**
 * Single place that builds an authenticated [SSHClient] for a stored host:
 * TOFU host-key verification, password or key auth, keep-alive.
 */
object SshConnectionFactory {

    /**
     * Wire contract: 15-second keep-alive, matching the iOS JSch default
     * (KeepAliveLoopTests). Android uses sshj's transport-level keep-alive,
     * not iOS's `:` shell beat. Pinned by InteractionStringContractTest.
     */
    const val KEEP_ALIVE_INTERVAL_SECONDS = 15

    /**
     * Unanswered keep-alives before the transport is declared dead: 3 × 15 s
     * = 45 s. sshj's default provider only *sends* IGNORE packets and never
     * expects a reply, so a transport whose network silently vanished
     * (Wi-Fi→cellular handover, NAT table flush) stayed "connected" until
     * TCP's own retransmit timeout — minutes — and the reconnect never
     * started. Request/response keep-alives give the reader thread a real
     * failure to act on.
     */
    const val KEEP_ALIVE_MAX_UNANSWERED = 3

    /**
     * Prefix for errors that only editing the host record can fix (no
     * password saved, key auth with no key chosen, jump host deleted). The
     * reconnector treats these as terminal — retrying with backoff forever
     * against a local misconfiguration only hides the message that says
     * what to do.
     */
    const val HOST_CONFIG_PREFIX = "Edit this host: "

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * @param prompt optional UI prompt for unknown/changed host keys; when null,
     *                untrusted keys are rejected (for background sessions).
     */
    fun connect(
        context: Context,
        host: Host,
        prompt: KeyPrompt? = null,
        agentKeys: AgentKeySource? = null,
    ): SSHClient {
        val jumpHost = host.jumpHostId?.let { id ->
            HostStore(context).load().firstOrNull { it.id == id }
                ?: error("${HOST_CONFIG_PREFIX}jump host not found — reselect a jump host")
        }
        val keys = agentKeys ?: if (host.forwardAgent) KeyManagerAgentSource(context) else null
        return connect(
            host = host,
            jumpHost = jumpHost,
            prompt = prompt,
            store = KnownHostsStore(context.filesDir),
            keyProvider = { ssh, keyId -> KeyManager(context).loadKeyProvider(ssh, keyId) },
            password = { h -> SecretsStore.get("host-pw:${h.id}") },
            mainHandler = mainHandler,
            agentKeys = keys,
        )
    }

    /**
     * JVM-testable core: same wiring, with storage/auth sources injected.
     * A null [mainHandler] runs the TOFU prompt synchronously.
     *
     * @param jumpHost optional saved host to ProxyJump through (single hop);
     *                 its own jumpHostId is ignored. Both host keys get TOFU.
     * @param agentKeys key source for ssh-agent forwarding; the Android
     *                 overload defaults it when the host opts in.
     */
    fun connect(
        host: Host,
        prompt: KeyPrompt?,
        store: KnownHostsStore,
        keyProvider: (SSHClient, String) -> KeyProvider,
        password: (Host) -> String?,
        mainHandler: Handler? = null,
        jumpHost: Host? = null,
        agentKeys: AgentKeySource? = null,
    ): SSHClient {
        val jump = jumpHost?.let {
            buildClient(it, prompt, store, keyProvider, password, mainHandler, agentKeys = null)
        }
        return buildClient(host, prompt, store, keyProvider, password, mainHandler, jump, agentKeys)
    }

    /**
     * Target client that owns its jump client: any disconnect() (clean,
     * failed auth, session teardown) tears the jump transport down too, so
     * SshSession's existing single-client lifecycle needs no changes.
     */
    private class JumpedClient(config: Config, private val jump: SSHClient) : SSHClient(config) {
        override fun disconnect() {
            try { super.disconnect() } catch (_: Exception) {}
            try { jump.disconnect() } catch (_: Exception) {}
        }
    }

    private fun buildClient(
        host: Host,
        prompt: KeyPrompt?,
        store: KnownHostsStore,
        keyProvider: (SSHClient, String) -> KeyProvider,
        password: (Host) -> String?,
        mainHandler: Handler?,
        jump: SSHClient? = null,
        agentKeys: AgentKeySource? = null,
    ): SSHClient {
        val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
        val ssh = if (jump != null) JumpedClient(config, jump) else SSHClient(config)
        ssh.addHostKeyVerifier(TofuHostKeyVerifier(store, prompt, mainHandler))
        ssh.connectTimeout = 10_000
        ssh.timeout = 0
        ssh.useCompression()

        try {
            if (jump != null) {
                // Inside the try: a target that is unreachable through the
                // jump, or whose host key is rejected, must not leak the
                // already-authenticated jump transport (one per retry).
                ssh.connectVia(jump.newDirectConnection(host.hostname, host.port))
            } else {
                ssh.connect(host.hostname, host.port)
            }
            authenticate(ssh, host, keyProvider, password)
            if (host.keepAlive) {
                ssh.connection.keepAlive.apply {
                    keepAliveInterval = KEEP_ALIVE_INTERVAL_SECONDS
                    (this as? KeepAliveRunner)?.maxAliveCount = KEEP_ALIVE_MAX_UNANSWERED
                }
            }
            if (host.forwardAgent && agentKeys != null) {
                // a refusing server must not kill the session (tunnel policy)
                try {
                    AgentForwarding.attach(ssh, agentKeys)
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            // Without this the socket and sshj reader threads (and the jump
            // client, via JumpedClient) leak on every failed attempt.
            try { ssh.disconnect() } catch (_: Exception) {}
            throw e
        }
        return ssh
    }

    private fun authenticate(
        ssh: SSHClient,
        host: Host,
        keyProvider: (SSHClient, String) -> KeyProvider,
        password: (Host) -> String?,
    ) {
        when (host.authType) {
            Host.AUTH_KEY -> {
                val keyId = host.keyId
                    ?: error("${HOST_CONFIG_PREFIX}key auth is selected but no key is chosen")
                ssh.authPublickey(host.username, keyProvider(ssh, keyId))
            }
            else -> {
                val pw = password(host)
                if (pw.isNullOrEmpty()) {
                    error("${HOST_CONFIG_PREFIX}no stored password — save a password")
                }
                ssh.authPassword(host.username, pw)
            }
        }
    }

    fun describeError(e: Exception): String = when {
        e is java.net.UnknownHostException -> "Cannot resolve hostname"
        e.message?.contains("Connection refused", true) == true -> "Connection refused (port closed?)"
        e is java.net.SocketTimeoutException || e.message?.contains("timed out", true) == true ->
            "Connection timed out"
        e is net.schmizz.sshj.userauth.UserAuthException -> "Authentication failed: ${e.message}"
        e.message?.contains("Auth fail", true) == true -> "Authentication failed (wrong user/password/key?)"
        e is IllegalStateException -> e.message ?: "Error"
        else -> "${e.message ?: "Error"} (${e.javaClass.simpleName})${e.cause?.let { " cause: ${it.message} (${it.javaClass.simpleName})" } ?: ""}"
    }
}
