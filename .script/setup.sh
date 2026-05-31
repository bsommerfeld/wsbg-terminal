#!/bin/bash

# ==============================================================================
# WSBG Terminal - macOS/Linux Setup Script (setup.sh)
# ==============================================================================
# Executes environment preparations upon application start.
# Responsibilities include:
# 1. Installing or updating the local Ollama service.
# 2. Modifying the shell's PATH dynamically.
# 3. Triggering initial downloads for LLMs natively via `ollama pull`.
# 4. Bootstrapping configuration scaffolds for newly configured apps.
#
# Model strategy: one multimodal gemma4:e4b serves agent + vision on every
# platform. The MLX build (gemma4:e4b-mlx) is intentionally NOT used — its
# published tag is text-only (no vision encoder), which would force a second
# model just for image analysis. Embeddings come from embeddinggemma
# (Google, 308M params, multilingual, 768d).
# ==============================================================================

set -e

echo "=========================================="
echo "   WSBG Terminal - Setup & Installation   "
echo "=========================================="

# ------------------------------------------------------------------------------
# 1. Install or update Ollama
# ------------------------------------------------------------------------------
# macOS: We utilize a direct binary zip download to bypass any 'sudo' requirements
#        invoked by the official installer script. This is highly important when
#        executing out of a launcher context with no interactive TTY!
# Linux: Defaults back to the official installer (`ollama.com/install.sh`) as
#        there is no sudo-less alternative officially distributed.

OLLAMA_BIN="/Applications/Ollama.app/Contents/Resources"
export PATH="$OLLAMA_BIN:$PATH"
OLLAMA_CHANGED=false

install_ollama_mac() {
    local DOWNLOAD_URL="https://ollama.com/download/Ollama-darwin.zip"
    local TMP_ZIP="/tmp/ollama-darwin-$$.zip"
    local TMP_EXTRACT="/tmp/ollama-extract-$$"

    echo "    Downloading Ollama..."
    curl -fL --progress-bar -o "$TMP_ZIP" "$DOWNLOAD_URL" || { echo "    [WARN] Download failed."; return 1; }

    # Essential precaution: macOS strictly shields executing binaries beneath SIP
    # and system file locks. Attempting to overwrite a running .app bundle yields
    # generic 'Operation not permitted' exceptions, corrupting the app.
    # Therefore, we forcibly term existing processes right before unzipping.
    echo "    Stopping running Ollama instances..."
    pkill -x "Ollama" 2>/dev/null || true
    pkill -x "ollama" 2>/dev/null || true
    sleep 1

    # Extract payloads safely enclosed within their own localized /tmp dir, explicitly
    # preventing extraction mid-flow collisions.
    echo "    Extracting Ollama update..."
    mkdir -p "$TMP_EXTRACT"
    unzip -qo "$TMP_ZIP" -d "$TMP_EXTRACT" || { echo "    [WARN] Extract failed."; rm -rf "$TMP_ZIP" "$TMP_EXTRACT"; return 1; }

    echo "    Installing to /Applications..."
    # A standard overwrite `cp` or `unzip` directly onto `/Applications` invokes issues.
    # To execute a clean structural replacement, we discard the previous entity completely
    # and subsequently atom-move the temporary entity.
    rm -rf /Applications/Ollama.app
    mv "$TMP_EXTRACT/Ollama.app" /Applications/ || { echo "    [WARN] Failed to move Ollama.app to /Applications. Check permissions."; rm -rf "$TMP_ZIP" "$TMP_EXTRACT"; return 1; }

    rm -rf "$TMP_ZIP" "$TMP_EXTRACT"
    OLLAMA_CHANGED=true
    return 0
}

OS="$(uname -s)"

# Initial sanity check — determines if a ground-up installation is required.
if ! command -v ollama &> /dev/null; then
    echo "[*] Installing Ollama..."
    if [ "$OS" = "Darwin" ]; then
        install_ollama_mac || echo "    [WARN] Ollama install failed — continuing."
    else
        curl -fsSL https://ollama.com/install.sh | sh || echo "    [WARN] Ollama install failed — continuing."
        OLLAMA_CHANGED=true
    fi
