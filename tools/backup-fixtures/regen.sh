#!/usr/bin/env bash
# Regenerates the cross-platform backup fixtures from the .json sources in
# this directory and copies them into BOTH repos' test trees. Needs a JDK
# (javax.crypto only — no dependencies). See docs/backup-format.md.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
android="$here/../../app/src/test/resources/fixtures/backup"
ios="$here/../../../conch-ios/ConchTests/Fixtures/Backup"
java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$java" "$here/GenFixtures.java" "$here"
mkdir -p "$android" "$ios"
cp "$here"/{android-v1,ios-v1}.{json,til} "$android/"
cp "$here"/{android-v1,ios-v1}.{json,til} "$ios/"
rm -f "$here"/*.til
echo "fixtures copied to $android and $ios"
