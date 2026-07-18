# Architecture

## Shield Hooks

The module separates observation from automation:

```text
Approved target process
  Activity lifecycle hook
    -> immutable event snapshot
    -> bounded asynchronous dispatcher
    -> ContentProvider call

Shield Hooks process
  Binder UID/package validation
    -> LSPosed scope check
    -> event allowlist + rate limit
    -> bounded runtime queue
    -> static script policy
    -> 150 ms BeanShell execution
    -> allowlisted ScriptApi
    -> rotated local audit log
```

The hook layer observes generic `Activity.onCreate()`, `onResume()`, `onPause()`,
`onUserLeaveHint()`, and `onDestroy()` callbacks. It discards all method
arguments and does not inspect application fields, view hierarchies, intents,
network traffic, media state, or credentials.

The provider is exported because a different application UID must deliver the
event. Exporting is not treated as authorization: Binder identity is checked
against the claimed package and current LSPosed scope before any event reaches
the script runtime.

BeanShell is intentionally downstream of the process boundary. User-authored
scripts cannot execute in the target process and never receive raw Android
objects. R8 rules preserve only the BeanShell classes loaded reflectively and
the small public facade called by scripts.

## Marquee Compose

```text
Compose for TV UI
  -> MarqueeController state flows
  -> independent, failure-isolated service requests
      -> TMDB discovery/providers
      -> Trakt OAuth/sync/recommendations
      -> TVmaze streaming schedule
  -> private settings/session stores + local watchlist
  -> local playback store
      -> authorized MediaSession snapshots
      -> package-scoped visible playback labels
  -> installed-provider detection
  -> provider app or preferred resolver intent
```

Marquee is a client-only application. It has no backend, account system,
analytics, or download engine. TMDB supplies discovery metadata and regional
watch-provider information. Trakt device authorization adds optional
recommendations, watchlist, and history sync. TVmaze supplies a no-key daily
streaming schedule. A failed optional service becomes a small status notice
instead of blanking the home screen. Provider playback remains the
responsibility of the installed provider app.

The Providers destination fetches the current TMDB watch-provider registry for
the configured region, moves installed Android TV apps first, and builds
provider-filtered discovery shelves using `watch_region`,
`with_watch_providers`, and subscription/free/ad-supported monetization types.
Trakt playback and recommendation items are availability-checked before they
appear inside a provider tab.

The playback monitor is a system-bound notification-listener service. It reads
active MediaController state for an explicit package allowlist, corrects its
base snapshot every five seconds, and lets the UI extrapolate position once per
second using the reported speed. A package-restricted accessibility service
parses only visible title, episode, and time labels while the same provider has
an active media session. Raw node text is discarded. Local playback records are
stored privately and can be resolved to TMDB metadata; selecting one hands
control back to its original provider app.

Service JSON is capped at 4 MB. Artwork URLs are canonicalized through a strict
HTTPS host allowlist before loading; image responses are capped at 8 MB and
downsampled to a bounded decoded size before entering the 48 MB memory cache.
The disk cache is pruned to 96 MB/300 files. This also satisfies Trakt's
requirement that clients cache its image assets.

The native app keeps the legacy package ID (`dev.roesler.marquee`) and can upgrade
the WebView build only when signed with the same key. Its hidden migration view
exists solely to copy compatible local settings from the old app.

## Desktop and service projects

Shield Control owns host-side ADB/scrcpy workflows and delegates device changes
to visible user actions. AdGuard Home is a separate root service with dedicated
enable, disable, and health scripts. Neither is linked into the LSPosed runtime.
