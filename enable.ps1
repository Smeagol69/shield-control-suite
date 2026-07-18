$ErrorActionPreference = 'Stop'

$adb = 'C:\Users\roesl\Desktop\test\shield-control\vendor\platform-tools\adb.exe'
$serial = '10.0.0.6:5555'

& $adb connect $serial | Out-Null
& $adb -s $serial shell "su -c 'if [ -f /data/adb/service.d/98-adguardhome.sh.disabled ]; then mv -f /data/adb/service.d/98-adguardhome.sh.disabled /data/adb/service.d/98-adguardhome.sh; fi; chmod 0755 /data/adb/service.d/98-adguardhome.sh; /data/adb/service.d/98-adguardhome.sh'"

Write-Output 'AdGuard Home enabled and started.'
