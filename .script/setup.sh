#!/bin/bash

# ==============================================================================
# WSBG Terminal - macOS/Linux Setup Script (setup.sh)
# ==============================================================================
# Executes environment preparations upon application start.
# Responsibilities include:
# 1. Installing or updating the local Ollama service.
# 2. Modifying the shell's PATH dynamically.
# 3. Reading TOML configurations to detect Power Mode states.
# 4. Triggering initial downloads for LLMs natively via `ollama pull`.
# 5. Bootstrapping configuration scaffolds for newly configured apps.
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

# After a successful install/update cycle, start a dedicated, background CLI 
# `ollama serve` process. Bypassing the App UI (`Ollama.app`) limits interference 
# originating from other system tasks explicitly reliant upon it.
if [ "$OLLAMA_CHANGED" = true ] && command -v ollama &> /dev/null; then
    echo "[*] Starting Ollama server..."
    ollama serve > /dev/null 2>&1 &
    READY=false
    
    # We poll API tags via curl strictly limiting wait times until the service acknowledges operations.
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

# ------------------------------------------------------------------------------
# 2. Check Configuration & Determine Mode
# ------------------------------------------------------------------------------
# Dynamically determine the application config placement directory relative 
# to the underlying operating system. Mirrors `StorageUtils` Java routines.
CONFIG_DIR=""
if [ "$OS" = "Darwin" ]; then
    CONFIG_DIR="$HOME/Library/Application Support/wsbg-terminal"
else
    # Linux standard locations
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

# ------------------------------------------------------------------------------
# 3. Select Models based on Mode
# ------------------------------------------------------------------------------
# Establish default internal LLM associations
EMBED_MODEL="nomic-embed-text-v2-moe:latest"
REASONING_MODEL="gemma4:e2b"

if [ "$POWER_MODE" = true ]; then
    echo "[*] Power Mode: ON"
    REASONING_MODEL="gemma4:e4b"
else
    echo "[*] Power Mode: OFF"
fi

echo "[*] Configuration Roadmap:"
echo "    - Reasoning Agent: $REASONING_MODEL"
echo "    - Embeddings:      $EMBED_MODEL"


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
pull_if_missing "$EMBED_MODEL"

# ------------------------------------------------------------------------------
# 5. Generate Configuration File (if strictly new)
# ------------------------------------------------------------------------------

if [ ! -f "$CONFIG_FILE" ]; then
    echo "[*] Generating Application Configuration..."
    
    mkdir -p "$CONFIG_DIR"

    # Export a raw minimal definition to TOML structuring, automatically 
    # anchoring settings strictly required by the application runtime later.
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
