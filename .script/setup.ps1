# ==============================================================================
# WSBG Terminal - Windows Setup Script (setup.ps1)
# ==============================================================================
# This script handles the automated configuration and environment preparation
# for the WSBG Terminal on Windows platforms.
# It is executed by the Launcher and performs the following core steps:
# 1. Installs or updates Ollama via winget.
# 2. Starts a headless Ollama server instance if needed.
# 3. Downloads missing AI models.
# 4. Generates a default `config.toml` structure for new setups.
#
# Model strategy: gemma4:e4b multimodal handles chat + vision + agent on
# Windows/Linux. Embeddings come from embeddinggemma (Google, 308M, 768d).
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
        Write-Host "    [WARN] Ollama server did not respond in time - pulls may fail." -ForegroundColor Yellow
    }
} catch {
    Write-Host "    [WARN] Could not start Ollama server: $_" -ForegroundColor Yellow
}

# ------------------------------------------------------------------------------
# 2. Locate the application data directory
# ------------------------------------------------------------------------------
# Mirrors the Java backend's StorageUtils logic precisely.
$appDataPath = $env:APPDATA
if ([string]::IsNullOrEmpty($appDataPath)) {
    $appDataPath = [System.IO.Path]::Combine($env:USERPROFILE, "AppData", "Roaming")
}
$configDir = [System.IO.Path]::Combine($appDataPath, "wsbg-terminal")
$configFile = [System.IO.Path]::Combine($configDir, "config.toml")

# ------------------------------------------------------------------------------
# 3. Model selection - one multimodal model for chat + vision + agent
# ------------------------------------------------------------------------------
# Windows uses the standard multimodal gemma4:e4b for chat + vision +
# agent. Embeddings use Google's embeddinggemma (308M, multilingual).

$embedModel = "embeddinggemma:latest"
$reasoningModel = "gemma4:e4b"

Write-Host "[*] Configuration Roadmap:" -ForegroundColor Gray
Write-Host "    - Reasoning / Vision / Agent: $reasoningModel" -ForegroundColor Gray
Write-Host "    - Embeddings:                 $embedModel" -ForegroundColor Gray


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
# 5. Pre-install JCEF (embedded Chromium) native bundle
# ------------------------------------------------------------------------------
# Without this, the terminal downloads ~120 MB of Chromium on the first
# real run, blocking the UI for several seconds. Doing it here means the
# launcher progress UI shows what's happening instead.
#
# Skipped when the install marker exists. The JCEF Maven version is
# coupled to jcefbuild release 1.0.65 - bump together.

$jcefDir = Join-Path $env:USERPROFILE "jcef-bundle"
$jcefMarker = Join-Path $jcefDir "install.lock"
# Coupled to jcefmaven 132.3.1 - bump together with the Maven version.
$jcefNativeVersion = "jcef-1770317+cef-132.3.1+g144febe+chromium-132.0.6834.83"

