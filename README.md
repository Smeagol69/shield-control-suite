# Marquee

A TMDB-powered discovery app for Android TV (built for the NVIDIA Shield). Browse
trending / popular / top-rated, search titles, **search by actor**, get
**"more like this"** recommendations — then pick a title and it opens the show/movie
in the right **provider app** (Netflix, Disney+, Max, Prime, Hulu, Apple TV, …) using
TMDB's "where to watch" data, filtered to the apps actually installed on the Shield.

It is a native WebView shell (so it gets a real Leanback launcher icon and can fire
Android intents to launch provider apps) hosting a D-pad-navigable web UI.

## Setup

1. Open **Marquee** from the Shield's app row.
2. **Settings** → paste a free **TMDB v3 API key** (themoviedb.org → Settings → API).
3. Home fills with Trending / Popular / Top rated. Search + Actors tabs work immediately.
4. Open a title → **Where to watch** shows the providers; installed ones are highlighted
   and launch that app.

## Build

Needs the toolchain in `tools/` (JDK 17 + Android SDK cmdline-tools + platform-30 +
build-tools 34; not committed). Then:

```
powershell -File build.ps1     # aapt2 -> javac -> d8 -> zipalign -> apksigner
adb install -r marquee.apk
```

## Structure

- `app/src/main/assets/www/` — the web UI (`index.html`, `tmdb.js`, `nav.js` (D-pad
  spatial navigation), `app.js`, `styles.css`).
- `app/src/main/java/.../MainActivity.java` — WebView shell + JS bridge
  (`openPackage` / `isInstalled` / `installedPackages`).
- `app/src/main/AndroidManifest.xml` — Leanback launcher intent + `<queries>` for the
  provider packages (Android 11 visibility).
- `build.ps1` — Gradle-free APK build. `make-art.ps1` — generates banner + icon.

## Roadmap

- **Trakt** — personal watchlist / history / scrobble (needs a Trakt app client id).
- **Exact-title deep-links** — currently launches the provider app; landing on the exact
  title needs per-provider content IDs.
