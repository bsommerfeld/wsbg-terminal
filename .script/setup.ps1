
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
        winget install -e --id Ollama.Ollama --accept-source-agreements --accept-package-agreements
        if ($LASTEXITCODE -ne 0) {
            throw "Winget installation failed."
        }

        # Winget install auto-launches the Desktop GUI and registers autostart.
        # We only need the headless server — kill the GUI and remove the entry.
        Start-Sleep -Seconds 3
        Get-Process "Ollama" -ErrorAction SilentlyContinue | Stop-Process -Force
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

# Start headless server if no ollama process is serving yet.
# If the user already has Ollama Desktop running, it serves the API too — leave it.
if (!(Get-Process "ollama*" -ErrorAction SilentlyContinue)) {
    Write-Host "[*] Starting Ollama server (headless)..."
    Start-Process "ollama" "serve" -WindowStyle Hidden

    Write-Host "    Waiting for Ollama to be ready..."
    $ollamaReady = $false
    for ($i = 0; $i -lt 30; $i++) {
        try {
            $null = Invoke-WebRequest -Uri "http://localhost:11434/api/tags" -UseBasicParsing -TimeoutSec 2
            $ollamaReady = $true
            break
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if (-not $ollamaReady) {
        Write-Host "    Error: Ollama failed to start within 30 seconds." -ForegroundColor Red
        exit 1
    }
    Write-Host "    Ollama is ready." -ForegroundColor Green
} else {
    Write-Host "[*] Ollama is already running." -ForegroundColor Green
}

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

if ($powerMode) {
    Write-Host "[*] Power Mode: ON (Using 12b Models)" -ForegroundColor Magenta
    $reasoningModel = "gemma3:12b"
    $translatorModel = "translategemma:12b"
} else {
    Write-Host "[*] Power Mode: OFF (Using 4b Models)" -ForegroundColor Cyan
    $reasoningModel = "gemma3:4b"
    $translatorModel = "translategemma:4b"
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
