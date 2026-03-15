param(
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$workspaceRoot = Split-Path -Parent $scriptDir
$distDir = Join-Path $scriptDir "dist"
$buildDir = Join-Path $scriptDir "build"
$specDir = Join-Path $scriptDir "dist\PrepPro"

$workspaceVenv = Join-Path $workspaceRoot ".venv\Scripts\python.exe"
$localVenv = Join-Path $scriptDir ".venv\Scripts\python.exe"
$venvPython = if (Test-Path $workspaceVenv) { $workspaceVenv } elseif (Test-Path $localVenv) { $localVenv } else { $null }

if (-not $venvPython) {
    throw "No venv found. Create .venv and install dependencies first: python -m venv .venv; .venv\Scripts\pip install -r requirements.txt pyinstaller"
}

if ($Clean -and (Test-Path $distDir)) {
    Remove-Item -Path $distDir -Recurse -Force
}
if ($Clean -and (Test-Path $buildDir)) {
    Remove-Item -Path $buildDir -Recurse -Force
}

Write-Host "[PrepPro] Installing PyInstaller if needed..."
& $venvPython -m pip install pyinstaller -q

$imagePath = Join-Path $scriptDir "image"
$rapidOcrPath = Join-Path $scriptDir "RapidOCR-json_v0.2.0"
if (-not (Test-Path $imagePath)) {
    throw "image/ folder not found: $imagePath"
}
if (-not (Test-Path $rapidOcrPath)) {
    throw "RapidOCR-json_v0.2.0/ folder not found: $rapidOcrPath"
}

$iconPath = Join-Path $imagePath "icon.ico"
$iconArg = if (Test-Path $iconPath) { "--icon", $iconPath } else { @() }

Write-Host "[PrepPro] Running PyInstaller (--onedir)..."
Push-Location $scriptDir
try {
    & $venvPython -m PyInstaller --onedir -y --name PrepPro `
        --add-data "image;image" `
        --add-data "RapidOCR-json_v0.2.0;RapidOCR-json_v0.2.0" `
        --hidden-import agent.agent_service `
        --hidden-import agent.code_runner `
        --hidden-import agent.model_provider `
        --hidden-import agent.schemas `
        --hidden-import pystray._win32 `
        --collect-all pystray `
        --noconsole `
        @iconArg `
        main.py

    if ($LASTEXITCODE -ne 0) {
        throw "PyInstaller failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$exePath = Join-Path $specDir "PrepPro.exe"
if (-not (Test-Path $exePath)) {
    throw "PyInstaller completed but PrepPro.exe not found at $exePath"
}

Write-Host "[PrepPro] Build complete: $exePath"
Write-Host "[PrepPro] Output directory: $specDir"
