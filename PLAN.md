# PLAN — Conch Android

Goal: a free, open-source SSH client for Android. This is the Android
project's own truth; the iOS port keeps its own PLAN.md in ../conch-ios.
Cross-platform findings arrive as hypotheses in POC.md and become tasks
here only after their verify runs in THIS repo.

## Current state

Shipped 0.9.0 (bracketed paste, encrypted history, themes). 0.9.1 WIP
is in the working tree UNCOMMITTED (reconnect arc: ReconnectPolicy,
SessionReconnector, health banner, tmux-by-default for new hosts, plus a
large interaction-test suite incl. TestSshd harness). Phase A below is
about landing that arc; later phases are ported-candidate work.

## Tasks

### Phase A — land 0.9.1 (reconnect arc, WIP already in tree)

- [ ] A1: Land the reconnect arc (policy + reconnector + banner) with its tests
  acceptance: `./gradlew test` green (full JVM suite incl. the new
  interaction tests); CHANGELOG 0.9.1 entry matches shipped behavior;
  manual QA: drop Wi-Fi → amber banner → auto-reconnect → tmux pane restored
  deps: none

### Phase B — cross-platform bug ports (each gated by its POC hypothesis)

- [ ] B1: Fix the two history killers (OSC-title + DECSET 2004 set dropPending) and lock with a replay corpus
  acceptance: POC H2 verify green; new corpus tests (zsh-prompt replay,
  DECSET-on/off, OSC title mid-line) all green; existing
  CommandHistoryTest / InputLineAssemblerTest stay green
  deps: none

- [ ] B2: Harden the tmux attach line — `command -v` guard + `printf` wipe + wait-for-first-output before sending
  acceptance: POC H3 verify green (tmux-less silent fallback; truecolor
  attach; reconnect-into-existing-session with zero residue); no behavior
  change when tmuxAutoAttach is off
  deps: none

- [ ] B3: Ctrl latch audit — release on ANY keystroke (iOS C42 shape)
  acceptance: POC H4 verify green or a documented N/A with the audit note
  deps: none

### Phase C — interoperability

- [ ] C1: Cross-platform TILDBAK1 fixture round-trip with conch-ios
  acceptance: POC H5 verify green in BOTH repos (same fixture file, same
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
