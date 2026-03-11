@echo off
setlocal

cd /d "%~dp0"

echo [PropPro] Starting windows-server...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0get-start.ps1"
set EXIT_CODE=%ERRORLEVEL%

if not "%EXIT_CODE%"=="0" (
  echo.
  echo [PropPro] Startup failed. Exit code: %EXIT_CODE%
  pause
  exit /b %EXIT_CODE%
)

endlocal
