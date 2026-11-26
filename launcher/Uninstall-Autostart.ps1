<#
  Removes the Startup shortcut and stops a running Skip Intro watcher.
#>
$lnk = Join-Path ([Environment]::GetFolderPath('Startup')) 'Stremio Skip Intro.lnk'
Remove-Item $lnk -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
  Where-Object { $_.CommandLine -like '*Start-StremioSkipIntro.ps1*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Write-Host 'Autostart removed and watcher stopped.'
