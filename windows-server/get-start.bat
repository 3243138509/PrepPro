@echo off
setlocal

cd /d "%~dp0"

echo [RemoteCapture] Starting windows-server...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0get-start.ps1"
set EXIT_CODE=%ERRORLEVEL%

if not "%EXIT_CODE%"=="0" (
  echo.
  echo [RemoteCapture] Startup failed. Exit code: %EXIT_CODE%
  pause
  exit /b %EXIT_CODE%
)

endlocal
