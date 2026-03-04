
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "   WSBG Terminal - Setup & Installation   " -ForegroundColor Cyan
Write-Host "=========================================="
Write-Host ""

# 1. Check/Install Ollama
Write-Host "[*] Checking for Ollama..."
if (Get-Command "ollama" -ErrorAction SilentlyContinue) {
    Write-Host "    Ollama is already installed." -ForegroundColor Green
} else {
    Write-Host "    Ollama not found. Attempting to install via Winget..." -ForegroundColor Yellow
    try {
        # --silent suppresses the Ollama installer GUI that pops up during
        # winget install — without it, users see an unwanted desktop window.
        winget install -e --id Ollama.Ollama --silent --accept-source-agreements --accept-package-agreements
        if ($LASTEXITCODE -ne 0) {
            throw "Winget installation failed."
        }

        # Winget triggers the Ollama desktop app post-install (GUI + autostart).
        # We only need the headless CLI server. Kill all Ollama processes with a
        # retry loop — the single-attempt approach was unreliable because the
        # GUI spawns asynchronously after winget returns.
        for ($attempt = 0; $attempt -lt 5; $attempt++) {
            Start-Sleep -Seconds 2
            Get-Process "Ollama*" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
        }
        Remove-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "Ollama" -ErrorAction SilentlyContinue
        Write-Host "    Ollama installed (headless mode)." -ForegroundColor Green

        # Refresh PATH so the current session finds ollama.exe
        $machinePath = [Environment]::GetEnvironmentVariable("Path", "Machine")
        $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
        $env:Path = "$machinePath;$userPath"
    } catch {
        Write-Host "    Failed to install Ollama automatically." -ForegroundColor Red
        Write-Host "    Please install it manually from https://ollama.com/download/windows"
        exit 1
    }
}

# Ollama CLI commands (pull, list) handle their own server lifecycle internally —
# they start a temporary server on demand. An explicit 'ollama serve' + HTTP
# readiness check was causing spurious failures: the first launch timed out
# waiting for the server, but the second succeeded because the orphaned server
# from the previous attempt was still running.

# 2. Check Configuration & Determine Mode
$appDataPath = $env:APPDATA
if ([string]::IsNullOrEmpty($appDataPath)) {
    $appDataPath = [System.IO.Path]::Combine($env:USERPROFILE, "AppData", "Roaming")
}
$configDir = [System.IO.Path]::Combine($appDataPath, "wsbg-terminal")
$configFile = [System.IO.Path]::Combine($configDir, "config.toml")

$powerMode = $false

if (Test-Path $configFile) {
    Write-Host "[*] Configuration found at: $configFile"
    # Simple check for power-mode = true
    if (Select-String -Path $configFile -Pattern "power-mode\s*=\s*true") {
        $powerMode = $true
    }
} else {
    Write-Host "[*] No existing configuration found. Using defaults (Power Mode: OFF)."
}

# 3. Select Models based on Mode
$visionModel = "glm-ocr:latest"
$embedModel = "nomic-embed-text-v2-moe:latest"

$translatorModel = "translategemma:4b"

if ($powerMode) {
    Write-Host "[*] Power Mode: ON" -ForegroundColor Magenta
    $reasoningModel = "qwen3.5:9b"
} else {
    Write-Host "[*] Power Mode: OFF" -ForegroundColor Cyan
    $reasoningModel = "qwen3.5:4b"
}

Write-Host "[*] Configuration Roadmap:" -ForegroundColor Gray
Write-Host "    - Reasoning Agent: $reasoningModel" -ForegroundColor Gray
Write-Host "    - Translator:      $translatorModel" -ForegroundColor Gray
Write-Host "    - Vision/OCR:      $visionModel" -ForegroundColor Gray
Write-Host "    - Embeddings:      $embedModel" -ForegroundColor Gray


# 4. Pull Models (only if not already present)
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
Pull-IfMissing $translatorModel
Pull-IfMissing $visionModel
Pull-IfMissing $embedModel

# 5. Generate Config (if strictly new)
if (!(Test-Path $configFile)) {
    Write-Host "[*] Generating Application Configuration..."
    
    if (!(Test-Path $configDir)) {
        New-Item -ItemType Directory -Force -Path $configDir | Out-Null
    }

    # Here-strings break when the file has LF line endings (git on Windows).
    # Array-join is immune to line-ending mismatches.
    $configContent = @(
        "# WSBG Terminal Configuration",
        "# Auto-generated by setup.bat",
        "",
        "debug-mode = false",
        "ui-reddit-visible = true",
        "",
        "[agent]",
        "power-mode = false",
        "ollama.vision-model = `"$visionModel`"",
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


Write-Host "Setup Complete! Ready to Run." -ForegroundColor Green
Exit 0
