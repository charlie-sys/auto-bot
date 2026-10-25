$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function Protect-PrivateFile([string]$Path) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $Path /inheritance:r /grant:r ("*{0}:(F)" -f $identity) '*S-1-5-18:(F)' '*S-1-5-32-544:(F)' *> $null
    if ($LASTEXITCODE -ne 0) { throw '无法收紧连接文件权限。' }
}

function Read-EnvFile([string]$Path) {
    $values = @{}
    Get-Content -LiteralPath $Path | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { $values[$matches[1]] = $matches[2] }
    }
    return $values
}

if (-not (Test-Path -LiteralPath '.env')) { throw '请先双击 deploy.cmd，完成本机部署。' }
$localConfig = Read-EnvFile '.env'
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw '请安装并打开 Docker Desktop。' }
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    $desktop = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path -LiteralPath $desktop)) { throw '请先启动 Docker Desktop。' }
    Start-Process -FilePath $desktop -WindowStyle Hidden
    $ready = $false
    for ($i = 0; $i -lt 45; $i++) {
        Start-Sleep -Seconds 2
        docker info *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    }
    if (-not $ready) { throw 'Docker Desktop 启动超时。' }
}

$privateDir = Join-Path $PSScriptRoot '.tools\tunnel'
New-Item -ItemType Directory -Path $privateDir -Force | Out-Null
$exe = Join-Path $privateDir 'cloudflared.exe'
$statePath = Join-Path $privateDir 'state.json'
$connectionPath = Join-Path $privateDir 'connection.txt'

# Pinned official Cloudflare release; digest published by GitHub Releases.
$expectedSha = '76b2805253d0d6ffacb092a7acfbee99e7af6917835c0c5410e23f3ef5052da2'
if (-not (Test-Path -LiteralPath $exe) -or (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedSha) {
    Write-Host '正在下载 Cloudflare 官方客户端（2026.10.3）...'
    & curl.exe -L --fail --retry 2 --connect-timeout 20 --max-time 240 --silent --show-error -o $exe 'https://github.com/cloudflare/cloudflared/releases/download/2026.10.3/cloudflared-windows-amd64.exe'
    if ($LASTEXITCODE -ne 0 -or (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedSha) { throw '客户端下载或 SHA256 校验失败。' }
}

$composeArgs = @('-f', 'compose.yaml', '-f', 'compose.tunnel.yaml')
docker compose @composeArgs up -d --build api tunnel-gateway
if ($LASTEXITCODE -ne 0) { throw '本机识题服务或穿透网关启动失败。' }
$headers = @{}
$gatewayReady = $false
for ($i = 0; $i -lt 20; $i++) {
    try {
        $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8081/health' -Headers $headers -TimeoutSec 3
        if ($health.ok -and $health.accounts_ready) { $gatewayReady = $true; break }
    } catch {}
    Start-Sleep -Seconds 1
}
if (-not $gatewayReady) { throw '穿透网关健康检查失败。' }

$url = $null
$activeProcess = $null
if (Test-Path -LiteralPath $statePath) {
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $existing = Get-Process -Id $state.processId -ErrorAction SilentlyContinue
    if ($existing -and $existing.Path -eq $exe) { $activeProcess = $existing; $url = $state.url }
}
if (-not $activeProcess) {
    # Recover a tunnel if an earlier startup was interrupted after launching it.
    $owned = Get-Process cloudflared -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe } | Select-Object -First 1
    $recoveryLog = Join-Path $privateDir 'stderr.log'
    if ($owned -and (Test-Path -LiteralPath $recoveryLog)) {
        $recovered = [regex]::Match([string](Get-Content -LiteralPath $recoveryLog -Raw), 'https://[a-z0-9-]+\.trycloudflare\.com')
        if ($recovered.Success) { $activeProcess = $owned; $url = $recovered.Value }
        else { Stop-Process -Id $owned.Id -ErrorAction SilentlyContinue }
    }
}
if (-not $activeProcess) {
    $stdout = Join-Path $privateDir 'stdout.log'
    $stderr = Join-Path $privateDir 'stderr.log'
    $activeProcess = Start-Process -FilePath $exe -ArgumentList @('tunnel', '--no-autoupdate', '--protocol', 'http2', '--url', 'http://127.0.0.1:8081') -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    for ($i = 0; $i -lt 75; $i++) {
        if ($activeProcess.HasExited) { throw 'Cloudflare 客户端已退出，检查 .tools\tunnel\stderr.log。' }
        if (Test-Path -LiteralPath $stderr) {
            $logs = [string](Get-Content -LiteralPath $stderr -Raw)
            $found = [regex]::Match($logs, 'https://[a-z0-9-]+\.trycloudflare\.com')
            if ($found.Success) { $url = $found.Value; break }
        }
        Start-Sleep -Seconds 1
    }
    if (-not $url) { Stop-Process -Id $activeProcess.Id -ErrorAction SilentlyContinue; throw '未能取得临时地址，当前网络可能无法连接 Cloudflare。' }
}
@{ processId = $activeProcess.Id; url = $url; executable = $exe } | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8

$instructions = @"
临时 HTTPS 地址：$url
管理后台：$url/admin

手机 App → 登录 / 注册页面：
服务地址填写上面的 HTTPS 地址，使用自己的用户名和密码登录。
新用户注册后须由超级管理员审核。模型 URL、模型名和 API Key 在登录后的个人设置中填写。
初始管理员信息保存在 .tools/admin-initial-credentials.txt，不要公开密码。
电脑、Docker Desktop 和后台 cloudflared 进程需要保持运行；重启穿透后地址通常变化。
双击 stop-tunnel.cmd 可关闭外网入口。
"@
[IO.File]::WriteAllText($connectionPath, $instructions, [Text.UTF8Encoding]::new($true))
Protect-PrivateFile $connectionPath
& (Join-Path $PSScriptRoot 'initialize-accounts.ps1') -BaseUrl $url

$externalReady = $false
for ($i = 0; $i -lt 12; $i++) {
    try {
        $externalHealth = Invoke-RestMethod -Uri ($url + '/health') -Headers $headers -TimeoutSec 10
        if ($externalHealth.ok -and $externalHealth.accounts_ready) { $externalReady = $true; break }
    } catch {}
    Start-Sleep -Seconds 2
}
Write-Host "临时地址：$url"
Write-Host "手机连接信息（账号登录）已保存：$connectionPath"
if ($externalReady) { Write-Host 'HTTPS 外网健康检查通过。' }
else { Write-Warning '已取得地址，但 HTTPS 外网检查暂未通过；查看 .tools\tunnel\stderr.log，并用手机移动网络测试。' }
