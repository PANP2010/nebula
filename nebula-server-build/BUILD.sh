#!/usr/bin/env bash
# Nebula Server Build Instructions
#
# Prerequisites:
#   - Java 25 (already installed)
#   - Network access (fix SSL trust store issue first)
#
# Fix SSL trust store (run once):
#   The Java 25 trust store is missing some CAs. Either:
#   1. Import system certs:
#      security find-certificate -a -p /System/Library/Keychains/SystemRootCertificates.keychain > /tmp/certs.pem
#      keytool -importcert -trustcacerts -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit -file /tmp/certs.pem
#   2. Or set GRADLE_OPTS to use system trust store:
#      export GRADLE_OPTS="-Djavax.net.ssl.trustStore=/Library/Keychains/System.keychain -Djavax.net.ssl.trustStoreType=KeychainStore"
#
# Build steps:
set -euo pipefail
cd "$(dirname "$0")"

echo "=== Step 1: Apply all patches ==="
./gradlew applyAllPatches

echo "=== Step 2: Build nebula-server.jar ==="
./gradlew build

echo "=== Done! ==="
echo "Server jar: nebula-server/build/libs/nebula-server-*.jar"
