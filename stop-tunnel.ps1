$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$statePath = Join-Path $PSScriptRoot '.tools\tunnel\state.json'
$expectedExe = Join-Path $PSScriptRoot '.tools\tunnel\cloudflared.exe'
if (Test-Path -LiteralPath $statePath) {
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $process = Get-Process -Id $state.processId -ErrorAction SilentlyContinue
    if ($process -and $process.Path -eq $expectedExe) { Stop-Process -Id $process.Id }
}
docker compose -f compose.yaml -f compose.tunnel.yaml stop tunnel-gateway
if ($LASTEXITCODE -ne 0) { throw '穿透网关停止失败。' }
Write-Host '内网穿透入口已关闭，本机识题后端仍可继续使用。'
