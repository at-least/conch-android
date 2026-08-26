# Changelog

## 0.9.1 (unreleased)

- Fixed: crash and gesture bugs — the command palette crashed when history
  contained the same command twice (LazyColumn duplicate key; history only
  dedups consecutive repeats); scrollback drag + fling moved opposite to the
  mouse branch and platform convention (finger down now reveals older
  history, pinned by TerminalScrollTest); the text-selection Copy chip could
  throw on ultra-narrow viewports
- Fixed: the sessions switcher now switches to the TAPPED session — each
  terminal runs in its own task and the switcher moves that task to the
  front; previously tapping session B could surface session A's terminal
- Fixed: ending one session no longer drops the foreground protection of
  the others — per-session notifications, and the service stops only when
  the LAST session goes away
- Fixed: resource leaks — a failed login leaked the connected socket and
  sshj reader threads; the Files tab leaked one SFTP channel per visit
  (channel exhaustion eventually killed the interactive shell); Monitor's
  5s exec poll leaked its channel on failure; a SOCKS port collision
  killed an otherwise healthy shell connection
- Fixed: declining a host-key prompt or failing authentication now stops
  the reconnect loop instead of re-prompting / retrying forever (auth
  retry storms can trip server lockouts)
- Data durability: every store (hosts, snippets, keys, known_hosts,
  command history) writes atomically (temp file + fsync + rename) so a
  crash mid-write can no longer truncate data into permanent loss; a
  corrupt hosts/snippets/keys file is preserved as `*.corrupt` for
  recovery instead of being silently overwritten; a keystore failure no
  longer drops the tail of the host list during legacy migration
- Command history: a transient keystore failure disables the store for
  the session instead of regenerating the key (which permanently bricked
  the file once the keystore recovered); a corrupt stored key is
  regenerated AND persisted (previously each process got a different
  ephemeral key, so history never survived a restart); hardware keys no
  longer reach the hidden terminal while a tool tab is showing; snippets
  reload when their sheet opens
- Internal: unreachable standalone SFTP/Monitor/Docker activities removed
  (~1,100 lines — the in-session tabs replaced them long ago); secrets
  registry gains contains(); About shows the real build version;
  OpenSSH checkint uses SecureRandom
- Internal: codebase cleanup — dead code removed (pre-tab-era standalone
  SFTP/Monitor launchers, a duplicated URL regex, unused state/params),
  `error()` replaces `throw IllegalStateException`, ktlint formatting
  applied project-wide, detekt baseline regenerated from 315 stale/legacy
  entries down to 68 current structural ones; README/VENDOR.md doc drift
  fixed
- Internal/tests: POC.md H2 verified (SharedConnectionProbesTest —
  Monitor/Docker probes parse identically over the shared connection);
  SOCKS5 wire tests consolidated in SocksProxyTest with new error-path
  coverage (bad-ATYP 0x08, silent non-SOCKS5 greeting drop, RST-tolerant
  close detection); TerminalActivity decomposed (self-contained
  composables extracted, 988→790 lines)

- Internal: serialization/persistence tooling upgrade, no user-visible format
  changes (wire formats are pinned byte-equivalent by a new golden-format
  test suite):
  - org.json → kotlinx.serialization for all stores and the TILDBAK1 backup
    payload (typed wire DTOs: HostWire/KeyWire/SnippetWire; corrupt files
    still degrade to empty; legacy plaintext-password migration covered by
    new tests via a mocked SecretsStore)
  - `conchapp_settings` SharedPreferences → Preferences DataStore with
    verbatim automatic migration on first launch (SecretsStore stays on
    Keystore-backed prefs by design)
  - detekt (baseline mode) wired into the build — existing findings locked,
    new code held to the rule set
  - test infra: Robolectric for real SharedPreferences/filesDir paths
    (LEGACY graphics/sqlite mode to coexist with the real-sshd test
    classpath), MockK for the Keystore-bound seam, golden wire-format pins
