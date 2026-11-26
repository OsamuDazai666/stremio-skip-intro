<#
  Starts the Skip Intro watcher at Windows sign-in (Startup folder shortcut),
  and starts it right now. Open Stremio however you like; the plugin is added
  automatically. Remove with Uninstall-Autostart.ps1.
#>
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$vbs = Join-Path $here 'run-hidden.vbs'
$lnk = Join-Path ([Environment]::GetFolderPath('Startup')) 'Stremio Skip Intro.lnk'
$shell = New-Object -ComObject WScript.Shell
$s = $shell.CreateShortcut($lnk)
$s.TargetPath = Join-Path $env:WINDIR 'System32\wscript.exe'
$s.Arguments = '"' + $vbs + '"'
$s.WorkingDirectory = $here
$s.Description = 'Adds the Skip Intro plugin to Stremio'
$s.Save()
Start-Process -FilePath $s.TargetPath -ArgumentList $s.Arguments
Write-Host "Autostart installed: $lnk"
