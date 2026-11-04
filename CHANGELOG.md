# Changelog

## 0.1.0 — 2026-11-04

First implementation of the NanoTune brief (Project.md).

### Android app
* Tap-to-identify with ShazamKit `StreamingSession` (stops at first match; 5–20 s limit), honest
  match / no-match / not-configured / error states, permission explanation before the system prompt.
* User-started listening sessions in a microphone foreground service: ongoing notification with
  Pause/Resume/Stop, continuous or interval mode, sliding duplicate cooldown (default 4 min), mobile-data
  rule, microphone-contention detection, never auto-started or restarted.
* Local history (Room): day/session grouping, search, date/favourite filters, favourites, delete one / clear
  all, CSV and JSON export through the system file picker.
* Track details with provenance (engine vs. MusicBrainz enrichment), Apple Music / Shazam / YouTube / Spotify
  links and sharing.
* Settings: theme, listen limit, session mode/interval/cooldown, mobile data, metadata + Wi-Fi only, server
  URL with live status, enhanced recognition (gated + consent), privacy screen, delete local data,
  open-source notices.
* Optional account: register / sign in / sign out / delete account; sync of history, favourites and settings.
* ShazamKit adapter compiled only when Apple's AAR is present; otherwise the app states the engine is not
  included. Builds with AGP 9.5, Kotlin 2.4.21, compile/target SDK 37, min SDK 26.

### Backend
* FastAPI service: health, capabilities, anonymous ES256 ShazamKit developer tokens (cached, rate limited),
  Argon2id accounts with rotating refresh tokens and reuse detection, LWW sync with tombstones, account and
  cloud-data deletion, enhanced-recognition router with quotas/kill switch (no provider enabled).
* SQLite + Alembic, Docker image, Compose + nginx/Caddy/Traefik examples, backup script.

### Known limitations
* Recognition was not exercised end to end: Apple's AAR and a Media Services key are required and were not
  available to the build. See docs/RECOGNITION_PROVIDERS.md.
* No physical-device testing yet (docs/TESTING.md, docs/ANDROID_COMPATIBILITY.md).
* Email verification / password reset not implemented.
