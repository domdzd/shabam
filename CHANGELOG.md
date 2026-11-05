# Changelog

## 0.3.0 — 2026-11-05

The Identify button always records and keeps the song; recordings can be managed, imported and
identified later. Download: [latest APK](https://github.com/domdzd/shabam/releases/latest/download/NanoTune.apk).

### Android app
* **Always records** (owner request): tapping the big microphone records even when no engine can identify
  the song right now (no ShazamKit in the build, no server, offline, engines not allowed yet) and keeps the
  recording, then says why it couldn't be identified. Tap again to stop early and keep what was recorded;
  leaving the screen while recording does the same. The recording is saved as soon as it stops, before any
  upload, so network errors or cancelling never lose it.
* **Recordings on by default**: *Keep recordings* now defaults to on and to *Every recording* (choose
  *Only not identified* or turn it off in Settings › Recordings). The permission explanation, the Home
  screen and the privacy screen say so.
* **Recordings encrypted at rest** with AES-256-GCM (Android Keystore key). Recordings from 0.2 are
  encrypted on first start; if the Keystore fails, audio is stored unencrypted in private storage rather
  than lost.
* **Manage recordings** (History › Recordings): seekable player, rename, trim (*keep only this part* or
  *identify this part*), share one or several, save to the device (system file picker), delete one or
  several (long-press to select), filters, *identify all not identified*.
* **Import an audio or video file** (MP3, M4A/AAC, OGG, FLAC, WAV, MP4… whatever the phone can decode):
  the first 90 s are kept as a recording and identified if an engine is available.
* Engines get the **loudest 12 seconds** of a recording instead of the first 12 seconds.
* **One-tap entry points**: Quick Settings tile *Identify song*, a 1×1 home-screen widget and a launcher
  shortcut, all opening NanoTune and starting to record.
* **Forgot password / confirm email**: when the server can send email, sign-in offers *Forgot password?*
  (code by email, new password, every device signed out) and the account screen offers to confirm the address.

### Backend
* Password reset and email verification with 6-digit codes: SMTP settings (`SMTP_*`), codes valid 15
  minutes, five tries, stored only as an HMAC keyed with the server secret, sent after the response so timing
  doesn't reveal accounts, rate-limited per address and IP. New migration `0002`.
* `/config` advertises `password_reset` / `email_verification` only when SMTP is configured.
* 83 tests (10 new).

### Releases
* Releases are full releases marked *latest*, with a fixed-name `NanoTune.apk`, so the README's download
  link always points at the newest build.

### Not done yet
* Translations and automated UI tests on an emulator (see docs/ROADMAP.md).

## 0.2.0 — 2026-11-04

Saved recordings, "identify later", and server engines you can attach (AudD, ACRCloud).

### Android app
* **Saved recordings (off by default, explicit consent)**: Settings › Recordings › *Keep recordings*. Keeps
  the audio of identifications that found nothing (or every identification) as 16 kHz mono WAV files in the
  app's private storage. Automatic deletion after 7 / 30 / 90 days or never, 200 MB cap, excluded from
  backups and device transfer. New **History › Recordings** tab: play, share (system share sheet), delete,
  delete all, and see which engines already tried.
* **Identify later**: *Identify* on any saved recording runs ShazamKit first and then (only with consent)
  your server's engines; a match is added to History with the time of the original recording.
* **Offline capture**: with recordings on, tapping Identify while offline records the clip anyway and
  identifies it automatically when the phone is back online (WorkManager, network constraint, exponential
  backoff), then posts a "Found it" notification that opens the song.
* If recordings are on but set to keep only songs that weren't identified, a match offers
  **Keep this recording** so you can still save that one.
* **Server engines as the main engine**: builds without ShazamKit can identify songs through the engines
  your NanoTune server offers. Consent names each engine and is asked again if the list changes. Signed-in
  users use their daily allowance; anonymous use only if the server owner allows it.
* Results from AudD/ACRCloud show direct Spotify links when the engine returns them.
* The privacy screen, settings texts and Home hints describe the new behaviour.

### Backend
* **AudD** and **ACRCloud** adapters, written against and checked with the providers' official code
  (AudD's Go SDK; ACRCloud's Identify Protocol V1 examples and result parser). Both are off by default and
  need `*_ENABLED`, credentials and a positive `*_DAILY_LIMIT`; `PROVIDER_ORDER` sets the order.
* `POST /api/v1/recognition/identify`: server engines as the main engine. Signed-in users use their
  allowance. Anonymous use only with `SERVER_RECOGNITION_ANONYMOUS=true`, capped per install and per IP.
* `/api/v1/config` advertises a `server_engines` block. "Enhanced" (fallback after a ShazamKit no-match)
  still needs two engines, so one server engine is never presented as "enhanced".
* Privacy: anonymous daily counters store only a keyed hash of the install ID and IP address and are
  deleted after the day; deleting an account also deletes its usage counters.
* 73 tests (21 new, mocked HTTP; no live keys).

### Known limitations
* Still no end-to-end recognition test on a device: ShazamKit needs Apple's AAR and key, and the server
  engines need your AudD/ACRCloud keys (docs/TESTING.md).
* Clips sent to server engines are at most 12 s (AudD analyses only the first 12 s anyway).

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
