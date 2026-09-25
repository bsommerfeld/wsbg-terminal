#!/bin/bash
# Downloads libcurl-impersonate, the native library TinyFetch binds through
# FFM, into .native/<platform>/ - the directory TinyFetch's tests and a local
# run look in. Pass platforms as arguments to fetch others than this machine's:
#
#   .script/natives.sh                          this machine
#   .script/natives.sh windows-x86_64 linux-x86_64
#
# Platform names are TinyFetch's (and TinyUpdate's): {macos,windows,linux}-{x86_64,aarch64}.
set -euo pipefail

# Bump together with Browser.CHROME: the impersonation target it names must
# exist in this release.
VERSION="v2.2.3"
BASE="https://github.com/lexiforest/curl-impersonate/releases/download/$VERSION"

cd "$(dirname "$0")/.."

current_platform() {
    local os arch
    case "$(uname -s)" in
        Darwin) os=macos ;;
        Linux) os=linux ;;
        MINGW*|MSYS*|CYGWIN*) os=windows ;;
        *) echo "unsupported OS: $(uname -s)" >&2; return 1 ;;
    esac
    case "$(uname -m)" in
        arm64|aarch64) arch=aarch64 ;;
        x86_64|amd64) arch=x86_64 ;;
        *) echo "unsupported architecture: $(uname -m)" >&2; return 1 ;;
    esac
    echo "$os-$arch"
}

# Our platform name -> the release's target triple and the library inside it.
asset_of() {
    case "$1" in
        macos-aarch64)   echo "arm64-macos libcurl-impersonate.4.dylib libcurl-impersonate.dylib" ;;
        macos-x86_64)    echo "x86_64-macos libcurl-impersonate.4.dylib libcurl-impersonate.dylib" ;;
        linux-x86_64)    echo "x86_64-linux-gnu libcurl-impersonate.so.4 libcurl-impersonate.so" ;;
        linux-aarch64)   echo "aarch64-linux-gnu libcurl-impersonate.so.4 libcurl-impersonate.so" ;;
        windows-x86_64)  echo "x86_64-win32 lib/libcurl-impersonate.dll libcurl-impersonate.dll" ;;
        windows-aarch64) echo "arm64-win32 lib/libcurl-impersonate.dll libcurl-impersonate.dll" ;;
        *) echo "unknown platform: $1" >&2; return 1 ;;
    esac
}

platforms=("$@")
if [ ${#platforms[@]} -eq 0 ]; then
    platforms=("$(current_platform)")
fi

for platform in "${platforms[@]}"; do
    read -r triple member target <<< "$(asset_of "$platform")"
    out=".native/$platform"
    mkdir -p "$out"
    work="$(mktemp -d)"
    curl -fsSL "$BASE/libcurl-impersonate-$VERSION.$triple.tar.gz" | tar xzf - -C "$work"
    # The dylib/so symlinks point at the versioned file; copy the real bytes
    # under the one name TinyFetch looks for.
    cp -L "$work/$member" "$out/$target"
    rm -rf "$work"
    echo "$platform: $out/$target ($VERSION)"
done
