#!/bin/bash
# ==============================================================================
# Signs every native library of a macOS package (sign-macos-natives.sh)
# ==============================================================================
# Apple's notary service looks into every zip and jar of a submission and
# refuses the whole DMG over one unsigned library - the seeds inside the
# bundle are zips of jars, and JavaFX's jars carry dylibs. So before anything
# is packed, every .dylib/.jnilib in the given directories is signed: loose
# files as they are, those inside a jar extracted, signed and written back.
#
# Signed before TinyUpdate packs the release, so the jars the manifest hashes
# are the signed ones: the seed an installer carries and the release an
# update fetches stay byte for byte the same.
#
# Usage: sign-macos-natives.sh <identity> <keychain> <dir>...
# ==============================================================================
set -euo pipefail

IDENTITY="$1"
KEYCHAIN="$2"
shift 2

sign() {
    codesign --force --timestamp --options runtime --keychain "$KEYCHAIN" --sign "$IDENTITY" "$1"
}

count=0
for dir in "$@"; do
    [ -d "$dir" ] || continue

    while IFS= read -r library; do
        sign "$library"
        count=$((count + 1))
    done < <(find "$dir" -type f \( -name '*.dylib' -o -name '*.jnilib' \))

    while IFS= read -r jar; do
        entries=$(unzip -Z1 "$jar" | grep -E '\.(dylib|jnilib)$' || true)
        [ -n "$entries" ] || continue

        jar="$(cd "$(dirname "$jar")" && pwd)/$(basename "$jar")"
        work="$(mktemp -d)"
        while IFS= read -r entry; do
            (cd "$work" && unzip -q -o "$jar" "$entry")
            sign "$work/$entry"
            # Written back under its own path, the rest of the jar untouched.
            (cd "$work" && zip -q "$jar" "$entry")
            count=$((count + 1))
        done <<< "$entries"
        rm -rf "$work"
        echo "Signed natives in $(basename "$jar")"
    done < <(find "$dir" -type f -name '*.jar')
done

echo "Signed $count native libraries."
