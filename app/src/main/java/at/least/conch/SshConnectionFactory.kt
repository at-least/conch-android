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
    ): SSHClient {
        // Resolved here, once, for every caller (terminal, Files tab, SAF
        // provider, share target): a broken chain (deleted jump host,
        // cycle, too deep) is a host-config error — only editing fixes it.
        val jumps = when (val r = ProxyJumpResolver.resolve(host, HostStore(context).load())) {
            is ProxyJumpResolver.Resolution.Chain -> r.jumps
            is ProxyJumpResolver.Resolution.Broken -> error("$HOST_CONFIG_PREFIX${ProxyJumpResolver.BROKEN_MESSAGE}")
        }
        return connect(
            host = host,
            jumps = jumps,
            prompt = prompt,
            store = KnownHostsStore(context.filesDir),
            keyProvider = { ssh, keyId -> KeyManager(context).loadKeyProvider(ssh, keyId) },
            password = { h -> SecretsStore.get("host-pw:${h.id}") },
            mainHandler = mainHandler,
        )
    }

    /**
     * JVM-testable core: same wiring, with storage/auth sources injected.
     * A null [mainHandler] runs the TOFU prompt synchronously.
     *
     * @param jumpHost optional single hop to ProxyJump through — shorthand
     *                 for `jumps = listOf(jumpHost)`; ignored when [jumps]
     *                 is given.
     * @param jumps ProxyJump chain, OUTERmost first (the host dialed
     *                 directly, …, the last hop before [host]); each hop's
     *                 own `jumpHostId` is NOT followed — resolve the chain
     *                 first ([ProxyJumpResolver], as the Android overload
     *                 does). Every hop authenticates with its own
     *                 credentials and is TOFU-verified against its own
     *                 endpoint.
     */
    fun connect(
        host: Host,
        prompt: KeyPrompt?,
        store: KnownHostsStore,
        keyProvider: (SSHClient, String) -> KeyProvider,
        password: (Host) -> String?,
        mainHandler: Handler? = null,
        jumpHost: Host? = null,
        jumps: List<Host>? = null,
    ): SSHClient {
        val chain = jumps ?: listOfNotNull(jumpHost)
        // Each hop is dialed through the previous one's direct-tcpip channel
        // and owns it (JumpedClient), so a failure at hop N — or the final
        // disconnect — tears down hops N-1 … 1 as well.
        var previous: SSHClient? = null
        for (hop in chain) {
            previous = attributing(hop) {
                buildClient(hop, prompt, store, keyProvider, password, mainHandler, previous)
            }
        }
        return buildClient(host, prompt, store, keyProvider, password, mainHandler, previous)
    }

    /**
     * Re-labels a hop's failure so the user learns WHICH host in the chain
     * failed, keeping the exception type (describeError and the
     * reconnector's terminal-failure rule key on it): auth failures and
     * host-config errors name the hop; anything else (network, host key)
     * passes through unchanged.
     */
    private inline fun attributing(hop: Host, dial: () -> SSHClient): SSHClient {
        try {
            return dial()
        } catch (e: Exception) {
            throw attributed(hop, e)
        }
    }

    /** The same failure, naming [hop] where the type allows it (see [attributing]). */
    private fun attributed(hop: Host, e: Exception): Exception {
        val label = "jump host '${hop.alias.ifBlank { "${hop.username}@${hop.hostname}" }}'"
        val msg = e.message.orEmpty()
        return when {
            e is net.schmizz.sshj.userauth.UserAuthException ->
                net.schmizz.sshj.userauth.UserAuthException("$label: $msg", e)
            e is IllegalStateException && msg.startsWith(HOST_CONFIG_PREFIX) ->
                IllegalStateException("$HOST_CONFIG_PREFIX$label: ${msg.removePrefix(HOST_CONFIG_PREFIX)}", e)
            else -> e
        }
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
                // Port knocking (iOS parity): the firewall opens the SSH
                // port on seeing the UDP sequence from THIS device, so a
                // jumped target (dialed by the jump host) is not knocked.
                if (host.knockPorts.isNotEmpty()) PortKnocker.knock(host.hostname, host.knockPorts)
                ssh.connect(host.hostname, host.port)
            }
            authenticate(ssh, host, keyProvider, password)
            if (host.keepAlive) {
                ssh.connection.keepAlive.apply {
                    keepAliveInterval = KEEP_ALIVE_INTERVAL_SECONDS
                    (this as? KeepAliveRunner)?.maxAliveCount = KEEP_ALIVE_MAX_UNANSWERED
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
                // keyProvider (missing-key / host-config errors) stays outside
                // the mapper so its actionable message survives verbatim.
                val provider = keyProvider(ssh, keyId)
                authMappingTransportDrop { ssh.authPublickey(host.username, provider) }
            }
            else -> {
                val pw = password(host)
                if (pw.isNullOrEmpty()) {
                    error("${HOST_CONFIG_PREFIX}no stored password — save a password")
                }
                authMappingTransportDrop { ssh.authPassword(host.username, pw) }
            }
        }
    }

    /**
     * Runs an auth method, remapping a transport drop DURING authentication to
     * an auth failure. A hardened server (e.g. `MaxAuthTries 1`) closes the
     * socket the instant it rejects a credential, so sshj surfaces a bare
     * "Socket closed" [net.schmizz.sshj.transport.TransportException] with no
     * auth context — the user would read "Socket closed" instead of "check the
     * password", and the reconnect loop would retry a bad credential forever.
     * Mapping it to [net.schmizz.sshj.userauth.UserAuthException] makes
     * describeError say "Authentication failed" and [SshSession.isTerminalFailure]
     * stop the loop. A UserAuthException (the server that stays up and just
     * refuses) already carries auth context and passes straight through; a
     * genuine network blip inside the sub-second auth window is a rare, benign
     * false positive that a manual retry resolves. Mirrors conch-ios C100.
     */
    private inline fun authMappingTransportDrop(auth: () -> Unit) {
        try {
            auth()
        } catch (e: net.schmizz.sshj.transport.TransportException) {
            throw net.schmizz.sshj.userauth.UserAuthException(
                "server closed the connection during authentication (wrong credentials or too many attempts)",
                e,
            )
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
