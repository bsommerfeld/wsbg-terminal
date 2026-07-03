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

# Models reconciled into our ISOLATED store (<appData>\ollama\models): section 3
# installs/updates these to the latest registry build and removes anything else.
# One multimodal gemma4:e4b serves agent + vision -- the single deployed model.
# (The gemma4:e4b-mlx build is text-only -- no vision encoder -- so we avoid it.)
$ReasoningModel = "gemma4:e4b"             # editorial agent + vision (multimodal)
$VisionModel    = "gemma4:e4b"             # same model serves vision

# Private endpoint -- our instance binds here, NEVER the user's default 11434.
$OllamaPort = "11500"
# ==============================================================================

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "   WSBG Terminal - Setup & Installation   " -ForegroundColor Cyan
Write-Host "=========================================="
Write-Host ""

# Degraded-but-not-fatal steps report through Write-Warn; the script then exits
# with code 10 so the launcher can show "Setup completed with warnings" instead
# of claiming a clean run. Keep the code in sync with
# EnvironmentSetup.EXIT_WITH_WARNINGS (launcher) and setup.bat.
$script:SetupWarned = $false
function Write-Warn($msg) {
    $script:SetupWarned = $true
    Write-Host "    [WARN] $msg" -ForegroundColor Yellow
}

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
        # curl exit 35 with schannel is usually CRYPT_E_NO_REVOCATION_CHECK: the
        # machine (corporate proxy / AV TLS-interception / blocked OCSP+CRL hosts)
        # can't reach the CA's revocation endpoint, and schannel treats "couldn't
        # check" as fatal before a single byte transfers. Retry once telling
        # schannel to skip the revocation *check* only (the cert is still
        # validated) so these locked-down networks can still install.
        if ($LASTEXITCODE -eq 35) {
            Write-Warn "Ollama download hit a TLS revocation-check failure -- retrying without revocation check."
            & curl.exe -fL --ssl-no-revoke --retry 3 --retry-delay 2 -o $tmpZip $url
        }
        if ($LASTEXITCODE -ne 0) {
            Write-Warn "Ollama download failed (curl exit $LASTEXITCODE) -- continuing."
        } else {
            Write-Host "    Extracting..."
            & tar.exe -xf $tmpZip -C $aiDir
            if ($LASTEXITCODE -ne 0) {
                Write-Warn "Ollama extract failed (tar exit $LASTEXITCODE) -- continuing."
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
                Write-Warn "ollama.exe not found after extraction -- check archive layout."
            }
        }
    } catch {
        Write-Warn "Isolated Ollama install failed: $_ -- continuing."
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
            Write-Warn "Server did not respond in time - pulls may fail."
        }
    } catch {
        Write-Warn "Could not start isolated Ollama server: $_"
    }
}

# ------------------------------------------------------------------------------
# 3. Reconcile the isolated store to the desired model set (install / update / GC)
# ------------------------------------------------------------------------------
# $desiredModels is the single source of truth. For each one we compare the local
# manifest digest ('ollama list' ID) against the registry's manifest digest
# (Docker-Content-Digest, fetched WITHOUT downloading the model) and pull only
# when missing or stale. Anything in the store that is NOT desired is removed, so
# a model switch leaves no Altlasten. To switch models, edit $desiredModels (and
# $OllamaVersion above if the new model needs a newer runtime).
$desiredModels = @($ReasoningModel)
# Agent and vision share the one gemma4:e4b -- only add a distinct vision model
# if a future config ever diverges them. (Mirrors setup.sh.)
if ($VisionModel -ne $ReasoningModel) { $desiredModels += $VisionModel }

Write-Host "[*] Models (isolated store: $aiModels):" -ForegroundColor Gray
foreach ($m in $desiredModels) { Write-Host "    - $m" -ForegroundColor Gray }

# Local manifest digest (= 'ollama list' ID) for an installed tag, or $null.
function Get-LocalDigest($model) {
    try {
        $line = & $ollamaExe list 2>$null | Select-Object -Skip 1 |
            Where-Object { ($_ -split '\s+')[0].ToLower() -eq $model.ToLower() } |
            Select-Object -First 1
        if ($line) { return ($line -split '\s+')[1] }
    } catch {}
    return $null
}