- Terminal engine swap: the in-house VT100/xterm parser is replaced by the
  battle-tested Termux terminal-emulator (vendored, pinned upstream commit;
  GPL-compatible — see app/src/main/java/com/termux/terminal/VENDOR.md).
  User-visible wins: exact per-cell 24-bit truecolor (no more palette
  quantization when many colors are on screen), full wide/combining-char
  coverage (emoji included), modern escape-sequence support (colon-SGR
  underlines, fish 4.0 / kitty / tmux fixes), and terminal device queries
  (cursor-position reports etc.) now answered instead of silently dropped.
  Same renderer, gestures, themes and scrollback (4000 lines)
- In-session tabs: Terminal / Monitor / Docker / Files now share ONE SSH
  connection (the same one as your live shell) via a bottom NavigationBar.
  Switching away from Terminal and back keeps your buffer, PTY and
  scrollback intact (the terminal view is never torn down). Monitor/Docker/
  Files reuse the live connection instead of opening a separate one
- Command palette: pull-down search over command history + snippets, tap to
  run — two taps instead of ten characters on glass. Prefix matches rank
  above substring; snippets win ties. Snippets & History management sheets
  open from the palette (terminal menu → Command palette)
- Tunnel capsule: a green `⇅ N` chip on the session toolbar shows active
  local port forwards; tap → confirm to stop all tunnels (the SSH session
  stays connected)
- Sessions switcher: the main menu lists every live terminal session when
  more than one is open — tap to switch, swipe to disconnect that session
  only
- Host cards now show a green dot + `live` / `N live` badge when a host has
  active sessions
- Auto-reconnect with exponential backoff (1s → 2s → … → 30s cap, unlimited
  retries): mobile networks drop, and with tmux attached the session lives
  server-side — conch now comes back on its own and restores the screen.
  Tap the amber "Reconnecting" banner to give up
- Connection health banner: status dot with a visible 15s heartbeat pulse
  while connected (synced to the SSH keep-alive cadence), blinking while
  connecting/reconnecting, dim when stopped
- New hosts now default to "Auto-attach tmux" ON (existing hosts keep their
  saved setting; pre-feature backups import as off)

## 0.9.0 (2026-08-19)

- Bracketed paste mode (xterm DECSET 2004): pastes into vim/nano/less land
  verbatim — wrapped in `ESC[200~…ESC[201~` when the remote app enables
  bracketed paste, newline-sanitized (CRLF/CR → LF) otherwise; the IME
  clipboard path routes multi-line commits through the same logic and
  never leaves Ctrl armed
- Searchable per-host command history (encrypted at rest, AES-256-GCM via
  the Android Keystore): terminal menu → History; search, tap to re-run,
  save any entry as a snippet. Conservative capture — arrow-edited and
  tab-completed lines are skipped rather than recorded wrong; opt-out and
  "clear history" in Settings
- Terminal color themes: Default, Dracula, Solarized Dark, Nord, Gruvbox
  Dark — pick in Settings, applied to new sessions
- README: the encrypted backup format is now publicly documented (magic,
  salt/IV layout, AES-256-GCM, PBKDF2-HMAC-SHA256 600k iterations, merge
  semantics, free-forever commitment) — anti-lock-in

## 0.8.1 (2026-08-17)

- Crash reporting wired into all failure paths (14 sites: SSH connect,
  SFTP operations, monitor/docker connect, key import, backup export/
  import) — still opt-in and fully disabled in builds without a DSN;
  wrong backup passphrases are not reported (user input, not a bug)

## 0.8.0 (2026-08-17)

- Multiple concurrent sessions: "Connect (new session)" opens another
  terminal in its own task; each session keeps its own persistent
  notification
- SOCKS5 dynamic port forwarding per host (CONNECT, no-auth; IPv4/IPv6/
  domain targets) — set a port in host settings, then point any
  socks5-aware app at 127.0.0.1:<port>
