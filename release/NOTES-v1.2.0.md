# Shield Control Suite 1.2.0

## Shield Control 1.2.0

- Adds GitHub Release checks on launch and every 15 minutes.
- Installed builds download verified updates in the background and install on restart.
- Portable builds report a newer release and open its download page.
- Retains bundled ADB/scrcpy, Wi-Fi-to-USB fallback, live Shield telemetry,
  root-aware file browsing, folder pulls, collision-safe `Downloads\KodiDrop`
  downloads, APK installs, app management, remote input, and Morphe status.

## Marquee 2.5.0

- Loads six core provider shelves first, then personalized rows and remaining
  categories progressively.
- Caches up to 160 completed provider shelves for 30 minutes.
- Adds All, Movies, and Series catalog filters plus `Surprise me`.
- Keeps 26 provider categories and up to 60 unique titles per shelf.
- Preserves the installed Marquee signing identity and in-place settings.

## Included

- Shield Control installer, portable executable, blockmap, and `latest.yml`
- Marquee 2.5.0 signed Android TV APK
- Shield Hooks 0.2.0 APK using the currently deployed signing identity
- AdGuard Home 0.107.78 Linux arm64 package
- SHA-256 checksum manifest

The Windows executables are not Authenticode-signed. Verify downloaded files
against `SHA256SUMS.txt`. No TMDB, Trakt, account, or device credentials are
included in the repository or release assets.
