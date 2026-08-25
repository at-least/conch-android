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
  verdict: CONFIRMED 2026-08-25 — audit during F10: ctrlArmed is cleared
  ONLY in sendText's letter path and pasteText(); non-letter input keeps
  the latch armed, so a later letter fires as Ctrl-letter (stuck-latch).
  Pinned as-is by CtrlLatchTest `non-letter passes through … STAYS armed`;
  fix = B3, must flip that pin.
  evidence: KeyInput.applyCtrlLatch extracted (F10) preserves the exact
  sendText behavior; CtrlLatchTest 7/0 green against it.

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
  change when tmuxAutoAttach is off; update the F1
  InteractionStringContractTest tmux pin to the hardened line in the same
  change
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

### Phase E — iOS feature parity (native Android UI) 2026-08-22

Match conch-ios's C50–C58 redesign: in-session 4 tabs sharing one
connection, command palette, tunnel capsule, sessions switcher, host live
badge. UI stays Material3 / Android-native — NO SwiftUI ports. POC in
POC.md; H1 verified (one SSHClient multiplexes PTY+exec+SFTP, 254 green).

Spike conclusions (from verified H1):
- sshj SSHClient already multiplexes shell + exec + SFTP on one connection
  — SshSession only needs to EXPOSE exec()/sftpClient(), not re-architect.
- Monitor/Docker probes are `startSession().exec(cmd)` + pure parsers;
  swapping the connection source changes nothing about command strings.
- iOS keeps the terminal mounted via ZStack opacity; the Android analogue
  is a Compose AndroidView that is never conditionally removed (visibility
  swap only), so emulator/scrollback survive tab switches.

- [x] E1: Expose `exec(command)` and `sftpClient()` on SshSession (shared
      connection) without breaking the shell or existing tests
  acceptance: new `SharedConnectionMultiplexTest` green (already added,
  H1); full `./gradlew testFossDebugUnitTest` green (254); existing
  SessionReconnectorInteractionTest + SshShellPtyInteractionTest stay green
  deps: H1 (verified)
  evidence: 2026-08-22 — SshSession.exec()/sftpClient()/isConnected added
  (@Volatile client+session), SessionReconnector delegates; full suite 254/0.

- [x] E2: Extract Monitor + Docker + SFTP screens as Compose composables
      that take a shared SshSession (reusing existing parsers/command
      strings verbatim)
  acceptance: the three composables compile and render against a shared
  session in a host-less preview/test; parsers unchanged (existing
  MonitorDockerParserTest + DockerParserTest + SftpInteractionTest green)
  deps: E1
  evidence: 2026-08-22 — SessionTabs.kt MonitorTab/DockerTab/SftpTab reuse
  parsers + command strings verbatim; compile + 254/0, parser tests green.

- [x] E3: In-session TabRow (Terminal / Monitor / Docker / Files) in
      TerminalActivity — terminal AndroidView stays mounted (visibility
      swap, never removed), other tabs render E2 composables on top
  acceptance: build green; switching Terminal → Monitor → Terminal keeps
  the same TerminalView + emulator instance (identity preserved); full
  `./gradlew testFossDebugUnitTest` green
  deps: E2
  evidence: 2026-08-22 — terminal AndroidView never removed (alpha swap,
  iOS ZStack-opacity analogue), NavigationBar hosts 4 tabs; 254/0.
  Identity preservation structural; instrumented-UI assert = manual QA.

- [x] E4: Command Palette — ModalBottomSheet searching history + snippets
      (prefix > substring rank, snippets win ties), tap-to-run; Snippets &
      History management sheets open from it (replaces the overflow menu
      items)
  acceptance: new `CommandPaletteModelTest` (pure filter/rank logic ported
  from iOS) green; build green; overflow menu's Snippets/History items
  replaced by the palette
  deps: E1
  evidence: 2026-08-22 — CommandPaletteModel.kt (pure rank) +
  CommandPaletteSheet.kt wired into overflow menu; model test 7/0, 261/0.

