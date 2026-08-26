# Conch Android

<a href="https://least.at"><strong>least.at</strong></a> · GitHub: [at-least/conch-android](https://github.com/at-least/conch-android)

A free, open-source SSH client for Android — no subscription, no tracking, no ads (an optional one-time "remove ads" purchase may arrive in the Play build later; the direct-APK build will always be clean).

This is the original Conch codebase. An iOS sibling lives at
[at-least/conch-ios](https://github.com/at-least/conch-ios) — same feature
set and byte-compatible backups (`TILDBAK1`), built with Citadel/SwiftTerm.

Built for people who manage servers from their phone: ops, devs, and anyone left stranded by abandoned SSH apps.

## Features

### Terminal
- Built-in VT100/xterm emulator (not a WebView): 256-color + **24-bit truecolor**, CJK wide chars, scroll regions, alt-screen
- **Bracketed paste mode** (DECSET 2004) — multi-line pastes into vim/nano land verbatim, no auto-execution, no justify-mangling
- **Searchable command history** — per-host, encrypted at rest, one tap to re-run or save as snippet
- **Color themes** — Default, Dracula, Solarized Dark, Nord, Gruvbox Dark
- **Scrollback history** (4000 lines) with gesture scrolling and a position indicator
- Hardware keyboard: F1–F12, PgUp/PgDn, Home/End, Del, Ctrl+arrows
- **User-configurable extra-keys row** (18-key pool, layout persists across sessions)
- **Text selection & copy** — long-press + drag selects (into scrollback too), floating Copy chip; long-press a URL still copies just the URL
- **Mouse reporting** — apps that request xterm mouse tracking (htop, vim, tmux, Claude Code) get tap=click, drag, and scroll-as-wheel
- 64 KB read buffer + per-frame repaint throttling — `cat` a huge file without stutter

### Connectivity
- **Auto-reconnect with exponential backoff** (1s → 2s → … → 30s, unlimited retries) — mobile networks drop; conch comes back on its own. Typing `exit` / CTRL+D on a healthy session ends it cleanly instead of looping back in
- **Connection health banner** — four states (connecting / connected / reconnecting(n) / stopped) with a status dot that pulses on the 15-second keep-alive heartbeat; tap the amber banner to give up retrying
- **Multiple concurrent sessions** — "Connect (new session)" opens another terminal; each gets its own persistent notification
- **Foreground service** keeps sessions alive when backgrounded (survives Android's task killers)
- **tmux auto-attach on by default** for new hosts (`tmux new -A -s conch`) — a dropped connection never loses your work; existing hosts keep their saved setting
- **Port forwarding**: local tunnels per host + **SOCKS5 dynamic forwarding** (point any socks5-aware app at `127.0.0.1:<port>`)
- Keep-alive, per-host terminal font size, OSC window-title tracking

### Authentication & security
- Password or **Ed25519 keys** (generate on-device or import OpenSSH/PKCS#8)
- **TOFU host-key verification** with fingerprints (`known_hosts`)
- All secrets encrypted with the **Android Keystore** (AES-256-GCM)
- Optional **biometric app lock** (fingerprint / face / device credential)
- **Opt-in crash reporting** — self-hosted Sentry, off by default, host addresses scrubbed, no PII (builds without a DSN have it fully disabled)

### Tools
- **In-session tabs** — Terminal / Monitor / Docker / Files share ONE SSH
  connection (the same one as your live shell) via a bottom navigation bar;
  switching away from Terminal and back keeps your buffer, PTY and
  scrollback intact
- **Command palette** — pull-down search over command history + snippets,
  tap to run; prefix matches rank above substring, snippets win ties
- **Sessions switcher** — list every live terminal session, tap to switch,
  swipe to disconnect that session only
- **SFTP** — browse, download, upload, rename, delete, mkdir/new-file, sort by name/size/time
- **Monitor** — live CPU / memory / swap / disk / load / uptime dashboard
- **Docker** — list containers, start/stop/restart, view logs
- **Snippets** — save frequent commands, run them from the terminal menu
- **Tunnel capsule** — a green `⇅ N` chip on the session toolbar shows
  active local port forwards; tap to stop all tunnels (session stays connected)
- **Home-screen widget** — first four hosts, one tap deep-links into a terminal
- **OpenSSH config import** — pull `Host` blocks from your `~/.ssh/config`
- **Encrypted backup & restore** — single-file export of everything (hosts, passwords, keys, snippets, known hosts), passphrase-protected (AES-256-GCM + PBKDF2), restores on any device

## Backup format

Backups are portable and self-contained — you are never locked into Conch.
The format is fully specified here so that other tools (or a future Conch
version) can read what Conch writes today.

**File layout** (binary, plain byte concatenation):

| Offset | Field | Size |
|---|---|---|
| 0 | Magic `TILDBAK1` | 8 bytes |
| 8 | Random salt | 16 bytes |
| 24 | Random IV (nonce) | 12 bytes |
| 36 | Ciphertext + GCM tag | rest of file |

**Crypto:**
- Payload encryption: **AES-256-GCM** (128-bit tag). A fresh random salt and a fresh random IV are generated for **every export** — no two backups share key material or nonce.
- Key derivation: **PBKDF2-HMAC-SHA256, 600,000 iterations** over your passphrase with the 16-byte salt, producing a 256-bit key.
- The key is derived from the passphrase only — not from the Android Keystore — so a backup restores on any device. Wrong passphrase = GCM tag verification failure (nothing decrypts, no oracle).

**Contents** (AES-GCM plaintext is a JSON object, `version: 1`):
- `hosts` + `hostSecrets` — all host entries including their passwords
- `keys` + `keySecrets` — SSH keys (Ed25519, PEM private keys included)
- `snippets` — command snippets
- `knownHosts` — your TOFU `known_hosts` file

**Import semantics:** merging, never destructive — importing a backup adds
hosts, keys and snippets that are new; existing entries are never overwritten
or deleted, and `known_hosts` entries are merged (unique lines only).

**Commitment: export and import are free in every Conch build and every
tier — forever.** Data portability is a right, not a feature gate.

## Downloads

| Build | Source | Notes |
|---|---|---|
| Play | Google Play (pending) | `at.least.conch`, ad-free during the reputation phase |
| Direct APK | GitHub Releases | FOSS flavor (`at.least.conch.foss`), zero proprietary dependencies — can coexist with the Play build |

## Build

```bash
./gradlew assembleFossDebug      # FOSS debug APK
./gradlew assemblePlayDebug      # Play debug APK
./gradlew assembleFossRelease    # minified release APK (~5 MB)
./gradlew testFossDebugUnitTest  # unit tests
```

Requirements: JDK 17+ (Temurin 21 tested; note Android Studio's bundled JBR
25 currently fails the Kotlin compiler's version parsing — use a standalone
JDK), Android SDK 35.

Optional build inputs (via `local.properties`, never committed):
- `SENTRY_DSN` / `SENTRY_URL` / `SENTRY_TOKEN` — enable crash reporting and
  R8 mapping upload to a self-hosted Sentry; without them the app runs with
  reporting fully disabled
- `RELEASE_STOREFILE` / `RELEASE_STOREPASSWORD` / `RELEASE_KEY_ALIAS` /
  `RELEASE_KEYPASSWORD` — release signing (falls back to the debug key)

## Project layout

```
app/src/main/java/at/least/conch/
  TerminalEmulator.kt      # VT100/xterm state machine (pure Kotlin, unit-tested)
  TerminalView.kt          # canvas renderer + gesture/keyboard input
  SshSession.kt            # sshj shell + PTY + tunnels + SOCKS5 + shared exec/SFTP
  SessionReconnector.kt    # drop → backoff → rebuild → re-attach orchestration
  ReconnectPolicy.kt       # exponential backoff (1s…30s cap, unlimited retries)
  SocksProxy.kt            # minimal SOCKS5 server bridging to direct-tcpip
  SshConnectionFactory.kt  # auth (password/key), TOFU, keep-alive
  KeyManager.kt            # Ed25519 generate/import (OpenSSH v1 format)
  SecretsStore.kt          # Android Keystore AES-GCM vault
  KnownHosts.kt            # known_hosts store + TOFU verifier
  BackupCodec.kt           # portable encrypted backup format (PBKDF2+GCM)
  SessionTabs.kt           # in-session Monitor/Docker/Files composables (shared connection)
  CommandPaletteModel.kt   # pure filter/rank for the command palette
  CommandPaletteSheet.kt   # pull-down search history+snippets, tap-to-run
  SessionsSheet.kt         # live-sessions switcher (tap switch, swipe disconnect)
  LiveSessions.kt          # process-level live-session registry
  HostCardStatus.kt        # pure host-card live badge derivation
  SftpActivity.kt          # SFTP browser (standalone entry point)
  MonitorActivity.kt       # metrics dashboard (standalone entry point; pure parser unit-tested)
  DockerActivity.kt        # container management (standalone entry point; docker CLI over SSH)
  SessionService.kt        # foreground service keeping sessions alive
  HostsWidget.kt           # home-screen widget
  CrashReporting.kt        # opt-in Sentry wrapper with host scrubbing
  ...
```

Tests run against a real in-process sshd (Apache MINA SSHD) — the same
code paths the app drives, including connect/auth/PTY/SFTP/forwarding,
TOFU accept/reject, and reconnect-after-drop.

## Development roadmap

Active work is gated by the hypotheses in [POC.md](POC.md) — each must run
its `verify` command in this repo and paste real output before
implementation work is committed. The iOS equivalents live in
[conch-ios](https://github.com/at-least/conch-ios).

## Privacy

No accounts, no cloud, no analytics by default. All connection data stays on
the device, encrypted at rest. Crash reports are opt-in and scrub host
addresses before sending.

## License

GPL-3.0 — see [LICENSE](LICENSE).
