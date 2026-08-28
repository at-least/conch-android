package at.least.conch

import android.content.Context
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.channel.AgentChannelRequests
import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.forwarded.AbstractForwardedChannel
import net.schmizz.sshj.connection.channel.forwarded.AbstractForwardedChannelOpener
import net.schmizz.sshj.connection.channel.forwarded.ConnectListener

/**
 * Client side of ssh-agent forwarding. sshj has none, so this is built on
 * its primitives, mirroring upstream's own X11Forwarder:
 *
 *  1. per session channel, send the "auth-agent-req@openssh.com" CHANNEL
 *     request (a global request is NOT it — OpenSSH only honors the channel
 *     request; that mistake is why SSH_AUTH_SOCK never appeared)
 *  2. serve incoming "auth-agent@openssh.com" channels with [SshAgentServer]
 */
object AgentForwarding {
    const val CHANNEL_TYPE = "auth-agent@openssh.com"
    const val CHANNEL_REQUEST = "auth-agent-req@openssh.com"

    /**
     * Attaches the opener that serves agent channels on this connection.
     * Called once after authentication for hosts with forwardAgent set.
     */
    fun attach(ssh: SSHClient, source: AgentKeySource) {
        ssh.connection.attach(AgentOpener(ssh.connection, source))
    }

    /**
     * Asks the server to set up agent forwarding for THIS session channel
     * (OpenSSH exposes it via SSH_AUTH_SOCK in the session) when the host
     * opted in. Must be sent before the shell/command starts. A refusal
     * never throws — the session proceeds without an agent, matching ssh's
     * non-fatal behavior.
     */
    fun requestOn(host: Host, session: Session) {
        if (!host.forwardAgent) return
        requestOn(session)
    }

    /** Sends the raw channel request; JVM tests drive this directly. */
    fun requestOn(session: Session) {
        try {
            AgentChannelRequests.send(session, CHANNEL_REQUEST, true, Buffer.PlainBuffer())
        } catch (_: Exception) {
        }
    }

    private class AgentOpener(
        conn: Connection,
        private val source: AgentKeySource,
    ) : AbstractForwardedChannelOpener(CHANNEL_TYPE, conn) {

        override fun handleOpen(packet: SSHPacket) {
            val chan = AgentChannel(conn, packet)
            callListener(
                ConnectListener { ch ->
                    // sshj's listener contract: confirming (attach + open
                    // confirmation to the server) is the LISTENER's job —
                    // unconfirmed channels never receive data
                    ch.confirm()
                    serveAsync(ch)
                },
                chan,
            )
        }

        private fun serveAsync(channel: Channel.Forwarded) {
            Thread {
                try {
                    SshAgentServer(source).serve(channel.inputStream, channel.outputStream)
                } catch (_: Exception) {
                    // channel closed — server-side ssh client is done with the agent
                } finally {
                    // a server that sends only EOF (never CLOSE) would keep the
                    // channel registered until disconnect
                    runCatching { channel.close() }
                }
            }.apply {
                name = "conch-ssh-agent"
                isDaemon = true
                start()
            }
        }

        /** auth-agent channels carry no channel-specific data beyond the header. */
        private class AgentChannel(conn: Connection, packet: SSHPacket) : AbstractForwardedChannel(
            conn,
            CHANNEL_TYPE,
            packet.readUInt32AsInt(),
            packet.readUInt32(),
            packet.readUInt32(),
            "",
            0,
        )
    }
}

/**
 * App key source: metadata from [KeyManager] (fingerprints, authorized_keys
 * lines), private halves from [SecretsStore]. The agent offers EVERY stored
 * key to the server — that is how agent forwarding works, and why the UI
 * gates it behind an explicit trust decision.
 */
class KeyManagerAgentSource(private val context: Context) : AgentKeySource {

    override fun identities(): List<SshAgentIdentity> =
        KeyManager(context).list()
            // DSA cannot be signed by SshAgentSigner — never advertise a key
            // the agent would refuse to use (breaks server-side key offering)
            .filter { it.algorithm != "ssh-dss" }
            .mapNotNull { info ->
                val b64 = info.publicLine.trim().split(" ").getOrNull(1)
                val blob = runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull()
                blob?.let { SshAgentIdentity(it, info.name) }
            }

    override fun sign(blob: ByteArray, data: ByteArray, flags: Int): ByteArray? {
        val fingerprint = KeyManager.fingerprintOfBlob(blob)
        val info = KeyManager(context).list().firstOrNull { it.fingerprint == fingerprint }
            ?: return null
        return try {
            // Same loader the connect path uses, so the "decrypted key material
            // never reaches the filesystem" invariant lives in exactly one
            // place — this path signs once per server-side hop, so it is the
            // one that would spill most often if the two ever drifted.
            SSHClient().use { ssh ->
                val privateKey = KeyManager(context).loadKeyProvider(ssh, info.id).private
                SshAgentSigner.sign(privateKey, data, flags)
            }
        } catch (_: Exception) {
            null
        }
    }
}
