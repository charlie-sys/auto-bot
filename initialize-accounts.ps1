param([string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$privateDir = Join-Path $PSScriptRoot '.tools'
New-Item -ItemType Directory -Path $privateDir -Force | Out-Null
$credentialPath = Join-Path $privateDir 'admin-initial-credentials.txt'
$initialJson = & docker compose exec -T api python manage.py bootstrap-info
if ($LASTEXITCODE -ne 0) { throw '无法读取管理员初始化状态。' }
$initial = $initialJson | ConvertFrom-Json
if ($initial.password) {
    $text = @"
识题助手 v2 管理员初始化信息（私密文件，请勿公开）

管理地址：$($BaseUrl.TrimEnd('/'))/admin
用户名：$($initial.username)
初始密码：$($initial.password)

首次登录必须修改密码。此文件中的初始密码修改后失效。
手机 App 使用同一个用户名和密码，服务地址填写：$BaseUrl
现有模型配置仅迁移至此初始管理员账号；其他账号须自行配置。
"@
    [IO.File]::WriteAllText($credentialPath, $text, [Text.UTF8Encoding]::new($true))
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $credentialPath /inheritance:r /grant:r ("*{0}:(F)" -f $identity) '*S-1-5-18:(F)' '*S-1-5-32-544:(F)' *> $null
    if ($LASTEXITCODE -ne 0) { throw '无法收紧初始管理员信息文件权限。' }
    Write-Host "初始管理员信息已保存至：$credentialPath"
} else { Write-Host '管理员已初始化，请使用自己设置的密码登录。' }

# Delete the old shared provider settings only after their private import is confirmed.
$stateJson = & docker compose exec -T api python manage.py status
if ($LASTEXITCODE -ne 0) { throw '无法确认账号数据状态。' }
$state = $stateJson | ConvertFrom-Json
$envPath = Join-Path $PSScriptRoot '.env'
if ((Test-Path -LiteralPath $envPath) -and $state.initial_model_configured) {
    $lines = Get-Content -LiteralPath $envPath
    $filtered = @($lines | Where-Object { $_ -notmatch '^(MODEL_BASE_URL|MODEL_NAME|MODEL_API_KEY|APP_TOKEN)=' })
    if ($filtered.Count -ne $lines.Count) {
        [IO.File]::WriteAllLines($envPath, $filtered, [Text.UTF8Encoding]::new($false))
        Write-Host '旧全局模型配置已迁移并从 .env 移除。'
    }
}