- [x] E5: Tunnel capsule on the session screen — `AssistChip` showing
      `⇅ N` when tunnels are live, tap → confirmation to stop all tunnels
  acceptance: build green; capsule visible iff host.tunnels non-empty;
  stop-all tears down forwarder sockets (existing PortForwardInteractionTest
  green)
  deps: E1
  evidence: 2026-08-22 — SshSession.stopTunnels()/tunnelCount + AssistChip
  w/ confirm dialog; 261/0, PortForwardInteractionTest green.

- [x] E6: Sessions switcher — ModalBottomSheet listing live sessions
      (host name + relative start time), tap to switch, swipe to disconnect
  acceptance: build green; switcher lists every active TerminalActivity
  session; swipe-disconnect tears down that session only
  deps: E1
  evidence: 2026-08-22 — LiveSessions registry (pure singleton) +
  SessionsSheet (SwipeToDismissBox) + MainActivity "Sessions (N)";
  261/0; sheet swipe/tap = manual QA (no emulator in env).

- [x] E7: Host card live badge on MainActivity host list — green dot +
      `live` / `N live` text when a host has live sessions
  acceptance: build green; badge derives from a live-session registry
      (HostCardStatus parity with iOS); unit test for the badge derivation green
  deps: E6
  evidence: 2026-08-22 — HostCardStatus.kt pure derivation + test 3/0;
  HostCard badge via LiveSessions.countForHost, refreshed onResume; 264/0.

- [x] E8: README + CHANGELOG roll-up for the parity arc
  acceptance: README Features list matches shipped behavior; CHANGELOG
  entry covers E1–E7
  deps: E1–E7
  evidence: 2026-08-22 — README tools section + project layout updated,
  CHANGELOG 0.9.1 covers E1–E7; 264/0.

### Phase F — test parity with conch-ios (fill coverage gaps)

Audit on 2026-08-22 compared every conch-ios test (ConchTests +
ConchIntegrationTests + ConchUITests, ~140 unit + ~30 integration/UI
methods across 26+ feature areas) against conch-android's 35 test files
(~160 @Test methods). Below are the gaps, prioritized by
invariant-broken severity × current-zero-coverage. Acceptance for every
task is the named test file green inside `./gradlew testFossDebugUnitTest`.

### Phase F-P0 — cross-platform byte contracts (parity risk highest)

- [x] F1: InteractionStringContractTest — pin tmux attach line, keep-alive
      interval, docker list command, monitor probe byte contracts
  acceptance: new `InteractionStringContractTest.kt` green in
  `./gradlew testFossDebugUnitTest`
  deps: none
  evidence: 2026-08-25 — constants extracted & deduped (SshSession.TMUX_ATTACH_LINE,
  SshConnectionFactory.KEEP_ALIVE_INTERVAL_SECONDS=15, MonitorParser.PROBE
  — was copy-pasted in MonitorActivity + SessionTabs, DockerParser.
  LIST_COMMAND); InteractionStringContractTest 5/0 pins them; full suite
  269/0. iOS DIVERGENCES documented in test KDoc (tmux guard/wipe = B2;
  keep-alive mechanism; docker PATH prefix; palette CR vs LF).

- [x] F2: MonitorParser probe command exact bytes + cpu clamp edge cases
  acceptance: `MonitorParserTest` (was 3 tests) gains probe-command
  exact-string assertion + zero-delta clamp + full-idle + full-busy cases;
  green in `./gradlew testFossDebugUnitTest`
  deps: none
  evidence: 2026-08-25 — MonitorParserTest → 6/0 (full-idle 0%, full-busy
  100%, probe shape mirroring iOS testProbeCommandExact). Zero-delta clamp
  was already covered; full-string pin lives in F1's contract test.

