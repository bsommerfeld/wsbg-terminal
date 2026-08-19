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
# CONFIG -- change the models here; the Ollama version resolves itself
# ==============================================================================
# Ollama is NOT pinned: on every launch we ask GitHub for the current release
# and install it if the local binary is older. Nothing to bump by hand.
#   Releases: https://github.com/ollama/ollama/releases

# Models reconciled into our ISOLATED store (<appData>/ollama/models): section 3
# installs/updates these to the latest registry build and removes anything else.
# ONE model tag serves the whole editorial pipeline -- the single deployed model.
# The launcher passes the resolved tag via WSBG_REASONING_MODEL (hardware check
# + the user's config.toml choice live in ModelSelection there; the valid tiers
# are the launcher's ModelCatalog, -mlx twins as the STANDARD on Apple Silicon
# where the registry has them). The fallback below only applies to standalone
# script runs without the launcher and mirrors that platform split.
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
# 1. Install / update OUR isolated Ollama binary (latest; never the system one)
# ------------------------------------------------------------------------------
# The current release tag, without the leading "v", or "" if GitHub is out of
# reach. Two ways in, both unauthenticated:
#   1. the /releases/latest redirect -- a plain HTTP hop, no API rate limit
#   2. the release API, in case the redirect is blocked/rewritten by a proxy
# Prereleases never appear on /releases/latest, so this only ever yields a
# stable version.
resolve_latest_ollama() {
    local v=""
    v=$(curl -fsSLI -m 15 -o /dev/null -w '%{url_effective}' \
            "https://github.com/ollama/ollama/releases/latest" 2>/dev/null \
        | grep -oE '[0-9]+\.[0-9]+\.[0-9]+$') || true
    if [ -z "$v" ]; then
        v=$(curl -fsSL -m 15 "https://api.github.com/repos/ollama/ollama/releases/latest" 2>/dev/null \
            | grep -oE '"tag_name"[[:space:]]*:[[:space:]]*"v?[0-9]+\.[0-9]+\.[0-9]+"' \
            | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1) || true
    fi
    printf '%s' "$v"
}

