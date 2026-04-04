#!/bin/bash

# setup.sh - automatic installation and configuration for WSBG Terminal
# Supports macOS and Linux

set -e

echo "=========================================="
echo "   WSBG Terminal - Setup & Installation   "
echo "=========================================="

# 1. Install or update Ollama
# macOS: Manual download+extract to avoid sudo (the official install script
#        runs `sudo ln -sf` for /usr/local/bin — blocks in a launcher with no TTY).
# Linux: Uses the official install.sh since there's no sudo-free alternative.
# Windows: Handled via winget in setup.ps1 (no version check needed there).
OLLAMA_BIN="/Applications/Ollama.app/Contents/Resources"
export PATH="$OLLAMA_BIN:$PATH"
OLLAMA_CHANGED=false

install_ollama_mac() {
    local DOWNLOAD_URL="https://ollama.com/download/Ollama-darwin.zip"
    local TMP_ZIP="/tmp/ollama-darwin-$$.zip"
    echo "    Downloading Ollama..."
    curl -fL --progress-bar -o "$TMP_ZIP" "$DOWNLOAD_URL" || { echo "    [WARN] Download failed."; return 1; }
    echo "    Extracting to /Applications..."
    unzip -qo "$TMP_ZIP" -d /Applications/ || { echo "    [WARN] Extract failed."; return 1; }
    rm -f "$TMP_ZIP"
    OLLAMA_CHANGED=true
    return 0
}

OS="$(uname -s)"
if ! command -v ollama &> /dev/null; then
    echo "[*] Installing Ollama..."
    if [ "$OS" = "Darwin" ]; then
        install_ollama_mac || echo "    [WARN] Ollama install failed — continuing."
    else
        curl -fsSL https://ollama.com/install.sh | sh || echo "    [WARN] Ollama install failed — continuing."
        OLLAMA_CHANGED=true
    fi
else
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

# After install/update, start the server explicitly. Using `ollama serve &`
# instead of launching the .app avoids conflicts with other apps that bundle
# Ollama (e.g. Kodex) and works without a GUI session.
if [ "$OLLAMA_CHANGED" = true ] && command -v ollama &> /dev/null; then
    echo "[*] Starting Ollama server..."
    ollama serve > /dev/null 2>&1 &
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

# 2. Check Configuration & Determine Mode
# Detect AppData path (matching StorageUtils logic)
CONFIG_DIR=""
if [ "$OS" = "Darwin" ]; then
    CONFIG_DIR="$HOME/Library/Application Support/wsbg-terminal"
else
    # Linux
    if [ -n "$XDG_DATA_HOME" ]; then
        CONFIG_DIR="$XDG_DATA_HOME/wsbg-terminal"
    else
        CONFIG_DIR="$HOME/.local/share/wsbg-terminal"
    fi
fi
CONFIG_FILE="$CONFIG_DIR/config.toml"

POWER_MODE=false

if [ -f "$CONFIG_FILE" ]; then
    echo "[*] Configuration found at: $CONFIG_FILE"
    if grep -q "power-mode\s*=\s*true" "$CONFIG_FILE"; then
        POWER_MODE=true
    fi
else
     echo "[*] No existing configuration found. Using defaults (Power Mode: OFF)."
fi

# 3. Select Models based on Mode
# Constant models
EMBED_MODEL="nomic-embed-text-v2-moe:latest"

# Variable models
REASONING_MODEL="gemma4:e2b"
TRANSLATOR_MODEL="translategemma:4b"

if [ "$POWER_MODE" = true ]; then
    echo "[*] Power Mode: ON"
    REASONING_MODEL="gemma4:e4b"
else
    echo "[*] Power Mode: OFF"
fi

echo "[*] Configuration Roadmap:"
echo "    - Reasoning Agent: $REASONING_MODEL"
echo "    - Translator:      $TRANSLATOR_MODEL"
echo "    - Embeddings:      $EMBED_MODEL"


# 4. Pull Models (only if not already present)
INSTALLED_MODELS=$(ollama list 2>/dev/null | tail -n +2 | awk '{print $1}')

pull_if_missing() {
    # Case-insensitive match — ollama list may return names in different casing.
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
pull_if_missing "$TRANSLATOR_MODEL"
pull_if_missing "$EMBED_MODEL"

# 5. Generate Configuration File (if strictly new)
if [ ! -f "$CONFIG_FILE" ]; then
    echo "[*] Generating Application Configuration..."
    
    mkdir -p "$CONFIG_DIR"

    # Write TOML
    cat > "$CONFIG_FILE" <<EOL
# WSBG Terminal Configuration
# Auto-generated by setup.sh

debug-mode = false
ui-reddit-visible = true

[agent]
power-mode = false
ollama.embedding-model = "${EMBED_MODEL}"

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
