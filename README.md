# Conch

<a href="https://least.at"><strong>least.at</strong></a> · GitHub: [at-least/conch-android](https://github.com/at-least/conch-android)

A free, open-source SSH client for Android — no subscription, no tracking, no ads (an optional one-time "remove ads" purchase may arrive in the Play build later; the direct-APK build will always be clean).

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
- Long-press any URL in the output to copy it
- 64 KB read buffer + per-frame repaint throttling — `cat` a huge file without stutter

### Connectivity
- **Multiple concurrent sessions** — "Connect (new session)" opens another terminal; each gets its own persistent notification
- **Foreground service** keeps sessions alive when backgrounded (survives Android's task killers)
- Optional auto-attach into `tmux` (`tmux new -A -s conch`) — a dropped connection never loses your work
- **Port forwarding**: local tunnels per host + **SOCKS5 dynamic forwarding** (point any socks5-aware app at `127.0.0.1:<port>`)
- Keep-alive, per-host terminal font size, OSC window-title tracking

### Authentication & security
- Password or **Ed25519 keys** (generate on-device or import OpenSSH/PKCS#8)
- **TOFU host-key verification** with fingerprints (`known_hosts`)
- All secrets encrypted with the **Android Keystore** (AES-256-GCM)
- Optional **biometric app lock** (fingerprint / face / device credential)
- **Opt-in crash reporting** — self-hosted Sentry, off by default, host addresses scrubbed, no PII (builds without a DSN have it fully disabled)

### Tools
- **SFTP** — browse, download, upload, rename, delete, mkdir/new-file, sort by name/size/time
- **Monitor** — live CPU / memory / swap / disk / load / uptime dashboard
- **Docker** — list containers, start/stop/restart, view logs
- **Snippets** — save frequent commands, run them from the terminal menu
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

Requirements: JDK 17, Android SDK 35.

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
  SshSession.kt            # sshj shell + PTY + tunnels + SOCKS5
  SocksProxy.kt            # minimal SOCKS5 server bridging to direct-tcpip
  SshConnectionFactory.kt  # auth (password/key), TOFU, keep-alive
  KeyManager.kt            # Ed25519 generate/import (OpenSSH v1 format)
  SecretsStore.kt          # Android Keystore AES-GCM vault
  KnownHosts.kt            # known_hosts store + TOFU verifier
  BackupCodec.kt           # portable encrypted backup format (PBKDF2+GCM)
  SftpActivity.kt          # SFTP browser
  MonitorActivity.kt       # metrics dashboard (pure parser unit-tested)
  DockerActivity.kt        # container management (docker CLI over SSH)
  SessionService.kt        # foreground service keeping sessions alive
  HostsWidget.kt           # home-screen widget
  CrashReporting.kt        # opt-in Sentry wrapper with host scrubbing
  ...
```

## Privacy

No accounts, no cloud, no analytics by default. All connection data stays on
the device, encrypted at rest. Crash reports are opt-in and scrub host
addresses before sending.

## License

GPL-3.0 — see [LICENSE](LICENSE).
