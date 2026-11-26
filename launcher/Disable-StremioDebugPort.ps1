<#
  Undo Enable-StremioDebugPort.ps1 (needs admin): removes Stremio's WebView2
  debugging flag. Restart Stremio afterwards.
#>
$key = 'HKCU:\Software\Policies\Microsoft\Edge\WebView2\AdditionalBrowserArguments'
if (Test-Path $key) {
  Remove-ItemProperty -Path $key -Name 'stremio-shell-ng.exe' -ErrorAction SilentlyContinue
  if (-not (Get-Item $key).Property) { Remove-Item $key -ErrorAction SilentlyContinue }
}
Write-Host 'Removed the Stremio debugging flag.'
