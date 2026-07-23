# Verification

Run the desktop verification set from `apps/shield-control`:

```powershell
npm ci
npm run check
npm audit --audit-level=high
```

`npm run check` validates every Electron JavaScript entry point and runs the
Node test suite, including collision-safe `Downloads\KodiDrop` path handling.
The same commands run on `windows-latest` in the Desktop verification workflow.

Run the complete Android verification set from the repository root:

```powershell
.\gradlew.bat --no-daemon `
  :modules:shield-hooks:testDebugUnitTest `
  :apps:marquee-compose:testDebugUnitTest `
  :modules:shield-hooks:lintDebug `
  :apps:marquee-compose:lintDebug `
  :modules:shield-hooks:assembleDebug `
  :apps:marquee-compose:assembleDebug `
  :modules:shield-hooks:assembleRelease `
  :apps:marquee-compose:assembleRelease
```

Release builds also require:

- `aapt2 dump badging` checks for package, activity, SDK, and TV launcher metadata;
- `apksigner verify --verbose --print-certs` checks signatures;
- archive inspection checks modern libxposed metadata under `META-INF/xposed/`;
- on-device launch and logcat smoke tests on the target Android TV version.

Shield Hooks has an additional offline `Test event` action. It exercises policy,
BeanShell evaluation, the public script facade, timeout handling, failure
tracking, and audit persistence without requiring LSPosed to be installed.

Real lifecycle events cannot be validated until an LSPosed framework is
separately installed, the module is enabled, and at least one package is
explicitly approved.
