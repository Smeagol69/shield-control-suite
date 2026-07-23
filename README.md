# Shield Control Suite

One repository for the Windows and Android TV software built for an NVIDIA
Shield TV Pro: the desktop control plane, Marquee media hub, package-scoped
LSPosed automation, and the root-hosted AdGuard service.

## Download the finished apps

Open the [latest GitHub release](https://github.com/Smeagol69/shield-control-suite/releases/latest)
for the one-click Windows installer, portable Windows executable, Android APKs,
AdGuard package, and SHA-256 checksums.

| Component | Source version | Purpose |
| --- | --- | --- |
| Shield Control | 1.1.0 | Standalone Windows control center with bundled ADB and scrcpy |
| Marquee | 2.3.0 | Native Android TV discovery, provider, Trakt, and playback hub |
| Shield Hooks | 0.2.0 | Package-scoped LSPosed observation and constrained BeanShell automation |
| AdGuard Home service | 0.107.78 | Boot-persistent whole-home DNS filtering on the Shield |

## Projects

| Path | Purpose |
| --- | --- |
| `apps/shield-control` | Electron desktop control center with bundled ADB and scrcpy, telemetry, file management, APK installs, and guarded root operations. |
| `apps/marquee-compose` | Native Kotlin + Jetpack Compose for TV media discovery app. This is the maintained Marquee implementation. |
| `apps/marquee` | Legacy WebView Marquee source retained for migration history and rollback. |
| `modules/shield-hooks` | Modern libxposed module with package-scoped lifecycle observation and a constrained BeanShell automation layer. |
| `services/adguard` | Boot-persistent AdGuard Home deployment and maintenance scripts. |
| `docs/ai-collaboration.md` | Shared Git and handoff workflow for Codex and Claude. |
| `release` | Release inventory and reproducible SHA-256 manifest. |

Shield Control 1.1 routes pulls, screenshots, and Kodi logs to the collision-safe
`Downloads\KodiDrop` folder and can download whole device folders. Marquee 2.3
keeps Claude's expanded provider shelves responsive with a bounded, expiring
catalog cache that avoids repeatedly hammering TMDB.

Each project has its own README with setup, operation, and safety details.

## Codex and Claude collaboration

Both assistants use this Git repository as shared memory. Codex reads
[`AGENTS.md`](AGENTS.md), Claude reads [`CLAUDE.md`](CLAUDE.md), and both follow
[`docs/ai-collaboration.md`](docs/ai-collaboration.md). They work on separate
branches, push every handoff, and review the other assistant's commits before
continuing.

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

The Gradle wrapper is committed, but SDKs, JDKs, keystores, credentials,
third-party toolchains, logs, and device dumps are intentionally excluded.
Signed APKs and packaged Windows builds are distributed through GitHub Releases.

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
