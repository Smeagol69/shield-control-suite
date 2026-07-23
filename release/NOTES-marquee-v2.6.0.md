# Marquee 2.6.0

- Calibrates the UI for the NVIDIA Shield's 960×540 logical 4K canvas.
- Reduces header, provider, control, poster, and grid sizing without reducing
  title readability.
- Keeps each provider category heading pinned while its shelf has focus.
- Replaces the oversized provider summary panel with compact, always-visible
  catalog controls.
- Repairs People search with popular-person fallback content, an explicit
  search action, TV keyboard submission, a responsive people grid, and a
  dedicated filmography view.
- Back now returns from a filmography to the People results instead of leaving
  the People screen.
- Adds a cast row to title details; selecting a cast member opens that person's
  filmography.
- Preserves all Marquee 2.5 provider caching, progressive loading, Trakt, local
  playback, watchlist, and handoff behavior.

Validated with 30 unit tests, Android lint, a debug APK build, and an
R8-minified signed release APK. The APK uses the existing Marquee signing
identity so it installs as an in-place update.