# Remote manifest digest (first 12 hex) with no model download. The registry
# serves the manifest body but no Docker-Content-Digest header, so the digest is
# the SHA-256 of the manifest bytes (verified to equal the 'ollama list' ID /
# tags-page digest). We write the body to a temp file and Get-FileHash it so the
# exact bytes are hashed. URL is DERIVED from the name: "name:tag" ->
# library/name/manifests/tag; a name already containing "/" is a full namespace
# (community model), used as-is. Returns $null on any failure (offline / 404).
function Get-RemoteDigest($model) {
    $parts = $model -split ':', 2
    $name = $parts[0]
    $tag = if ($parts.Count -gt 1 -and $parts[1]) { $parts[1] } else { "latest" }
    $path = if ($name -like "*/*") { $name } else { "library/$name" }
    $url = "https://registry.ollama.ai/v2/$path/manifests/$tag"
    $tmp = Join-Path $env:TEMP "ollama-manifest-$PID-$([guid]::NewGuid().ToString('N')).json"
    try {
        Invoke-WebRequest -Uri $url -OutFile $tmp -TimeoutSec 8 -UseBasicParsing -ErrorAction Stop `
            -Headers @{ Accept = "application/vnd.docker.distribution.manifest.v2+json" }
        # Guard: only a real manifest yields a digest, never an error body.
        if (-not (Select-String -Path $tmp -Pattern 'schemaVersion' -Quiet)) { return $null }
        return (Get-FileHash -Path $tmp -Algorithm SHA256).Hash.ToLower().Substring(0, 12)
    } catch {
        return $null
    } finally {
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
}

if (Test-Path $ollamaExe) {
    $allPresent = $true
    # 1-based position + total in the desired set, appended to each "> Pulling"
    # line as "(idx/total)". The launcher reads this to render one pip per model
    # so the user sees HOW MANY models are being installed, not just the current
    # one. Keep the "(idx/total)" token EXACTLY here -- EnvironmentSetup parses it.
    $midx = 0
    $mtotal = $desiredModels.Count
    foreach ($model in $desiredModels) {
        $midx++
        $have = Get-LocalDigest $model
        $want = Get-RemoteDigest $model
        # The launcher tracks model names from the exact "> Pulling <model>..."
        # wording -- keep extra detail on its own line, never appended.
        if (-not $have) {
            Write-Host "    > Pulling $model ($midx/$mtotal)..."
            & $ollamaExe pull $model
            if ($LASTEXITCODE -ne 0) {
                Write-Warn "Failed to pull $model"
                $allPresent = $false
            }
        } elseif (-not $want) {
            Write-Host "    [OK] $model present (update check skipped -- registry unreachable)" -ForegroundColor Green
        } elseif ($have -eq $want) {
            Write-Host "    [OK] $model up to date ($have)" -ForegroundColor Green
        } else {
            Write-Host "    Update available: $model ($have -> $want)"
            Write-Host "    > Pulling $model ($midx/$mtotal)..."
            & $ollamaExe pull $model
            if ($LASTEXITCODE -ne 0) { Write-Warn "Failed to update $model -- keeping $have" }
        }
    }

    # GC: remove any isolated-store model no longer desired. Skipped if a desired
    # pull failed, so the old model is never dropped before the new one lands.
    if ($allPresent) {
        $installed = @()
        try { $installed = (& $ollamaExe list 2>$null | Select-Object -Skip 1 | ForEach-Object { ($_ -split '\s+')[0] }) } catch {}
        $desiredLower = $desiredModels | ForEach-Object { $_.ToLower() }
        foreach ($inst in $installed) {
            if ($inst -and ($desiredLower -notcontains $inst.ToLower())) {
                Write-Host "    > Removing stale model $inst ..."
                & $ollamaExe rm $inst 2>$null | Out-Null
            }
        }
    }
} else {
    Write-Warn "Isolated Ollama binary missing -- skipping model install."
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

# Under <appData>\wsbg-terminal so the whole footprint (Ollama, models, fonts,
# config, JCEF) stays in one uninstall-clean directory. Keep aligned with
# CefHost.resolveInstallDir().
$jcefDir = Join-Path $configDir "jcef-bundle"
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
        Write-Warn "Unsupported arch for JCEF - skipping."
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
            Write-Warn "JCEF install failed: $_ - falling back to runtime download."
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
            Write-Warn "Failed to download $($f.name): $_"
            $failed++
        }
    }

    if ($failed -eq 0) {
        "" | Out-File -FilePath $fontMarker -Encoding ascii
        Write-Host "    Fonts ready." -ForegroundColor Green
    } else {
        Write-Warn "Font install partial - UI will use system fallback for missing weights."
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

# Exit 10 = "finished, but degraded" -- the launcher shows "Setup completed
# with warnings" and proceeds. 0 = clean run. (EnvironmentSetup.EXIT_WITH_WARNINGS)
if ($script:SetupWarned) { Exit 10 }
Exit 0
