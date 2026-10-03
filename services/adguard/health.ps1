param([string]$AdbPath, [string]$Serial)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\..\apps\shield-control\tools\ShieldConnection.ps1')
$connection = Resolve-ShieldConnection -AdbPath $AdbPath -Serial $Serial
$adb = $connection.Adb
$serial = $connection.Serial
$ip = $serial -replace ':\d+$', ''

$process = & $adb -s $serial shell "su -c 'pidof AdGuardHome'"
if (-not $process.Trim()) {
  throw 'AdGuard Home is not running.'
}

$allowed = Resolve-DnsName -Name 'example.org' -Server $ip -DnsOnly
$blocked = Resolve-DnsName -Name 'pagead2.googlesyndication.com' -Server $ip -DnsOnly

$blockedV4 = $blocked | Where-Object IPAddress -eq '0.0.0.0'
$blockedV6 = $blocked | Where-Object IPAddress -eq '::'
if (-not $allowed -or (-not $blockedV4 -and -not $blockedV6)) {
  throw 'DNS is responding, but the allow/block checks did not pass.'
}

[pscustomobject]@{
  Status    = 'healthy'
  Pid       = $process.Trim()
  Dns       = "${ip}:53"
  Dashboard = "http://${ip}:3000"
  Filtering = 'verified'
}
