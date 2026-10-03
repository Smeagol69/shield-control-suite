param([string]$AdbPath, [string]$Serial)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\..\apps\shield-control\tools\ShieldConnection.ps1')
$connection = Resolve-ShieldConnection -AdbPath $AdbPath -Serial $Serial
$adb = $connection.Adb
$serial = $connection.Serial

& $adb -s $serial shell "su -c 'if [ -f /data/adb/service.d/98-adguardhome.sh.disabled ]; then mv -f /data/adb/service.d/98-adguardhome.sh.disabled /data/adb/service.d/98-adguardhome.sh; fi; chmod 0755 /data/adb/service.d/98-adguardhome.sh; /data/adb/service.d/98-adguardhome.sh'"

Write-Output 'AdGuard Home enabled and started.'
