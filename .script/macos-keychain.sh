#!/bin/bash
# ==============================================================================
# Imports the Developer ID certificate into a throwaway keychain (CI, macOS)
# ==============================================================================
# Reads APPLE_CERT_BASE64 (a "Developer ID Application" certificate with its
# private key, as .p12, base64) and APPLE_CERT_PASSWORD. On success it writes
# MAC_SIGN_IDENTITY and MAC_SIGN_KEYCHAIN to $GITHUB_ENV, which is what the
# signing steps after it key off. Without a certificate it does nothing - the
# build then ships unsigned.
# ==============================================================================
set -euo pipefail

if [ -z "${APPLE_CERT_BASE64:-}" ]; then
    echo "No APPLE_CERT_BASE64 - building unsigned."
    exit 0
fi

CERT="$RUNNER_TEMP/developer_id.p12"
KEYCHAIN="$RUNNER_TEMP/app-signing.keychain-db"
KEYCHAIN_PASSWORD="$(openssl rand -base64 24)"

echo -n "$APPLE_CERT_BASE64" | base64 --decode > "$CERT"

security create-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN"
# Unlocked for the whole job.
security set-keychain-settings -lut 21600 "$KEYCHAIN"
security unlock-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN"
security import "$CERT" -P "$APPLE_CERT_PASSWORD" -k "$KEYCHAIN" -T /usr/bin/codesign
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KEYCHAIN_PASSWORD" "$KEYCHAIN" >/dev/null

# codesign and jpackage search the keychain search list, not the default
# keychain: ours joins it, the login keychain stays.
security list-keychains -d user -s "$KEYCHAIN" $(security list-keychains -d user | sed s/\"//g)

IDENTITY=$(security find-identity -v -p codesigning "$KEYCHAIN" \
    | sed -n 's/.*"\(Developer ID Application: .*\)".*/\1/p' | head -1)
if [ -z "$IDENTITY" ]; then
    echo "::error::No 'Developer ID Application' identity in the certificate. Export a Developer ID"
    echo "::error::Application certificate WITH its private key as .p12 (base64 -> APPLE_CERT_BASE64)."
    security find-identity -v -p codesigning "$KEYCHAIN" || true
    exit 1
fi

echo "Signing identity: $IDENTITY"
{
    echo "MAC_SIGN_IDENTITY=$IDENTITY"
    echo "MAC_SIGN_KEYCHAIN=$KEYCHAIN"
} >> "$GITHUB_ENV"
