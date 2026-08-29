# Conch Android

<a href="https://least.at"><strong>least.at</strong></a> · GitHub: [at-least/conch-android](https://github.com/at-least/conch-android)

A free, open-source SSH client for Android — no subscription, no tracking, no ads (an optional one-time "remove ads" purchase may arrive in the Play build later; the direct-APK build will always be clean).

This is the original Conch codebase. An iOS sibling lives at
[at-least/conch-ios](https://github.com/at-least/conch-ios) — same feature
set and byte-compatible backups (`conch-backup.conchbak`), built with Citadel/SwiftTerm.
Feature parity is tracked in [docs/parity.md](docs/parity.md); the shared
backup format is specified in [docs/backup-format.md](docs/backup-format.md).

Built for people who manage servers from their phone: ops, devs, and anyone left stranded by abandoned SSH apps.

## Features

### Terminal
- Built-in VT100/xterm emulator (not a WebView): 256-color + **24-bit truecolor**, CJK wide chars, scroll regions, alt-screen
- **Bracketed paste mode** (DECSET 2004) — multi-line pastes into vim/nano land verbatim, no auto-execution, no justify-mangling
- **Searchable command history** — per-host, encrypted at rest, one tap to re-run or save as snippet
- **Color themes** — Default, Dracula, Solarized Dark, Nord, Gruvbox Dark
- **Bundled JetBrains Mono Nerd Font** (box-drawing/powerline glyphs for tmux, htop, starship…) — pick it or the system monospace in Settings, same choice as Conch iOS
- **Scrollback history** (4000 lines) with gesture scrolling and a position indicator
- Hardware keyboard: F1–F12, PgUp/PgDn, Home/End, Del, Ctrl+arrows
- **User-configurable extra-keys row** (18-key pool, layout persists across sessions)
- **Text selection & copy** — long-press + drag selects (into scrollback too), floating Copy chip; long-press a URL still copies just the URL
- **Mouse reporting** — apps that request xterm mouse tracking (htop, vim, tmux, Claude Code) get tap=click, drag, and scroll-as-wheel
- 64 KB read buffer + per-frame repaint throttling — `cat` a huge file without stutter

### Connectivity
- **Auto-reconnect with exponential backoff** (1s → 2s → … → 30s, unlimited retries) — mobile networks drop; conch comes back on its own. A Wi-Fi↔cellular handover doesn't wait out the backoff: the moment the device has a network again, the pending retry fires immediately. Typing `exit` / CTRL+D on a healthy session ends it cleanly instead of looping back in
- **Connection health banner** — four states (connecting / connected / reconnecting(n) / stopped) with a status dot that pulses on the 15-second keep-alive heartbeat; tap the amber banner to give up retrying
- **Multiple concurrent sessions** — "Connect (new session)" opens another terminal; each gets its own persistent notification
- **Foreground service** keeps sessions alive when backgrounded (survives Android's task killers)
- **tmux auto-attach on by default** for new hosts (`tmux new -A -s conch`) — a dropped connection never loses your work; existing hosts keep their saved setting
- **Why no mosh?** tmux auto-attach + auto-reconnect already deliver mosh's core promise — work survives drops and network switches, and the client comes back on its own — over plain SSH with no extra server daemon. A native mosh client is not on the roadmap (no JVM implementation exists to build on)
- **Port forwarding**: local (-L) and remote (-R, with server bind address) tunnels per host + **SOCKS5 dynamic forwarding** (point any socks5-aware app at `127.0.0.1:<port>`)
- **ProxyJump, multi-hop** — "Connect via" a saved host whose own jump host is followed too (up to 3 hops); every hop uses its own credentials and host-key pin, failures name the hop, and the editor refuses choices that would form a cycle
- **UDP port knocking** — an ordered knock sequence sent before every dial, for firewalls that hide the SSH port
- **ssh-agent forwarding** (`-A`) per host — offer your stored keys to the server's own `ssh`/`git` hops; off by default with an explicit trust warning
- Keep-alive, per-host terminal font size, OSC window-title tracking

### Authentication & security
- Password or **public-key auth**: generate **Ed25519** on-device, or import
  **Ed25519 / RSA / ECDSA** keys (OpenSSH, PKCS#8/PKCS#5 PEM, PuTTY —
  passphrase-protected included; wrong passphrase just re-prompts) and
  export any key back out (`ssh -i` compatible). No lock-in.
- **TOFU host-key verification** with fingerprints (`known_hosts`)
- All secrets encrypted with the **Android Keystore** (AES-256-GCM) — and
  decrypted private keys are parsed in memory, never spilled to a file, so
  no plaintext key material ever reaches the filesystem
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
- **Share to host** — any app's share sheet → "Upload to SSH host": pick a
  live session and the files land in that shell's current directory
  (tracked via OSC 7), or a saved host to upload into its home; never
  overwrites — a same-named file gets a numbered suffix
- **SAF file provider** — expose any host to Android's system file pickers
  (opt-in per host): every app can open/save remote files through Conch,
  like a mounted drive
- **Monitor** — live CPU / memory / swap / disk / load / uptime dashboard
  with 5-minute CPU/RAM history sparklines (no extra server load)
- **Docker** — list containers, start/stop/restart, view logs
- **Snippets** — save frequent commands, run them from the terminal menu
- **ZMODEM transfers** — `sz <file>` saves straight to your Downloads; `rz`
  on the server pops a file picker and uploads. Both directions verified
  byte-identical against real lrzsz (Termius's most-requested feature, free
  here)
- **Tunnel capsule** — a green `⇅ N` chip on the session toolbar shows
  active local port forwards; tap to stop all tunnels (session stays connected)
- **Home-screen widget** — first four hosts, one tap deep-links into a terminal
- **Host groups & search** — group hosts into sections, filter by name/host/user/group
- **OpenSSH config import** — pull `Host` blocks from your `~/.ssh/config`
- **Encrypted backup & restore** — single-file export of everything (hosts, passwords, keys, snippets, known hosts), passphrase-protected (AES-256-GCM + PBKDF2), restores on any device

## Backup format

Backups are portable and self-contained — you are never locked into Conch.
The format (`CONCHBAK`: PBKDF2-HMAC-SHA256 600k + AES-256-GCM, header
authenticated as AAD, over a canonical JSON payload) is **shared with Conch
iOS** and fully specified in [docs/backup-format.md](docs/backup-format.md);
the cross-platform fixtures are decoded by both apps' test suites so a
backup written on either phone restores on the other with nothing lost.

**Account-free sync**: Settings can keep `conch-backup.conchbak` continuously
materialized in a folder of your choice (Syncthing, Dropbox, a cable —
whatever moves files, no account needed). It refreshes while the app is
open, at most hourly and only when data actually changed; the other device
restores with Import, which merges and never overwrites.

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
  HostGrouping.kt          # pure host-list grouping/search (iOS parity)
  PortKnocker.kt           # UDP port-knock sequence before dial
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

For real-OpenSSH wire behavior there is an opt-in Docker matrix
(`tools/sshd-matrix/`, independent of the conch-ios harness — own image,
container and ports). The default container (Debian bookworm, OpenSSH 9.2)
runs many sshd configs on 127.0.0.1 with fixed users and throwaway test keys:

| port | instance | exercised by |
|---|---|---|
| 2233 | password + pubkey | auth, TOFU, host-key change/RSA pin, SFTP + stress (large / many / non-ASCII / symlink / disk-full / permission), PTY, tmux, ZMODEM (real lrzsz), Monitor probe, throughput + exit codes + SIGWINCH, Docker tab (host docker socket mounted); also on `[::1]` for IPv6 |
| 2234 | pubkey only | key auth (ed25519 / RSA-3072 / ECDSA-P256), refused password, unknown key, FIDO2 `sk-ssh-ed25519` authorized_keys entry |
| 2235 | forwarding allowed | -L/-R tunnels, ssh-agent forwarding, ProxyJump into the container's inner sshd, SOCKS5 |
| 2236 | keyboard-interactive only (PAM) | the 2FA-prompt server shape through the plain password path |
| 2237 | gated by knockd | port knocking: opens for 8 s after UDP 2260,2261,2262 |
| 2238 | hardened | login `Banner`, CA-trusted certificate user, forced-command / `restrict` / `no-pty` authorized_keys keys, `MaxSessions 2`, `PermitOpen`, chrooted `internal-sftp` account |
| 2239 | strict | `MaxAuthTries 1`, `ChannelTimeout` idle-shell reaping |
| 2240 / 2241 | ecdsa-only / rsa-only host key | host-key type pinned by TOFU and matched on promptless reconnect |
| 2242 | legacy appliance | SHA-1 kex, CBC ciphers, `ssh-rsa` (skipped where OpenSSH 10 refuses them) |
| 2270 / 2271 | silent accept / banner-then-stall | bounded handshake-timeout behaviour |

The container also has `NET_ADMIN` so tests can shape its link with
`tc netem` (latency/jitter/loss), a 1 MB tmpfs at `/mnt/tiny` for the
disk-full SFTP case, and the reconnect tests kill session processes,
`docker restart` and `docker pause` it to reproduce real outages (session
kill, sshd host reboot with persisted host keys, silent network freeze
detected only by keep-alive).

`run.sh --variants` adds the distro matrix — the same recipe on Ubuntu
20.04 (OpenSSH 8.2), Ubuntu 24.04 (9.6), Alpine 3.20 (busybox userland),
Debian trixie (OpenSSH 10) and Rocky 9 — and `DockerDistroMatrixTest` runs
auth/SFTP/PTY/Monitor-probe rows against each.

`run.sh --servers` adds non-OpenSSH servers (their own images under
`tools/sshd-matrix/servers/`), so conch is exercised against the SSH stacks
real users actually meet — **Dropbear** (routers / OpenWrt / NAS, 2263-2265),
**tinyssh** (ed25519-only, no password, 2266), **golang.org/x/crypto/ssh**
(Gitea / gliderlabs / bespoke bastions, 2267) and **Paramiko** (Fabric /
pysftp / network automation, 2268). `DockerAltServerTest` pins auth + a
session channel against each. A variant or server that is not running skips
unless `-Dconch.distroMatrix=true` demands it (CI does).

```bash
tools/sshd-matrix/run.sh                        # idempotent: builds image, generates keys, starts container
tools/sshd-matrix/run.sh --variants --servers   # + distro variants and non-OpenSSH servers (or --variant alpine / --server dropbear)
./gradlew testFossDebugUnitTest -Dconch.localSshdTest=true --tests 'at.least.conch.Docker*'
./gradlew testFossDebugUnitTest -Dconch.localSshdTest=true -Dconch.distroMatrix=true \
    --tests 'at.least.conch.Docker*'   # variants + servers must be up
tools/sshd-matrix/run.sh --stop
```

CI runs the whole Docker matrix on every push/PR (`docker-matrix` job).

### On-device tests

`app/src/androidTest` holds instrumented tests for what neither the JVM
nor Robolectric can reach: the Android-Keystore-backed `SecretsStore`
feeding a real connect, the SAF provider driven through `ContentResolver`
(real `ParcelFileDescriptor` pipes), the foreground `SessionService`
posting real notifications, and two UI end-to-end flows — the add-host
Compose form persisting to `HostStore`/`SecretsStore`, and opening
`TerminalActivity` for a saved host so the real `SshSession` connects and a
live session appears in `LiveSessions`. They talk to the same sshd matrix
via the emulator gateway (`10.0.2.2`; `-Pconch.matrixHost=` for a device).

Two constraints keep this suite green: `@Test` method names use underscores
(a spaced backtick name is embedded into a synthesized SimpleName, which
D8 rejects at `minSdk 26` / DEX < 040), and the Compose-form test is gated
to API ≤ 34 (Espresso's `onIdle` calls the `InputManager.getInstance()`
hidden method removed on API 35+). CI's instrumented job runs API 34.

```bash
tools/sshd-matrix/run.sh
./gradlew :app:connectedFossDebugAndroidTest            # matrix missing → skipped
./gradlew :app:connectedFossDebugAndroidTest -Pconch.localSshdTest=true   # → fails (CI)
```

CI runs them nightly, on manual dispatch and on pushes to `main`
(`instrumented` job, API 34 x86_64 emulator).

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
