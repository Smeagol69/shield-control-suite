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

The hook layer observes generic `Activity.onResume()` and
`Activity.onPause()` callbacks. It does not inspect application fields, method
arguments, view hierarchies, network traffic, or credentials.

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
  -> TMDB HTTPS client
  -> local watchlist/settings
  -> installed-provider detection
  -> provider app or preferred resolver intent
```

Marquee is a client-only application. It has no backend, account system,
analytics, or download engine. TMDB supplies discovery metadata and regional
watch-provider information. Provider playback remains the responsibility of the
installed provider app.

The native app keeps the legacy package ID (`dev.roesler.marquee`) and can upgrade
the WebView build only when signed with the same key. Its hidden migration view
exists solely to copy compatible local settings from the old app.

## Desktop and service projects

Shield Control owns host-side ADB/scrcpy workflows and delegates device changes
to visible user actions. AdGuard Home is a separate root service with dedicated
enable, disable, and health scripts. Neither is linked into the LSPosed runtime.
