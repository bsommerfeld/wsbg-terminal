
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "   WSBG Terminal - Setup & Installation   " -ForegroundColor Cyan
Write-Host "=========================================="
Write-Host ""

# 1. Install/Update Ollama
# winget install handles both fresh installs and in-place updates. Exits
# quickly when already current. Keeping Ollama up-to-date prevents model pull
# failures caused by version incompatibilities with newer model formats.
Write-Host "[*] Installing/updating Ollama..."
winget install -e --id Ollama.Ollama --silent --accept-source-agreements --accept-package-agreements 2>$null
if ($LASTEXITCODE -ne 0 -and -not (Get-Command "ollama" -ErrorAction SilentlyContinue)) {
    Write-Host "    Failed to install Ollama." -ForegroundColor Red
    Write-Host "    Please install manually from https://ollama.com/download/windows"
    exit 1
}

# winget may trigger the Ollama desktop GUI. Kill it — we only need the
# headless CLI server. Retry loop because the GUI spawns asynchronously.
for ($attempt = 0; $attempt -lt 3; $attempt++) {
    Start-Sleep -Seconds 1
    Get-Process "Ollama*" -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -ne "ollama" } | Stop-Process -Force -ErrorAction SilentlyContinue
}
Remove-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "Ollama" -ErrorAction SilentlyContinue

# Refresh PATH so the current session finds ollama.exe
$machinePath = [Environment]::GetEnvironmentVariable("Path", "Machine")
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
$env:Path = "$machinePath;$userPath"

Write-Host "    Ollama ready." -ForegroundColor Green

# Windows Ollama CLI does NOT auto-start a temporary server like macOS/Linux.
# Without a running server, every 'ollama pull' fails with "connection refused".
# Start a background server and wait for readiness before pulling models.
Write-Host "[*] Starting Ollama server..."
$ollamaProcess = $null
try {
    $ollamaProcess = Start-Process -FilePath "ollama" -ArgumentList "serve" -PassThru -WindowStyle Hidden -ErrorAction Stop
    # Poll /api/tags until the server accepts connections (max 15s)
    $ready = $false
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


# Stop the background Ollama server — it was only needed for model pulls.
# The application manages its own Ollama connection at runtime.
if ($ollamaProcess -ne $null -and -not $ollamaProcess.HasExited) {
    Stop-Process -Id $ollamaProcess.Id -Force -ErrorAction SilentlyContinue
}

Write-Host "Setup Complete! Ready to Run." -ForegroundColor Green
Exit 0
