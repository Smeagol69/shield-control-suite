# AdGuard Home on NVIDIA Shield

AdGuard Home v0.107.78 runs as root on the Shield at `10.0.0.6`.

## Endpoints

- DNS: `10.0.0.6:53` (TCP and UDP)
- Dashboard: `http://10.0.0.6:3000`
- Credentials: `credentials.txt`
- Upstream: Quad9 DNS-over-HTTPS
- Filter: AdGuard DNS filter, refreshed every 24 hours

## Make it whole-home

1. Reserve `10.0.0.6` for the Shield in the router's DHCP settings.
2. Set the router's LAN/DHCP DNS server to `10.0.0.6`.
3. Leave secondary DNS blank, or also use `10.0.0.6`. A public secondary DNS
   lets clients bypass filtering.
4. Renew DHCP leases or reconnect clients.

Do not change the router's WAN DNS field unless it is also the DNS value handed
to LAN clients.

## Maintenance

```powershell
.\health.ps1
.\disable.ps1
.\enable.ps1
```

Magisk starts `/data/adb/service.d/98-adguardhome.sh` after every boot.
AdGuard data and configuration live in `/data/adb/adguardhome`.

## Rollback

Run `disable.ps1`, then restore the router's previous LAN/DHCP DNS setting.
No AdGuard files are deleted, so `enable.ps1` restores the service.
