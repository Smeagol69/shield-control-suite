# Marquee Compose

Marquee Compose is the maintained native Android TV version of Marquee. It uses
Kotlin and Jetpack Compose for TV instead of a WebView shell while preserving the
legacy package ID `dev.roesler.marquee`.

## Features

- Trending, popular, now-playing, and top-rated movie/TV rows from TMDB.
- Title search plus actor/director search and filmographies.
- Details, runtime or season information, recommendations, and a local
  watchlist.
- A dynamic `Because you liked …` row based on the last opened title.
- Regional `Where to watch` data with installed-provider detection.
- Preferred resolver launch through Stremio, Kodi, or a custom Android package.
- D-pad-first Compose for TV UI with no analytics.

Marquee does not stream, download, patch, or bypass provider applications. It
opens an installed provider/resolver or the provider options page returned by
TMDB.

## First launch

1. Open Settings.
2. Enter either a TMDB v3 API key or v4 read-access token.
3. Set the two-letter provider region, such as `US`.
4. Select Stremio, Kodi, None, or enter a custom resolver package.
5. Save. Home data loads immediately.

The credential and watchlist stay in application-private storage. Poster
requests are restricted to `https://image.tmdb.org`.

## Legacy upgrade

The native app uses version code 2 and the same application ID as the legacy
WebView build. Android permits an in-place upgrade only when both APKs use the
same signing certificate.

Set the `marquee.*` values in `local.properties` to the existing keystore before
building a release. Using a different key requires uninstalling the old app,
which also removes its private settings.

On first launch, a local-only migration bridge attempts to import compatible
TMDB settings from the legacy WebView. The bridge blocks network requests and is
destroyed after migration. If no prior credential exists, Settings opens
normally.

## Build and install

```powershell
.\gradlew.bat :apps:marquee-compose:lintDebug `
  :apps:marquee-compose:assembleRelease

adb install -r .\apps\marquee-compose\build\outputs\apk\release\marquee-compose-release.apk
```

Release output:

```text
apps/marquee-compose/build/outputs/apk/release/marquee-compose-release.apk
```
