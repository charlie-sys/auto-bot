param([switch]$BuildOnly)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function Ensure-File($url, $path, $expectedSha) {
    if (Test-Path -LiteralPath $path) {
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -eq $expectedSha) { return }
        Remove-Item -LiteralPath $path -Force
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    Write-Host "Downloading $(Split-Path -Leaf $path)..."
    & curl.exe -L --fail --retry 3 --silent --show-error -o $path $url
    if ($LASTEXITCODE -ne 0 -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedSha) { throw "下载或 SHA256 校验失败：$path" }
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw '需要 JDK 17。请先安装 JDK 17 并加入 PATH。' }
$javaVersion = (& javac -version | Select-Object -First 1).ToString()
if ($javaVersion -notmatch '17\.') { throw "需要 JDK 17，当前为 $javaVersion" }
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent (Get-Command java).Source)

$envPath = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    [System.IO.File]::WriteAllLines($envPath, @('PORT=8080'), (New-Object System.Text.UTF8Encoding($false)))
    Write-Host 'Created .env'
}
$config = @{}
Get-Content -LiteralPath $envPath | ForEach-Object { if ($_ -match '^([^#=]+)=(.*)$') { $config[$matches[1]] = $matches[2] } }
$apiPort = if ($config['PORT']) { $config['PORT'] } else { '8080' }

$sdkRoot = Join-Path $PSScriptRoot '.android-sdk'
$sdkManager = Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) {
    $zip = Join-Path $PSScriptRoot '.tools\commandlinetools-win-15859902_latest.zip'
    Ensure-File 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' $zip '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
    $temp = Join-Path $PSScriptRoot '.tools\sdk-extracted'
    Expand-Archive -LiteralPath $zip -DestinationPath $temp -Force
    New-Item -ItemType Directory -Path (Join-Path $sdkRoot 'cmdline-tools\latest') -Force | Out-Null
    Get-ChildItem -LiteralPath (Join-Path $temp 'cmdline-tools') | Move-Item -Destination (Join-Path $sdkRoot 'cmdline-tools\latest')
}
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
if (-not (Test-Path -LiteralPath (Join-Path $sdkRoot 'platforms\android-35\android.jar'))) {
    Write-Host 'Installing Android SDK packages...'
    1..30 | ForEach-Object { 'y' } | & $sdkManager --sdk_root=$sdkRoot --licenses | Out-Null
    & $sdkManager --sdk_root=$sdkRoot 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK 安装失败。' }
}

$gradleExe = Join-Path $PSScriptRoot '.tools\gradle-8.11.1\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradleExe)) {
    $gradleZip = Join-Path $PSScriptRoot '.tools\gradle-8.11.1-bin.zip'
    Ensure-File 'https://mirrors.aliyun.com/gradle/distributions/v8.11.1/gradle-8.11.1-bin.zip' $gradleZip 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'
    Expand-Archive -LiteralPath $gradleZip -DestinationPath (Join-Path $PSScriptRoot '.tools') -Force
}

$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
if (-not $BuildOnly) {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw '请先安装 Docker Desktop。' }
    docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        $desktop = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
        if (-not (Test-Path -LiteralPath $desktop)) { throw 'Docker Desktop 未安装。' }
        Write-Host 'Starting Docker Desktop...'
        Start-Process -FilePath $desktop -WindowStyle Hidden
        $ready = $false
        for ($i = 0; $i -lt 60; $i++) {
            Start-Sleep -Seconds 2
            docker info *> $null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        }
        if (-not $ready) { throw 'Docker Desktop 未能启动。' }
    }
    & (Join-Path $PSScriptRoot 'setup-camera.ps1') -ConfigureOnly
    docker compose -f compose.yaml -f compose.tunnel.yaml --profile camera up -d --build
    if ($LASTEXITCODE -ne 0) { throw '后端启动失败。' }
    & (Join-Path $PSScriptRoot 'allow-camera-firewall.ps1')
    $healthy = $false
    for ($i = 0; $i -lt 30; $i++) {
        try { $health = Invoke-RestMethod -Uri "http://127.0.0.1:$apiPort/health" -TimeoutSec 2; if ($health.ok -and $health.accounts_ready) { $healthy = $true; break } } catch {}
        Start-Sleep -Seconds 2
    }
    if (-not $healthy) { throw '后端健康检查失败。运行 docker compose logs api 查看原因。' }
    Write-Host 'Backend is ready.'
    & (Join-Path $PSScriptRoot 'initialize-accounts.ps1') -BaseUrl "http://127.0.0.1:$apiPort"
}

$device = $null
if (-not $BuildOnly) {
    for ($i = 0; $i -lt 30; $i++) {
        $deviceLine = & $adb devices | Select-String '^\S+\s+device$' | Select-Object -First 1
        if ($deviceLine) { $device = ($deviceLine.ToString() -split '\s+')[0] }
        if ($device) { break }
        if ($i -eq 0) { Write-Host '请用 USB 连接手机、开启 USB 调试并接受调试授权。等待设备连接...' }
        Start-Sleep -Seconds 2
    }
    if (-not $device) { throw '未发现已授权 Android 设备。APK 可通过 .\deploy.ps1 -BuildOnly 单独构建。' }
    & $adb -s $device reverse tcp:8080 "tcp:$apiPort"
    if ($LASTEXITCODE -ne 0) { throw 'ADB 反向端口转发失败。' }
}

$asset = Join-Path $PSScriptRoot 'android\app\src\main\assets\deploy.properties'
New-Item -ItemType Directory -Path (Split-Path -Parent $asset) -Force | Out-Null
@('base_url=http://127.0.0.1:8080') | Set-Content -LiteralPath $asset -Encoding ascii
Push-Location (Join-Path $PSScriptRoot 'android')
try {
    & $gradleExe --no-daemon assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'APK 构建失败。' }
} finally { Pop-Location }
$apk = Join-Path $PSScriptRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw '未生成 APK。' }
Write-Host "APK: $apk"
if (-not $BuildOnly) {
    & $adb -s $device install --no-streaming -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK 安装失败。' }
    & $adb -s $device shell am start -n 'com.autobot.reader/.MainActivity'
    Write-Host '部署完成。手机 App 已启动；使用账号登录，在设置中配置个人模型。初始管理员信息见 .tools/admin-initial-credentials.txt。'
}
