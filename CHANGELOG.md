# Changelog

## 0.9.1 (unreleased)

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
