@echo off
rem Double-click to build the Windows installer and the Android APK into .\dist
rem Extra args are forwarded, e.g.:  build-all.bat -Relay wss://relay.example.com
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-all.ps1" %*
if errorlevel 1 (
  echo.
  echo BUILD FAILED - see the messages above.
)
pause
