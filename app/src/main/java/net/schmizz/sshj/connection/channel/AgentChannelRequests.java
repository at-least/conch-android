package net.schmizz.sshj.connection.channel;

import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.transport.TransportException;

/**
 * Same-package bridge to sshj's protected sendChannelRequest. sshj has no
 * agent-forwarding support (no public way to send the session-channel
 * request "auth-agent-req@openssh.com" that OpenSSH requires — the X11
 * equivalent, reqX11Forwarding, is hardcoded inside SessionChannel). Java's
 * protected includes package access; Kotlin's does not, hence this file.
 */
public final class AgentChannelRequests {

    private AgentChannelRequests() {
    }

    /**
     * Sends an arbitrary channel request. Returns without waiting for the
     * reply; callers that must react to refusal should use wantReply and the
     * returned event.
     */
    public static void send(Channel channel, String request, boolean wantReply, Buffer.PlainBuffer buffer)
            throws TransportException {
        ((AbstractChannel) channel).sendChannelRequest(request, wantReply, buffer);
    }
}
