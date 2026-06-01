# ==============================================================================
# WSBG Terminal - Windows Setup Script (setup.ps1)
# ==============================================================================
# Executed by the Launcher to prepare the runtime environment:
# 1. Installs our OWN, isolated Ollama binary under <appData>\ollama (pinned).
# 2. Starts a private Ollama server (own port + own model store).
# 3. Downloads the AI models into that isolated store.
# 4. Pre-installs JCEF + fonts and scaffolds the config.
#
# Full isolation: we never touch a user's existing Ollama (binary, models, or
# the server on the default port 11434). Everything lives under <appData>\ollama,
# so uninstalling is just deleting the app data folder.
# ==============================================================================

# ==============================================================================
# CONFIG -- bump these to upgrade Ollama or change the models
# ==============================================================================
# Pinned Ollama version = the GitHub release tag WITHOUT the leading "v".
# Bump -> the isolated binary under <appData>\ollama re-downloads on next launch
# (downloaded models are kept). Releases: https://github.com/ollama/ollama/releases
$OllamaVersion = "0.24.0"

# Models pulled into our ISOLATED store (<appData>\ollama\models). Edit freely.
# One multimodal gemma4:e4b serves agent + vision; embeddinggemma does vectors.
$ReasoningModel = "gemma4:e4b"             # editorial agent + vision (multimodal)
$EmbedModel     = "embeddinggemma:latest"  # 768d cluster embeddings

# Private endpoint -- our instance binds here, NEVER the user's default 11434.
$OllamaPort = "11500"
# ==============================================================================

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "   WSBG Terminal - Setup & Installation   " -ForegroundColor Cyan
Write-Host "=========================================="
Write-Host ""

# ------------------------------------------------------------------------------
# Resolve the application data directory + the isolated ai\ layout
# ------------------------------------------------------------------------------
# Mirrors StorageUtils. LOCALAPPDATA (not Roaming) on purpose: the bundled AI
# runtime + models under ai\ are multiple GB and must not sync with a roaming
# profile.
$localAppData = $env:LOCALAPPDATA
if ([string]::IsNullOrEmpty($localAppData)) {
    $localAppData = [System.IO.Path]::Combine($env:USERPROFILE, "AppData", "Local")
}
$configDir = [System.IO.Path]::Combine($localAppData, "wsbg-terminal")
$configFile = [System.IO.Path]::Combine($configDir, "config.toml")

# Everything AI lives under <appData>\ollama, isolated from any Ollama the user
# already has. The Windows zip extracts ollama.exe (+ lib\) at the root.
$aiDir = [System.IO.Path]::Combine($configDir, "ollama")
$aiModels = [System.IO.Path]::Combine($aiDir, "models")
$ollamaExe = [System.IO.Path]::Combine($aiDir, "ollama.exe")
if (-not (Test-Path $ollamaExe)) {
    $alt = [System.IO.Path]::Combine($aiDir, "bin", "ollama.exe")
    if (Test-Path $alt) { $ollamaExe = $alt }
}

# Isolation env -- applies to every ollama invocation below (version check,
# serve, pulls). Pins our port + model store away from the user's instance.
$env:OLLAMA_HOST = "127.0.0.1:$OllamaPort"
$env:OLLAMA_MODELS = $aiModels
New-Item -ItemType Directory -Force -Path $aiModels | Out-Null

# ------------------------------------------------------------------------------
# 1. Install / update OUR isolated Ollama binary (pinned; never the system one)
# ------------------------------------------------------------------------------
$haveVer = $null
if (Test-Path $ollamaExe) {
    # Out-String collapses the multi-line output into ONE string. Without it,
    # 'ollama --version' returns a string[] and '-match' acts as an array filter
    # that never populates $matches -> '$matches[1]' threw "index into a null
    # array", $haveVer stayed null, and we re-downloaded ollama on every launch.
    $out = (& $ollamaExe --version 2>$null | Out-String)
    if ($out -match "(\d+\.\d+\.\d+)") { $haveVer = $matches[1] }
}