install_ollama() {
    local want have arch base url tmp
    have=""
    [ -x "$OLLAMA" ] && have=$("$OLLAMA" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)

    want="$(resolve_latest_ollama)"
    if [ -z "$want" ] && [ -n "$have" ]; then
        # Offline or GitHub blocked: an installed runtime is worth more than a
        # failed upgrade, so we keep it and try again on the next launch.
        warn "Could not resolve the latest Ollama version -- keeping installed $have."
        return 0
    fi

    if [ "$have" = "$want" ] && [ -n "$want" ]; then
        echo "[*] Isolated Ollama $want (latest) already present."
        return 0
    fi
    echo "[*] Installing isolated Ollama ${want:-latest} into $AI_DIR ..."

    arch="$(uname -m)"
    # No version resolved and nothing installed: let GitHub pick the release for
    # us via the /latest/download alias -- still the latest, just unnamed.
    if [ -n "$want" ]; then
        base="https://github.com/ollama/ollama/releases/download/v${want}"
    else
        base="https://github.com/ollama/ollama/releases/latest/download"
    fi

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
# Altlasten. To switch models, edit DESIRED_MODELS; the check URL, pull, and GC
# all follow (the runtime is always the latest release, see section 1).
#
# The same pass doubles as a SAFETY NET so the store matches what the terminal
# believes: leftovers of aborted downloads (*-partial*), blobs no manifest
# references, and broken entries (manifest present, blobs missing) are cleaned
# -- conservatively: only what is PROVABLY unreferenced goes; in doubt, keep.
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

# The model's manifest file inside OUR store, or "" -- path ends /<name>/<tag>,
# found under any registry host dir (registry.ollama.ai today).
manifest_file() {
    local name="${1%%:*}" tag="${1#*:}"
    [ "$tag" = "$1" ] && tag="latest"
    find "$AI_MODELS/manifests" -type f -path "*/$name/$tag" 2>/dev/null | head -1
}

# Whether the model's entry is COMPLETE: a manifest exists and every blob it
# references is present. 'ollama list' happily lists a manifest whose blobs are
# gone -- such a model looks installed but cannot load.
model_complete() {
    local mf blob
    mf=$(manifest_file "$1")
    [ -n "$mf" ] || return 1
    while read -r blob; do
        [ -f "$AI_MODELS/blobs/sha256-${blob#sha256:}" ] || return 1
    done < <(grep -oE 'sha256:[0-9a-f]{64}' "$mf" 2>/dev/null)
    return 0
}

# GC keep decision for one installed tag: kept exactly when it IS a desired
# (pulled) tag -- everything else in the store is an Altlast.
keep_model() {
    local inst_lc m
    inst_lc=$(printf '%s' "$1" | tr 'A-Z' 'a-z')
    for m in "${DESIRED_MODELS[@]}"; do
        [ "$inst_lc" = "$(printf '%s' "$m" | tr 'A-Z' 'a-z')" ] && return 0
    done
    return 1
}

# ==============================================================================
# ABSOLUTE BOUNDARY -- deliberate, not incidental. This helper is the ONLY way
# the safety net below deletes a file, and it deletes nothing it cannot PROVE
# to live inside OUR isolated store ($AI_MODELS). Rationale: everything above
# only ever calls '$OLLAMA rm' under OLLAMA_MODELS, but direct file surgery
# means a path bug would no longer be cosmetic -- it would delete models in the
# user's private ~/.ollama/models, the worst possible outcome of this script.
# Hence: canonical paths only (symlinks and '..' resolved via cd/pwd -P), the
# root itself pre-canonicalized by the caller, refusal on ANY doubt (empty
# root, unresolvable parent, symlinked file), and never a recursive delete.
# ==============================================================================
safe_store_rm() {
    local f="$1" root="$2" dir real
    if [ -z "$root" ]; then
        warn "Store root unset -- refusing to delete anything."
        return 1
    fi
    # Only plain files; a symlink here is nothing this store ever creates and
    # could point anywhere -- refuse rather than reason about it.
    if [ ! -f "$f" ] || [ -L "$f" ]; then
        warn "Refusing to delete (not a regular file): $f"
        return 1
    fi
    dir=$(cd "$(dirname "$f")" 2>/dev/null && pwd -P) || {
        warn "Refusing to delete (cannot canonicalize): $f"
        return 1
    }
    real="$dir/$(basename "$f")"
    case "$real" in
        "$root"/*)
            rm -f -- "$real"
            ;;
        *)
            warn "Refusing to delete outside the isolated store: $real"
            return 1
            ;;
    esac
}

# One "[*] Cleaning up old models..." phase header for the whole cleanup pass,
# emitted lazily -- only when something is actually removed (parsed token, see
# ScriptOutputClassifier).
CLEANUP_HEADER_SHOWN=false
cleanup_header() {
    [ "$CLEANUP_HEADER_SHOWN" = true ] && return 0
    CLEANUP_HEADER_SHOWN=true
    echo "[*] Cleaning up old models..."
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
        # The CONFIGURED model is the one entry this script may only REPAIR,
        # never remove: with it gone the terminal starts with no model at all
        # -- the exact state the settings UI's trash lock exists to prevent,
        # and the script must not produce it through the back door. An entry
        # whose blobs are missing therefore falls into the pull branch (same
        # "> Pulling" output, so the launcher progress stays correct). If the
        # pull fails, the broken entry STAYS (plus a warning, and ALL_PRESENT
        # keeps the whole cleanup pass away) -- honest, and fixable on the
        # next run or via the settings.
        if [ -n "$have" ] && ! model_complete "$model"; then
            echo "    Repairing incomplete model $model (missing blobs) -- re-pulling..."
            have=""
        fi
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

    # GC + safety net. Skipped entirely when a desired pull failed, so we never
    # remove anything while the configured model is not safely in place.
    #
    # Pass 1 -- stale models: everything 'ollama list' shows that keep_model()
    # rejects (not a desired tag) is an Altlast and goes -- broken or intact,
    # it is not desired. (The configured model was already repaired above,
    # never removed.)
    # Pass 2 -- file-level leftovers: *-partial* pieces of aborted downloads
    # and blobs no manifest references. Both are recomputed AFTER the rm
    # pass and deleted only through safe_store_rm (see the boundary above).
    if [ "$ALL_PRESENT" = true ]; then
        STALE_MODELS=()
        while read -r inst; do
            [ -z "$inst" ] && continue
            keep_model "$inst" || STALE_MODELS+=("$inst")
        done < <("$OLLAMA" list 2>/dev/null | tail -n +2 | awk '{print $1}')

        if [ "${#STALE_MODELS[@]}" -gt 0 ]; then
            # Phase header + removal lines are parsed tokens (the launcher's
            # "Räume Altlasten weg" step) -- keep the exact wording.
            cleanup_header
            sidx=0
            stotal=${#STALE_MODELS[@]}
            for inst in "${STALE_MODELS[@]}"; do
                sidx=$((sidx + 1))
                echo "    > Removing stale model $inst ($sidx/$stotal)..."
                "$OLLAMA" rm "$inst" >/dev/null 2>&1 || warn "Could not remove $inst"
            done
        fi

        # Pass 2: file-level hygiene, strictly inside the canonical store root.
        STORE_ROOT=$(cd "$AI_MODELS" 2>/dev/null && pwd -P) || STORE_ROOT=""
        if [ -n "$STORE_ROOT" ] && [ -d "$STORE_ROOT/blobs" ]; then
            # 2a: leftovers of aborted downloads. Never listed by 'ollama list',
            # never cleaned by anyone -- and with every desired pull verified
            # complete above, no live download can still need them.
            PARTIALS=()
            while read -r f; do
                [ -n "$f" ] && PARTIALS+=("$f")
            done < <(find "$STORE_ROOT/blobs" -maxdepth 1 -type f -name '*-partial*' 2>/dev/null)
            if [ "${#PARTIALS[@]}" -gt 0 ]; then
                cleanup_header
                pidx=0
                ptotal=${#PARTIALS[@]}
                for f in "${PARTIALS[@]}"; do
                    pidx=$((pidx + 1))
                    echo "    > Removing partial download $(basename "$f") ($pidx/$ptotal)..."
                    safe_store_rm "$f" "$STORE_ROOT" || true
                done
            fi

            # 2b: orphaned blobs -- blob files no manifest references anymore
            # (a half-done rm or pull leaves these). The reference set is
            # rebuilt AFTER the rm passes above, so blobs freed by them are
            # caught in the same run. Conservative by construction: a blob is
            # deleted only when it is PROVABLY absent from every manifest.
            if [ -d "$STORE_ROOT/manifests" ]; then
                REFERENCED=$(grep -rhoE 'sha256:[0-9a-f]{64}' "$STORE_ROOT/manifests" 2>/dev/null \
                    | sed 's/^sha256:/sha256-/' | sort -u)
            else
                REFERENCED=""
            fi
            ORPHANS=()
            while read -r f; do
                [ -n "$f" ] || continue
                case "$(basename "$f")" in
                    sha256-*) ;;
                    *) continue ;;   # unknown layout -- in doubt, keep
                esac
                if ! printf '%s\n' "$REFERENCED" | grep -qxF "$(basename "$f")"; then
                    ORPHANS+=("$f")
                fi
            done < <(find "$STORE_ROOT/blobs" -maxdepth 1 -type f ! -name '*-partial*' 2>/dev/null)
            if [ "${#ORPHANS[@]}" -gt 0 ]; then
                cleanup_header
                oidx=0
                ototal=${#ORPHANS[@]}
                for f in "${ORPHANS[@]}"; do
                    oidx=$((oidx + 1))
                    echo "    > Removing orphaned blob $(basename "$f") ($oidx/$ototal)..."
                    safe_store_rm "$f" "$STORE_ROOT" || true
                done
            fi
        elif [ -z "$STORE_ROOT" ]; then
            warn "Could not canonicalize $AI_MODELS -- store hygiene skipped."
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

ui-reddit-visible = true

[agent]
# Editorial agent reasoning model. REASONING_POWER (gemma4) - the one
# model serving the whole editorial pipeline. Managed centrally; leave as-is.
agent.editorial-model = "REASONING_POWER"
# Ollama model tag override (gemma4:e2b/e4b/26b, nemotron-3.5-lightning:30b,
# -mlx twins on Apple Silicon).
# Empty = managed default. Set by the launcher's model-choice screen; the launcher
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
