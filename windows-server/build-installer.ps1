param(
    [string]$InnoCompilerPath,
    [switch]$CleanOutput
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$issPath = Join-Path $scriptDir "installer\PrepPro.iss"
$outputDir = Join-Path $scriptDir "dist-installer"
$bundledPythonDir = Join-Path $scriptDir "installer\bundled-python"
$workspaceRoot = Split-Path -Parent $scriptDir

function Resolve-IsccPath {
    param([string]$PreferredPath)

    if ($PreferredPath) {
        if (Test-Path $PreferredPath) {
            return (Resolve-Path $PreferredPath).Path
        }
        throw "Specified Inno Setup compiler was not found: $PreferredPath"
    }

    $isccCmd = Get-Command iscc -ErrorAction SilentlyContinue
    if ($isccCmd) {
        return $isccCmd.Source
    }

    $candidates = @(
        "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
        "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    throw "ISCC.exe was not found. Install Inno Setup 6, or pass -InnoCompilerPath."
}

function Get-PythonCandidates {
    $candidates = @()
    $workspaceVenv = Join-Path $workspaceRoot ".venv\Scripts\python.exe"
    $localVenv = Join-Path $scriptDir ".venv\Scripts\python.exe"

    if (Test-Path $workspaceVenv) {
        $candidates += $workspaceVenv
    }
    if (Test-Path $localVenv) {
        $candidates += $localVenv
    }

    $pythonCmd = Get-Command python -ErrorAction SilentlyContinue
    if ($pythonCmd -and $pythonCmd.Source) {
        $candidates += $pythonCmd.Source
    }

    return @($candidates | Select-Object -Unique)
}

function Resolve-LocalPythonBaseDir {
    $candidates = Get-PythonCandidates
    foreach ($pythonExe in $candidates) {
        if (-not (Test-Path $pythonExe)) {
            continue
        }

        try {
            $basePrefix = & $pythonExe -c "import sys; print(sys.base_prefix)" 2>$null
            if ($LASTEXITCODE -ne 0) {
                continue
            }

            $resolved = ($basePrefix | Select-Object -First 1).Trim()
            if ([string]::IsNullOrWhiteSpace($resolved)) {
                continue
            }

            $pythonFromBase = Join-Path $resolved "python.exe"
            if (Test-Path $pythonFromBase) {
                Write-Host "[PrepPro] Using local Python runtime source: $resolved"
                return $resolved
            }
        }
        catch {
            continue
        }
    }

    throw "Local Python runtime was not found. Install Python locally or ensure .venv exists before building installer."
}

function Prepare-BundledPythonRuntime {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SourceDir,
        [Parameter(Mandatory = $true)]
        [string]$TargetDir
    )

    if (Test-Path $TargetDir) {
        Remove-Item -Path $TargetDir -Recurse -Force
    }

    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
    Write-Host "[PrepPro] Copying local Python runtime into installer payload..."
    Copy-Item -Path (Join-Path $SourceDir "*") -Destination $TargetDir -Recurse -Force

    if (-not (Test-Path (Join-Path $TargetDir "python.exe"))) {
        throw "Bundled Python runtime is invalid: python.exe missing in $TargetDir"
    }

    Write-Host "[PrepPro] Bundled local Python runtime ready: $TargetDir"
}

if (-not (Test-Path $issPath)) {
    throw "Installer script was not found: $issPath"
}

if ($CleanOutput -and (Test-Path $outputDir)) {
    Remove-Item -Path $outputDir -Recurse -Force
}

$pythonBaseDir = Resolve-LocalPythonBaseDir
Prepare-BundledPythonRuntime -SourceDir $pythonBaseDir -TargetDir $bundledPythonDir

$isccPath = Resolve-IsccPath -PreferredPath $InnoCompilerPath
Write-Host "[PrepPro] Using Inno Setup compiler: $isccPath"
Write-Host "[PrepPro] Building installer from: $issPath"

Push-Location $scriptDir
try {
    & $isccPath $issPath
    if ($LASTEXITCODE -ne 0) {
        throw "ISCC build failed with exit code: $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$setupExe = Join-Path $outputDir "PrepPro-Setup.exe"
if (-not (Test-Path $setupExe)) {
    throw "Build completed but installer was not found: $setupExe"
}

Write-Host "[PrepPro] Installer generated: $setupExe"
Write-Host "[PrepPro] Uninstall EXE will be generated after install at: %LOCALAPPDATA%\\Programs\\PrepPro\\unins000.exe"
