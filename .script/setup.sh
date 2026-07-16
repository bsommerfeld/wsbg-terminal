#!/bin/bash

# ==============================================================================
# WSBG Terminal - macOS/Linux Setup Script (setup.sh)
# ==============================================================================
# Prepares the runtime environment on app start:
# 1. Installs our OWN, isolated Ollama binary under <appData>/ollama/bin.
# 2. Starts a private Ollama server (own port + own model store).
# 3. Pulls the LLMs into that isolated store.
# 3b. Installs the OCR runtime (Tesseract) under <appData>/tesseract.
# 4. Pre-installs JCEF + fonts and scaffolds the config.
#
# Full isolation: we never touch a user's existing Ollama (binary, models, or
# the server on the default port 11434). Everything lives under <appData>/ollama,
# so uninstalling is just deleting the app data folder.
# ==============================================================================

set -e

# ==============================================================================
# CONFIG -- bump these to upgrade Ollama or change the models
# ==============================================================================
# Pinned Ollama version = the GitHub release tag WITHOUT the leading "v".
# To upgrade: set the new version here. On the next launch the isolated binary
# under <appData>/ollama is re-downloaded automatically (downloaded models kept).
#   Releases: https://github.com/ollama/ollama/releases
OLLAMA_VERSION="0.24.0"

# Models reconciled into our ISOLATED store (<appData>/ollama/models): section 3
# installs/updates these to the latest registry build and removes anything else.
# ONE gemma4 tag serves the whole editorial pipeline -- the single deployed model.
# The launcher passes the resolved tag via WSBG_REASONING_MODEL (hardware check
# + the user's config.toml choice live in ModelSelection there; valid tiers are
# gemma4:e2b..31b, with the -mlx twins as the STANDARD on Apple Silicon). The
# fallback below only applies to standalone script runs without the launcher
# and mirrors that platform split.
DEFAULT_MODEL="gemma4:e4b"
[ "$(uname -s)" = "Darwin" ] && [ "$(uname -m)" = "arm64" ] && DEFAULT_MODEL="gemma4:e4b-mlx"
REASONING_MODEL="${WSBG_REASONING_MODEL:-$DEFAULT_MODEL}"   # the editorial agent model

# Private endpoint -- our instance binds here, NEVER the user's default 11434.
OLLAMA_PORT="11500"
# ==============================================================================

echo "=========================================="
echo "   WSBG Terminal - Setup & Installation   "
echo "=========================================="

# Degraded-but-not-fatal steps report through warn(); the script then exits
# with code 10 so the launcher can show "Setup completed with warnings"
# instead of claiming a clean run. Keep the code in sync with
# EnvironmentSetup.EXIT_WITH_WARNINGS (launcher) and setup.bat.
SETUP_WARNED=0
warn() {
    SETUP_WARNED=1
    echo "    [WARN] $1"
}

OS="$(uname -s)"

# ------------------------------------------------------------------------------
# Resolve the app data dir (mirrors StorageUtils) + the isolated ai/ layout.
# ------------------------------------------------------------------------------
# Windows lives in setup.ps1. macOS/Linux have no Roaming/Local split, so these
# paths are unchanged.
if [ "$OS" = "Darwin" ]; then
    CONFIG_DIR="$HOME/Library/Application Support/wsbg-terminal"
elif [ -n "$XDG_DATA_HOME" ]; then
    CONFIG_DIR="$XDG_DATA_HOME/wsbg-terminal"
else
    CONFIG_DIR="$HOME/.local/share/wsbg-terminal"
fi
CONFIG_FILE="$CONFIG_DIR/config.toml"

# Everything AI lives under <appData>/ollama, fully isolated from any Ollama the
# user already has. Our binary lands at ai/bin/ollama (linux tarball) or
# ai/ollama (macOS tgz); we resolve both.
AI_DIR="$CONFIG_DIR/ollama"
AI_MODELS="$AI_DIR/models"
if [ -x "$AI_DIR/bin/ollama" ]; then
    OLLAMA="$AI_DIR/bin/ollama"
else
    OLLAMA="$AI_DIR/ollama"
fi

# Isolation env -- applies to every ollama invocation below (version check,
# serve, pulls). Pins our port + model store away from the user's instance.
export OLLAMA_HOST="127.0.0.1:$OLLAMA_PORT"
export OLLAMA_MODELS="$AI_MODELS"
mkdir -p "$AI_MODELS"

