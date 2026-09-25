#!/bin/bash

# ==============================================================================
# WSBG Terminal - macOS/Linux Setup Script (setup.sh)
# ==============================================================================
# Prepares the machine before the installed terminal starts. Ships in the
# update package under bin/ and runs from there; a dev checkout can run it from
# .script/ just the same.
#
# Exit codes (the launcher's contract, same as on master - keep setup.ps1 and
# setup.bat in sync):
#   0  ready
#   10 ready, but degraded: some step warned, the terminal still runs
#   *  failed
#
# Steps:
#   1. OpenGL for the orb. macOS always has it; Linux needs libEGL and a Mesa
#      (or vendor) driver from the distribution - a package cannot bring those.
# ==============================================================================

echo "=========================================="
echo "   WSBG Terminal - Setup & Installation   "
echo "=========================================="

# Degraded-but-not-fatal steps report through warn(); the script then exits
# with code 10 so the launcher can show "Setup completed with warnings"
# instead of claiming a clean run.
SETUP_WARNED=0
warn() {
    SETUP_WARNED=1
    echo "    [WARN] $1"
}

OS="$(uname -s)"

# ------------------------------------------------------------------------------
# 1. OpenGL for the orb
# ------------------------------------------------------------------------------
# The orb renders through OpenGL 4.1 bound at runtime (fx-orb): CGL on macOS,
# EGL on Linux. Without it the orb cannot draw its storm; everything else runs.
has_egl() {
    ldconfig -p 2>/dev/null | grep -q 'libEGL\.so\.1' && return 0
    ls /usr/lib/libEGL.so.1 /usr/lib64/libEGL.so.1 /usr/lib/*/libEGL.so.1 >/dev/null 2>&1
}

check_opengl() {
    case "$OS" in
        Darwin)
            echo "[*] OpenGL: provided by macOS."
            ;;
        Linux)
            if has_egl; then
                echo "[*] OpenGL: libEGL found."
            else
                warn "OpenGL (libEGL) not found - the orb stays a plain ring."
                echo "    Install it with your package manager, e.g.:"
                echo "      Debian/Ubuntu: sudo apt install libegl1 libgl1-mesa-dri"
                echo "      Fedora:        sudo dnf install mesa-libEGL mesa-dri-drivers"
                echo "      Arch:          sudo pacman -S libglvnd mesa"
            fi
            ;;
        *)
            warn "Unsupported operating system: $OS"
            ;;
    esac
}

check_opengl

echo ""
echo "=========================================="
echo "   Setup Complete! Ready to Run.          "
echo "=========================================="

if [ "$SETUP_WARNED" = "1" ]; then
    exit 10
fi
exit 0