- [x] F3: BackupCodec header layout / bad magic / too short / unsupported
      version
  acceptance: `BackupCodecTest` gains 4 structural-rejection assertions
  matching iOS `BackupCodecTests.swift` (header offset layout, badMagic,
  tooShort, unsupportedVersion); green in `./gradlew testFossDebugUnitTest`
  deps: none
  evidence: 2026-08-25 — BackupCodecTest → 10/0 (exact-size layout pin,
  salt/iv randomness at offsets, badMagic, tooShort, version=99 rejected —
  hand-built blob also pins PBKDF2-HMAC-SHA256 600k/256. iOS's RFC-7914
  vector test N/A: Android uses the JDK's PBKDF2, not a hand-rolled one).

- [x] F4: BackupCodec export/restore merge semantics + export-includes-secrets
  acceptance: `BackupCodecTest` gains a restore-merge test (existing host id
  NOT overwritten, new id added, known_hosts union) + an export-includes-
  secrets assertion (Keychain/EncryptedPrefs content present in decrypted
  blob); green in `./gradlew testFossDebugUnitTest`
  deps: F3
  evidence: 2026-08-25 — merge decisions extracted to BackupManager
  companion pure fns (hosts/snippets/keyIdsToImport/knownHosts union);
  BackupManagerMergeTest 5/0; suite 286/0. RESCOPE: SecretsStore/collect()
  are Android-bound (no Robolectric) — export-includes-secrets covered at
  codec level (BackupCodecTest roundtrip); store wiring = manual-QA.
)

- [x] F5: SnippetStoreTest — crud roundtrip, load empty/corrupt no crash,
      JSON field names match iOS, delete by id
  acceptance: new `SnippetStoreTest.kt` green in
  `./gradlew testFossDebugUnitTest`; covers the 4 iOS `SnippetStoreTests.swift`
  cases
  deps: none
  evidence: 2026-08-25 — SnippetStore File-seam ctor; SnippetStoreTest 5/0
  (cross-instance roundtrip, corrupt→[], field set {id,label,command},
  delete-by-id); suite 286/0.

- [x] F6: AppLockTest — grace window inside/outside, disabled by default,
      toggle flips state
  acceptance: new `AppLockTest.kt` green in `./gradlew testFossDebugUnitTest`;
  covers the 2 iOS `AppLockTests.swift` cases (grace window + default-off)
  deps: none
  evidence: 2026-08-25 — AppLock pure withinGrace() + DEFAULT_ENABLED/
  GRACE_MS consts (lockIfNeeded rewired, identical); AppLockTest 4/0
  (within/beyond 30s, relock-at-0, defaults pin). Suite 290/0. Prefs
  toggle + BiometricPrompt = instrumented-QA (no Robolectric).


- [x] F7: ExtraKeysConfigTest — default row, save/load roundtrip, unknown id
      dropped, legacy symbol ids filtered, pool emits no plain printable,
      empty falls back to default, arrow escape bytes, CTRL is toggle (null
      bytes)
  acceptance: new `ExtraKeysConfigTest.kt` green in
  `./gradlew testFossDebugUnitTest`; covers the 8 iOS
  `ExtraKeysAndThemeTests.swift` ExtraKeysConfig cases
  deps: none
  evidence: 2026-08-25 — ExtraKeysConfig pure parse()/serialize();
  ExtraKeysConfigTest 8/0 (default-row pin, round-trip, unknown-id drop,
  fallbacks, xterm byte pins, CTRL==null, symbols, labelFor). Suite 298/0.
  ADAPTED: iOS's no-printable-pool invariant is FALSE here (C48 design
  candidate, PLAN Notes) — current behavior pinned + divergence documented.


- [x] F8: SessionTabsTest + TunnelCapsuleTest — SessionTab enum exactly 4
      with title+icon non-empty; tunnel capsule visibility + pinned labels
  acceptance: new `SessionTabsTest.kt` (+ TunnelCapsuleTest) green in
  `./gradlew testFossDebugUnitTest`; covers the 5 iOS `TunnelStatusTests.swift`
  cases (SessionTabs.kt is 699 lines with 0 tests today)
  deps: none
  evidence: 2026-08-25 — SessionTab enum moved to SessionTabs.kt (internal)
  + TunnelCapsule pure labels wired into TerminalActivity (identical
  strings). SessionTabsTest 3/0 + TunnelCapsuleTest 3/0; suite 304/0.