# ------------------------------------------------------------------------------
# 1. Install / update OUR isolated Ollama binary (pinned; never the system one)
# ------------------------------------------------------------------------------
install_ollama() {
    local want="$OLLAMA_VERSION"
    local have=""
    [ -x "$OLLAMA" ] && have=$("$OLLAMA" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
    if [ "$have" = "$want" ]; then
        echo "[*] Isolated Ollama $want already present."
        return 0
    fi
    echo "[*] Installing isolated Ollama $want into $AI_DIR ..."

    local arch base url tmp
    arch="$(uname -m)"
    base="https://github.com/ollama/ollama/releases/download/v${want}"

    # Remove only the runtime (keep downloaded models under $AI_MODELS).
    rm -rf "$AI_DIR/bin" "$AI_DIR/lib" "$AI_DIR/ollama"
    mkdir -p "$AI_DIR"

    # --retry matches setup.ps1: a transient network hiccup must not abort
    # a ~1 GB download that was already underway.
    if [ "$OS" = "Darwin" ]; then
        url="$base/ollama-darwin.tgz"
        tmp="/tmp/ollama-darwin-$$.tgz"
        curl -fL --retry 3 --retry-delay 2 --progress-bar -o "$tmp" "$url" || { warn "Download failed."; return 1; }
        tar -xzf "$tmp" -C "$AI_DIR" || { warn "Extract failed."; rm -f "$tmp"; return 1; }
        rm -f "$tmp"
    else
        case "$arch" in
            aarch64|arm64) arch="arm64" ;;
            *)             arch="amd64" ;;
        esac
        url="$base/ollama-linux-${arch}.tar.zst"
        tmp="/tmp/ollama-linux-$$.tar.zst"
        curl -fL --retry 3 --retry-delay 2 --progress-bar -o "$tmp" "$url" || { warn "Download failed."; return 1; }
        # .tar.zst needs zstd: GNU tar --zstd (>=1.31), else the standalone CLI.
        if tar --zstd -xf "$tmp" -C "$AI_DIR" 2>/dev/null; then
            :
        elif command -v zstd >/dev/null 2>&1; then
            zstd -dc "$tmp" | tar -x -C "$AI_DIR" || { warn "Extract failed."; rm -f "$tmp"; return 1; }
        else
            warn "Cannot extract .tar.zst (need 'zstd' or GNU tar >=1.31)."
            rm -f "$tmp"; return 1
        fi
        rm -f "$tmp"
    fi

    # Re-resolve the binary location after extraction.
    if [ -x "$AI_DIR/bin/ollama" ]; then
        OLLAMA="$AI_DIR/bin/ollama"
    elif [ -x "$AI_DIR/ollama" ]; then
        OLLAMA="$AI_DIR/ollama"
    else
        warn "ollama binary not found after extraction -- check archive layout."
        return 1
    fi
    chmod +x "$OLLAMA" 2>/dev/null || true
    echo "    Isolated Ollama ready at $OLLAMA"
}

install_ollama || warn "Isolated Ollama install failed -- continuing."

# ------------------------------------------------------------------------------
# 2. Start OUR server on the private port (stopped again at the end of setup)
# ------------------------------------------------------------------------------
OLLAMA_PID=""
if [ -x "$OLLAMA" ]; then
    if curl -sf -m 2 "http://$OLLAMA_HOST/api/tags" > /dev/null 2>&1; then
        echo "[*] Our Ollama server already running on $OLLAMA_HOST."
    else
        echo "[*] Starting isolated Ollama server on $OLLAMA_HOST ..."
        "$OLLAMA" serve > /dev/null 2>&1 &
        OLLAMA_PID=$!

        READY=false
        for i in $(seq 1 30); do
            if curl -sf -m 2 "http://$OLLAMA_HOST/api/tags" > /dev/null 2>&1; then
                READY=true
                break
            fi
            sleep 0.5
        done

        if [ "$READY" = true ]; then
            echo "    Server ready."
        else
            warn "Server did not respond in time -- pulls may fail."
        fi
    fi
fi

