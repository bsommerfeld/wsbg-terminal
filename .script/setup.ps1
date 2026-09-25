# ==============================================================================
# WSBG Terminal - Windows Setup Script (setup.ps1)
# ==============================================================================
# Prepares the machine before the installed terminal starts. Ships in the
# update package under bin\ and runs from there.
#
# Exit codes (the launcher's contract, same as on master - keep setup.sh and
# setup.bat in sync):
#   0  ready
#   10 ready, but degraded: some step warned, the terminal still runs
#   *  failed
#
# Steps:
#   1. OpenGL for the orb. The graphics driver brings it; where it has no
#      OpenGL 4.1 (virtual machines, Remote Desktop) the orb falls back to the
#      Mesa software renderer the package ships in lib\.
# ==============================================================================

$ErrorActionPreference = "Stop"

Write-Host "=========================================="
Write-Host "   WSBG Terminal - Setup & Installation   "
Write-Host "=========================================="

# Degraded-but-not-fatal steps report through Write-SetupWarning; the script
# then exits with code 10 so the launcher can show "Setup completed with
# warnings" instead of claiming a clean run.
$script:SetupWarned = $false
function Write-SetupWarning([string]$Message) {
    $script:SetupWarned = $true
    Write-Host "    [WARN] $Message"
}

# ------------------------------------------------------------------------------
# 1. OpenGL for the orb
# ------------------------------------------------------------------------------
$LibDir = Join-Path (Split-Path -Parent $PSScriptRoot) "lib"
if (Test-Path (Join-Path $LibDir "opengl32.dll")) {
    Write-Host "[*] OpenGL: graphics driver, Mesa fallback in place."
} elseif (Test-Path (Join-Path $PSScriptRoot "..\.git")) {
    # A dev checkout runs this from .script\ - there is no package lib\.
    Write-Host "[*] OpenGL: graphics driver (dev checkout, no Mesa fallback)."
} else {
    Write-SetupWarning "Mesa fallback missing in $LibDir - without an OpenGL 4.1 driver the orb stays a plain ring."
}

Write-Host ""
Write-Host "=========================================="
Write-Host "   Setup Complete! Ready to Run.          "
Write-Host "=========================================="

if ($script:SetupWarned) {
    exit 10
}
exit 0
