# POC — Conch Android: iOS feature parity (native Android UI)

Goal: make Android match conch-ios's redesigned feature set (C50–C58:
in-session 4 tabs sharing one connection, command palette, tunnel capsule,
sessions switcher, host live badge), while keeping the UI Material3 /
Android-native — NOT SwiftUI ports.

## Hypotheses

Open technical assumptions to validate BEFORE committing implementation
work. Each must run its `verify` command in THIS repo and paste real output.

- [x] H1: One sshj `SSHClient` can drive a PTY shell AND concurrent exec
      channels AND an SFTP client at the same time, without breaking the
      shell or any existing test.
  why it matters: iOS's 4 tabs (Terminal/Monitor/Docker/Files) all ride
  `bridge.session` (one connection). Android's SshSession today makes
  `SSHClient` private and only runs the shell; SFTP/Monitor/Docker each
  open a SEPARATE connection. Parity requires exposing exec()/sftpClient()
  on the SAME connection as the live shell.
  verify: new JVM test `SharedConnectionMultiplexTest` against TestSshd —
  open one SSHClient, (a) start a PTY shell + read a marker line,
  (b) while the shell is open, `startSession().exec("echo X")` and assert
  "X\n", (c) `ssh.newSFTPClient().ls("/tmp")` returns a list, (d) send
  another keystroke to the shell and assert the echo comes back on the
  shell stream (shell NOT disrupted). Then `JAVA_HOME=... ./gradlew test
  --tests 'at.least.conch.SharedConnectionMultiplexTest'` green AND the
  full suite still green.
  verdict: verified
  evidence: 2026-08-22 — `SharedConnectionMultiplexTest` (1 test, 0 fail):
  one SSHClient opened PTY shell → echoed MARKER42; while shell open, exec
  `probe` → "ran [probe]\n"; SFTP ls("/") → contains "hello"; shell still
  echoes ALIVE99 after exec+sftp. Full suite 254 tests, 0 failures, 0 errors.

- [ ] H2: The Monitor probe (`cat /proc/stat /proc/loadavg /proc/uptime;
      free -b; df -B1 /`) and Docker probe (`docker ps -a --format
      '{{json .}}'`) work unchanged when run as exec channels on the
      shared connection instead of a dedicated one — i.e. the only
      change is WHERE the SSHClient comes from, not the command strings
      or parsers.
  why it matters: lets us reuse MonitorActivity/DockerActivity's existing
  parsers + command strings verbatim, swapping only the connection source.
  verify: new test running both probes as exec on a shared SSHClient (the
  H1 harness) and asserting MonitorParser.parse + DockerParser.parse
  produce the same shapes as the existing dedicated-connection tests.
  verdict:
  evidence:

- [ ] H3: A Material3 `NavigationBar` (bottom) or `TabRow` (top) can host
      the 4 in-session tabs while the terminal `AndroidView` stays mounted
      (opacity/visibility swap, never removed) so buffer + scrollback
      survive tab switches — the same invariant iOS enforces.
  why it matters: iOS keeps the terminal in a ZStack with opacity; on
  Android the equivalent is keeping the Compose `AndroidView` in the
  hierarchy and toggling visibility, so the emulator state isn't reset.
  verify: a small Compose UI test (or instrumented check) that switches
  Terminal → Monitor → Terminal and asserts the TerminalView instance and
  its emulator instance are the SAME object (identity, not just non-null)
  across the switch. `JAVA_HOME=... ./gradlew testFossDebugUnitTest` green.
  verdict:
  evidence:

## POC notes

- The UI will be Material3 throughout: `TabRow`/`NavigationBar` for tabs,
  `ModalBottomSheet` for the command palette + sessions switcher (the
  Android analogue of iOS sheet detents), `AssistChip` for the tunnel
  capsule, `LazyVerticalGrid` for host cards if we adopt the grid. No
  SwiftUI idioms are ported.
- The existing Activities (SftpActivity/MonitorActivity/DockerActivity)
  stay as standalone entry points for deep-links/widget parity; the
  in-session tabs are ADDITIVE composables that reuse the same parsers
  and exec paths, fed by the shared SshSession.