# ------------------------------------------------------------------------------
# 3. Reconcile the isolated store to the desired model set (install / update / GC)
# ------------------------------------------------------------------------------
# DESIRED_MODELS below is the single source of truth. For each one we compare the
# local manifest digest ('ollama list' ID) against the registry's manifest digest
# (SHA-256 of the manifest, fetched WITHOUT downloading the model) and pull only
# when missing or stale -- so models stay current with no blind re-pull. Anything
# in the store that is NOT desired is removed, so a model switch leaves no
# Altlasten. To switch models, edit DESIRED_MODELS (and OLLAMA_VERSION above if
# the new model needs a newer runtime); the check URL, pull, and GC all follow.
#
# FUTURE (separate, larger step): today this reconcile-against-local pattern
# ("declare the desired set, diff it against what is actually installed, GC the
# rest") governs ONLY the Ollama models. It should grow to cover the WHOLE
# managed footprint we install on the user's machine -- the Ollama binary/
# version, the JCEF (Chromium) runtime, bundled fonts, and any future dependency
# we add or swap. Then, when we e.g. move off JCEF or replace Ollama, the old
# artifact falls out of the desired set and is uninstalled automatically on the
# next setup run, on every OS, with no per-release one-shot cleanup code and no
# reliance on the user having seen the intervening release. One desired-set
# checked against local state is the whole design; models are just the first
# thing wired into it.
DESIRED_MODELS=("$REASONING_MODEL")

echo "[*] Models (isolated store: $AI_MODELS):"
for m in "${DESIRED_MODELS[@]}"; do echo "    - $m"; done

# Local manifest digest (= 'ollama list' ID, 12 hex) for an installed tag, or "".
local_digest() {
    "$OLLAMA" list 2>/dev/null | tail -n +2 \
        | awk -v m="$1" 'tolower($1)==tolower(m){print $2; exit}'
}

