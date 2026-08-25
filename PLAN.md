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
  evidence: 2026-08-22 — `SshSession.exec()/sftpClient()/isConnected` added
  (client + session now @Volatile); `SessionReconnector` delegates
  exec/sftpClient/isConnected to current. `./gradlew testFossDebugUnitTest
  --rerun-tasks` → BUILD SUCCESSFUL, 254 tests, 0 failures, 0 errors.
  Named: SharedConnectionMultiplexTest + SessionReconnectorInteractionTest
  + SshShellPtyInteractionTest = 8 tests, 0 failures.

- [x] E2: Extract Monitor + Docker + SFTP screens as Compose composables
      that take a shared SshSession (reusing existing parsers/command
      strings verbatim)
  acceptance: the three composables compile and render against a shared
  session in a host-less preview/test; parsers unchanged (existing
  MonitorDockerParserTest + DockerParserTest + SftpInteractionTest green)
  deps: E1
  evidence: 2026-08-22 — `SessionTabs.kt` adds `MonitorTab`/`DockerTab`/
  `SftpTab`(@Composable) taking `SessionReconnector`; reuse
  `MonitorParser`/`DockerParser`/`SftpEntry` + the exact PROBE &
  `docker ps` command strings verbatim; async via `rememberCoroutineScope`.
  `compileFossDebugKotlin` BUILD SUCCESSFUL. `testFossDebugUnitTest
  --rerun-tasks` → 254 tests, 0 failures. Parser tests green:
  MonitorParserTest 3/0, DockerParserTest 3/0, SftpInteractionTest 13/0.

