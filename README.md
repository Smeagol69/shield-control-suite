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
- `Shield Control Setup <version>.exe` — one-click installer (faster startup, Start Menu entry)

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

## Root-powered browsing

Folders the shell user can't read (`/storage/emulated`, `/sdcard/Android/data`, `/data`,
…) are listed via Magisk `su` automatically — a "root" chip shows when that happens.
Android's FUSE layer denies `/storage/emulated` even to root, so root reads go through
the raw backing store at `/data/media`. Pulls fall back to `su cat` the same way.

## Config keys (`%APPDATA%\Shield Control\config.json`)

`ip`, `port`, `pushDir` (default drop target), `kodiDropDir`, `pullDir`
(default: this PC's Downloads), `backupDir` (default `/sdcard/backup`), `lastDir`.

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

## Notes

- Drops anywhere in the window: regular files/folders → `adb push` to the folder shown
  in the file browser; `.apk` files → `adb install -r`.
- The ⤓ button on a file row pulls it to the PC (`adb pull`).
- Closing the app also closes the scrcpy window and stops the bundled adb server.
- scrcpy tips: right-click = Back, middle-click = Home, Alt+F = fullscreen.
- Audio is forwarded as AAC (`--audio-codec=aac`): the Shield's Android 11 build has no
  Opus encoder, and scrcpy's default Opus attempt kills the whole stream on this device.
