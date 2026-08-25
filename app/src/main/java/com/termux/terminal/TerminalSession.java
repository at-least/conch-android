package com.termux.terminal;

/**
 * CONCH STUB: upstream's TerminalSession drives a LOCAL pty via JNI
 * (libtermux.so). Conch feeds the emulator from an SSH channel instead and
 * never instantiates this class — it exists only so that
 * {@link TerminalSessionClient}'s signatures resolve without vendoring the
 * JNI machinery (JNI.java, native libs).
 */
public final class TerminalSession {
    private TerminalSession() {}
}
