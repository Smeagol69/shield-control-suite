# Verification

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
