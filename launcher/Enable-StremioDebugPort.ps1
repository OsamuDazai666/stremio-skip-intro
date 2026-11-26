<#
  One-time setup (needs admin): lets normal Stremio's built-in Edge WebView2
  accept a local debugging connection on 127.0.0.1:9334, so the Skip Intro
  launcher can add the plugin to Stremio's page.

  Only affects stremio-shell-ng.exe. Undo with Disable-StremioDebugPort.ps1.
  The flags before --remote-debugging-port are the ones Stremio itself uses,
  kept so its behaviour (autoplay etc.) is unchanged.
#>
$key = 'HKCU:\Software\Policies\Microsoft\Edge\WebView2\AdditionalBrowserArguments'
$flags = '--autoplay-policy=no-user-gesture-required --disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --remote-debugging-port=9334'
New-Item -Path $key -Force | Out-Null
New-ItemProperty -Path $key -Name 'stremio-shell-ng.exe' -PropertyType String -Value $flags -Force | Out-Null
Write-Host "Set $key\stremio-shell-ng.exe = $flags"
