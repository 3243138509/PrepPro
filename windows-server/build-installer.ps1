param(
    [string]$InnoCompilerPath,
    [switch]$CleanOutput,
    [switch]$SkipExeBuild
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$issPath = Join-Path $scriptDir "installer\PrepPro.iss"
$outputDir = Join-Path $scriptDir "dist-installer"
$distPrepProDir = Join-Path $scriptDir "dist\PrepPro"
$buildExeScript = Join-Path $scriptDir "build-exe.ps1"

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

if (-not (Test-Path $issPath)) {
    throw "Installer script was not found: $issPath"
}

if ($CleanOutput -and (Test-Path $outputDir)) {
    Remove-Item -Path $outputDir -Recurse -Force
}

if (-not $SkipExeBuild) {
    Write-Host "[PrepPro] Building exe with PyInstaller..."
    & $buildExeScript -Clean:$CleanOutput
    if ($LASTEXITCODE -ne 0) {
        throw "build-exe.ps1 failed with exit code $LASTEXITCODE"
    }
}

if (-not (Test-Path (Join-Path $distPrepProDir "PrepPro.exe"))) {
    throw "PrepPro.exe not found at $distPrepProDir. Run build-exe.ps1 first or omit -SkipExeBuild."
}

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
