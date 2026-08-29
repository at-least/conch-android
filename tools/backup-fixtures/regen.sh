#!/usr/bin/env bash
# Regenerates the cross-platform backup fixtures and copies them into BOTH
# repos' test trees. Needs python3 and a JDK (javax.crypto only — no
# dependencies). See docs/backup-format.md.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
android="$here/../../app/src/test/resources/fixtures/backup"
ios="$here/../../../conch-ios/ConchTests/Fixtures/Backup"
java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
python3 "$here/make_sources.py"
"$java" "$here/GenFixtures.java" "$here"
mkdir -p "$android" "$ios"
rm -f "$android"/*.{json,til,conchbak} "$ios"/*.{json,til,conchbak}
cp "$here"/{full-v1,sparse-v1}.{json,conchbak} "$android/"
cp "$here"/{full-v1,sparse-v1}.{json,conchbak} "$ios/"
rm -f "$here"/*.conchbak
echo "fixtures copied to $android and $ios"
