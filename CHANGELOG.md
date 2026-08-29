# Changelog

## 0.9.1 (unreleased)

- **New backup format, shared with Conch iOS** — the pre-release `TILDBAK1`
  container (which mirrored Android's internal `hosts.json` shape and
  needed per-platform tolerance rules) is replaced by `CONCHBAK`
  (`conch-backup.conchbak`), designed clean since neither app had shipped:
  a self-describing header (format version + KDF parameters, authenticated
  as GCM associated data, so an unknown version is rejected before key
  derivation and a tampered iteration count fails the tag), a canonical
  JSON payload (sorted keys — identical data is byte-identical on both
  platforms), SSH-shaped fields (`auth {method,password|keyId}`, `forwards`
  as `-L`/`-R`/`-D` rules, structured `knownHosts`, RFC 3339 timestamps)
  and secrets embedded in their entities instead of parallel id-keyed
  maps. Specified in one document ([docs/backup-format.md](docs/backup-format.md))
  that both apps implement via a boundary mapping (`BackupSchema`); the
  same two fixture backups (full + sparse/forward-compat) are decoded by
  both test suites. No migration from `TILDBAK1` — it never shipped
- New: **host groups** (section headers in the host list, picker of
  existing groups in the editor) and **host search** (name / host / user /
  group) — iOS parity, shared `group` field
- New: **UDP port knocking** per host — an ordered knock sequence fired
  before every dial (not for hosts reached through a jump host, which the
  jump dials). iOS parity, shared `knockPorts` field
- New: remote (-R) tunnels can set the **server bind address** (blank =
  loopback, `0.0.0.0` with `GatewayPorts`); previously always loopback
- Docs: [docs/parity.md](docs/parity.md) — the Android ↔ iOS feature
  matrix and the open gaps on each side
- Fixed: **Disconnect actually disconnected nothing** — the SSH teardown
  ran on the main thread, where Android forbids socket writes; the
  exception was swallowed, the UI said "Disconnected", and the socket,
  sshj reader thread and the remote shell (plus its tmux client) lived on
  until the server's TCP timeout. Teardown now runs on a background
  thread, which also removes the 30-second freeze sshj's channel close
  could cause on a dead link. The Files tab's SFTP channel close had the
  same defect (one leaked server-side channel per visit, until the
  server's channel cap killed the shell)
- Fixed: **a silently dead link is now noticed** — sshj's default
  keep-alive only *sends* packets and never expects an answer, so after a
  Wi-Fi→cellular handover the session sat "connected" (green dot) on a
  dead transport for minutes and the instant-reconnect never fired. The
  15-second keep-alive is now request/response; three unanswered (45 s)
  drops the transport and the reconnect loop takes over
- Fixed: backing out of a host while it was still "Connecting…" could
  leave its tunnel ports bound and the half-built connection leaked for
  the life of the process (the next session to that host then got
  "port in use" tunnels); a target unreachable through a jump host leaked
  the authenticated jump connection on every retry
- Fixed: hosts that cannot connect until you edit them (no password
  saved, key auth with no key, deleted jump host) no longer retry forever
  behind a "Reconnecting (n)…" banner — the error is terminal and says
  what to do, like the missing-key case already did
- Fixed: **ZMODEM — the terminal went dark after every download**: sz's
  final ZFIN arrived after the receiver had been dropped, and the
  replacement swallowed all further shell output until "Cancel file
  transfer". One receiver now lives for the whole session and returns to
  watching after each transfer, so `sz a b` batches work too, sz's "OO"
  sign-off no longer lands at the prompt, and the ZMODEM frames we send
  are no longer recorded as command-history entries. Position headers
  are little-endian as lrzsz expects (a resync at a non-zero offset used
  to stall), ZRINIT advertises its flags in the right byte, and uploads
  over 32 MB are refused with a pointer to SFTP instead of an OOM crash
- Fixed: hardware/IME Backspace sends DEL (0x7F) — the PTY's default
  `erase`, what Termux and ConnectBot send — instead of BS, which only
  readline understood (`sudo` password prompts, `read`, `less` inserted
  a literal ^H)
- Security: **crash reports actually scrub host addresses** — Sentry
  turns the throwable into its exception list before our scrubber ran,
  so "Connection refused: prod.example.com:22" still reached the server;
  the list is scrubbed now (and there is a test on the event, not just
  on the regex). Key import no longer writes the plaintext key to the
  cache directory; it is parsed from memory like connect already was
- Security: Android Auto Backup is off. Secrets are sealed with a
  per-device Keystore key that never leaves the device, so a cloud or
  device-to-device restore produced hosts and keys that could not be
  decrypted — every host present, none able to connect. Settings →
  Backup (passphrase-encrypted) and account-free sync are the portable
  paths and were always the intended ones
- Fixed: **account-free sync can no longer overwrite your only backup
  with an empty one** — when the Keystore cannot decrypt the stored
  secrets (a reset, or a transient failure), the export is refused with a
  clear reason instead of writing a file with no passwords or keys, which
  Syncthing would then have propagated everywhere. Restoring a backup on
  a device after a Keystore reset refills the unreadable secrets instead
  of adding nothing because "the ids already exist"
- Fixed: SAF file provider — a tree grant on a host's root is scoped to
  the SFTP home (an app holding it could construct any absolute path
  on the host), display names containing "/" or ".." are refused, a
  connection drop mid-copy now surfaces as an I/O error to the calling
  app instead of a clean EOF that saved a silently truncated file, the
  idle sweeper can no longer close a connection an operation just
  re-leased, and a dead pooled connection is replaced instead of retried
  against forever
- Fixed: a legacy-password migration that hit a Keystore error for one
  host rewrote hosts.json without that host's password (permanent loss);
  the file is now left intact so the migration retries next launch. A
  corrupt entry in the secrets store no longer crashes every store read
- Fixed: `~/.ssh/config` import understands tab-separated and `Key=value`
  lines (a tab after `HostName` produced a host with an empty hostname)
  and no longer applies a `Match` block's directives to the preceding
  `Host`
- Fixed: two sessions and one denied foreground-service start (Android
  14+ background timing) no longer stop the service for both; the 6-hour
  timeout now cancels every session's notification (the extra ones were
  ongoing and could not be dismissed)
