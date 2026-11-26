@echo off
rem Skip Intro for Stremio - double-click installer.
rem Runs the PowerShell installer from https://osamudazai666.github.io/stremio-skip-intro/install.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "irm https://osamudazai666.github.io/stremio-skip-intro/install.ps1 | iex"
echo.
pause
