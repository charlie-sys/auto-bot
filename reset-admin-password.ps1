$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
& docker compose exec -T api python manage.py reset-admin
if ($LASTEXITCODE -ne 0) { throw '管理员密码重置失败，请先启动后端。' }
& (Join-Path $PSScriptRoot 'initialize-accounts.ps1')
Write-Host '已生成新的临时密码；现有管理员登录已失效，首次登录须修改。'
