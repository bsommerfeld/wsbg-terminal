# ==============================================================================
# WSBG Terminal - Windows Setup Script (setup.ps1)
# ==============================================================================
# This script handles the automated configuration and environment preparation 
# for the WSBG Terminal on Windows platforms. 
# It is executed by the Launcher and performs the following core steps:
# 1. Installs or updates Ollama via winget.
# 2. Starts a headless Ollama server instance if needed.
# 3. Detects the application configuration (Power Mode: ON/OFF).
# 4. Downloads missing AI models (Reasoning, Translation, Embeddings).
# 5. Generates a default `config.toml` structure for new setups.
# ==============================================================================

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "   WSBG Terminal - Setup & Installation   " -ForegroundColor Cyan
Write-Host "=========================================="
Write-Host ""

# ------------------------------------------------------------------------------
# 1. Install/Update Ollama
# ------------------------------------------------------------------------------
# Keeping Ollama up-to-date prevents model pull failures caused by version 
# incompatibilities with newer model formats. We first check if Ollama is 
# installed, then compare the local version to the latest GitHub release.
# We only trigger the intensive `winget` update process if an update is required.

$updateRequired = $false
if (Get-Command "ollama" -ErrorAction SilentlyContinue) {
    $localOutput = (ollama --version 2>$null)
    if ($localOutput -match "(\d+\.\d+\.\d+)") { $localVer = $matches[1] }
    
    $remoteVer = $null
    try {
        $githubRelease = Invoke-RestMethod -Uri "https://api.github.com/repos/ollama/ollama/releases/latest" -TimeoutSec 3 -ErrorAction Stop
        if ($githubRelease.tag_name -match "(\d+\.\d+\.\d+)") { $remoteVer = $matches[1] }
    } catch {}
    
    if (-not [string]::IsNullOrEmpty($remoteVer) -and $localVer -ne $remoteVer) {
        Write-Host "    Update available: $localVer -> $remoteVer"
        $updateRequired = $true
    } else {
        Write-Host "[*] Ollama is up to date ($localVer)"
    }
} else {
    # If the ollama command is missing, we must install it.
    $updateRequired = $true
}

if ($updateRequired) {
    Write-Host "[*] Installing/updating Ollama..."
    
    # Windows strictly locks files that are in use. If the Ollama system tray 
    # app is running in the background, a silent winget update will fail to 
    # cleanly overwrite the executable. Thus, we forcefully stop any running 
    # Ollama instances prior to launching the update.
    Write-Host "    Stopping running Ollama instances..."
    Get-Process "Ollama*", "ollama*" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
    
    # Using winget handles both fresh installs and in-place updates.
    winget install -e --id Ollama.Ollama --silent --accept-source-agreements --accept-package-agreements 2>$null
    if ($LASTEXITCODE -ne 0 -and -not (Get-Command "ollama" -ErrorAction SilentlyContinue)) {
        Write-Host "    Failed to install Ollama." -ForegroundColor Red
        Write-Host "    Please install manually from https://ollama.com/download/windows"
        exit 1
    }
}

# winget may automatically trigger the Ollama desktop GUI after the update. 
# We kill it because the launcher only requires the headless CLI server to 
# pull models and operate. A retry loop is utilized because the GUI spawns 
# asynchronously after winget concludes.
for ($attempt = 0; $attempt -lt 3; $attempt++) {
    Start-Sleep -Seconds 1
    Get-Process "Ollama*" -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -ne "ollama" } | Stop-Process -Force -ErrorAction SilentlyContinue
}

# Remove Ollama from Windows autostart to prevent background bloat when the 
# terminal is not actively in use.
Remove-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "Ollama" -ErrorAction SilentlyContinue

# Refresh the shell's PATH variable so the current session finds `ollama.exe` 
# immediately after a fresh installation.
$machinePath = [Environment]::GetEnvironmentVariable("Path", "Machine")
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
$env:Path = "$machinePath;$userPath"

Write-Host "    Ollama ready." -ForegroundColor Green

# The Windows Ollama CLI does NOT automatically start a temporary server 
# (unlike macOS/Linux). Without an active server, every `ollama pull` will 
# fail with "connection refused". 
# Here, we spawn a background server and poll it for readiness before pulling.
Write-Host "[*] Starting Ollama server..."
$ollamaProcess = $null
try {
    $ollamaProcess = Start-Process -FilePath "ollama" -ArgumentList "serve" -PassThru -WindowStyle Hidden -ErrorAction Stop
    $ready = $false
    # Poll /api/tags until the server accepts connections (max 15s)
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            Invoke-RestMethod -Uri "http://127.0.0.1:11434/api/tags" -TimeoutSec 2 | Out-Null
            $ready = $true
            break
        } catch {}
    }
    if ($ready) {
        Write-Host "    Ollama server ready." -ForegroundColor Green
    } else {
        Write-Host "    [WARN] Ollama server did not respond in time — pulls may fail." -ForegroundColor Yellow
    }
} catch {
    Write-Host "    [WARN] Could not start Ollama server: $_" -ForegroundColor Yellow
}

