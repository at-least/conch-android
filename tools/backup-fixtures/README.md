# Backup format fixtures

Sources for the `CONCHBAK` conformance fixtures shared by conch-android and
conch-ios (spec: [docs/backup-format.md](../../docs/backup-format.md)).

- `make_sources.py` — the payloads, written out as canonical JSON:
  - `full-v1.json` — every field populated (both auth methods, all three
    forward types, jump host, platform-specific flags, a key
    without its private half, a revoked known host, IPv6, unicode/quoting)
  - `sparse-v1.json` — minimal writer: every optional field absent, unknown
    keys at every level that readers must ignore (including `knockPorts`,
    a field removed from the spec in 2026-09 — old backups must keep
    importing)
- `GenFixtures.java` — encrypts each with passphrase `conch-parity-2026`
  (fresh salt/nonce per run; the JSON is what matters)

`./regen.sh` runs both and copies `.json` + `.conchbak` into
`app/src/test/resources/fixtures/backup/` and
`../conch-ios/ConchTests/Fixtures/Backup/`. Then run
`CrossPlatformBackupFixtureTest` (Android) and
`CrossPlatformBackupFixtureTests` (iOS).
