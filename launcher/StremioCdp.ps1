# Minimal Chrome DevTools Protocol client for Stremio's WebView2 (127.0.0.1:9334).
# Dot-source this file.

$script:CdpPort = 9334

function Get-StremioPage {
  try {
    $list = Invoke-RestMethod -UseBasicParsing -TimeoutSec 3 -Uri "http://127.0.0.1:$script:CdpPort/json/list"
  } catch { return $null }
  return $list | Where-Object { $_.type -eq 'page' -and $_.url -like 'https://web.stremio.com/*' } | Select-Object -First 1
}

function Invoke-Cdp {
  param([string]$WsUrl, [string]$Method, [hashtable]$Params = @{}, [int]$TimeoutMs = 15000)
  $ws = New-Object System.Net.WebSockets.ClientWebSocket
  $ct = [Threading.CancellationToken]::None
  try {
    if (-not $ws.ConnectAsync([Uri]$WsUrl, $ct).Wait($TimeoutMs)) { throw 'CDP connect timeout' }
    $json = @{ id = 1; method = $Method; params = $Params } | ConvertTo-Json -Depth 20 -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $seg = New-Object 'System.ArraySegment[byte]' -ArgumentList @(, $bytes)
    if (-not $ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).Wait($TimeoutMs)) { throw 'CDP send timeout' }
    $buffer = New-Object byte[] 65536
    while ($true) {
      $ms = New-Object System.IO.MemoryStream
      do {
        $rseg = New-Object 'System.ArraySegment[byte]' -ArgumentList @(, $buffer)
        $task = $ws.ReceiveAsync($rseg, $ct)
        if (-not $task.Wait($TimeoutMs)) { throw 'CDP receive timeout' }
        $ms.Write($buffer, 0, $task.Result.Count)
      } while (-not $task.Result.EndOfMessage)
      $reply = [Text.Encoding]::UTF8.GetString($ms.ToArray()) | ConvertFrom-Json
      if ($reply.id -eq 1) { return $reply }
    }
  } finally {
    $ws.Dispose()
  }
}

function Invoke-StremioEval {
  param($Page, [string]$Expression)
  $r = Invoke-Cdp -WsUrl $Page.webSocketDebuggerUrl -Method 'Runtime.evaluate' -Params @{
    expression = $Expression; returnByValue = $true; awaitPromise = $true
  }
  if ($r.result.exceptionDetails) { throw ('page exception: ' + ($r.result.exceptionDetails | ConvertTo-Json -Depth 5 -Compress)) }
  return $r.result.result.value
}
