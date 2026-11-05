# NanoTune

A lightweight, privacy-first Android app that identifies the music playing around you, keeps a personal
discovery history, and can run user-started listening sessions that log every new song. Free to use, no
account required, no ads or trackers. Optional accounts back up and sync history through your own server.

### ⬇️ [Download the latest APK (NanoTune.apk)](https://github.com/domdzd/shabam/releases/latest/download/NanoTune.apk)

Android 8.0 or newer · [release notes and checksums](https://github.com/domdzd/shabam/releases/latest) ·
[all releases](https://github.com/domdzd/shabam/releases) · [changelog](CHANGELOG.md)

To install: open the downloaded file on your phone and allow installing from your browser or file manager
when Android asks. Newer builds install over the previous one as long as they're signed with the same key; if
Android refuses the update, uninstall the old app first ([why](docs/RELEASE.md)).

> **Status (0.3.0):** the app, backend, CI and docs build and test green. Recognition needs an engine that
> only you can switch on, because no legitimate free, key-less one exists:
> * **AudD or ACRCloud** on your NanoTune server, with your own key and a daily limit. This works with the
>   APK from the Releases page.
> * **Apple ShazamKit**, which needs Apple's SDK file (AAR) in the build and an Apple Developer Program
>   membership for the signing key.
>
> Until one is set up, the app works fully (history, recordings, sessions UI, export, accounts) and tells you
> plainly what is missing. Nothing is faked. Details: [docs/RECOGNITION_PROVIDERS.md](docs/RECOGNITION_PROVIDERS.md).

## Features

* **Tap to identify**: the big microphone button always records — even when no engine can answer right
  now — and keeps the recording. With an engine it identifies the song (ShazamKit, stopping at the first
  match, or your server's AudD / ACRCloud after you agree to them by name). Tap again to stop early.
* **Recordings** (on by default, encrypted on the phone): play with seek, rename, trim, identify again
  (the loudest 12 s are used), share one or several, save to the device, delete one or several, and
  **import an audio or video file** to identify it. Offline? The clip is identified automatically when
  you're back online, and you get a notification.
* **One-tap shortcuts**: Quick Settings tile, home-screen widget and a launcher shortcut that open
  NanoTune and start recording right away.
* **Listening sessions**: start/pause/stop, persistent notification with a Stop button, continuous or
  battery-saving interval mode, duplicate-free timeline.
* **History**: grouped by day and session, search, date/favourite filters, favourites, delete, CSV/JSON export.
* **Track details**: artwork, album/year (optional MusicBrainz lookup), provenance, links to Apple Music,
  Shazam, YouTube and Spotify search, share.
* **Optional account**: history/favourites/settings sync, password reset and email confirmation by code
  (when the server has SMTP), delete account and cloud data.
* **Honest results**: "No match" instead of guesses; providers are named on every result.
* **Privacy**: recordings stay on the phone, encrypted, and are never uploaded unless you share them or
  agree to your server's engines; turn recording off in Settings › Recordings. See
  [docs/PRIVACY.md](docs/PRIVACY.md).

## Download

**[NanoTune.apk — latest release](https://github.com/domdzd/shabam/releases/latest/download/NanoTune.apk)**
(the same file is also attached as `NanoTune-<version>-buildN.apk`, with SHA-256 checksums, on the
[release page](https://github.com/domdzd/shabam/releases/latest)). These builds are made by GitHub Actions
**without** Apple's ShazamKit AAR (Apple doesn't allow redistributing it). They record and keep songs
right away, and identify them once your server offers AudD or ACRCloud (option A below); otherwise they
explain what is missing.

## Turn on recognition (owner steps)

First deploy the backend on your VPS ([docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)): `docker compose up -d --build`
plus an HTTPS reverse proxy. Then set **Settings › NanoTune server** in the app to your URL and choose:

**A. AudD and/or ACRCloud (quickest; works with the Releases APK).** Both are paid after a trial. Get a key,
put it in the server's `.env` with a daily limit (`AUDD_ENABLED`, `AUDD_API_TOKEN`, `AUDD_DAILY_LIMIT`, or the
`ACRCLOUD_*` equivalents), and set `SERVER_RECOGNITION_ANONYMOUS=true` if people shouldn't need an account.
The app then shows "Your server can identify songs" and asks each user before uploading a clip (≤ 12 s).

**B. Apple ShazamKit (on-device signatures, no per-request fee).**
1. Join the Apple Developer Program (99 USD/year). Download the *ShazamKit SDK for Android* AAR, create a
   **Media ID** with ShazamKit enabled, then a **Media Services key** (`.p8`). Steps are in
   [docs/RECOGNITION_PROVIDERS.md](docs/RECOGNITION_PROVIDERS.md).
2. Put the key on the server (`APPLE_TEAM_ID`, `APPLE_KEY_ID`, `deploy/secrets/apple_media_services.p8`).
3. Build the app with the AAR (`android/app/libs/shazamkit-android-release.aar`, then
   `./gradlew assembleRelease`, or let CI fetch it from a private URL; see [docs/RELEASE.md](docs/RELEASE.md)).

You can use both: ShazamKit first, and your server's engines for saved recordings or as a fallback. Keys
stay on your server; the app only receives short-lived tokens and never holds provider keys.

## Repository layout

```
android/   Kotlin + Jetpack Compose app (AGP 9.5, Kotlin 2.4, min SDK 26, target SDK 37)
backend/   FastAPI service: ShazamKit token signing, accounts, sync, optional AudD/ACRCloud routing
deploy/    Docker Compose, nginx / Caddy / Traefik examples, backup script
docs/      Architecture, providers, privacy, compatibility, deployment, testing, release
Project.md The original project brief
```

## Development

```bash
# Android (JDK 17+, Android SDK 37)
cd android && ./gradlew testDebugUnitTest assembleDebug

# Backend (Python 3.12+)
cd backend && python -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt && python -m pytest
AUTH_JWT_SECRET=$(python -c "import secrets;print(secrets.token_urlsafe(48))") \
DATABASE_URL=sqlite:///./data/dev.db APP_ENV=development \
uvicorn app.main:create_app --factory --reload   # API docs at http://127.0.0.1:8000/api/docs
```

The debug app can reach a local server at `http://10.0.2.2:8000` (emulator) or your LAN IP.

## Documentation

* [Implementation plan & status](docs/IMPLEMENTATION_PLAN.md) · [Roadmap / next steps](docs/ROADMAP.md)
* [Architecture](docs/ARCHITECTURE.md)
* [Recognition providers, Apple setup, licence notes](docs/RECOGNITION_PROVIDERS.md)
* [Android compatibility](docs/ANDROID_COMPATIBILITY.md)
* [Deployment](docs/DEPLOYMENT.md) · [Release & signing](docs/RELEASE.md)
* [Privacy](docs/PRIVACY.md) · [Testing](docs/TESTING.md) · [Changelog](CHANGELOG.md)

NanoTune is a working name and is not affiliated with Apple or Shazam. "Shazam" and "Apple Music" are
trademarks of Apple Inc.
