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

Verify a downloaded file in PowerShell:

```powershell
Get-FileHash -Algorithm SHA256 .\downloaded-file
```

Compare the result with `SHA256SUMS.txt` from the same release.
