param(
    [switch]$SkipInstall,
    [switch]$SkipRun
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

function Join-CodePoints {
    param(
        [Parameter(Mandatory = $true)]
        [int[]]$Points
    )

    return -join ($Points | ForEach-Object { [char]$_ })
}

$resolvedScriptDir = (Resolve-Path $scriptDir).Path
if ($resolvedScriptDir -match "[\u4e00-\u9fff]") {
    $pathContainsChinese = Join-CodePoints @(0x68C0, 0x6D4B, 0x5230, 0x8DEF, 0x5F84, 0x5305, 0x542B, 0x4E2D, 0x6587)
    $moveToAsciiPath = Join-CodePoints @(0x8BF7, 0x653E, 0x5230, 0x6CA1, 0x6709, 0x4E2D, 0x6587, 0x8DEF, 0x5F84, 0x7684, 0x5730, 0x65B9, 0x7136, 0x540E, 0x6267, 0x884C, 0xFF0C, 0x4F8B, 0x5982, 0xFF1A)
    Write-Host ("[PrepPro] {0}: {1}" -f $pathContainsChinese, $resolvedScriptDir) -ForegroundColor Yellow
    Write-Host ("[PrepPro] {0} D:\" -f $moveToAsciiPath) -ForegroundColor Yellow
    exit 1
}

$workspaceDir = Split-Path -Parent $scriptDir
$rootVenvDir = Join-Path $workspaceDir ".venv"
$localVenvDir = Join-Path $scriptDir ".venv"
$venvDir = if (Test-Path (Join-Path $rootVenvDir "Scripts\python.exe")) {
    $rootVenvDir
}
elseif (Test-Path (Join-Path $localVenvDir "Scripts\python.exe")) {
    $localVenvDir
}
else {
    $rootVenvDir
}
$venvPython = Join-Path $venvDir "Scripts\python.exe"
$venvPythonW = Join-Path $venvDir "Scripts\pythonw.exe"
$bundledVenvMarker = Join-Path $venvDir ".preppro_bundled"
$requirements = Join-Path $scriptDir "requirements.txt"
$mainFile = Join-Path $scriptDir "main.py"
$pythonInstallerUrl = "https://www.python.org/ftp/python/3.10.10/python-3.10.10-amd64.exe"

if ($venvDir -eq $rootVenvDir) {
    Write-Host "[PrepPro] Using workspace root venv: $venvDir"
}
else {
    Write-Host "[PrepPro] Using windows-server local venv: $venvDir"
}

function Get-UserPythonInstallCandidates {
    $localPrograms = Join-Path $env:LocalAppData "Programs\Python"
    if (-not (Test-Path $localPrograms)) {
        return @()
    }

    $candidates = Get-ChildItem -Path $localPrograms -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName "python.exe" } |
        Where-Object { Test-Path $_ }

    return @($candidates)
}

function Refresh-ProcessPath {
    $machinePath = [Environment]::GetEnvironmentVariable("Path", "Machine")
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $env:Path = @($machinePath, $userPath) -join ";"
}

function Install-PythonWithWinget {
    $winget = Get-Command winget -ErrorAction SilentlyContinue
    if (-not $winget) {
        return $false
    }

    Write-Host "[0/4] Python not found. Installing Python via winget..."
    & $winget.Source install --id Python.Python.3.10 -e --scope user --silent --accept-package-agreements --accept-source-agreements
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "winget install failed with exit code $LASTEXITCODE."
        return $false
    }

    Refresh-ProcessPath
    return $true
}

function Install-PythonWithOfficialInstaller {
    $installerPath = Join-Path $env:TEMP "python-installer.exe"
    Write-Host "[0/4] Python not found. Downloading official Python installer..."
    Invoke-WebRequest -Uri $pythonInstallerUrl -OutFile $installerPath

    Write-Host "[0/4] Installing Python silently..."
    $process = Start-Process -FilePath $installerPath -ArgumentList "/quiet InstallAllUsers=0 PrependPath=1 Include_launcher=1 Include_test=0 SimpleInstall=1" -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "Official Python installer failed with exit code $($process.ExitCode)."
    }

    Refresh-ProcessPath
}

