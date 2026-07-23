# Release artifacts

Finished binaries are attached to this repository's
[GitHub Releases](https://github.com/Smeagol69/shield-control-suite/releases)
instead of being committed to Git history.

The release contains:

- Shield Control one-click Windows installer
- Shield Control portable Windows executable
- Shield Control `latest.yml` and blockmap update metadata
- Marquee signed Android TV APK
- Shield Hooks signed Android TV APK
- AdGuard Home Shield deployment package
- `SHA256SUMS.txt`

Marquee can also ship as a component release, such as
[`marquee-v2.6.0`](https://github.com/Smeagol69/shield-control-suite/releases/tag/marquee-v2.6.0).
Component releases are intentionally not marked as the repository's latest
release, so Shield Control continues to resolve its Electron update metadata
from the latest suite release.

Verify a downloaded file in PowerShell:

```powershell
Get-FileHash -Algorithm SHA256 .\downloaded-file
```

Compare the result with `SHA256SUMS.txt` from the same release.