# ------------------------------------------------------------------------------
# 2. Check Configuration & Determine Mode
# ------------------------------------------------------------------------------
# Locate the application data directory where configs are stored.
# This mimics the Java backend's StorageUtils logic precisely.
$appDataPath = $env:APPDATA
if ([string]::IsNullOrEmpty($appDataPath)) {
    $appDataPath = [System.IO.Path]::Combine($env:USERPROFILE, "AppData", "Roaming")
}
$configDir = [System.IO.Path]::Combine($appDataPath, "wsbg-terminal")
$configFile = [System.IO.Path]::Combine($configDir, "config.toml")

$powerMode = $false

# Identify if the user has explicitly enabled "power-mode" through the TOML.
if (Test-Path $configFile) {
    Write-Host "[*] Configuration found at: $configFile"
    if (Select-String -Path $configFile -Pattern "power-mode\s*=\s*true") {
        $powerMode = $true
    }
} else {
    Write-Host "[*] No existing configuration found. Using defaults (Power Mode: OFF)."
}

# ------------------------------------------------------------------------------
# 3. Select Models based on Mode
# ------------------------------------------------------------------------------
# We map internal systems (Embeddings, Reasoning) to specific 
# model definitions. The reasoning model is scaled alongside Power Mode.
$embedModel = "nomic-embed-text-v2-moe:latest"

if ($powerMode) {
    Write-Host "[*] Power Mode: ON" -ForegroundColor Magenta
    $reasoningModel = "gemma4:e4b"
} else {
    Write-Host "[*] Power Mode: OFF" -ForegroundColor Cyan
    $reasoningModel = "gemma4:e2b"
}

Write-Host "[*] Configuration Roadmap:" -ForegroundColor Gray
Write-Host "    - Reasoning Agent: $reasoningModel" -ForegroundColor Gray
Write-Host "    - Embeddings:      $embedModel" -ForegroundColor Gray


# ------------------------------------------------------------------------------
# 4. Pull Models (only if not already present)
# ------------------------------------------------------------------------------
# To optimize load times, we fetch a list of currently installed models 
# and only invoke `ollama pull` for datasets that are missing on disk.

$installedModels = @()
try {
    $installedModels = (ollama list 2>$null | Select-Object -Skip 1 | ForEach-Object { ($_ -split '\s+')[0] })
} catch {}

function Pull-IfMissing($modelName) {
    if ($installedModels -contains $modelName) {
        Write-Host "    [OK] $modelName already available" -ForegroundColor Green
    } else {
        Write-Host "    > Pulling $modelName..."
        ollama pull $modelName
        if ($LASTEXITCODE -ne 0) {
            Write-Host "    [WARN] Failed to pull $modelName" -ForegroundColor Yellow
        }
    }
}

Pull-IfMissing $reasoningModel
Pull-IfMissing $embedModel

# ------------------------------------------------------------------------------
# 5. Generate Configuration File (if strictly new)
# ------------------------------------------------------------------------------
# We initialize a base `config.toml` structure if the app is 
# running for the very first time.

if (!(Test-Path $configFile)) {
    Write-Host "[*] Generating Application Configuration..."
    
    if (!(Test-Path $configDir)) {
        New-Item -ItemType Directory -Force -Path $configDir | Out-Null
    }

    # Here-strings often break if Git checks out the repository with LF 
    # line endings on Windows. Creating an array and joining with standard 
    # Windows CRLF preserves integrity efficiently.
    $configContent = @(
        "# WSBG Terminal Configuration",
        "# Auto-generated by setup.sh/setup.ps1",
        "",
        "debug-mode = false",
        "ui-reddit-visible = true",
        "",
        "[agent]",
        "power-mode = false",
        "ollama.embedding-model = `"$embedModel`"",
        "",
        "[reddit]",
        "# Add reddit settings here if needed"
    ) -join "`r`n"

    Set-Content -Path $configFile -Value $configContent -Encoding utf8
    Write-Host "[*] Configuration written to: $configFile" -ForegroundColor Green
} else {
     Write-Host "[*] Configuration already exists. Skipping generation." -ForegroundColor Gray
}

# We stop the background Ollama server forcefully now, as it was only started 
# temporarily to accomplish model pulls during the launch setup. The primary Java 
# application will launch its own managed Ollama process.
if ($ollamaProcess -ne $null -and -not $ollamaProcess.HasExited) {
    Stop-Process -Id $ollamaProcess.Id -Force -ErrorAction SilentlyContinue
}

Write-Host "Setup Complete! Ready to Run." -ForegroundColor Green
Exit 0
