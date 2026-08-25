package at.least.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the EXACT strings/bytes the app sends to remote SSH hosts — port of
 * iOS InteractionStringTests.swift (C43). Silent drift here changes what
 * remote servers see; the build stays green either way.
 *
 * Documented divergences from the iOS contracts (product decisions pending,
 * not accidents):
 * - tmux attach: Android sends the bare inner command; iOS guards with
 *   `command -v tmux >/dev/null 2>&1 && printf '\033[H\033[2J\033[3J' &&`.
 *   Hardening Android is PLAN B2/H3 — this pin must move WITH that fix.
 * - keep-alive: Android uses sshj's transport-level keep-alive at 15s; iOS
 *   beats the shell with `:` (POSIX no-op) on its own loop. Same cadence,
 *   different mechanism.
 * - docker: iOS prefixes `export PATH=/opt/homebrew/bin:/usr/local/bin:$PATH;`
 *   (C33 live finding: non-login SSH PATH omits brew/local bins). Android
 *   relies on docker being on PATH.
 * - sendLine: iOS send(line:) = write(line + "\n") and lets icrnl translate;
 *   Android sends text + "\r" straight to the PTY (TerminalActivity inline,
 *   no shared helper to pin yet). Same wire effect.
 */
class InteractionStringContractTest {

    @Test
    fun `tmux attach line is byte-pinned and matches the iOS inner command`() {
        assertEquals(
            "COLORTERM=truecolor tmux new -A -s conch\r",
            SshSession.TMUX_ATTACH_LINE,
        )
        assertEquals(
            "inner command (minus the CR terminator) stays byte-identical to iOS — the part iOS guards with command -v + printf wipe",
            "COLORTERM=truecolor tmux new -A -s conch",
            SshSession.TMUX_ATTACH_LINE.trimEnd('\r'),
        )
    }

    @Test
    fun `keep-alive interval is 15 seconds matching the iOS default`() {
        assertEquals(15, SshConnectionFactory.KEEP_ALIVE_INTERVAL_SECONDS)
    }

    @Test
    fun `docker list command is pinned`() {
        assertEquals("docker ps -a --format '{{json .}}'", DockerParser.LIST_COMMAND)
    }

    @Test
    fun `monitor probe shape is the shared parser contract`() {
        // Shape assertions mirror iOS testMonitorProbeStartsWithCpuSection.
        assertTrue(MonitorParser.PROBE.startsWith("echo ---CPU; grep 'cpu ' /proc/stat"))
        assertTrue(MonitorParser.PROBE.contains("df -B1 /"))
        assertTrue(MonitorParser.PROBE.contains("cat /proc/loadavg"))
        assertTrue(MonitorParser.PROBE.contains("cat /proc/uptime"))
    }

    @Test
    fun `monitor probe command is byte-pinned in full`() {
        assertEquals(
            "echo ---CPU; grep 'cpu ' /proc/stat; sleep 1; grep 'cpu ' /proc/stat; " +
                "echo ---MEM; free -b | grep -E '^Mem:|^Swap:'; " +
                "echo ---DISK; df -B1 / | tail -1; " +
                "echo ---LOAD; cat /proc/loadavg; " +
                "echo ---UP; cat /proc/uptime",
            MonitorParser.PROBE,
        )
    }
}