- Fixed: the home screen's live-sessions badge updates when a session
  ends in another task (the registry was not observable state); a
  corrupt keys.json no longer lets the next generate/import/delete
  rewrite it as a one-key list (the stored keys' secrets are intact —
  the file is kept as keys.json.corrupt and writes are refused with a
  message until it is restored or removed); Ed25519 keys in PKCS#8 v2
  form (embedded public key / attributes) import correctly; ZMODEM
  16-bit frame headers and CRCs are ZDLE-escaped like the 32-bit ones
- Fixed: **the app lock re-prompted on every screen change** — Android
  stops the previous activity *after* starting the next one, so the
  per-activity "went to background" hook zeroed the 30-second grace window
  right after the new screen had passed it (and cancelling that prompt
  closed the app). The lock now re-arms only when no activity of the app
  is on screen
- Fixed: Docker tab — container logs and daemon errors are stderr, which
  `exec` never returned: logs of most services showed an empty dialog and
  "docker: command not found" showed an empty list. Files tab — "Retry"
  after an SFTP failure did nothing but spin; a slow directory listing
  could overwrite the folder you had already navigated to; leaving the tab
  mid-open leaked the SFTP channel; uploads from Downloads/Drive were
  named after the provider's opaque id (`msf:123`) instead of the file;
  downloads land in a `.part` file and are renamed on success instead of
  leaving a truncated file under the real name, and a hostile server name
  containing "/" is refused
- Fixed: command history no longer records a wrong line after readline
  editing keys (Ctrl-R search, Ctrl-W/K/A/E): the line the shell ran is
  not the line that was typed, so it is skipped like Tab completion
- Fixed: slow scrollback drags — each 120 Hz touch event was rounded to
  whole lines on its own, so a finger moving slowly never scrolled; the
  sub-cell remainder is carried between events. X10 mouse clicks beyond
  column 223 (wide landscape terminals) are clamped instead of dropped.
  CPU% no longer double-counts VM guest time
- Fixed: host editor — the tunnel list survived rotation as the only field
  that did not; invalid tunnels (port 0, blank host) and an out-of-range
  SOCKS port are refused at Save with a message instead of being silently
  skipped at connect; deleting a host updates the widget and clears other
  hosts' jump-host reference to it. Keys — passphrase-protected imports run
  bcrypt-pbkdf off the main thread (seconds of freeze before), generate/
  delete failures are messages instead of crashes, imported files get their
  real display name, and the export target survives process death behind
  the file picker. Settings — a too-short backup passphrase keeps the
  dialog open instead of discarding the picked file; two sync exports can
  no longer run at once and leave a "conch-backup (1).til" behind. The
  sessions sheet drops a swiped session immediately and both it and the
  host cards follow the live-session registry
- Fixed: rotating the screen during a backup export/import or sync setup
  no longer drops the picked file and the passphrase dialog, or re-enables
  the buttons while the previous instance is still writing (two imports
  could run at once) — the flow state and its worker live in a ViewModel
  now. Monitor: a host without `df -B1` (busybox) shows CPU/memory/load
  with the disk card marked n/a instead of "Failed to read metrics"
- Fixed: the ALT extra-key stayed lit after the view consumed the latch;
  editing a snippet updates the row immediately instead of on next
  resume; the app lock no longer closes the app when the system itself
  withdraws the biometric prompt (incoming call, rotation) — it just
  re-prompts
- New: **dark mode, and Material You** — every screen ran on Compose's
  default light palette regardless of the system setting, because the app
  had no theme wrapper at all; there is now one Material 3 theme that
  follows the system and, on Android 12+, takes its colors from the
  device's own wallpaper palette. The terminal keeps its own theme
  (Dracula, Nord, …) unchanged, and "connected" green / "reconnecting"
  amber stay fixed across dynamic color so a status color never stops
  meaning what it means
