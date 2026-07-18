$ErrorActionPreference = 'Stop'

$adb = 'C:\Users\roesl\Desktop\test\shield-control\vendor\platform-tools\adb.exe'
$serial = '10.0.0.6:5555'

& $adb connect $serial | Out-Null
& $adb -s $serial shell "su -c 'killall AdGuardHome 2>/dev/null || true; mv -f /data/adb/service.d/98-adguardhome.sh /data/adb/service.d/98-adguardhome.sh.disabled 2>/dev/null || true'"

Write-Output 'AdGuard Home stopped and disabled at boot. Configuration was preserved.'
