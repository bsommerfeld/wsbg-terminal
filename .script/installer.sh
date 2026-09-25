#!/bin/bash
# ==============================================================================
# WSBG Terminal - native installer (installer.sh)
# ==============================================================================
# Builds the installer for the platform this runs on - jpackage cannot build
# for another one. What it installs:
#
#   runtime/          a Java runtime (jlink), fixed until the next installer
#   starter.jar       the launchers' entry point (fx-starter)
#   seed/app.zip      the terminal's package, as the release ships it
#   seed/updater.zip  the updater's package, as the release ships it
#
# The seeds are zipped because jpackage puts every jar it finds in the bundle
# on the launchers' class path - the packages' jars belong in their module
# layer, never there.
#
# and two launchers, both running the starter:
#
#   WSBG Terminal   boots data/app - after laying out app and updater from the
#                   seed on the first start (and after every new installer)
#   WSBG Updater    boots data/updater; the terminal starts it with a handoff
#
# "data" is the per-user data directory (%LOCALAPPDATA%\WSBG,
# ~/Library/Application Support/WSBG, ~/.local/share/wsbg): updates go there,
# the installed bundle is never written to.
#
# Usage:
#   installer.sh <version> <app-package> <updater-package> <starter-jar> <out-dir> [type]
#
#   version          the release tag, e.g. v1.4.0 - recorded as the seed's
#                    version.txt; the installer version is its numeric part
#   app-package      the terminal's unpacked package (lib/, bin/, ...)
#   updater-package  the updater's unpacked package
#   starter-jar      fx-starter's jar
#   out-dir          where the installer is written
#   type             jpackage --type; default dmg (macOS), exe (Windows),
#                    deb (Linux); app-image for a local test without installing
#
# Signing (macOS): with MAC_SIGN_IDENTITY and MAC_SIGN_KEYCHAIN set (see
# macos-keychain.sh) the bundle is signed with that Developer ID, hardened
# runtime included. Notarizing the result is the caller's step.
#
# Requires JAVA_HOME to point at a JDK 27 of this platform (jlink, jpackage).
# Windows needs the WiX Toolset for exe/msi, Linux dpkg for deb, rpmbuild for rpm.
# ==============================================================================
set -euo pipefail

if [ $# -lt 5 ]; then
    sed -n '2,/^# ====/p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
fi

VERSION="$1"
APP_PACKAGE="$2"
UPDATER_PACKAGE="$3"
STARTER_JAR="$4"
OUT_DIR="$5"
TYPE="${6:-}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ICONS="$ROOT/.assets/icons"
JAVA_BIN="${JAVA_HOME:?JAVA_HOME must point at a JDK 27}/bin"

NAME="WSBG Terminal"
UPDATER_NAME="WSBG Updater"
DATA_NAME="WSBG"

# The runtime is not updated with the application, so it carries more than
# today's jars need (jdeps: base, compiler, desktop, logging, scripting, jfr,
# unsupported; net.http for TinyUpdate): the whole Java SE, so a later update
# that reaches for another SE module still boots. jdk.localedata for the
# German number and date formats, limited to the languages we speak.
MODULES="java.se,jdk.unsupported,jdk.jfr,jdk.localedata,jdk.zipfs,jdk.management"
LOCALES="en,de"

# ------------------------------------------------------------------------------
# Platform
# ------------------------------------------------------------------------------
case "$(uname -s)" in
    Darwin)               OS=macos ;;
    Linux)                OS=linux ;;
    MINGW*|MSYS*|CYGWIN*) OS=windows ;;
    *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac
case "$OS" in
    macos)   TYPE="${TYPE:-dmg}"; ICON_EXT=icns ;;
    windows) TYPE="${TYPE:-exe}"; ICON_EXT=ico ;;
    linux)   TYPE="${TYPE:-deb}"; ICON_EXT=png ;;
esac

# jpackage wants a plain numeric version, and macOS one whose first number
# is not 0. A tag without one (a test build) gets 1.0.0.
APP_VERSION="$(echo "$VERSION" | sed -E 's/^[^0-9]*//; s/[^0-9.].*$//; s/\.+$//')"
if ! echo "$APP_VERSION" | grep -Eq '^[1-9][0-9]*(\.[0-9]+){0,2}$'; then
    APP_VERSION="1.0.0"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# A path as the JDK's tools read it: on Windows they are native programs and
# know nothing of the bash's /c/... paths.
native() {
    if [ "$OS" = windows ]; then cygpath -w "$1"; else echo "$1"; fi
}

echo "== $NAME $VERSION ($APP_VERSION) - $OS $TYPE"

# ------------------------------------------------------------------------------
# 1. Stage the bundle's content
# ------------------------------------------------------------------------------
# Each seed is its package as it is, plus the version the starter records
# when it lays it out (Seeder). Zipped with the JDK's jar tool, which every
# platform's JDK has - Windows' bash has no zip.
INPUT="$WORK/input"
mkdir -p "$INPUT/seed"
cp "$STARTER_JAR" "$INPUT/starter.jar"