- Changed: the UI is rebuilt on stock Material components — host, key,
  snippet, file, container and session rows are list items; settings are
  switch rows in labelled groups instead of one card each; the auth-type
  and tunnel-direction pickers are segmented buttons; multi-action
  dialogs became bottom sheets; transient feedback is a snackbar instead
  of a toast that painted over the terminal. Every emoji standing in for
  an icon (🔑 ⚙ ⇅ ↕ ● ⚠) is a real vector icon that follows the theme
- Fixed: **the port-forwarding editor no longer scrolls sideways** — four
  fields on one line hid inputs entirely on a 375 dp phone; each tunnel
  is now a stacked card. Host-form validation errors appear on the field
  they belong to instead of in a toast that is gone before you look
- Fixed: the Files tab's sort control cycled blindly through six states
  behind one button (picking "Modified, newest" took five taps, and the
  options were never listed) — it is a menu with a checkmark now; and
  deleting a remote file asks first, since SFTP cannot undo it
- Fixed: **edge-to-edge insets**, mandatory from targetSdk 35 — content
  is inset properly instead of running under the status and gesture bars,
  the terminal's extra-keys row no longer double-pads when the keyboard
  opens, and the per-host actions that were long-press-only got a visible
  button. Extra-key buttons meet the 48 dp minimum touch target
- Changed: the home-screen widget follows the system theme and Material
  You (it was three hardcoded near-blacks that stayed dark on a light
  home screen) and uses the launcher's own corner radius
- New: **hosts in Android's system file pickers (SAF)** — opt in per host
  (Edit host → "Files in system picker") and every app can open/save remote
  files through Conch once you grant a folder; browse/mkdir/rename/delete,
  roots land in the SFTP home like your shell does (the Secure ShellFish
  gap on Android)
- New: **ssh-agent forwarding** (`-A`) per host — your stored keys sign for
  the server's own ssh/git hops; off by default with an explicit trust
  warning (enabling exposes every stored key). Includes `ForwardAgent`
  config import; the on-device agent implements the wire protocol itself
  because no JVM SSH library ships one
- New: **account-free sync** — Settings keeps the encrypted backup
  continuously materialized in a folder you pick (Syncthing/Dropbox/cable),
  refreshing at most hourly and only when data changed; restore on any
  device with merge-only Import. No accounts, no cloud, no new permissions
- New: **instant reconnect when the network comes back** — a Wi-Fi↔cellular
  handover or airplane-mode-off no longer leaves the terminal dark for the
  rest of the backoff (up to 30s); the pending retry fires the moment the
  device has a usable network again. The backoff itself is unchanged, so a
  server that is genuinely down is not hammered
- Fixed: the **FOSS and Play builds can now actually be installed side by
  side**, as the README promises — both declared the same SAF file-provider
  authority, and Android rejects the second install of a duplicated
  authority (INSTALL_FAILED_CONFLICTING_PROVIDER)
- Security: **decrypted private keys never touch the filesystem** — connect
  and agent-forwarding signatures used to write the plaintext PEM to the app
  cache and delete it afterwards (leaving it behind entirely if the process
  was killed in between); both now parse the key from memory
- Accessibility: a tap on the terminal is a real click, so TalkBack (and any
  service using ACTION_CLICK) can raise the keyboard; themed monochrome
  launcher icon for Android 13+
- New: **Monitor history sparklines** — 5 minutes of CPU/RAM lines under
  the live cards, at zero extra server load (samples ride the existing 5s
  poll; failed probes leave honest gaps instead of a fake flat line)
- New: key export (`ssh -i`-compatible OpenSSH/PKCS#8), public-key
  one-tap copy, passphrase-protected key import with retry
- Fixed: connecting with a key whose stored material is gone (a Keystore
  reset invalidates device-encrypted blobs) or unreadable now fails with
  a named, actionable error ("re-import the key, then edit the host to
  use it") instead of the cryptic "Key data not found", and the reconnect
  loop stops — no retry can bring key material back (previously it
  retried forever)
- Fixed: sessions left running past Android 15's 6-hour dataSync
  foreground-service cap no longer crash the app — the limit now ends
  background protection gracefully with a notification (tmux sessions
  survive server-side and re-attach on reopen)
- Compatibility: targetSdk/compileSdk 36 (predictive-back-safe: no
  legacy onBackPressed paths), Gradle 8.13 / AGP 8.9.2 / Robolectric
  4.16.1 toolchain bump
- Internal/tests: Android lint now gates CI alongside tests and detekt
  (it caught the provider-authority collision above); the terminal's tap
  handling, the Monitor cards and the settings/backup preference writes were
  cleaned up to keep the report at zero errors
- Internal/tests: an independent Docker OpenSSH test matrix
  (`tools/sshd-matrix`, deliberately separate from the iOS project's)
  now covers auth scenarios, TOFU pinning, SFTP, both tunnel directions,
  PTY semantics, real tmux, ZMODEM against real rz/sz through a real SSH
  PTY, and agent forwarding end-to-end against real OpenSSH

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