- [x] F9: ConnectionHealthDeriveTest — N/A for the health-derive half;
      tmux-default half already/now pinned
  acceptance: health-derive documented N/A with audit note (PLAN B3
  precedent); tmux-default pins green in `./gradlew testFossDebugUnitTest`
  deps: none
  evidence: 2026-08-25 — health-derive N/A (no Android model: sshj
  keep-alive is transport-internal, ConnState carries no derive logic;
  tests land with any future health model). Tmux-default half completed:
  added `explicit tmux off survives a json round-trip`; HostStoreJsonTest
  5/0 (missing→false divergence from iOS's true is deliberate:
  pre-feature backups).


- [x] F10: Extract Ctrl latch transform from TerminalView into a pure
      function (KeyInput), then add CtrlLatchTest + CtrlComboTest
  acceptance: new `CtrlLatchTest.kt` + `CtrlComboTest.kt` green in
  `./gradlew testFossDebugUnitTest`; covers iOS `CtrlKeyTransformTests.swift`
  + `CtrlLatchLifecycleTests.swift` + `CtrlComboTests.swift` (adapted)
  deps: none (refactor is part of this task)
  evidence: 2026-08-25 — KeyInput.kt (applyCtrlLatch + keyBytes) extracted
  from TerminalView.sendText/sendKey (byte-identical, rewired);
  CtrlLatchTest 7/0 + CtrlComboTest 4/0. Suite 316/0. H4 verdict CONFIRMED
  (latch survives non-letter — pinned as-is, B3 flips). iOS CSI-rewrite-
  under-latch N/A: ctrl+arrows come from the hardware-keyboard path.


- [x] F11: NavAndAltTest — N/A (Android has no nav-gesture/alt features)
  acceptance: documented N/A with audit note (PLAN B3 precedent)
  deps: none
  evidence: 2026-08-25 — N/A AUDIT: (a) iOS gestures EMIT keys; Android
  gestures scroll LOCAL scrollback (different product behavior);
  (b) no ALT/meta latch exists anywhere; (c) no drawer. Tests land with
  any ported feature; pinning non-existent code = dead test-only code.


- [x] F12: KeepAliveLoopTest — N/A (no loop on Android; contract already pinned)
  acceptance: documented N/A with audit note
  deps: none
  evidence: 2026-08-25 — N/A AUDIT: no KeepAliveLoop class; keep-alive is
  sshj transport-level setKeepAliveInterval(15) + cosmetic animation.
  Contract already pinned: interval tests in SshConnectAuthInteractionTest
  + constant via F1. Shell-beat loop tests land if that design is ported.


- [x] F13: CrashReportingLifecycleTest — default-off gate matrix + SDK
      privacy options pinned
  acceptance: new `CrashReportingLifecycleTest.kt` green in
  `./gradlew testFossDebugUnitTest`
  deps: none
  evidence: 2026-08-25 — pure shouldReport(available, enabled) gate +
  applyPrivacyOptions(SentryOptions) extracted (initSdk rewired);
  CrashReportingLifecycleTest 5/0 (default-off, no-DSN, no-opt-in, both-on,
  options: no PII/sessions/tracing/threads). Suite 321/0. Marker lifecycle
  = Sentry-internal on Android; no-host-data also covered by existing
  CrashReportingScrubberTest (6).


- [x] F14: TerminalReplay fresh-attach fixture — residue pinned as-is (B2 flips)
  acceptance: replay-shaped TerminalEmulator tests green in
  `./gradlew testFossDebugUnitTest`
  deps: none
  evidence: 2026-08-25 — TerminalReplayTest 3/0 (in MonitorPollTest.kt):
  fresh-attach renders via alt screen; attach-echo RESIDUE pinned as
  current behavior (B2/H3 must flip with its fix); reconnect shows
  persisted+fresh markers. ADAPTED: synthetic PTY streams (no .bin
  fixtures; emulator feed API); live residue QA stays with H3.


- [x] F15: MonitorPollTest — parse-failure/dead-exec preserve prior snapshot
  acceptance: pure poll-loop policy pinned green in
  `./gradlew testFossDebugUnitTest`
  deps: none
  evidence: 2026-08-25 — poll-loop decisions extracted to pure
  MonitorPoll.reduce (MonitorTab rewired, identical); MonitorPollTest 5/0
  (good→refresh, parse-fail/dead-exec preserve prior snapshot, errors only
  when nothing to show). Suite 329/0. KeepAlive half N/A per F12.
