$ErrorActionPreference='Stop'
$taskIdentity=[Security.Principal.WindowsIdentity]::GetCurrent()
$taskPrincipal=New-Object Security.Principal.WindowsPrincipal($taskIdentity)
if(-not $taskPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){
    Write-Host 'Windows 防火墙规则需要管理员权限。请右键 allow-camera-firewall.cmd，选择“以管理员身份运行”。'
    return
}
$taskPort=1935
Get-Content -LiteralPath (Join-Path $PSScriptRoot '.env') | ForEach-Object {if($_ -match '^CAMERA_RTMP_PORT=(\d+)$'){$taskPort=[int]$matches[1]}}
$taskName='AutoBot-NetworkCamera-RTMP'
if(Get-NetFirewallRule -Name $taskName -ErrorAction SilentlyContinue){Remove-NetFirewallRule -Name $taskName}
New-NetFirewallRule -Name $taskName -DisplayName 'Auto Bot Camera RTMP (Local Subnet)' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $taskPort -Profile Private,Public -RemoteAddress LocalSubnet | Out-Null
Write-Host "已允许同一子网访问 TCP $taskPort（专用/公用配置均可）；不开放 RTSP 或无认证画面读取。"