# Remote manifest digest (first 12 hex) with no model download -- the cheap
# "is an update available?" probe. The registry serves the manifest body but no
# Docker-Content-Digest header, so the digest is the SHA-256 of the manifest
# bytes (verified to equal the 'ollama list' ID / tags-page digest). The URL is
# DERIVED from the name: "name:tag" -> .../library/name/manifests/tag. A name
# already containing a "/" is a full namespace (community model), used verbatim.
remote_digest() {
    local model="$1" name tag path tmp sha
    name="${model%%:*}"
    tag="${model#*:}"
    [ "$tag" = "$model" ] && tag="latest"
    case "$name" in
        */*) path="$name" ;;
        *)   path="library/$name" ;;
    esac
    tmp="/tmp/ollama-manifest-$$-$RANDOM.json"
    # -f makes curl fail (no output, nonzero) on 404/5xx; a temp file hashes the
    # exact bytes (command substitution would strip a trailing newline and change
    # the digest). Empty/echo "" on any failure so the caller treats it as
    # "registry unreachable" rather than computing a bogus digest.
    if ! curl -sf -m 8 -o "$tmp" \
        -H 'Accept: application/vnd.docker.distribution.manifest.v2+json' \
        "https://registry.ollama.ai/v2/$path/manifests/$tag" 2>/dev/null; then
        rm -f "$tmp"; return 0
    fi
    if ! grep -q 'schemaVersion' "$tmp" 2>/dev/null; then rm -f "$tmp"; return 0; fi
    if command -v sha256sum >/dev/null 2>&1; then sha="sha256sum"; else sha="shasum -a 256"; fi
    $sha < "$tmp" | grep -oE '^[0-9a-f]{12}'
    rm -f "$tmp"
}

# Install-or-update each desired model. Track full presence so the GC below never
# deletes the old model while a new one failed to land (e.g. an offline switch).
# Guarded on the binary (mirrors setup.ps1): without it every pull would just
# fail noisily. The launcher tracks model names from the exact "> Pulling
# <model>..." wording -- keep extra detail on its own line, never appended.
if [ -x "$OLLAMA" ]; then
    ALL_PRESENT=true
    # 1-based position + total in the desired set, appended to each "> Pulling"
    # line as "(idx/total)". The launcher reads this to render one pip per model
    # so the user sees HOW MANY models are being installed, not just the current
    # one. Keep the "(idx/total)" token EXACTLY here -- EnvironmentSetup parses it.
    midx=0
    mtotal=${#DESIRED_MODELS[@]}
    for model in "${DESIRED_MODELS[@]}"; do
        midx=$((midx + 1))
        have=$(local_digest "$model")
        want=$(remote_digest "$model")
        if [ -z "$have" ]; then
            echo "    > Pulling $model ($midx/$mtotal)..."
            "$OLLAMA" pull "$model" || { warn "Failed to pull $model -- continuing"; ALL_PRESENT=false; }
        elif [ -z "$want" ]; then
            echo "    [OK] $model present (update check skipped -- registry unreachable)"
        elif [ "$have" = "$want" ]; then
            echo "    [OK] $model up to date ($have)"
        else
            echo "    Update available: $model ($have -> $want)"
            echo "    > Pulling $model ($midx/$mtotal)..."
            "$OLLAMA" pull "$model" || warn "Failed to update $model -- keeping $have"
        fi
    done

    # GC: drop every isolated-store model that is no longer desired. Skipped when
    # a desired pull failed, so we never remove the old model before the new one
    # is safely in place. Collect the stale set FIRST so the launcher gets one
    # "[*] Cleaning up old models..." phase header (emitted ONLY when there is
    # actually something to remove -- an empty run stays silent) plus an
    # "(idx/total)" on each removal line.
    if [ "$ALL_PRESENT" = true ]; then
        STALE_MODELS=()
        while read -r inst; do
            [ -z "$inst" ] && continue
            keep=false
            for model in "${DESIRED_MODELS[@]}"; do
                [ "$(printf '%s' "$inst" | tr 'A-Z' 'a-z')" = "$(printf '%s' "$model" | tr 'A-Z' 'a-z')" ] && { keep=true; break; }
            done
            [ "$keep" = false ] && STALE_MODELS+=("$inst")
        done < <("$OLLAMA" list 2>/dev/null | tail -n +2 | awk '{print $1}')

        if [ "${#STALE_MODELS[@]}" -gt 0 ]; then
            # Phase header the launcher (ScriptOutputClassifier) turns into the
            # "Räume Altlasten weg" step -- keep this exact wording, it is a
            # parsed token. Same for the "> Removing stale model <m> (idx/total)"
            # line below.
            echo "[*] Cleaning up old models..."
            sidx=0
            stotal=${#STALE_MODELS[@]}
            for inst in "${STALE_MODELS[@]}"; do
                sidx=$((sidx + 1))
                echo "    > Removing stale model $inst ($sidx/$stotal)..."
                "$OLLAMA" rm "$inst" >/dev/null 2>&1 || warn "Could not remove $inst"
            done
        fi
    fi
else
    warn "Isolated Ollama binary missing -- skipping model install."
fi

# ------------------------------------------------------------------------------
# 3b. Install the OCR runtime (Tesseract) into the isolated container
# ------------------------------------------------------------------------------
# The terminal reads Reddit images mechanically (OCR); the engine probes
# <appData>/tesseract/{lib,tessdata} BEFORE any system install. Every app
# release carries the assets (release.yml, job tesseract_bundle): the macOS
# arm64 dylib closure and the platform-neutral tessdata (eng+osd+deu).
# /releases/latest/download always resolves — every release has the assets.
# Fully optional: on any failure the terminal runs without image text.
TESS_DIR="$CONFIG_DIR/tesseract"
TESS_BASE="https://github.com/bsommerfeld/wsbg-terminal/releases/latest/download"

install_tesseract() {
    # macOS arm64 gets our self-contained dylib bundle; Linux uses the distro
    # library (hint below) and only needs the traineddata. Intel Macs have no
    # bundle (arm64-only CI runner) and fall back to a system install too.
    local need_lib=false
    [ "$OS" = "Darwin" ] && [ "$(uname -m)" = "arm64" ] && need_lib=true

    local have_data=false have_lib=true
    [ -f "$TESS_DIR/tessdata/eng.traineddata" ] && have_data=true
    if [ "$need_lib" = true ] && [ ! -f "$TESS_DIR/lib/libtesseract.dylib" ]; then
        have_lib=false
    fi
    if [ "$have_data" = true ] && [ "$have_lib" = true ]; then
        echo "[*] OCR runtime already installed."
        return 0
    fi

    echo "[*] Installing OCR runtime (Tesseract) into $TESS_DIR ..."
    mkdir -p "$TESS_DIR"
    local tmp
    if [ "$have_data" = false ]; then
        tmp="/tmp/tess-data-$$.tar.gz"
        curl -fL --retry 3 --retry-delay 2 --progress-bar -o "$tmp" "$TESS_BASE/tesseract-tessdata.tar.gz" \
            || { warn "OCR tessdata download failed."; rm -f "$tmp"; return 1; }
        tar -xzf "$tmp" -C "$TESS_DIR" || { warn "OCR tessdata extract failed."; rm -f "$tmp"; return 1; }
        rm -f "$tmp"
    fi
    if [ "$have_lib" = false ]; then
        tmp="/tmp/tess-lib-$$.tar.gz"
        curl -fL --retry 3 --retry-delay 2 --progress-bar -o "$tmp" "$TESS_BASE/tesseract-macos-arm64.tar.gz" \
            || { warn "OCR library download failed."; rm -f "$tmp"; return 1; }
        tar -xzf "$tmp" -C "$TESS_DIR" || { warn "OCR library extract failed."; rm -f "$tmp"; return 1; }
        rm -f "$tmp"
    fi
    if [ "$OS" = "Linux" ] && ! ls /usr/lib/*/libtesseract.so* /usr/lib/libtesseract.so* >/dev/null 2>&1; then
        echo "    Hint: image reading needs the system Tesseract library (e.g. 'sudo apt install tesseract-ocr')."
    fi
    echo "    OCR runtime ready at $TESS_DIR"
}

