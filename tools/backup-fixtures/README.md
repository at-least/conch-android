# Backup format fixtures

Sources for the `TILDBAK1` conformance fixtures shared by conch-android and
conch-ios (spec: [docs/backup-format.md](../../docs/backup-format.md)).

- `android-v1.json` — payload as Android writes it (every shared field,
  platform flags set)
- `ios-v1.json` — payload as iOS wrote it before the shared spec (sorted
  keys, tunnel `direction`, `group`/`knockPorts` present, fractional
  `createdAt`)
- `GenFixtures.java` — encrypts each with passphrase `conch-parity-2026`
  (fresh salt/IV per run; the JSON is what matters)

`./regen.sh` writes `*.til` next to the sources and copies both `.json`
and `.til` into `app/src/test/resources/fixtures/backup/` and
`../conch-ios/ConchTests/Fixtures/Backup/`. Then run
`CrossPlatformBackupFixtureTest` (Android) and
`CrossPlatformBackupFixtureTests` (iOS).
