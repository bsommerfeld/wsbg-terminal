#!/bin/bash

# ==============================================================================
# WSBG Terminal - macOS/Linux Setup Script (setup.sh)
# ==============================================================================
# Prepares the runtime environment on app start:
# 1. Installs our OWN, isolated Ollama binary under <appData>/ollama/bin.
# 2. Starts a private Ollama server (own port + own model store).
# 3. Pulls the LLMs into that isolated store.
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
# One multimodal gemma4:e4b serves agent + vision; embeddinggemma does vectors.
# (The gemma4:e4b-mlx build is text-only -- no vision encoder -- so we avoid it.)
REASONING_MODEL="gemma4:e4b"          # editorial agent + vision (multimodal)
VISION_MODEL="gemma4:e4b"             # same model serves vision
EMBED_MODEL="embeddinggemma:latest"   # 768d cluster embeddings

# Private endpoint -- our instance binds here, NEVER the user's default 11434.
OLLAMA_PORT="11500"
# ==============================================================================

echo "=========================================="
echo "   WSBG Terminal - Setup & Installation   "
echo "=========================================="

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

    if [ "$OS" = "Darwin" ]; then
        url="$base/ollama-darwin.tgz"
        tmp="/tmp/ollama-darwin-$$.tgz"
        curl -fL --progress-bar -o "$tmp" "$url" || { echo "    [WARN] Download failed."; return 1; }
        tar -xzf "$tmp" -C "$AI_DIR" || { echo "    [WARN] Extract failed."; rm -f "$tmp"; return 1; }
        rm -f "$tmp"
    else
        case "$arch" in
            aarch64|arm64) arch="arm64" ;;
            *)             arch="amd64" ;;
        esac
        url="$base/ollama-linux-${arch}.tar.zst"
        tmp="/tmp/ollama-linux-$$.tar.zst"
        curl -fL --progress-bar -o "$tmp" "$url" || { echo "    [WARN] Download failed."; return 1; }
        # .tar.zst needs zstd: GNU tar --zstd (>=1.31), else the standalone CLI.
        if tar --zstd -xf "$tmp" -C "$AI_DIR" 2>/dev/null; then
            :
        elif command -v zstd >/dev/null 2>&1; then
            zstd -dc "$tmp" | tar -x -C "$AI_DIR" || { echo "    [WARN] Extract failed."; rm -f "$tmp"; return 1; }
        else
            echo "    [WARN] Cannot extract .tar.zst (need 'zstd' or GNU tar >=1.31)."
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
        echo "    [WARN] ollama binary not found after extraction -- check archive layout."
        return 1
    fi
    chmod +x "$OLLAMA" 2>/dev/null || true
    echo "    Isolated Ollama ready at $OLLAMA"
}

install_ollama || echo "    [WARN] Isolated Ollama install failed -- continuing."

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
            echo "    [WARN] Server did not respond in time -- pulls may fail."
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
DESIRED_MODELS=("$REASONING_MODEL" "$EMBED_MODEL")
# Agent and vision share the one gemma4:e4b -- only add a distinct vision model
# if a future config ever diverges them.
[ "$VISION_MODEL" != "$REASONING_MODEL" ] && DESIRED_MODELS+=("$VISION_MODEL")

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
ALL_PRESENT=true
for model in "${DESIRED_MODELS[@]}"; do
    have=$(local_digest "$model")
    want=$(remote_digest "$model")
    if [ -z "$have" ]; then
        echo "    > Pulling $model (not installed)..."
        "$OLLAMA" pull "$model" || { echo "    [WARN] Failed to pull $model -- continuing"; ALL_PRESENT=false; }
    elif [ -z "$want" ]; then
        echo "    [OK] $model present (update check skipped -- registry unreachable)"
    elif [ "$have" = "$want" ]; then
        echo "    [OK] $model up to date ($have)"
    else
        echo "    > Updating $model ($have -> $want)..."
        "$OLLAMA" pull "$model" || echo "    [WARN] Failed to update $model -- keeping $have"
    fi
done

# GC: drop every isolated-store model that is no longer desired. Skipped when a
# desired pull failed, so we never remove the old model before the new one is
# safely in place.
if [ "$ALL_PRESENT" = true ]; then
    while read -r inst; do
        [ -z "$inst" ] && continue
        keep=false
        for model in "${DESIRED_MODELS[@]}"; do
            [ "$(printf '%s' "$inst" | tr 'A-Z' 'a-z')" = "$(printf '%s' "$model" | tr 'A-Z' 'a-z')" ] && { keep=true; break; }
        done
        if [ "$keep" = false ]; then
            echo "    > Removing stale model $inst ..."
            "$OLLAMA" rm "$inst" >/dev/null 2>&1 || echo "    [WARN] Could not remove $inst"
        fi
    done < <("$OLLAMA" list 2>/dev/null | tail -n +2 | awk '{print $1}')
fi

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
        *)      echo "    [WARN] Unsupported OS for JCEF: $UNAME_OS"; return 1 ;;
    esac
    case "$UNAME_ARCH" in
        arm64|aarch64) PLATFORM_ARCH="arm64" ;;
        x86_64|amd64)  PLATFORM_ARCH="amd64" ;;
        *)             echo "    [WARN] Unsupported arch for JCEF: $UNAME_ARCH"; return 1 ;;
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
    curl -fL --progress-bar -o "$TMP_JAR" "$URL" || { echo "    [WARN] JCEF download failed."; rm -f "$TMP_JAR"; return 1; }
    # Extract the inner tar.gz from the JAR (it's a regular ZIP file).
    unzip -p "$TMP_JAR" "*.tar.gz" > "$TMP_TAR" || { echo "    [WARN] JCEF inner tarball extract failed."; rm -f "$TMP_JAR" "$TMP_TAR"; return 1; }
    tar -xzf "$TMP_TAR" -C "$JCEF_DIR" || { echo "    [WARN] JCEF extract failed."; rm -f "$TMP_JAR" "$TMP_TAR"; return 1; }
    rm -f "$TMP_JAR" "$TMP_TAR"
    : > "$JCEF_DIR/install.lock"
    echo "    Browser runtime ready."
}

install_jcef || echo "    [WARN] JCEF install incomplete -- falling back to runtime download on first launch."

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
            echo "    [WARN] Failed to download $name"
            failed=1
        fi
    done

    if [ "$failed" = 0 ]; then
        : > "$FONT_MARKER"
        echo "    Fonts ready."
    else
        echo "    [WARN] Font install partial -- UI will use system fallback for missing weights."
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
# Editorial agent reasoning model. REASONING_POWER (gemma4:e4b) - one
# multimodal model serving agent + vision. Managed centrally; leave as-is.
agent.editorial-model = "REASONING_POWER"

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
