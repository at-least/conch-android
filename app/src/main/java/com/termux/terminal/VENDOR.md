# Vendored: Termux terminal-emulator

Origin: https://github.com/termux/termux-app — module `terminal-emulator`
(plus its test suite under `app/src/test/java/com/termux/terminal/`).

- Pinned commit: `30ebb2dee381d292ade0f2868cfde0f9f20b89fe` (master, 2026-04)
- License: the repository is GPLv3-only; `terminal-emulator` derives from
  jackpal/Android-Terminal-Emulator (Apache-2.0) — see the repo's LICENSE.md
  exceptions section. Conch Android is GPL-3.0, so both readings are
  compatible. Upstream copyright headers are preserved in every file.
- Files NOT vendored (local-PTY machinery conch does not use):
  `JNI.java`. `TerminalSession.java` is replaced by a compile-only stub
  (private constructor, never instantiated) so `TerminalSessionClient`'s
  signatures — which take `TerminalSession` parameters — build without
  upstream's JNI-backed implementation; conch feeds the emulator from an
  SSH channel instead of a local PTY.

## Local modifications

All modifications are marked with `CONCH PATCH` comments:

- `TerminalEmulator.java`: added `isBracketedPasteMode()` exposing the
  DECSET 2004 bit so conch's paste path can pick bracketed vs sanitized
  encoding (upstream answers this only inside `TerminalOutput`-routed
  `paste()`, which conch bypasses to keep its own sanitize rules).
- `WcWidth.java`: variation selectors U+FE00..U+FE0F return width 0
  (upstream omits them, so U+FE0F emoji presentation advanced the cursor
  a full cell and misaligned every following glyph).

## Why vendor instead of JitPack

JitPack serves `com.termux.termux-app:terminal-emulator`, but the upstream
README states the project is looking for maintainers; vendoring pins the
bytes under our VCS, keeps the FOSS flavor free of extra repository
sources, and lets us carry the (documented) patch above. Re-sync
procedure: copy the files listed above from a termux-app commit, re-apply
the `CONCH PATCH` markers, run `./gradlew testFossDebugUnitTest`.
