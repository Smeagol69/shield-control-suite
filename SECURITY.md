# Security model

## Trust boundaries

- LSPosed scopes are explicit and package-specific.
- Hook callbacks expose immutable event snapshots, not raw framework objects.
- BeanShell scripts receive an allowlisted facade only.
- Scripts cannot access root, shell execution, arbitrary reflection, files,
  sockets, class loaders, package identity, or Android credentials.
- Every script has an enable switch, execution budget, failure circuit breaker,
  and append-only audit record.

## Explicit non-goals

- Hiding root, Magisk, LSPosed, hooks, or bootloader state.
- Spoofing device identity, signatures, integrity verdicts, or entitlements.
- Disabling `FLAG_SECURE` or bypassing protected media paths.
- Capturing credentials, tokens, keystrokes, or private app storage.
- Hooking financial, authentication, DRM, or system-security components.
