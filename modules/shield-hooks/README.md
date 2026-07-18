# Shield Hooks

Shield Hooks is a modern libxposed module and Android TV configuration app for
package-scoped lifecycle automation. It observes a small generic activity
lifecycle and feeds immutable snapshots into a constrained BeanShell runtime.

## What it can do

- Request and display the package scope currently approved by LSPosed.
- Observe `activity.created`, `activity.resumed`, `activity.paused`,
  `activity.user_leave`, and `activity.destroyed` for approved packages.
- Run a small local automation script in the module's own process.
- Load built-in, policy-tested recipes for Marquee readiness, foreground
  tracing, provider handoff auditing, and troubleshooting session boundaries.
- Record accepted events, script actions, failures, and timing in a local audit
  log.
- Test scripts while the framework is offline.

Available variables:

- `eventType`
- `packageName`
- `className`
- `timestamp`

Available methods:

- `api.log(message)`
- `api.toast(message)`
- `api.tag(name, value)`
- `api.contains(value, text)`
- `api.startsWith(value, prefix)`
- `api.equalsText(left, right)`

Example:

```java
if (api.equalsText(eventType, "activity.resumed")) {
    api.log("Opened " + packageName + " / " + className);
}
```

Every bundled recipe passes the same static policy as user-entered scripts.
Recipes only receive package name, activity class, event type, and timestamp.
They do not inspect view content, intents, media, credentials, or network data.

## Deliberate limits

Scripts cannot import classes, construct objects, assign variables, loop, catch
exceptions, reflect, access Android framework objects, read files, use the
network, or execute shell/root commands. The source is capped at 4,096
characters and 32 statements, evaluation is capped at 150 ms, and automation
pauses after three consecutive failures.

These limits are part of the product design, not temporary placeholders.

## Build

```powershell
.\gradlew.bat :modules:shield-hooks:testDebugUnitTest `
  :modules:shield-hooks:lintDebug `
  :modules:shield-hooks:assembleRelease
```

Output:

```text
modules/shield-hooks/build/outputs/apk/release/shield-hooks-release.apk
```

Release builds use a local debug-signing fallback so a clean checkout remains
installable for development. To use a durable production key, copy
`local.properties.example` to `local.properties` and set the four
`shieldHooks.*` signing properties.

## Install and activate

Install the APK like a normal application:

```powershell
adb install -r .\modules\shield-hooks\build\outputs\apk\release\shield-hooks-release.apk
```

The APK does not install LSPosed or modify the boot image. If a compatible
LSPosed framework is already present:

1. Enable Shield Hooks in the LSPosed manager.
2. Approve only the target package needed for the automation.
3. Restart the target package, or follow the manager's restart prompt.
4. Open Shield Hooks and confirm that Framework and Active scope are healthy.
5. Save and enable the script only after `Test event` passes.

Without LSPosed, `Framework offline` is the expected state and the local test
runtime remains usable.