if (Test-Path $jcefMarker) {
    Write-Host "[*] Browser runtime already installed." -ForegroundColor Gray
} else {
    $arch = if ([Environment]::Is64BitOperatingSystem) {
        # On Windows we only ship amd64 + arm64. PROCESSOR_ARCHITECTURE is
        # the canonical source for the running CPU.
        if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") { "arm64" } else { "amd64" }
    } else { $null }

    if (-not $arch) {
        Write-Host "    [WARN] Unsupported arch for JCEF - skipping." -ForegroundColor Yellow
    } else {
        $platform = "windows-$arch"
        Write-Host "[*] Installing browser runtime ($platform)..." -ForegroundColor Cyan
        # Pull the jcefmaven native artifact - that JAR contains an inner
        # tar.gz with the flat install layout the runtime library expects.
        # The GitHub jcefbuild releases use a different (bundle-wrapped)
        # layout that would need per-platform flattening.
        $url = "https://repo1.maven.org/maven2/me/friwi/jcef-natives-$platform/$jcefNativeVersion/jcef-natives-$platform-$jcefNativeVersion.jar"
        $tmpJar = Join-Path $env:TEMP "jcef-native-$PID.jar"
        $tmpTar = Join-Path $env:TEMP "jcef-native-$PID.tar.gz"

        if (-not (Test-Path $jcefDir)) {
            New-Item -ItemType Directory -Force -Path $jcefDir | Out-Null
        }
        try {
            Invoke-WebRequest -Uri $url -OutFile $tmpJar -UseBasicParsing -ErrorAction Stop
            # The JAR is a regular ZIP - pull the inner .tar.gz out.
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            $zip = [System.IO.Compression.ZipFile]::OpenRead($tmpJar)
            $tarEntry = $zip.Entries | Where-Object { $_.FullName -like "*.tar.gz" } | Select-Object -First 1
            if (-not $tarEntry) { throw "Native JAR missing inner tarball" }
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($tarEntry, $tmpTar, $true)
            $zip.Dispose()
            # Native tar.exe ships with Windows 10+; expands tar.gz directly.
            tar -xzf $tmpTar -C $jcefDir
            if ($LASTEXITCODE -ne 0) { throw "tar extract failed" }
            Remove-Item $tmpJar, $tmpTar -Force -ErrorAction SilentlyContinue
            "" | Out-File -FilePath $jcefMarker -Encoding ascii
            Write-Host "    Browser runtime ready." -ForegroundColor Green
        } catch {
            Write-Host "    [WARN] JCEF install failed: $_ - falling back to runtime download." -ForegroundColor Yellow
            Remove-Item $tmpJar, $tmpTar -Force -ErrorAction SilentlyContinue
        }
    }
}

# ------------------------------------------------------------------------------
# 6. Install JetBrains Mono + Inter fonts for the terminal UI
# ------------------------------------------------------------------------------
# Web fonts served locally by the terminal's AssetServer at /fonts/.
# Without them, the page falls back to the system mono/sans stack.

$fontDir = Join-Path $configDir "fonts"
$fontMarker = Join-Path $fontDir ".install.ok"

if (Test-Path $fontMarker) {
    Write-Host "[*] Fonts already installed." -ForegroundColor Gray
} else {
    Write-Host "[*] Installing terminal fonts..." -ForegroundColor Cyan
    if (-not (Test-Path $fontDir)) {
        New-Item -ItemType Directory -Force -Path $fontDir | Out-Null
    }

    $fontBase = "https://cdn.jsdelivr.net/fontsource/fonts"
    $fontFiles = @(
        @{ name = "jetbrains-mono-400.woff2"; url = "$fontBase/jetbrains-mono@latest/latin-400-normal.woff2" },
        @{ name = "jetbrains-mono-500.woff2"; url = "$fontBase/jetbrains-mono@latest/latin-500-normal.woff2" },
        @{ name = "jetbrains-mono-600.woff2"; url = "$fontBase/jetbrains-mono@latest/latin-600-normal.woff2" },
        @{ name = "inter-400.woff2";          url = "$fontBase/inter@latest/latin-400-normal.woff2" },
        @{ name = "inter-600.woff2";          url = "$fontBase/inter@latest/latin-600-normal.woff2" }
    )
    $failed = 0
    foreach ($f in $fontFiles) {
        $dest = Join-Path $fontDir $f.name
        try {
            Invoke-WebRequest -Uri $f.url -OutFile $dest -UseBasicParsing -ErrorAction Stop
            Write-Host "    [OK] $($f.name)" -ForegroundColor Green
        } catch {
            Write-Host "    [WARN] Failed to download $($f.name): $_" -ForegroundColor Yellow
            $failed++
        }
    }

    if ($failed -eq 0) {
        "" | Out-File -FilePath $fontMarker -Encoding ascii
        Write-Host "    Fonts ready." -ForegroundColor Green
    } else {
        Write-Host "    [WARN] Font install partial - UI will use system fallback for missing weights." -ForegroundColor Yellow
    }
}

# ------------------------------------------------------------------------------
# 7. Generate Configuration File (if strictly new)
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
        "# Editorial agent reasoning model. REASONING_POWER (gemma4:e4b) - one",
        "# multimodal model serving agent + vision. Managed centrally; leave as-is.",
        "agent.editorial-model = `"REASONING_POWER`"",
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
