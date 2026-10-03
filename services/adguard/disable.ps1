param([string]$AdbPath, [string]$Serial)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\..\apps\shield-control\tools\ShieldConnection.ps1')
$connection = Resolve-ShieldConnection -AdbPath $AdbPath -Serial $Serial
$adb = $connection.Adb
$serial = $connection.Serial

& $adb -s $serial shell "su -c 'killall AdGuardHome 2>/dev/null || true; mv -f /data/adb/service.d/98-adguardhome.sh /data/adb/service.d/98-adguardhome.sh.disabled 2>/dev/null || true'"

Write-Output 'AdGuard Home stopped and disabled at boot. Configuration was preserved.'