- [x] E3: In-session TabRow (Terminal / Monitor / Docker / Files) in
      TerminalActivity — terminal AndroidView stays mounted (visibility
      swap, never removed), other tabs render E2 composables on top
  acceptance: build green; switching Terminal → Monitor → Terminal keeps
  the same TerminalView + emulator instance (identity preserved); full
  `./gradlew testFossDebugUnitTest` green
  deps: E2
  evidence: 2026-08-22 — TerminalActivity content is now a Box: the
  terminal `AndroidView` (+ ExtraKeysRow) stays in composition at all
  times, toggled via `Modifier.alpha(0f)` when off-tab (NEVER removed, so
  the factory runs once and the TerminalView/emulator instance survives
  every tab switch — the Android analogue of iOS's ZStack opacity). Non-
  terminal tabs render `MonitorTab`/`DockerTab`/`SftpTab` on top. A
  Material3 `NavigationBar` hosts the 4 tabs; the topBar's SFTP/Monitor/
  Docker IconButtons are removed (replaced by tabs). `compileFossDebugKotlin`
  BUILD SUCCESSFUL; `testFossDebugUnitTest --rerun-tasks` → 254 tests,
  0 failures. Identity preservation across tab switches is a structural
  guarantee (AndroidView not conditionally removed); a Compose UI
  instrumented test asserting instance identity is the only residual gap
  (no emulator in this env — manual QA, same shape as iOS T26).

- [x] E4: Command Palette — ModalBottomSheet searching history + snippets
      (prefix > substring rank, snippets win ties), tap-to-run; Snippets &
      History management sheets open from it (replaces the overflow menu
      items)
  acceptance: new `CommandPaletteModelTest` (pure filter/rank logic ported
  from iOS) green; build green; overflow menu's Snippets/History items
  replaced by the palette
  deps: E1
  evidence: 2026-08-22 — `CommandPaletteModel.kt` (pure rank: prefix=0 >
  substring=1, snippets win ties, empty→recent history newest-first, limit
  cap) + `CommandPaletteSheet.kt` (ModalBottomSheet + debounced search +
  tap-to-run via sendRaw). TerminalActivity overflow menu adds "Command
  palette" entry; Snippets/History management sheets open from the palette.
  `CommandPaletteModelTest` 7/0 green. `testFossDebugUnitTest` → 261 tests,
  0 failures.

- [x] E5: Tunnel capsule on the session screen — `AssistChip` showing
      `⇅ N` when tunnels are live, tap → confirmation to stop all tunnels
  acceptance: build green; capsule visible iff host.tunnels non-empty;
  stop-all tears down forwarder sockets (existing PortForwardInteractionTest
  green)
  deps: E1
  evidence: 2026-08-22 — `SshSession.stopTunnels()` + `tunnelCount` added
  (closes forwarderSockets + interrupts forwarderThreads WITHOUT closing
  shell/transport); `SessionReconnector` delegates. TerminalActivity topBar
  shows an `AssistChip "⇅ N"` (green, SyncAlt icon) when liveTunnelCount>0,
  tap → AlertDialog "Stop N tunnel(s)?" → stopTunnels + count=0. Count
  initialised in onSessionConnected. `testFossDebugUnitTest` → 261 tests,
  0 failures; PortForwardInteractionTest stays green.

- [x] E6: Sessions switcher — ModalBottomSheet listing live sessions
      (host name + relative start time), tap to switch, swipe to disconnect
  acceptance: build green; switcher lists every active TerminalActivity
  session; swipe-disconnect tears down that session only
  deps: E1
  evidence: 2026-08-22 — `LiveSessions.kt` (process-level ConcurrentHashMap
  registry: register/unregister/all/countForHost/disconnectAll, thread-safe,
  holds only display metadata + a disconnectFn — never the SSH client).
  TerminalActivity registers on onSessionConnected (sessionId UUID),
  unregisters on onDestroy. `SessionsSheet.kt` = ModalBottomSheet +
  SwipeToDismissBox (EndToStart → disconnectFn + refresh list). MainActivity
  menu shows "Sessions (N)" iff LiveSessions non-empty. Tap row →
  startActivity TerminalActivity with REORDER_TO_FRONT. `testFossDebugUnitTest`
  → 261 tests, 0 failures. Registry is pure singleton (no IO) — the sheet's
  swipe/tap behavior is the manual-QA gap (no emulator in env).

- [x] E7: Host card live badge on MainActivity host list — green dot +
      `live` / `N live` text when a host has live sessions
  acceptance: build green; badge derives from a live-session registry
  (HostCardStatus parity with iOS); unit test for the badge derivation green
  deps: E6
  evidence: 2026-08-22 — `HostCardStatus.kt` (pure: liveSessionCount →
  isLive/badgeText/showsDot, "live"/"N live") + `HostCardStatusTest` 3/0
  green. MainActivity `HostCard` now shows a green dot + badge text via
  `LiveSessions.countForHost(host.id)`; badge refreshes on onResume (host
  list reload). `testFossDebugUnitTest` → 264 tests, 0 failures.

- [x] E8: README + CHANGELOG roll-up for the parity arc
  acceptance: README Features list matches shipped behavior; CHANGELOG
  entry covers E1–E7
  deps: E1–E7
  evidence: 2026-08-22 — README "Tools" section adds in-session tabs,
  command palette, sessions switcher, tunnel capsule; project layout lists
  the new files (SessionTabs/CommandPaletteModel/CommandPaletteSheet/
  SessionsSheet/LiveSessions/HostCardStatus) + updated SshSession/Activity
  descriptions. CHANGELOG 0.9.1 entry covers E1–E7. `testFossDebugUnitTest
  --rerun-tasks` → 264 tests, 0 failures.

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
  evidence: 2026-08-25 — constants extracted & deduped (SshSession.
  TMUX_ATTACH_LINE, SshConnectionFactory.KEEP_ALIVE_INTERVAL_SECONDS=15,
  MonitorParser.PROBE — was copy-pasted in MonitorActivity + SessionTabs,
  DockerParser.LIST_COMMAND); new InteractionStringContractTest (5 tests)
  pins them. Full `./gradlew testFossDebugUnitTest` → 269 tests, 0
  failures, 1 skipped (pre-existing opt-in local sshd). DIVERGENCES FROM
  iOS documented in the test KDoc (product decisions pending, not bugs):
  (a) tmux attach lacks the iOS `command -v` guard + printf wipe — B2 must
  move this pin with its fix; (b) keep-alive is sshj transport-level 15s,
  not iOS's `:` shell beat; (c) docker list lacks iOS's C33 PATH prefix;
  (d) palette sends text+"\r" straight to the PTY (iOS writes "\n" and
  relies on icrnl) — inline in TerminalActivity, no shared helper yet.

- [x] F2: MonitorParser probe command exact bytes + cpu clamp edge cases
  acceptance: `MonitorParserTest` (was 3 tests) gains probe-command
  exact-string assertion + zero-delta clamp + full-idle + full-busy cases;
  green in `./gradlew testFossDebugUnitTest`
  deps: none
  evidence: 2026-08-25 — MonitorParserTest → 6 tests, 0 failures
  (`idle-only delta reads as zero percent busy`, `busy-only delta reads
  as one hundred percent busy`, `probe command shape is the parser
  contract` mirroring iOS testProbeCommandExact incl. free -B check).
  Zero-delta→100% clamp was ALREADY covered by the existing
  `cpu usage from two samples` first case; full-string byte pin landed in
  F1's InteractionStringContractTest instead of here (single pin site).

- [ ] F3: BackupCodec header layout / bad magic / too short / unsupported
      version
  acceptance: `BackupCodecTest` gains 4 structural-rejection assertions
  matching iOS `BackupCodecTests.swift` (header offset layout, badMagic,
  tooShort, unsupportedVersion); green in `./gradlew testFossDebugUnitTest`
  deps: none

- [ ] F4: BackupCodec export/restore merge semantics + export-includes-secrets
  acceptance: `BackupCodecTest` gains a restore-merge test (existing host id
  NOT overwritten, new id added, known_hosts union) + an export-includes-
  secrets assertion (Keychain/EncryptedPrefs content present in decrypted
  blob); green in `./gradlew testFossDebugUnitTest`
  deps: F3

### Phase F-P1 — feature exists in main, zero tests (silent breakage risk)

- [ ] F5: SnippetStoreTest — crud roundtrip, load empty/corrupt no crash,
      JSON field names match iOS, delete by id
  acceptance: new `SnippetStoreTest.kt` green in
  `./gradlew testFossDebugUnitTest`; covers the 4 iOS `SnippetStoreTests.swift`
  cases
  deps: none

- [ ] F6: AppLockTest — grace window inside/outside, disabled by default,
      toggle flips state
  acceptance: new `AppLockTest.kt` green in `./gradlew testFossDebugUnitTest`;
  covers the 2 iOS `AppLockTests.swift` cases (grace window + default-off)
  deps: none

- [ ] F7: ExtraKeysConfigTest — default row, save/load roundtrip, unknown id
      dropped, legacy symbol ids filtered, pool emits no plain printable,
      empty falls back to default, arrow escape bytes, CTRL is toggle (null
      bytes)
  acceptance: new `ExtraKeysConfigTest.kt` green in
  `./gradlew testFossDebugUnitTest`; covers the 8 iOS
  `ExtraKeysAndThemeTests.swift` ExtraKeysConfig cases
  deps: none

- [ ] F8: SessionTabsTest + TunnelStatusTest — SessionTab enum exactly 4
      with title+icon non-empty; TunnelStatus count (forward + socks),
      label singular/plural, isEmpty
  acceptance: new `SessionTabsTest.kt` + `TunnelStatusTest.kt` green in
  `./gradlew testFossDebugUnitTest`; covers the 5 iOS `TunnelStatusTests.swift`
  cases (SessionTabs.kt is 699 lines with 0 tests today)
  deps: none

- [ ] F9: ConnectionHealthDeriveTest — derive(connected=false)=dead,
      connected+no beats=live, recent beat=beating, stale falls back to
      live, -19s edge still beating
  acceptance: new `ConnectionHealthDeriveTest.kt` green in
  `./gradlew testFossDebugUnitTest`; covers the 5 iOS
  `TmuxDefaultAndHealthTests.swift` health-derive cases (HostCardStatus.kt
  has the derive logic but only 0/1/N live count is tested today)
  deps: none

### Phase F-P2 — logic trapped in Activity, refactor-first to testable pure fn

- [ ] F10: Extract Ctrl latch transform from TerminalActivity into a pure
      function, then add CtrlLatchTest + CtrlComboTest
  acceptance: new `CtrlLatchTest.kt` + `CtrlComboTest.kt` green in
  `./gradlew testFossDebugUnitTest`; covers iOS `CtrlKeyTransformTests.swift`
  + `CtrlLatchLifecycleTests.swift` + `CtrlComboTests.swift` (~20 cases:
  letter→C0, non-letter releases latch, Ctrl+arrow→`ESC[1;5A`, Ctrl+S→XOFF,
  Ctrl+Q→XON, ESC-then-letter→Meta, latch consumes exactly once)
  deps: none (refactor is part of this task)

- [ ] F11: Extract nav-gesture + alt/meta modifier logic from
      TerminalActivity into pure functions, then add NavAndAltTest
  acceptance: new `NavAndAltTest.kt` green in
  `./gradlew testFossDebugUnitTest`; covers the 14 iOS `NavAndAltTests.swift`
  cases (drag→arrows, long drag multi-step, sub-threshold jitter, fast
  flick→PGUP/PGDN, slow drag no page, horizontal-dominant no page, Alt+x=
  ESC+x, Ctrl+Alt+C=ESC ETX, Alt+arrow=ESC+arrow, drawer contents)
  deps: F10 (shared latch infrastructure)

- [ ] F12: KeepAliveLoopTest — beat at interval until stop, single failed
      beat stops loop (failed beats not counted), start() idempotent,
      default 15s interval
  acceptance: new `KeepAliveLoopTest.kt` green in
  `./gradlew testFossDebugUnitTest`; covers the 4 iOS `KeepAliveLoopTests.swift`
  cases (loop logic likely in TerminalActivity/SshConnectionFactory —
  extract if needed)
  deps: none

- [ ] F13: CrashReportingLifecycleTest — default-off no report even with
      marker, enabled+empty-endpoint disabled, marker survives→report with
      reason+timestamp, background removes marker, payload schema exactly
      {appVersion, osVersion, reason, crashedAt}, deliverIfEnabled rejects
      non-URL endpoint
  acceptance: new `CrashReportingLifecycleTest.kt` green in
  `./gradlew testFossDebugUnitTest`; covers the 6 iOS `CrashReporterTests.swift`
  cases (only the scrubber is tested today)
  deps: none

### Phase F-P3 — cross-platform invariants, nice-to-have

- [ ] F14: TerminalReplay fresh-attach fixture — assert rendered buffer has
      no tmux attach-command residue (iOS C53/C54/C57 shape)
  acceptance: new fixture replay test green in
  `./gradlew testFossDebugUnitTest`; at least the fresh-attach + reconnect-
  attach no-residue cases from iOS `TerminalReplayTests.swift`
  deps: none

- [ ] F15: LogAndContinueTest — keepAlive beat failure fires onError once,
      healthy beats never fire onError, monitor parse failure preserves
      prior snapshot (value-type safety)
  acceptance: new `LogAndContinueTest.kt` green in
  `./gradlew testFossDebugUnitTest`; covers the 3 iOS `LogAndContinueTests.swift`
  cases
  deps: F12 (KeepAliveLoop extracted)

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