function Test-PythonLauncher {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Exe,
        [string[]]$Args = @()
    )

    try {
        $output = & $Exe @Args -c "import sys, venv; print(sys.executable)" 2>$null
        if ($LASTEXITCODE -ne 0) {
            return $false
        }
        $line = ($output | Select-Object -First 1)
        return -not [string]::IsNullOrWhiteSpace($line)
    }
    catch {
        return $false
    }
}

function Resolve-PythonCommand {
    if (Test-PythonLauncher -Exe "python") {
        return [PSCustomObject]@{
            Exe = "python"
            Args = @()
        }
    }
    if (Test-PythonLauncher -Exe "py" -Args @("-3")) {
        return [PSCustomObject]@{
            Exe = "py"
            Args = @("-3")
        }
    }

    $installed = Install-PythonWithWinget
    if (-not $installed) {
        Install-PythonWithOfficialInstaller
    }

    if (Test-PythonLauncher -Exe "python") {
        return [PSCustomObject]@{
            Exe = "python"
            Args = @()
        }
    }
    if (Test-PythonLauncher -Exe "py" -Args @("-3")) {
        return [PSCustomObject]@{
            Exe = "py"
            Args = @("-3")
        }
    }

    $candidate = Get-UserPythonInstallCandidates | Select-Object -First 1
    if ($candidate -and (Test-PythonLauncher -Exe $candidate)) {
        return [PSCustomObject]@{
            Exe = $candidate
            Args = @()
        }
    }

    throw "Python installation completed, but python command is still unavailable."
}

function Test-PythonImport {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PythonExe,
        [Parameter(Mandatory = $true)]
        [string]$ModuleName
    )

    try {
        & $PythonExe -c "import $ModuleName" 2>$null
        return $LASTEXITCODE -eq 0
    }
    catch {
        return $false
    }
}

if (-not (Test-Path $venvPython)) {
    Write-Host "[1/4] Creating virtual environment..."
    $pythonCmd = Resolve-PythonCommand
    & $pythonCmd.Exe @($pythonCmd.Args) -m venv $venvDir
}
else {
    Write-Host "[1/4] Virtual environment already exists."
}

if (-not (Test-Path $venvPython)) {
    throw "Failed to create virtual environment: $venvPython not found."
}

$dependencyActionHandled = $false

if ((Test-Path $bundledVenvMarker) -and (-not $SkipInstall)) {
    if (Test-PythonImport -PythonExe $venvPython -ModuleName "qrcode") {
        Write-Host "[PrepPro] Bundled venv detected and qrcode is available. Skipping pip install."
        $SkipInstall = $true
        $dependencyActionHandled = $true
    }
    else {
        Write-Host "[PrepPro] Bundled venv detected but qrcode is missing. Installing missing dependency..."
        Write-Host "[2/4] Upgrading pip..."
        & $venvPython -m pip install --upgrade pip

        Write-Host "[3/4] Installing qrcode[pil]..."
        & $venvPython -m pip install "qrcode[pil]==8.0"
        $SkipInstall = $true
        $dependencyActionHandled = $true
    }
}

if (-not $SkipInstall) {
    Write-Host "[2/4] Upgrading pip..."
    & $venvPython -m pip install --upgrade pip

    Write-Host "[3/4] Installing dependencies..."
    & $venvPython -m pip install -r $requirements
}
elseif (-not $dependencyActionHandled) {
    Write-Host "[2/4] Skipping dependency installation."
    Write-Host "[3/4] Skipping dependency installation."
}

if (-not $SkipRun) {
    Write-Host "[4/4] Starting windows-server (tray mode)..."
    $launcher = if (Test-Path $venvPythonW) { $venvPythonW } else { $venvPython }
    Start-Process -FilePath $launcher -ArgumentList "`"$mainFile`"" -WorkingDirectory $scriptDir
    Write-Host "[PrepPro] Server started. Check system tray."
}
else {
    Write-Host "[4/4] SkipRun set. Server was not started."
}