seed() {
    local package="$1" name="$2" staging="$WORK/seed-$2"
    cp -R "$package" "$staging"
    echo "$VERSION" > "$staging/version.txt"
    "$JAVA_BIN/jar" --create --no-manifest --file "$(native "$INPUT/seed/$name.zip")" -C "$(native "$staging")" .
}
seed "$APP_PACKAGE" app
seed "$UPDATER_PACKAGE" updater

# ------------------------------------------------------------------------------
# 2. Link the runtime
# ------------------------------------------------------------------------------
"$JAVA_BIN/jlink" \
    --add-modules "$MODULES" \
    --include-locales "$LOCALES" \
    --strip-debug --no-header-files --no-man-pages --strip-native-commands \
    --output "$(native "$WORK/runtime")"

# ------------------------------------------------------------------------------
# 3. The launchers' options
# ------------------------------------------------------------------------------
# $APPDIR stays literal: jpackage expands it at launch to where the bundle's
# content was installed. ALL-SYSTEM puts every runtime module into the boot
# layer the starter's module layer resolves against; native access for the
# starter, so it may pass it on (ModuleBoot).
COMMON=(
    "--add-modules=ALL-SYSTEM"
    "--enable-native-access=ALL-UNNAMED"
    "-Dstarter.data=$DATA_NAME"
    '-Dstarter.seed=$APPDIR/seed'
)
TERMINAL=(
    "${COMMON[@]}"
    "-Dstarter.install=app"
    "-Dstarter.seed.installs=app,updater"
    "-Dstarter.main=de.bsommerfeld.wsbg.terminal/de.bsommerfeld.wsbg.terminal.TerminalApp"
    "-Dstarter.native-access=javafx.graphics,de.bsommerfeld.wsbg.orb"
)
UPDATER=(
    "${COMMON[@]}"
    "-Dstarter.install=updater"
    "-Dstarter.main=de.bsommerfeld.updater/de.bsommerfeld.updater.UpdaterApp"
    "-Dstarter.native-access=javafx.graphics"
)

JAVA_OPTIONS=()
for option in "${TERMINAL[@]}"; do
    JAVA_OPTIONS+=(--java-options "$option")
done

# The updater is a second launcher of the same bundle: the terminal finds it
# next to its own (Installation.updaterLauncher). No shortcut, no menu entry -
# only the terminal starts it.
cat > "$WORK/updater.properties" <<EOF
main-jar=starter.jar
main-class=de.bsommerfeld.starter.Starter
java-options=${UPDATER[*]}
icon=$(native "$ICONS/UpdateIcon.$ICON_EXT" | sed 's/\\/\\\\/g')
win-shortcut=false
win-menu=false
linux-shortcut=false
EOF

# ------------------------------------------------------------------------------
# 4. Package
# ------------------------------------------------------------------------------
PLATFORM_OPTIONS=()
case "$OS" in
    macos)
        PLATFORM_OPTIONS+=(--mac-package-identifier de.bsommerfeld.wsbg.terminal)
        if [ -n "${MAC_SIGN_IDENTITY:-}" ]; then
            # jpackage signs everything it finds with the hardened runtime
            # and a secure timestamp; its default entitlements keep library
            # validation off, so natives an update brings still load.
            # --mac-signing-key-user-name wants the part after the prefix.
            PLATFORM_OPTIONS+=(--mac-sign
                --mac-signing-key-user-name "${MAC_SIGN_IDENTITY#Developer ID Application: }"
                --mac-signing-keychain "$MAC_SIGN_KEYCHAIN")
            echo "== Signing as $MAC_SIGN_IDENTITY"
        fi
        ;;
    windows)
        # Per user: no admin rights, and everything lives in the user's own
        # profile, the data directory included. The upgrade UUID stays fixed
        # forever - it is how a new installer recognises and replaces an old one.
        PLATFORM_OPTIONS+=(--win-per-user-install --win-menu --win-menu-group WSBG --win-shortcut
            --win-upgrade-uuid 5b0a6c2e-8f0e-4d1a-9b8e-6f5d2a7c3e41)
        ;;
    linux)
        PLATFORM_OPTIONS+=(--linux-package-name wsbg-terminal --linux-shortcut --linux-app-category Office)
        ;;
esac

mkdir -p "$OUT_DIR"
"$JAVA_BIN/jpackage" \
    --type "$TYPE" \
    --name "$NAME" \
    --app-version "$APP_VERSION" \
    --vendor "bsommerfeld" \
    --description "WSBG Terminal" \
    --icon "$(native "$ICONS/AppIcon.$ICON_EXT")" \
    --input "$(native "$INPUT")" \
    --main-jar starter.jar \
    --main-class de.bsommerfeld.starter.Starter \
    --runtime-image "$(native "$WORK/runtime")" \
    "${JAVA_OPTIONS[@]}" \
    --add-launcher "$UPDATER_NAME=$(native "$WORK/updater.properties")" \
    "${PLATFORM_OPTIONS[@]}" \
    --dest "$(native "$OUT_DIR")"

echo "== Written to $OUT_DIR:"
ls -la "$OUT_DIR"
