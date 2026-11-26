# Skip Intro for Stremio - uninstaller
#
#   irm https://osamudazai666.github.io/stremio-skip-intro/uninstall.ps1 | iex
#
# Stops the watcher, removes the Startup shortcut, the installed files and the
# Stremio-only WebView2 setting (asks for admin once), then restarts Stremio.

& {
  $dir = Join-Path $env:LOCALAPPDATA 'StremioSkipIntro'
  $policyKey = 'Software\Policies\Microsoft\Edge\WebView2\AdditionalBrowserArguments'
  function Say([string]$msg, [string]$color = 'Gray') { Write-Host "  $msg" -ForegroundColor $color }

  Write-Host ''
  Write-Host '  Uninstalling Skip Intro for Stremio' -ForegroundColor Magenta

  Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
    Where-Object { $_.CommandLine -like '*Start-StremioSkipIntro.ps1*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  Remove-Item (Join-Path ([Environment]::GetFolderPath('Startup')) 'Stremio Skip Intro.lnk') -ErrorAction SilentlyContinue
  Say 'Watcher stopped and removed from startup.'

  $hasFlag = $false
  try { $hasFlag = [bool](Get-ItemProperty -Path "HKCU:\$policyKey" -ErrorAction Stop).'stremio-shell-ng.exe' } catch { }
  if ($hasFlag) {
    Say 'Windows will ask for admin permission to remove the Stremio setting. Click "Yes".' Yellow
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $path = "Registry::HKEY_USERS\$sid\$policyKey"
    $cmd = "Remove-ItemProperty -Path '$path' -Name 'stremio-shell-ng.exe' -ErrorAction SilentlyContinue; if (-not (Get-Item '$path' -ErrorAction SilentlyContinue).Property) { Remove-Item '$path' -ErrorAction SilentlyContinue }"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($cmd))
    try {
      Start-Process powershell.exe -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList '-NoProfile', '-EncodedCommand', $encoded
      Say 'Stremio setting removed.'
    } catch {
      Say 'Admin permission was declined: the Stremio setting is still there. Run this again to remove it.' Yellow
    }
  }

  if (Test-Path $dir) { Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue }
  Say 'Files removed.'

  $running = Get-Process stremio-shell-ng -ErrorAction SilentlyContinue
  if ($running) {
    $exe = $running[0].Path
    $running | Stop-Process -Force
    Start-Sleep -Seconds 2
    if ($exe) { Start-Process -FilePath $exe }
    Say 'Stremio restarted without the plugin.'
  }
  Write-Host ''
  Say 'Done.' Green
  Write-Host ''
}
