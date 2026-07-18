# Marquee service setup

Marquee keeps service credentials in Android application-private storage. API
keys, OAuth client secrets, and account tokens must never be added to source,
Gradle properties, or committed files.

## TMDB

1. Sign in at `https://www.themoviedb.org/`.
2. Request an API credential from the account API settings.
3. In Marquee Settings, paste either the v3 API key or v4 API read-access token.
4. Set the two-letter provider region and save.

Marquee uses TMDB for metadata, people search, recommendations, and regional
watch-provider availability.

## Trakt

1. Sign in at `https://trakt.tv/`.
2. Create an application at `https://trakt.tv/oauth/applications`.
3. Give it a private name such as `Marquee Shield`.
4. Configure a redirect URI and enter that exact same value in Marquee. The
   default shown by Marquee is `urn:ietf:wg:oauth:2.0:oob`; use a different value
   only if the Trakt application is configured to match.
5. Paste the client ID and client secret into Marquee and save.
6. Select `Save & connect`. Open the displayed activation URL on a phone, sign
   in to Trakt, and approve the shown code.

Marquee never asks for a Trakt password. The client secret and OAuth tokens are
stored locally because Trakt's device token and refresh exchanges require the
application secret. A rooted process can read app-private storage, so treat the
Shield as a convenience device rather than a hardware-backed secrets vault.

## Local playback bridge

After installing Marquee, enable its two local Android services:

```powershell
.\apps\marquee-compose\tools\enable-playback-bridge.ps1 `
  -AdbPath C:\path\to\adb.exe `
  -Serial 10.0.0.6:5555
```

The script preserves any already-enabled accessibility services. Android keeps
the grants across signed in-place APK upgrades. Marquee Settings shows the
bridge status and the currently observed provider/time.

- The notification-listener service filters active sessions to an explicit
  media-app package allowlist and stores only playback state.
- The accessibility service is package-restricted in its Android metadata and
  retains only parsed title, episode, and clock fields while a media session is
  active.
- Nothing is uploaded, and no account token, private database, video frame, or
  network payload is read.

## TVmaze

TVmaze's public schedule API needs no account or key. Marquee requests the
current web/streaming schedule, ranks major services first, and resolves each
show to TMDB metadata. Settings includes a TVmaze attribution link as required
by its CC BY-SA API license.

## Related Shield services

Beyond Marquee's external APIs, the suite runs a Shield-wide **AdGuard Home** DNS
service for whole-home ad and tracker filtering. Its endpoints, whole-home router
steps, and `enable`/`disable`/`health` scripts are documented in
[`services/adguard/README.md`](../services/adguard/README.md). AdGuard Home is a
separate root service and is not linked into the LSPosed hook runtime.
