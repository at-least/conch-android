# POC — Conch Android

Goal: validate the open technical hypotheses for Conch Android BEFORE
committing implementation work. This file is the Android project's own
truth — the iOS port (../conch-ios) keeps its own POC.md; cross-platform
findings are PORTED as hypotheses here and only ticked after their verify
command actually runs in THIS repo.

## Stack (in production since 0.8.x — foundations, not hypotheses)

| Component | Library | Why |
|-----------|---------|-----|
| SSH transport | sshj (net.schmizz) | PTY, port forwarding, SOCKS5, key auth |
| Terminal emulator | own `TerminalEmulator.kt` | VT100/xterm, 256-color + truecolor, CJK, alt-screen, bracketed paste |
| Secret storage | Android Keystore | AES-256-GCM (history, secrets) |
| Backup | own TILDBAK1 codec | byte-compatible with conch-ios (`BackupCodec.kt`) |
| Test sshd | Apache MINA sshd (in-process, JVM tests) | `TestSshd.kt` |

## Hypotheses

- [ ] H1: Auto-reconnect restores a tmux-attached session after network loss
  verify: `./gradlew test --tests 'at.least.conch.SessionReconnectorInteractionTest' --tests 'at.least.conch.ReconnectPolicyTest'` green, plus a manual drop-Wi-Fi run where the amber banner shows, backoff reconnects, and the pane content is restored server-side by tmux `-A`
  verdict:
  evidence:

- [ ] H2: The two command-history killers ported from iOS C45 are real here — OSC title (`ESC ]0;…`) and DECSET 2004 (`ESC[?2004h`) both set `dropPending`, so real zsh hosts record almost no history
  verify: replay-corpus test (zsh prompt bytes incl. OSC title + `ESC[?2004h` before the typed line) against `InputLineAssembler` asserting the line IS recorded before the fix, then green after the fix
  verdict:
  evidence:
  (source: iOS LEDGER C45 — "ANDROID HAS THE SAME TWO BUGS (verified
  identical source)"; audit pass on `CommandHistory.kt` handleEscByte/
  handleCsiByte confirms the same code shape. Verify command must still
  run HERE before any tick.)

- [ ] H3: The hardened tmux attach line (guard + self-clearing wipe) is safe on this stack — no `command not found` noise on tmux-less hosts, no echoed attach line residue, truecolor still advertised
  verify: `SshShellPtyInteractionTest`-style live test: (a) host without tmux on PATH → attach line is a silent no-op, shell alive; (b) host with tmux → `$TMUX` set + `$COLORTERM` == truecolor + rendered pane shows no attach-command residue; (c) reconnect attaches to the EXISTING session with markers intact and zero residue (iOS C57 regression shape)
  verdict:
  evidence:
  (current `SshSession.kt` sends the bare line immediately after
  `startShell()` — iOS C53/C54/C57 all apply.)

- [ ] H4: The Ctrl latch bug iOS fixed in C42 exists here too — a latched CTRL that is only cleared by single letters mangles later keystrokes
  verify: unit test on the latch state machine (ARM → any non-letter key → released); grep-audit `TerminalView.ctrlArmed` clear sites first
  verdict:
  evidence:

- [ ] H5: TILDBAK1 backups round-trip cross-platform (export on Android → import on iOS and vice versa)
  verify: same passphrase fixture backup file imported by both repos' codec tests (add the fixture to both test suites; assert host/key/snippet merge counts match)
  verdict:
  evidence:

## Notes

- 0.9.1 WIP (reconnect arc) is IN TREE but unreleased/uncommitted — its
  hypotheses live above (H1); do not re-plan around it.
- Cross-platform principle declared in iOS C48 (extra-keys pool should
  hold terminal-special/combo keys only, printable symbols belong to the
  system keyboard) is a DESIGN CANDIDATE here, not a hypothesis — needs a
  user decision before planning (Android ships SLASH/PIPE/TILDE/DOLLAR…).
