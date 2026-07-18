# Shield Lab

Source-controlled tooling and applications for a rooted NVIDIA Shield TV Pro
(2019), managed from Windows over ADB.

## Projects

- `apps/shield-control` — Electron control center with bundled ADB and scrcpy.
- `apps/marquee` — Android TV discovery app.
- `services/adguard` — boot-persistent whole-home AdGuard Home deployment.
- `modules/shield-hooks` — package-scoped modern LSPosed module with an
  allowlisted BeanShell event API and Compose configuration UI.

## Safety boundaries

Shield Hooks is designed for observability, user-authored automation, and UI
experimentation on explicitly selected packages. It does not implement identity
spoofing, root/integrity hiding, DRM bypasses, credential interception, secure
surface capture, or unrestricted shell/root execution.

## Repository policy

The repository contains source and reproducible setup only. Credentials,
keystores, SDKs, downloaded platform tools, build outputs, APKs, logs, and device
dumps are excluded.
