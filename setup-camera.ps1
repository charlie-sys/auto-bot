param([string]$ComputerIP, [switch]$ConfigureOnly)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
$taskEnvPath=Join-Path $PSScriptRoot '.env'
$taskLines=if(Test-Path -LiteralPath $taskEnvPath){[IO.File]::ReadAllLines($taskEnvPath)}else{@('PORT=8080')}
$taskValues=@{}
foreach($taskLine in $taskLines){if($taskLine -match '^([^#=]+)=(.*)$'){$taskValues[$matches[1]]=$matches[2]}}
if(-not $ComputerIP){
    $taskAdapters=Get-NetAdapter -Physical -ErrorAction SilentlyContinue | Where-Object {$_.Status -eq 'Up'} | Sort-Object @{Expression={if($_.Name -match 'WLAN|Wi-Fi'){0}else{1}}}
    foreach($taskAdapter in $taskAdapters){
        $taskAddress=Get-NetIPAddress -InterfaceIndex $taskAdapter.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object {$_.IPAddress -notmatch '^(169\.254\.|127\.)'} | Select-Object -First 1
        if($taskAddress){$ComputerIP=$taskAddress.IPAddress;break}
    }
}
$taskParsed=$null
if(-not [Net.IPAddress]::TryParse($ComputerIP,[ref]$taskParsed) -or $taskParsed.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or $ComputerIP -match '^(127\.|169\.254\.|0\.)'){
    throw '无法自动找到局域网 IPv4；请运行 .\setup-camera.ps1 -ComputerIP 电脑局域网IP'
}
if(-not $taskValues['CAMERA_AUTH_SECRET'] -or $taskValues['CAMERA_AUTH_SECRET'].Length -lt 32){
    $taskBytes=New-Object byte[] 32;$taskRng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try{$taskRng.GetBytes($taskBytes)}finally{$taskRng.Dispose()}
    $taskValues['CAMERA_AUTH_SECRET']=([BitConverter]::ToString($taskBytes)).Replace('-','').ToLowerInvariant()
}
$taskValues['CAMERA_ENABLED']='true';$taskValues['CAMERA_RTMP_HOST']=$ComputerIP
if(-not $taskValues['CAMERA_RTMP_PORT']){$taskValues['CAMERA_RTMP_PORT']='1935'}
$taskProfiles=@($taskValues['COMPOSE_PROFILES'] -split ',' | Where-Object {$_})+@('camera')
$taskValues['COMPOSE_PROFILES']=($taskProfiles | Select-Object -Unique)-join ','
$taskKeys=@('CAMERA_ENABLED','CAMERA_RTMP_HOST','CAMERA_RTMP_PORT','CAMERA_AUTH_SECRET','COMPOSE_PROFILES')
$taskOutput=New-Object 'System.Collections.Generic.List[string]'
foreach($taskLine in $taskLines){if($taskLine -notmatch '^(CAMERA_ENABLED|CAMERA_RTMP_HOST|CAMERA_RTMP_PORT|CAMERA_AUTH_SECRET|COMPOSE_PROFILES)='){$taskOutput.Add($taskLine)}}
foreach($taskKey in $taskKeys){$taskOutput.Add($taskKey+'='+$taskValues[$taskKey])}
[IO.File]::WriteAllLines($taskEnvPath,$taskOutput,(New-Object Text.UTF8Encoding($false)))
Write-Host "Camera RTMP server: rtmp://${ComputerIP}:$($taskValues['CAMERA_RTMP_PORT'])"
Write-Host '推流密钥请在 App 设置中获取，不会在终端打印。'
if(-not $ConfigureOnly){
    & docker compose -f compose.yaml -f compose.tunnel.yaml --profile camera up -d --build api tunnel-gateway camera
    if($LASTEXITCODE -ne 0){throw '接流服务部署失败，请检查 Docker Desktop 和镜像下载。'}
    & (Join-Path $PSScriptRoot 'allow-camera-firewall.ps1')
    Write-Host '接流服务已启动。App → 设置 → 自动取景 → 网络摄像头 / Action 4，复制个人推流地址到 DJI Mimo。'
}
