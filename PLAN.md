# PLAN — Conch Android

Goal: a free, open-source SSH client for Android. This is the Android
project's own truth; the iOS port keeps its own PLAN.md in ../conch-ios.
Cross-platform findings arrive as hypotheses below and become tasks only
after their verify runs in THIS repo.

## Current state

Shipped 0.9.0 (bracketed paste, encrypted history, themes). 0.9.1 WIP
is in the working tree UNCOMMITTED (reconnect arc: ReconnectPolicy,
SessionReconnector, health banner, tmux-by-default for new hosts, plus a
large interaction-test suite incl. TestSshd harness). Phase A below is
about landing that arc; later phases are ported-candidate work.

## Stack (in production since 0.8.x — foundations, not hypotheses)

| Component | Library | Why |
|-----------|---------|-----|
| SSH transport | sshj (net.schmizz) | PTY, port forwarding, SOCKS5, key auth |
| Terminal emulator | own `TerminalEmulator.kt` | VT100/xterm, 256-color + truecolor, CJK, alt-screen, bracketed paste |
| Secret storage | Android Keystore | AES-256-GCM (history, secrets) |
| Backup | own TILDBAK1 codec | byte-compatible with conch-ios (`BackupCodec.kt`) |
| Test sshd | Apache MINA sshd (in-process, JVM tests) | `TestSshd.kt` |

## Hypotheses

Open technical hypotheses to validate BEFORE committing implementation
work. The iOS port (../conch-ios) keeps its own hypotheses; cross-platform
findings are PORTED here and only ticked after their verify command
actually runs in THIS repo.

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

## Tasks

### Phase A — land 0.9.1 (reconnect arc, WIP already in tree)

- [ ] A1: Land the reconnect arc (policy + reconnector + banner) with its tests
  acceptance: `./gradlew test` green (full JVM suite incl. the new
  interaction tests); CHANGELOG 0.9.1 entry matches shipped behavior;
  manual QA: drop Wi-Fi → amber banner → auto-reconnect → tmux pane restored
  deps: none

### Phase B — cross-platform bug ports (each gated by its hypothesis)

- [ ] B1: Fix the two history killers (OSC-title + DECSET 2004 set dropPending) and lock with a replay corpus
  acceptance: H2 verify green; new corpus tests (zsh-prompt replay,
  DECSET-on/off, OSC title mid-line) all green; existing
  CommandHistoryTest / InputLineAssemblerTest stay green
  deps: none

- [ ] B2: Harden the tmux attach line — `command -v` guard + `printf` wipe + wait-for-first-output before sending
  acceptance: H3 verify green (tmux-less silent fallback; truecolor
  attach; reconnect-into-existing-session with zero residue); no behavior
  change when tmuxAutoAttach is off
  deps: none

- [ ] B3: Ctrl latch audit — release on ANY keystroke (iOS C42 shape)
  acceptance: H4 verify green or a documented N/A with the audit note
  deps: none

### Phase C — interoperability

- [ ] C1: Cross-platform TILDBAK1 fixture round-trip with conch-ios
  acceptance: H5 verify green in BOTH repos (same fixture file, same
  merge counts)
  deps: B-phase independent

### Phase D — release

- [ ] D1: 0.9.1 release (tag, README/CHANGELOG roll-up)
  acceptance: tagged commit builds a clean direct APK; changelog matches
  deps: A1; B/C items ride along if closed by then

## Android-specific considerations

1. Foreground service + persistent notifications keep sessions alive —
   the reconnect arc complements this (network loss ≠ process death).
2. Own TerminalEmulator is a differentiating asset (no WebView) — port
   fixes from SwiftTerm findings selectively; the emulator test suite is
   the safety net.
3. Interaction tests run against an in-process Apache MINA sshd on the
   JVM — fast and CI-friendly; live-device QA stays a manual layer.

## Notes

- 0.9.1 WIP (reconnect arc) is IN TREE but unreleased/uncommitted — its
  hypothesis lives above (H1); do not re-plan around it.
- Cross-platform principle declared in iOS C48 (extra-keys pool should
  hold terminal-special/combo keys only, printable symbols belong to the
  system keyboard) is a DESIGN CANDIDATE here, not a hypothesis — needs a
  user decision before planning (Android ships SLASH/PIPE/TILDE/DOLLAR…).