if ($haveVer -eq $OllamaVersion) {
    Write-Host "[*] Isolated Ollama $OllamaVersion already present." -ForegroundColor Gray
} else {
    Write-Host "[*] Installing isolated Ollama $OllamaVersion into $aiDir ..." -ForegroundColor Cyan
    $arch = if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") { "arm64" } else { "amd64" }
    $url = "https://github.com/ollama/ollama/releases/download/v$OllamaVersion/ollama-windows-$arch.zip"
    $tmpZip = Join-Path $env:TEMP "ollama-windows-$PID.zip"
    try {
        # Remove only the runtime (keep downloaded models under $aiModels).
        Remove-Item (Join-Path $aiDir "ollama.exe"), (Join-Path $aiDir "lib") -Recurse -Force -ErrorAction SilentlyContinue
        if (-not (Test-Path $aiDir)) { New-Item -ItemType Directory -Force -Path $aiDir | Out-Null }

        # Use curl.exe + tar.exe (both ship with Windows 10+). Invoke-WebRequest
        # buffers the whole ~1 GB response in memory and is glacially slow (the
        # earlier 9-minute "hang"); Expand-Archive is likewise very slow/flaky on
        # large zips. curl streams to disk; bsdtar (tar.exe) extracts the zip.
        Write-Host "    Downloading $url ..."
        & curl.exe -fL --retry 3 --retry-delay 2 -o $tmpZip $url
        if ($LASTEXITCODE -ne 0) {
            Write-Host "    [WARN] Ollama download failed (curl exit $LASTEXITCODE) -- continuing." -ForegroundColor Yellow
        } else {
            Write-Host "    Extracting..."
            & tar.exe -xf $tmpZip -C $aiDir
            if ($LASTEXITCODE -ne 0) {
                Write-Host "    [WARN] Ollama extract failed (tar exit $LASTEXITCODE) -- continuing." -ForegroundColor Yellow
            }
            Remove-Item $tmpZip -Force -ErrorAction SilentlyContinue

            # Re-resolve the binary location after extraction.
            $ollamaExe = [System.IO.Path]::Combine($aiDir, "ollama.exe")
            if (-not (Test-Path $ollamaExe)) {
                $alt = [System.IO.Path]::Combine($aiDir, "bin", "ollama.exe")
                if (Test-Path $alt) { $ollamaExe = $alt }
            }
            if (Test-Path $ollamaExe) {
                Write-Host "    Isolated Ollama ready at $ollamaExe" -ForegroundColor Green
            } else {
                Write-Host "    [WARN] ollama.exe not found after extraction -- check archive layout." -ForegroundColor Yellow
            }
        }
    } catch {
        Write-Host "    [WARN] Isolated Ollama install failed: $_ -- continuing." -ForegroundColor Yellow
        Remove-Item $tmpZip -Force -ErrorAction SilentlyContinue
    }
}

# ------------------------------------------------------------------------------
# 2. Start OUR server on the private port (stopped again at the end of setup)
# ------------------------------------------------------------------------------
# -WorkingDirectory $env:TEMP: setup.bat runs us from wsbg-terminal\bin, and a
# child inheriting that CWD locks the install folder on Windows (an orphaned
# ollama then makes the folder undeletable). Pin it to TEMP.
$ollamaProcess = $null
if (Test-Path $ollamaExe) {
    Write-Host "[*] Starting isolated Ollama server on $($env:OLLAMA_HOST) ..."
    try {
        $ollamaProcess = Start-Process -FilePath $ollamaExe -ArgumentList "serve" -WorkingDirectory $env:TEMP -PassThru -WindowStyle Hidden -ErrorAction Stop
        $ready = $false
        for ($i = 0; $i -lt 30; $i++) {
            Start-Sleep -Milliseconds 500
            try {
                Invoke-RestMethod -Uri "http://$($env:OLLAMA_HOST)/api/tags" -TimeoutSec 2 | Out-Null
                $ready = $true
                break
            } catch {}
        }
        if ($ready) {
            Write-Host "    Server ready." -ForegroundColor Green
        } else {
            Write-Host "    [WARN] Server did not respond in time - pulls may fail." -ForegroundColor Yellow
        }
    } catch {
        Write-Host "    [WARN] Could not start isolated Ollama server: $_" -ForegroundColor Yellow
    }
}

# ------------------------------------------------------------------------------
# 3. Pull models into the isolated store (only if missing)
# ------------------------------------------------------------------------------
Write-Host "[*] Models (isolated store: $aiModels):" -ForegroundColor Gray
Write-Host "    - Reasoning / Vision / Agent: $ReasoningModel" -ForegroundColor Gray
Write-Host "    - Embeddings:                 $EmbedModel" -ForegroundColor Gray

if (Test-Path $ollamaExe) {
    $installedModels = @()
    try {
        $installedModels = (& $ollamaExe list 2>$null | Select-Object -Skip 1 | ForEach-Object { ($_ -split '\s+')[0] })
    } catch {}

    function Pull-IfMissing($modelName) {
        if ($installedModels -contains $modelName) {
            Write-Host "    [OK] $modelName already available" -ForegroundColor Green
        } else {
            Write-Host "    > Pulling $modelName..."
            & $ollamaExe pull $modelName
            if ($LASTEXITCODE -ne 0) {
                Write-Host "    [WARN] Failed to pull $modelName" -ForegroundColor Yellow
            }
        }
    }

    Pull-IfMissing $ReasoningModel
    Pull-IfMissing $EmbedModel
}

# ------------------------------------------------------------------------------
# 4. Pre-install JCEF (embedded Chromium) native bundle
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
# 5. Install JetBrains Mono + Inter fonts for the terminal UI
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
# 6. Generate Configuration File (if strictly new)
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

# ------------------------------------------------------------------------------
# 7. Stop the temporary setup server
# ------------------------------------------------------------------------------
# The terminal app starts and OWNS its own isolated instance, so we shut down
# the server we spun up for the pulls.
#
# taskkill /T targets ONLY our process tree (our serve PID + the 'ollama runner'
# children it spawned during pulls) -- it does not touch a separately-running
# Ollama the user may have started. /F forces, since serve ignores soft signals.
if ($ollamaProcess -ne $null -and -not $ollamaProcess.HasExited) {
    taskkill /PID $ollamaProcess.Id /T /F 2>$null | Out-Null
}

Write-Host "Setup Complete! Ready to Run." -ForegroundColor Green
Exit 0
