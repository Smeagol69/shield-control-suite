# Marquee Compose

Marquee Compose is the maintained native Android TV version of Marquee. It uses
Kotlin and Jetpack Compose for TV instead of a WebView shell while preserving the
legacy package ID `dev.roesler.marquee`.

## Features

- Trending, popular, now-playing, and top-rated movie/TV rows from TMDB.
- Title search plus a remote-friendly People browser with popular actors,
  explicit search submission, and dedicated filmography views.
- Details, runtime or season information, recommendations, and a local
  watchlist, plus a cast row that opens each person's filmography.
- A dynamic `Because you liked …` row based on the last opened title.
- Regional `Where to watch` data with installed-provider detection.
- A provider hub inspired by native TV launchers: live regional provider tabs,
  installed apps first, 26 provider-filtered discovery categories, and
  All/Movies/Series filters with a `Surprise me` action. Category headings stay
  pinned while their poster shelf has focus.
- Paginated discovery that loads up to 60 unique poster titles per shelf instead
  of stopping at TMDB's 20-item first page.
- A responsive 16:9 TV layout calibrated for the Shield's 960×540 logical
  canvas, with stable focus sizing, clean 2:3 posters, fit-to-card provider
  logos, safe-edge spacing, and a larger bounded artwork cache.
- Progressive provider loading: six core shelves appear first, personalization
  follows, and the remaining categories arrive three at a time with visible
  progress.
- A 30-minute, 160-entry per-shelf cache with bounded three-shelf concurrency.
  Completed categories survive provider switches, while the on-screen Refresh
  action clears the cache.
- Trakt device authorization, automatic token refresh, personalized movie/show
  recommendations, synced watchlist, playback progress, recent history, and
  explicit watchlist/mark-watched actions.
- A local playback bridge that reads Android MediaSession state, extrapolates
  the active position every second, and captures only visible title/episode/time
  labels from an explicit TV-player package allowlist.
- A keyless `Streaming today` shelf from TVmaze, ranked toward major streaming
  services and linked with required attribution.
- Exact IMDb-title handoff to Stremio, with a title/year search fallback, plus
  Kodi, provider-app, or custom Android package handoff.
- D-pad-first Compose for TV UI with explicit provider-to-catalog focus routing
  and no analytics.

Marquee does not stream, download, patch, or bypass provider applications. It
opens an installed provider/resolver or the provider options page returned by
TMDB.

For movies with an IMDb ID, Marquee uses Stremio's canonical detail/video link
with `autoPlay=true`. Stremio then asks the add-ons configured in that Stremio
account for streams. Android TV autoplay succeeds only when Stremio already has
a stream URL or binge group for the title; otherwise Stremio shows its source
list so the user can choose once. Series open at the exact show because Marquee
does not guess a season or episode.

## First launch

1. Open Settings.
2. Enter either a TMDB v3 API key or v4 read-access token.
3. Set the two-letter provider region, such as `US`.
4. Select Stremio, Kodi, None, or enter a custom resolver package.
5. Optional: enter a Trakt application client ID, client secret, and the exact
   redirect URI configured for that application.
6. Save. Home data loads immediately.
7. Select `Save & connect`, open the displayed Trakt activation URL on your
   phone, and approve the device code.
8. Enable the local playback bridge once using
   `tools/enable-playback-bridge.ps1`. Android remembers both grants across
   upgrades.

TMDB/Trakt credentials, Trakt OAuth tokens, and the local watchlist stay in
application-private storage. No credential is compiled into the APK. Image
requests are restricted to the TMDB, Trakt, and TVmaze media hosts.

Trakt access tokens are refreshed before expiry. Changing any Trakt application
credential clears the prior local session so tokens cannot silently cross
applications. Disconnect attempts to revoke the token and always clears the
local session.

TVmaze requires no API key. Schedule data is used under CC BY-SA and the app
links back to TVmaze from Settings.

## Real-time playback bridge

The bridge uses Android's notification-listener authorization to access active
MediaSession state. A five-second correction snapshot is extrapolated in memory
at 1 Hz, providing a second-level UI without writing to storage every second.
An accessibility service restricted to supported TV-player packages reads only
visible title, episode, and clock labels when a player exposes its controls.
Parsed identity and progress remain in Marquee's private storage.

The bridge does not read another app's database, account tokens, cookies,
keystrokes, video frames, DRM state, or network traffic. A provider that
publishes no MediaSession and no accessible playback labels can still use Trakt
progress, but cannot provide exact local progress through this interface.

## Legacy upgrade

The native app uses version code 9 and the same application ID as the legacy
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
