#!/system/bin/sh
# AdGuard Home whole-home DNS blocker, started by Magisk service.d.

DIR=/data/adb/adguardhome
BIN="$DIR/AdGuardHome"
LOG="$DIR/agh.log"

# Wait for Android and networking to settle.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
done
sleep 8

# Do not create a second listener after a service restart.
if pidof AdGuardHome >/dev/null 2>&1; then
  exit 0
fi

# Keep the previous startup log for diagnostics.
if [ -f "$LOG" ]; then
  mv -f "$LOG" "$LOG.previous"
fi

umask 077
export SSL_CERT_DIR=/system/etc/security/cacerts
"$BIN" --no-check-update -w "$DIR" >"$LOG" 2>&1 &