install_tesseract || warn "OCR runtime install failed -- images are skipped, the terminal still runs."

# ------------------------------------------------------------------------------
# 4. Pre-install JCEF (embedded Chromium) native bundle
# ------------------------------------------------------------------------------
# Without this, the terminal downloads ~120 MB of Chromium on the first
# real run, blocking the UI for several seconds. Doing it here means the
# launcher progress UI shows what's happening instead.
#
# Skipped when the install marker exists. The JCEF maven version is
# coupled to jcefbuild release 1.0.65 -- bump together.

# Under <appData>/wsbg-terminal so the whole footprint (Ollama, models, fonts,
# config, JCEF) stays in one uninstall-clean directory. Keep aligned with
# CefHost.resolveInstallDir().
JCEF_DIR="$CONFIG_DIR/jcef-bundle"
# Coupled to jcefmaven 132.3.1 -- bump together with the Maven version in pom.xml.
JCEF_NATIVE_VERSION="jcef-1770317+cef-132.3.1+g144febe+chromium-132.0.6834.83"

install_jcef() {
    if [ -f "$JCEF_DIR/install.lock" ]; then
        echo "[*] Browser runtime already installed."
        return 0
    fi

    local UNAME_OS UNAME_ARCH PLATFORM_OS PLATFORM_ARCH PLATFORM
    UNAME_OS="$(uname -s)"
    UNAME_ARCH="$(uname -m)"

    case "$UNAME_OS" in
        Darwin) PLATFORM_OS="macosx" ;;
        Linux)  PLATFORM_OS="linux" ;;
        *)      warn "Unsupported OS for JCEF: $UNAME_OS"; return 1 ;;
    esac
    case "$UNAME_ARCH" in
        arm64|aarch64) PLATFORM_ARCH="arm64" ;;
        x86_64|amd64)  PLATFORM_ARCH="amd64" ;;
        *)             warn "Unsupported arch for JCEF: $UNAME_ARCH"; return 1 ;;
    esac
    PLATFORM="${PLATFORM_OS}-${PLATFORM_ARCH}"

    echo "[*] Installing browser runtime ($PLATFORM)..."
    # Pull the jcefmaven native artifact -- that JAR contains an inner
    # tar.gz with the flat install layout the runtime library expects.
    # The GitHub jcefbuild releases use a different (bundle-wrapped)
    # layout that would need per-platform flattening.
    local URL="https://repo1.maven.org/maven2/me/friwi/jcef-natives-${PLATFORM}/${JCEF_NATIVE_VERSION}/jcef-natives-${PLATFORM}-${JCEF_NATIVE_VERSION}.jar"
    local TMP_JAR="/tmp/jcef-native-$$.jar"
    local TMP_TAR="/tmp/jcef-native-$$.tar.gz"

    mkdir -p "$JCEF_DIR"
    curl -fL --progress-bar -o "$TMP_JAR" "$URL" || { warn "JCEF download failed."; rm -f "$TMP_JAR"; return 1; }
    # Extract the inner tar.gz from the JAR (it's a regular ZIP file).
    unzip -p "$TMP_JAR" "*.tar.gz" > "$TMP_TAR" || { warn "JCEF inner tarball extract failed."; rm -f "$TMP_JAR" "$TMP_TAR"; return 1; }
    tar -xzf "$TMP_TAR" -C "$JCEF_DIR" || { warn "JCEF extract failed."; rm -f "$TMP_JAR" "$TMP_TAR"; return 1; }
    rm -f "$TMP_JAR" "$TMP_TAR"
    : > "$JCEF_DIR/install.lock"
    echo "    Browser runtime ready."
}

