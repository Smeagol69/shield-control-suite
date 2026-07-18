$ErrorActionPreference = 'Stop'

$adb = 'C:\Users\roesl\Desktop\test\shield-control\vendor\platform-tools\adb.exe'
$serial = '10.0.0.6:5555'

& $adb connect $serial | Out-Null
if ($LASTEXITCODE -ne 0) {
  throw 'Shield is not reachable over ADB.'
}

$process = & $adb -s $serial shell "su -c 'pidof AdGuardHome'"
if (-not $process.Trim()) {
  throw 'AdGuard Home is not running.'
}

$allowed = Resolve-DnsName -Name 'example.org' -Server '10.0.0.6' -DnsOnly
$blocked = Resolve-DnsName -Name 'pagead2.googlesyndication.com' -Server '10.0.0.6' -DnsOnly

$blockedV4 = $blocked | Where-Object IPAddress -eq '0.0.0.0'
$blockedV6 = $blocked | Where-Object IPAddress -eq '::'
if (-not $allowed -or (-not $blockedV4 -and -not $blockedV6)) {
  throw 'DNS is responding, but the allow/block checks did not pass.'
}

[pscustomobject]@{
  Status    = 'healthy'
  Pid       = $process.Trim()
  Dns       = '10.0.0.6:53'
  Dashboard = 'http://10.0.0.6:3000'
  Filtering = 'verified'
}
