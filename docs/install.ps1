# Skip Intro for Stremio - Windows installer
#
#   irm https://osamudazai666.github.io/stremio-skip-intro/install.ps1 | iex
#
# What it does:
#   1. Downloads the plugin and its watcher to %LOCALAPPDATA%\StremioSkipIntro
#   2. Asks once for admin rights to add a Stremio-only WebView2 setting that opens a
#      local debugging port (127.0.0.1:9334, reachable only from this PC)
#   3. Starts the watcher now and at every sign-in (Startup folder shortcut)
#   4. (Re)starts Stremio
# Uninstall: irm https://osamudazai666.github.io/stremio-skip-intro/uninstall.ps1 | iex

& {
  $ErrorActionPreference = 'Stop'
  $repo = 'OsamuDazai666/stremio-skip-intro'
  $raw = "https://raw.githubusercontent.com/$repo/main"
  $dir = Join-Path $env:LOCALAPPDATA 'StremioSkipIntro'
  $stremioExe = Join-Path $env:LOCALAPPDATA 'Programs\Stremio\stremio-shell-ng.exe'
  $policyKey = 'Software\Policies\Microsoft\Edge\WebView2\AdditionalBrowserArguments'
  $flags = '--autoplay-policy=no-user-gesture-required --disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --remote-debugging-port=9334'

  function Say([string]$msg, [string]$color = 'Gray') { Write-Host "  $msg" -ForegroundColor $color }

  Write-Host ''
  Write-Host '  Skip Intro for Stremio' -ForegroundColor Magenta
  Write-Host '  ----------------------' -ForegroundColor DarkMagenta

  if (-not (Test-Path $stremioExe)) {
    Say 'Stremio for Windows (v5) was not found. Install it first, then run this again.' Yellow
    Start-Process 'https://www.stremio.com/downloads'
    return
  }

  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

  # 1. Files ------------------------------------------------------------------
  Say 'Downloading the plugin...'
  $files = @('skip-intro.plugin.js', 'launcher/StremioCdp.ps1', 'launcher/Start-StremioSkipIntro.ps1', 'launcher/run-hidden.vbs', 'docs/uninstall.ps1')
  foreach ($f in $files) {
    $dest = Join-Path $dir (($f -replace '^docs/', '') -replace '/', '\')
    New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
    Invoke-WebRequest -UseBasicParsing -Uri "$raw/$f" -OutFile $dest
  }
  # Lets the watcher fetch plugin updates from GitHub.
  Set-Content -Path (Join-Path $dir 'launcher\update-url.txt') -Value "$raw/skip-intro.plugin.js" -Encoding ASCII

  # 2. Stremio-only WebView2 debugging flag (needs admin once) ------------------
  $current = $null
  try { $current = (Get-ItemProperty -Path "HKCU:\$policyKey" -ErrorAction Stop).'stremio-shell-ng.exe' } catch { }
  $flagChanged = $false
  if ($current -ne $flags) {
    Say 'Windows will now ask for admin permission. Click "Yes".' Yellow
    Say '(It adds one setting that only affects Stremio.)' DarkGray
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $path = "Registry::HKEY_USERS\$sid\$policyKey"
    $cmd = "New-Item -Path '$path' -Force | Out-Null; New-ItemProperty -Path '$path' -Name 'stremio-shell-ng.exe' -PropertyType String -Value '$flags' -Force | Out-Null"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($cmd))
    try {
      Start-Process powershell.exe -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList '-NoProfile', '-EncodedCommand', $encoded
    } catch {
      Say 'Admin permission was declined, so nothing else was changed. Run the installer again and click "Yes".' Red
      return
    }
    try { $current = (Get-ItemProperty -Path "HKCU:\$policyKey" -ErrorAction Stop).'stremio-shell-ng.exe' } catch { $current = $null }
    if ($current -ne $flags) {
      Say 'Could not add the Stremio setting. Please report this on GitHub.' Red
      return
    }
    $flagChanged = $true
  }

  # 3. Watcher at sign-in ------------------------------------------------------
  Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
    Where-Object { $_.CommandLine -like '*Start-StremioSkipIntro.ps1*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  $vbs = Join-Path $dir 'launcher\run-hidden.vbs'
  $lnk = Join-Path ([Environment]::GetFolderPath('Startup')) 'Stremio Skip Intro.lnk'
  $shell = New-Object -ComObject WScript.Shell
  $s = $shell.CreateShortcut($lnk)
  $s.TargetPath = Join-Path $env:WINDIR 'System32\wscript.exe'
  $s.Arguments = '"' + $vbs + '"'
  $s.WorkingDirectory = Split-Path $vbs
  $s.Description = 'Adds the Skip Intro plugin to Stremio'
  $s.Save()
  Start-Process -FilePath $s.TargetPath -ArgumentList $s.Arguments

  # 4. (Re)start Stremio ---------------------------------------------------------
  $portOpen = $false
  try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri 'http://127.0.0.1:9334/json/version' | Out-Null; $portOpen = $true } catch { }
  $running = Get-Process stremio-shell-ng -ErrorAction SilentlyContinue
  if ($running -and ($flagChanged -or -not $portOpen)) {
    Say 'Restarting Stremio...'
    $running | Stop-Process -Force
    Start-Sleep -Seconds 2
    $running = $null
  }
  if (-not $running) { Start-Process -FilePath $stremioExe }

  Write-Host ''
  Say 'Installed! Play an episode: "Skip Intro" appears during intros.' Green
  Say "Uninstall any time: irm https://osamudazai666.github.io/stremio-skip-intro/uninstall.ps1 | iex" DarkGray
  Write-Host ''
}
