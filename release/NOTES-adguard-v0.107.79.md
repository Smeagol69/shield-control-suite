# AdGuard Home 0.107.79 for NVIDIA Shield

This component release packages the official AdGuard Home v0.107.79 Linux
ARM64 archive used by the Shield Control deployment. The archive was verified
against AdGuard Team's published `checksums.txt` before release.

The existing configuration, filters, query history, service script, and
dashboard credentials are preserved by an in-place device upgrade. The prior
binary should be retained on-device for rollback.

## Asset

- `AdGuardHome_linux_arm64.tar.gz`

Verify the archive with `SHA256SUMS-adguard-v0.107.79.txt` before deployment.

Upstream release:
<https://github.com/AdguardTeam/AdGuardHome/releases/tag/v0.107.79>
