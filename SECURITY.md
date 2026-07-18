# Security model

Shield Lab controls a rooted Android TV device. Root removes many platform
guardrails, so the repository uses explicit scope, local-only credentials,
bounded execution, and auditable actions instead of assuming the device is a
trusted boundary.

## Shield Hooks trust boundaries

- LSPosed remains the scope authority. A target package must be explicitly
  approved before its events are accepted.
- The exported event provider verifies that Android's Binder calling UID owns the
  package claimed by each event.
- Only allowlisted activity lifecycle snapshots (`created`, `resumed`, `paused`,
  `user_leave`, and `destroyed`) are accepted. Raw `Activity`, `Context`,
  lifecycle arguments, `Intent`, or framework objects never cross the process
  boundary.
- Hook callbacks enqueue bounded asynchronous work so automation cannot block a
  target application's main thread.
- BeanShell runs only in the Shield Hooks application process, never in the
  hooked package's process or UID.
- Scripts receive an allowlisted facade with logging, toast, tagging, and string
  comparison methods. Policy rejects imports, object construction, assignment,
  loops, exceptions, reflection, class access, Android APIs, file/network I/O,
  and shell execution.
- Source length, statement count, nesting, event rate, queue size, execution
  time, and consecutive failures are bounded. Three failures open a circuit
  breaker.
- Script activity is recorded in a size-limited local audit log.

## Marquee privacy

- TMDB/Trakt credentials, Trakt OAuth tokens, and the local watchlist remain in
  application-private storage and are never compiled into the APK.
- Media downloads are restricted to HTTPS requests to explicitly allowlisted
  TMDB, Trakt, and TVmaze image hosts.
- Trakt uses device authorization, refreshes tokens before expiry, and clears
  the local session when application credentials change.
- TVmaze uses only its unauthenticated public schedule API.
- The app has no analytics or telemetry SDK.
- Real-time playback uses Android-authorized MediaSession access and an explicit
  media-package allowlist. Position snapshots, speed, provider package, parsed
  title/episode labels, and local history stay in app-private storage.
- The playback accessibility service is statically restricted to supported TV
  players, runs only while a matching media session is active, stores no raw
  view text, and never performs clicks or input.
- The one-time legacy migration view can load only the bundled local asset,
  blocks network requests, and is destroyed after migration.

On a rooted device, a process with root access can still read another app's
private storage. Do not treat a Shield as a hardware-backed secrets vault.

## Repository policy

Do not commit:

- signing keys or passwords;
- ADB private keys;
- API tokens, cookies, or service credentials;
- downloaded SDKs, platform tools, APKs, logs, backups, or device dumps.

Use `local.properties` for local Android paths and signing values. It is ignored
by Git; `local.properties.example` documents only placeholders.

## Explicit non-goals

- Hiding root, Magisk, LSPosed, hooks, or bootloader state.
- Spoofing device identity, signatures, integrity verdicts, or entitlements.
- Disabling `FLAG_SECURE` or bypassing protected media paths.
- Capturing credentials, tokens, keystrokes, or private app storage.
- Intercepting provider network traffic or extracting personalized private
  catalogs from provider databases.
- Hooking financial, authentication, DRM, or system-security components.
