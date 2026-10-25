$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$result = & docker compose exec -T api python manage.py backup
if ($LASTEXITCODE -ne 0) { throw '数据库备份失败。' }
$snapshot = $result | ConvertFrom-Json
$container = & docker compose ps -q api
if (-not $container -or $snapshot.path -notmatch '^/data/backups/reader-[0-9-]+\.zip$') { throw '备份路径无效。' }
$folder = Join-Path $PSScriptRoot '.tools\backups'
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$destination = Join-Path $folder ([IO.Path]::GetFileName($snapshot.path))
& docker cp "${container}:$($snapshot.path)" $destination
if ($LASTEXITCODE -ne 0) { throw '导出备份失败。' }
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $destination /inheritance:r /grant:r ("*{0}:(F)" -f $identity) '*S-1-5-18:(F)' '*S-1-5-32-544:(F)' *> $null
if ($LASTEXITCODE -ne 0) { throw '无法收紧备份文件权限。' }
Write-Host "账号、个人模型加密配置和解密密钥已备份至：$destination"