- Docker management: list containers, start/stop/restart, view logs
  (via docker CLI over SSH; entry in the terminal top bar)
- SFTP: create new (empty) files alongside folders

## 0.7.0 (2026-08-17)

- Foreground service keeps SSH sessions alive in the background with a
  persistent notification (fixes Android killing sessions; requires the
  notification permission on Android 13+)
- Optional biometric app lock (fingerprint / face / device credential),
  off by default, 30 s grace window between activities

## 0.6.0 (2026-08-17)

- Home-screen widget: first four hosts, one tap deep-links into a terminal
- Extra keys row is user-configurable (add/remove keys from an 18-key pool)
  and persists across sessions
- Terminal read buffer 8 KB → 64 KB + per-frame repaint throttling
  (`seq 1 20000` renders smoothly)
- Long-press on a URL in the terminal copies it (URL scan)
- SFTP: sort by name/size/time with direction toggle
- Settings: keep-screen-on while the terminal is open
- Fixed: the extra-keys row was hidden behind the soft keyboard
  (missing `imePadding` on the terminal screen)

## 0.5.0 (2026-08-17)

- Encrypted backup & restore (research-driven, JuiceSSH refugees' #1 pain):
  single-file export of hosts (with passwords), keys, snippets and known_hosts,
  AES-256-GCM with PBKDF2-HMAC-SHA256 (600k iterations) passphrase — restores
  on any device; import merges without overwriting existing data

## 0.4.0 (2026-08-17)

- Truecolor (24-bit RGB) support: exact colors for `38;2;r;g;b` / `48;2` SGR,
  255-slot dynamic color table with palette fallback; `COLORTERM=truecolor`
  advertised for tmux sessions
- Opt-in crash reporting via self-hosted Sentry: OFF by default, Settings
  toggle, host/port scrubbing before send, no PII/breadcrumbs/sessions/tracing;
  DSN injected at build time — builds without it have reporting fully disabled
- Monetization groundwork (reputation-first, ads OFF by default):
  `play` (ads/IAP later) and `foss` (always clean) product flavors,
  Keystore-encrypted one-time unlock flag
- Release builds minified + resource shrinking: APK 24 MB → 4.2 MB
  (R8 keep rules for sshj/jzlib/BouncyCastle/Sentry)
- GPLv3 LICENSE, README, direct-APK (GitHub Releases) distribution plan
  (F-Droid submission dropped by decision 2026-08-17)

## 0.3.0 (2026-08-17)

- tmux auto-attach (`tmux new -A -s conch`): sessions survive disconnects;
  reconnect restores the working screen
- Opt-in local-sshd integration test; fixed test cleanup bug

## 0.2.0 (2026-08-16)

- Market-research backlog implemented:
  - Ed25519 key auth (generate/import, OpenSSH v1 storage)
  - TOFU host key verification + known_hosts
  - Secrets encrypted with Android Keystore (AES-GCM); legacy plaintext migrated
  - Local port forwarding; keep-alive
  - Scrollback (4000 lines) with gesture scrolling and indicator
  - Full hardware-key support (F1–F12, PgUp/PgDn, Home/End, Del, Ctrl+arrows)
  - SFTP browser (list/download/upload/rename/delete/mkdir)
  - Server monitor dashboard (CPU/mem/swap/disk/load/uptime)
  - Snippets; OpenSSH config import; clipboard copy/paste
- Fixed: sshj PKCS#8 cannot parse Ed25519 (now stored as OpenSSH v1);
  key temp-file deleted before lazy parse; terminal focus/IME handling;
  host edit save wrote to stale object

## 0.1.0 (2026-08-16)

- Initial release: host management, sshj shell with PTY, built-in
  VT100/xterm terminal (CJK, 256-color, alt-screen, scroll regions),
  extra keys row, Compose UI