else
    # Core Update Engine — Checks GitHub API for newer releases compared to the local CLI scope.
    LOCAL_VER=$(ollama --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
    echo "    Ollama local version: $LOCAL_VER"
    REMOTE_VER=$(curl -fsSL -m 5 "https://api.github.com/repos/ollama/ollama/releases/latest" 2>/dev/null \
        | grep -oE '"tag_name":\s*"v?([0-9]+\.[0-9]+\.[0-9]+)"' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')

    if [ -z "$REMOTE_VER" ]; then
        echo "    Remote version check failed (no network?) — skipping update."
    else
        echo "    Ollama remote version: $REMOTE_VER"
        if [ "$LOCAL_VER" != "$REMOTE_VER" ]; then
            echo "[*] Updating Ollama ($LOCAL_VER → $REMOTE_VER)..."
            if [ "$OS" = "Darwin" ]; then
                install_ollama_mac || echo "    [WARN] Ollama update failed — continuing."
            else
                curl -fsSL https://ollama.com/install.sh | sh || echo "    [WARN] Ollama update failed — continuing."
                OLLAMA_CHANGED=true
            fi
        else
            echo "[*] Ollama is up to date ($LOCAL_VER)."
        fi
    fi
fi

# Always ensure an Ollama server is running before any pull — regardless of
# whether we just installed/updated. On macOS a fresh zip install has no
# background service, and even an up-to-date install may simply not be serving
# (the user never launched Ollama.app). Without an active server every
# `ollama pull` fails with "connection refused" on the common already-installed
# path. We bypass the App UI (`Ollama.app`) and run a dedicated background CLI
# `ollama serve`, mirroring setup.ps1's unconditional behaviour.
if command -v ollama &> /dev/null; then
    if curl -sf -m 2 "http://127.0.0.1:11434/api/tags" > /dev/null 2>&1; then
        echo "[*] Ollama server already running."
    else
        echo "[*] Starting Ollama server..."
        ollama serve > /dev/null 2>&1 &

        # Poll API tags via curl, strictly limiting wait time until the service responds.
        READY=false
        for i in $(seq 1 30); do
            if curl -sf -m 2 "http://127.0.0.1:11434/api/tags" > /dev/null 2>&1; then
                READY=true
                break
            fi
            sleep 0.5
        done

        if [ "$READY" = true ]; then
            echo "    Ollama server ready."
        else
            echo "    [WARN] Ollama server did not respond in time — pulls may fail."
        fi
    fi
fi

# ------------------------------------------------------------------------------
# 2. Determine the config directory
# ------------------------------------------------------------------------------
# Mirrors `StorageUtils` Java routines.
CONFIG_DIR=""
if [ "$OS" = "Darwin" ]; then
    CONFIG_DIR="$HOME/Library/Application Support/wsbg-terminal"
else
    if [ -n "$XDG_DATA_HOME" ]; then
        CONFIG_DIR="$XDG_DATA_HOME/wsbg-terminal"
    else
        CONFIG_DIR="$HOME/.local/share/wsbg-terminal"
    fi
fi
CONFIG_FILE="$CONFIG_DIR/config.toml"

# ------------------------------------------------------------------------------
# 3. Models — one multimodal gemma4:e4b for everything
# ------------------------------------------------------------------------------
# The standard gemma4:e4b (Text+Image) serves both the editorial agent and
# vision on every platform — one model resident. The MLX build is text-only
# and would need a second model for vision, so we don't use it. Embeddings
# are embeddinggemma everywhere.

EMBED_MODEL="embeddinggemma:latest"
REASONING_MODEL="gemma4:e4b"
VISION_MODEL="gemma4:e4b"

echo "[*] Configuration Roadmap:"
echo "    - Reasoning / Agent + Vision: $REASONING_MODEL"
echo "    - Embeddings:                 $EMBED_MODEL"


# ------------------------------------------------------------------------------
# 4. Pull Models (only if not already present)
# ------------------------------------------------------------------------------
# Capture current array of installed binaries to prevent executing verbose `ollama pull`
# iterations that may generate disruptive download UI bars upon the launcher.

INSTALLED_MODELS=$(ollama list 2>/dev/null | tail -n +2 | awk '{print $1}')

pull_if_missing() {
    # Case-insensitive checks correctly mitigate discrepancies when handling edge deployments.
    if echo "$INSTALLED_MODELS" | grep -qi "^$1$"; then
        echo "    [OK] $1 already available"
    else
        echo "    > Pulling $1..."
        if ! ollama pull "$1"; then
            echo "    [WARN] Failed to pull $1 — continuing anyway"
        fi
    fi
}

pull_if_missing "$REASONING_MODEL"
# Agent and vision share the one gemma4:e4b — only pull a separate vision
# model if a future config ever diverges them.
if [ "$VISION_MODEL" != "$REASONING_MODEL" ]; then
    pull_if_missing "$VISION_MODEL"
fi
pull_if_missing "$EMBED_MODEL"

# ------------------------------------------------------------------------------
# 5. Pre-install JCEF (embedded Chromium) native bundle
# ------------------------------------------------------------------------------
# Without this, the terminal downloads ~120 MB of Chromium on the first
# real run, blocking the UI for several seconds. Doing it here means the
# launcher progress UI shows what's happening instead.
#
# Skipped when the install marker exists. The JCEF maven version is
# coupled to jcefbuild release 1.0.65 — bump together.

JCEF_DIR="$HOME/jcef-bundle"
# Coupled to jcefmaven 132.3.1 — bump together with the Maven version in pom.xml.
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
    # Pull the jcefmaven native artifact — that JAR contains an inner
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

install_jcef || echo "    [WARN] JCEF install incomplete — falling back to runtime download on first launch."

# ------------------------------------------------------------------------------
# 6. Install JetBrains Mono + Inter fonts for the terminal UI
# ------------------------------------------------------------------------------
# Web fonts served locally by the terminal's AssetServer at /fonts/.
# Without them, the page falls back to the system mono/sans stack —
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
        echo "    [WARN] Font install partial — UI will use system fallback for missing weights."
    fi
}

install_fonts || true

# ------------------------------------------------------------------------------
# 7. Generate Configuration File (if strictly new)
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
# Editorial agent reasoning model. REASONING_POWER (gemma4:e4b) — one
# multimodal model serving agent + vision. Managed centrally; leave as-is.
agent.editorial-model = "REASONING_POWER"

[reddit]
# Add reddit settings here if needed
EOL

    echo "[*] Configuration written to: $CONFIG_FILE"
else
    echo "[*] Configuration already exists. Skipping generation."
fi

echo ""
echo "=========================================="
echo "   Setup Complete! Ready to Run.          "
echo "=========================================="
echo "Run using: .script/run.sh"
