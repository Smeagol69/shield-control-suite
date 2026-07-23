# Shield Control

Desktop control center for a NVIDIA Shield TV over adb: live screen control (scrcpy),
drag-and-drop file transfer, APK install, and a device file browser. adb and scrcpy are
bundled in `vendor/` — nothing else needs to be installed.

## Run (dev)

```
npm start
```

## Build the .exe

```
npm run dist
```

Outputs to `dist/`:
- `ShieldControl-<version>-portable.exe` — single standalone exe
- `Shield-Control-Setup-<version>.exe` — one-click installer (faster startup, Start Menu entry)
- `latest.yml` and the installer blockmap — update metadata for GitHub Releases

## Automatic updates

The one-click installer is the recommended build. Packaged copies check the
repository's latest GitHub Release on launch and every 15 minutes. Installed
copies download a newer installer in the background, verify electron-builder's
SHA-512 metadata, and offer a one-click restart when it is ready.

Portable copies use the same live release check but open the release page for
the replacement executable. They do not overwrite their own running `.exe`.
Development and `SHIELD_SMOKE` sessions never contact the update feed.

## How it connects

On launch the app runs `adb connect 10.0.0.6:5555`. If WiFi fails it looks for a USB
device, re-enables WiFi adb with `adb tcpip 5555`, and retries; if WiFi is still
unreachable it keeps working over USB. It retries automatically every ~10 s and
self-heals stale (`offline`) connections.

- Green pill = connected. Amber = connecting/authorize on TV. Red = unreachable.
- Settings persist in `%APPDATA%\Shield Control\config.json` (`ip`, `port`, last folder).
  Edit `ip` there if the Shield's address ever changes — there is no setup UI by design.

## Status dashboard

The left panel polls the Shield every 8 s (60 s for log/backup) over a single adb call:
online state, IP, storage, memory, CPU temp (via `thermalservice`), uptime, Kodi
running/playback state, recent Kodi log errors (view + pull full log), last backup in
`backupDir`, host adb server, WiFi-adb bridge port, and root availability.

## Downloads and folders

Pulled files, screenshots, and Kodi logs default to this PC's
`Downloads\KodiDrop` folder. The folder button in the device-browser toolbar opens
that destination immediately. File and folder rows both have a download action;
name collisions are preserved as `name (1)`, `name (2)`, and so on.

## Root-powered browsing

Folders the shell user can't read (`/storage/emulated`, `/sdcard/Android/data`, `/data`,
…) are listed via Magisk `su` automatically — a "root" chip shows when that happens.
Android's FUSE layer denies `/storage/emulated` even to root, so root reads go through
the raw backing store at `/data/media`. Pulls fall back to `su cat` the same way.

## Config keys (`%APPDATA%\Shield Control\config.json`)

`ip`, `port`, `pushDir` (default drop target), `kodiDropDir`, `pullDir`
(default: this PC's `Downloads\KodiDrop`), `backupDir` (default
`/sdcard/backup`), `lastDir`.

## Patching (Morphe + De-Vanced)

The Shield runs [Morphe](https://morphe.software/) — an on-device app patcher (the
ReVanced successor) that applies a patch bundle (here the
[De-Vanced](https://github.com/RookieEnough/De-Vanced) `patches.jar`) to a source
APK and installs the result. Morphe has a phone-oriented UI with no D-pad support,
so the **Patching · Morphe** card:

- detects Morphe (version), the loaded De-Vanced bundle (size/date, read via root),
  and installed patched apps (`*revanced*` / `*rvx*` packages with versions)
- **Open Morphe on desktop** launches Morphe on the Shield and opens scrcpy, so you
  drive the patcher with mouse and keyboard from Windows

Patch flow: click *Open Morphe on desktop*, pick the app + patches in the mirrored
window, let Morphe build and install. New/updated bundles install like any APK —
drop them on the app. (Fetching De-Vanced bundle releases from GitHub directly into
the app is a planned follow-on.)

## TV App Store (sideload)

Because the Shield is rooted, Play Integrity fails and the Play Store hides apps like
Disney+, Hulu, and Max — a `market://` deep link is useless. The **TV App Store**
(store icon in the header) is therefore a *sideload* catalog of popular Android TV
apps that shows which are installed and:

- **Get APK** — opens the app's Android-TV build on APKMirror in your browser; download
  it and drop it on the window to install (the existing APK-drop pipeline).
- **Install link** — paste a direct `.apk` URL (GitHub release, official CDN, etc.);
  it downloads, verifies the APK signature bytes, and `adb install`s it with progress.
- **Open** / **Uninstall** for installed apps (uninstall is two-click to confirm;
  falls back to `pm uninstall --user 0` for preinstalled apps).

Downloads are always something you initiate and can see the source of — the app never
auto-fetches APKs. For a permanent fix that makes the Play Store itself work again, a
Play Integrity Fix Magisk module is the deeper route.

## Notes

- Drops anywhere in the window: regular files/folders → `adb push` to the folder shown
  in the file browser; `.apk` files → `adb install -r`.
- The ⤓ button on a file row pulls it to the PC (`adb pull`).
- Closing the app also closes the scrcpy window and stops the bundled adb server.
- scrcpy tips: right-click = Back, middle-click = Home, Alt+F = fullscreen.
- Audio is forwarded as AAC (`--audio-codec=aac`): the Shield's Android 11 build has no
  Opus encoder, and scrcpy's default Opus attempt kills the whole stream on this device.
