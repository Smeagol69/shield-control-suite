# Shield Lab

Shield Lab is a private monorepo for the Windows and Android TV software used with
an NVIDIA Shield TV Pro. It keeps the desktop control plane, native TV apps,
root-hosted services, and package-scoped LSPosed experiments in one reviewable
history.

## Projects

| Path | Purpose |
| --- | --- |
| `apps/shield-control` | Electron desktop control center with bundled ADB and scrcpy, telemetry, file management, APK installs, and guarded root operations. |
| `apps/marquee-compose` | Native Kotlin + Jetpack Compose for TV media discovery app. This is the maintained Marquee implementation. |
| `apps/marquee` | Legacy WebView Marquee source retained for migration history and rollback. |
| `modules/shield-hooks` | Modern libxposed module with package-scoped lifecycle observation and a constrained BeanShell automation layer. |
| `services/adguard` | Boot-persistent AdGuard Home deployment and maintenance scripts. |

Each project has its own README with setup, operation, and safety details.

## Android quick start

Requirements:

- JDK 17
- Android SDK Platform 37 and Build Tools 37.0.0
- Windows PowerShell for the commands below

Create the untracked local configuration:

```powershell
Copy-Item .\local.properties.example .\local.properties
```

Set `sdk.dir` in `local.properties`. Add signing values only when building an
upgrade for an already-installed app.

Build and verify both Android projects:

```powershell
.\gradlew.bat :modules:shield-hooks:testDebugUnitTest `
  :apps:marquee-compose:testDebugUnitTest `
  :modules:shield-hooks:lintDebug `
  :apps:marquee-compose:lintDebug `
  :modules:shield-hooks:assembleDebug `
  :apps:marquee-compose:assembleDebug
```

Build optimized release APKs:

```powershell
.\gradlew.bat :modules:shield-hooks:assembleRelease `
  :apps:marquee-compose:assembleRelease
```

The Gradle wrapper is committed, but SDKs, JDKs, keystores, credentials, APKs,
vendor binaries, logs, and device dumps are intentionally excluded.

## Safety boundaries

Shield Hooks is for observability, user-authored automation, and UI
experimentation on packages explicitly approved in LSPosed. It does not implement
identity spoofing, root or integrity hiding, DRM bypasses, credential
interception, secure-surface capture, arbitrary target-process code, or
shell/root execution.

Installing the Shield Hooks APK does not install or flash LSPosed. Framework
installation changes boot state and remains a separate, explicit device-owner
operation.

See [SECURITY.md](SECURITY.md), [docs/architecture.md](docs/architecture.md),
and [docs/service-setup.md](docs/service-setup.md) for trust boundaries and
credential setup.
