# Shield Control Suite 1.2.1

## Shield Control 1.2.1

- Finds the same Shield again after a DHCP address change by combining adb mDNS,
  the local ARP table, saved device identity, and model validation.
- Keeps the existing Wi-Fi-to-USB recovery path and remembers the repaired address.
- Updates Electron to 44.5.1 while retaining bundled ADB 37 and scrcpy 4.1.
- Makes the command-line, Marquee bridge, and AdGuard maintenance tools discover
  Shield Control's bundled adb and the active Shield automatically.
- Ships with a clean production-dependency audit and 20 passing desktop tests.

## Marquee 2.7.0

- Adds learned recommendations from local and Trakt history, ratings, genres,
  decades, cast affinity, and explicit feedback.
- Adds Browse facets, ranked shelves, recommendation explanations, `Not interested`,
  `Up next`, franchises, cast filmographies, trailers, and title-logo artwork.
- Imports and backfills Trakt history and ratings without inflating play counts.
- Preserves provider discovery, playback tracking, Stremio handoff, and TV focus.

## Included

- Shield Control installer, portable executable, blockmap, and `latest.yml`
- Marquee 2.7.0 signed Android TV APK
- Shield Hooks 0.2.0 APK
- AdGuard Home 0.107.78 Linux arm64 package
- SHA-256 checksum manifest

The Windows executables are not Authenticode-signed. Verify downloads against
`SHA256SUMS.txt`. No TMDB, Trakt, account, or device credentials are included.
