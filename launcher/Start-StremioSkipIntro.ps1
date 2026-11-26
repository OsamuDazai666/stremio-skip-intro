<#
.SYNOPSIS
  Adds the Skip Intro plugin to the normal Stremio desktop app.

.DESCRIPTION
  Watches for Stremio (stremio-shell-ng.exe). While it runs, checks its page
  every few seconds through the local WebView2 debugging port (127.0.0.1:9334,
  enabled once with Enable-StremioDebugPort.ps1) and injects
  skip-intro.plugin.js when it is missing (first start, page reloads).
  Stremio itself is not modified: it still loads https://web.stremio.com and
  plays through mpv.

.PARAMETER StartStremio
  Also start Stremio if it is not running.

.PARAMETER Once
  Exit when Stremio exits (default: keep watching for the next start).
#>
param(
  [switch]$StartStremio,
  [switch]$Once
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here 'StremioCdp.ps1')
$pluginPath = Join-Path (Split-Path -Parent $here) 'skip-intro.plugin.js'
$stremioExe = Join-Path $env:LOCALAPPDATA 'Programs\Stremio\stremio-shell-ng.exe'
$logPath = Join-Path $env:TEMP 'stremio-skip-intro.log'

function Write-Log([string]$line) {
  try { Add-Content -Path $logPath -Value ((Get-Date).ToString('HH:mm:ss') + '  ' + $line) } catch { }
}

# Only one watcher at a time.
$mutex = New-Object System.Threading.Mutex($false, 'Local\StremioSkipIntroWatcher')
if (-not $mutex.WaitOne(0)) { Write-Log 'watcher already running'; return }

if ($StartStremio -and -not (Get-Process stremio-shell-ng -ErrorAction SilentlyContinue)) {
  Start-Process -FilePath $stremioExe
}
Write-Log 'watcher started'

# Installed copies (not the dev checkout) get plugin updates from GitHub.
$updateUrlFile = Join-Path $here 'update-url.txt'
$lastUpdateCheck = [DateTime]::MinValue
function Update-Plugin {
  if (-not (Test-Path $updateUrlFile)) { return }
  $url = (Get-Content $updateUrlFile -TotalCount 1).Trim()
  if (-not $url) { return }
  try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $new = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 20 -Uri $url).Content
    if ($new -is [byte[]]) { $new = [Text.Encoding]::UTF8.GetString($new) }
    if ($new.Length -lt 10000 -or $new -notmatch '@name Skip Intro') { Write-Log 'update skipped: unexpected download'; return }
    $old = if (Test-Path $pluginPath) { [IO.File]::ReadAllText($pluginPath) } else { '' }
    if ($new -ne $old) {
      [IO.File]::WriteAllText($pluginPath, $new, (New-Object Text.UTF8Encoding($false)))
      Write-Log 'plugin updated from GitHub'
    }
  } catch {
    Write-Log ('update check failed: ' + $_.Exception.Message)
  }
}

$wasRunning = $false
$warnedNoPort = $false
while ($true) {
  if (((Get-Date) - $lastUpdateCheck).TotalHours -ge 6) {
    $lastUpdateCheck = Get-Date
    Update-Plugin
  }
  $running = [bool](Get-Process stremio-shell-ng -ErrorAction SilentlyContinue)
  if ($running) {
    $wasRunning = $true
    $page = Get-StremioPage
    if ($page) {
      $warnedNoPort = $false
      try {
        # The file's timestamp + size identifies the build; a changed file is hot-swapped.
        $file = Get-Item $pluginPath
        $build = '' + $file.LastWriteTimeUtc.Ticks + '-' + $file.Length
        $current = Invoke-StremioEval $page 'window.__skipIntroLoaded ? String(window.__SKIPINTRO_BUILD || "old") : ""'
        if ($current -ne $build) {
          $src = [IO.File]::ReadAllText($pluginPath)
          # Remove the old build, then make sure Stremio's own postMessage is the native one
          # (the shell keeps a copy in window.qt.webChannelTransport.send).
          $prefix = @"
if (window.SkipIntro && window.SkipIntro.destroy) window.SkipIntro.destroy();
(function () {
  var wv = window.chrome && window.chrome.webview;
  var nat = window.qt && window.qt.webChannelTransport && window.qt.webChannelTransport.send;
  if (wv && typeof nat === 'function' && /native code/.test(String(nat)) && wv.postMessage !== nat) wv.postMessage = nat;
})();
window.__SKIPINTRO_BUILD = '$build';

"@
          Invoke-StremioEval $page ($prefix + $src + "`n;'ok'") | Out-Null
          Write-Log ("plugin $build injected into " + $page.url + $(if ($current) { " (replaced $current)" } else { '' }))
        }
      } catch {
        Write-Log ('inject failed: ' + $_.Exception.Message)
      }
    } elseif (-not $warnedNoPort) {
      $warnedNoPort = $true
      Write-Log 'Stremio is running but its debug port is not reachable (run Enable-StremioDebugPort.ps1 once, then restart Stremio)'
    }
    Start-Sleep -Seconds 2
  } else {
    if ($wasRunning -and $Once) { break }
    $wasRunning = $false
    Start-Sleep -Seconds 3
  }
}
Write-Log 'watcher stopped'