install_jcef || warn "JCEF install incomplete -- falling back to runtime download on first launch."

# ------------------------------------------------------------------------------
# 5. Install JetBrains Mono + Inter fonts for the terminal UI
# ------------------------------------------------------------------------------
# Web fonts served locally by the terminal's AssetServer at /fonts/.
# Without them, the page falls back to the system mono/sans stack --
# functional but visually off-brand. jsDelivr hosts the OFL-licensed
# woff2 builds, no rate limit and no version pinning needed for the
# latin subset.

FONT_DIR="$CONFIG_DIR/fonts"
FONT_MARKER="$FONT_DIR/.install.ok"

install_fonts() {
    if [ -f "$FONT_MARKER" ]; then
        echo "[*] Fonts already installed."
        return 0
    fi
    echo "[*] Installing terminal fonts..."
    mkdir -p "$FONT_DIR"

    local base="https://cdn.jsdelivr.net/fontsource/fonts"
    local failed=0
    for spec in \
        "jetbrains-mono-400.woff2|$base/jetbrains-mono@latest/latin-400-normal.woff2" \
        "jetbrains-mono-500.woff2|$base/jetbrains-mono@latest/latin-500-normal.woff2" \
        "jetbrains-mono-600.woff2|$base/jetbrains-mono@latest/latin-600-normal.woff2" \
        "inter-400.woff2|$base/inter@latest/latin-400-normal.woff2" \
        "inter-600.woff2|$base/inter@latest/latin-600-normal.woff2"; do
        local name="${spec%%|*}"
        local url="${spec##*|}"
        if curl -fsL "$url" -o "$FONT_DIR/$name"; then
            echo "    [OK] $name"
        else
            warn "Failed to download $name"
            failed=1
        fi
    done

    if [ "$failed" = 0 ]; then
        : > "$FONT_MARKER"
        echo "    Fonts ready."
    else
        warn "Font install partial -- UI will use system fallback for missing weights."
    fi
}

install_fonts || true

# ------------------------------------------------------------------------------
# 6. Generate Configuration File (if strictly new)
# ------------------------------------------------------------------------------

if [ ! -f "$CONFIG_FILE" ]; then
    echo "[*] Generating Application Configuration..."

    mkdir -p "$CONFIG_DIR"

    cat > "$CONFIG_FILE" <<EOL
# WSBG Terminal Configuration
# Auto-generated by setup.sh

debug-mode = false
ui-reddit-visible = true

[agent]
# Editorial agent reasoning model. REASONING_POWER (gemma4) - the one
# model serving the whole editorial pipeline. Managed centrally; leave as-is.
agent.editorial-model = "REASONING_POWER"
# Ollama model tag override (gemma4:e2b..31b, -mlx twins on Apple Silicon).
# Empty = managed default. Set by the future model-choice UI; the launcher
# reads it and installs the matching model on the next start.
agent.model-tag = ""

[reddit]
# Add reddit settings here if needed
EOL

    echo "[*] Configuration written to: $CONFIG_FILE"
else
    echo "[*] Configuration already exists. Skipping generation."
fi

# ------------------------------------------------------------------------------
# 7. Stop the temporary setup server
# ------------------------------------------------------------------------------
# The terminal app starts and OWNS its own isolated instance, so we shut down
# the server we spun up for the pulls. Kill only the PID we started (and its
# runner children) -- never a separately-running Ollama.
if [ -n "$OLLAMA_PID" ] && kill -0 "$OLLAMA_PID" 2>/dev/null; then
    echo "[*] Stopping temporary setup Ollama server (PID $OLLAMA_PID)..."
    pkill -P "$OLLAMA_PID" 2>/dev/null || true
    kill "$OLLAMA_PID" 2>/dev/null || true
fi

echo ""
echo "=========================================="
echo "   Setup Complete! Ready to Run.          "
echo "=========================================="
echo "Run using: .script/run.sh"

# Exit 10 = "finished, but degraded" -- the launcher shows "Setup completed
# with warnings" and proceeds. 0 = clean run. (EnvironmentSetup.EXIT_WITH_WARNINGS)
if [ "$SETUP_WARNED" = "1" ]; then
    exit 10
fi
exit 0
