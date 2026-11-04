# NanoTune

A lightweight, privacy-first Android app that identifies the music playing around you, keeps a personal
discovery history, and can run user-started listening sessions that log every new song. Free to use, no
account required, no ads or trackers. Optional accounts back up and sync history through your own server.

> **Status (0.1.0):** the app, backend, CI and docs are complete and build/test green. Music recognition uses
> Apple's official **ShazamKit for Android**, which needs two things only you can get from Apple: the SDK file
> (AAR) and an Apple Developer Program membership for the signing key. Until you add them the app works
> fully (history, sessions UI, export, accounts) but tells you plainly that the recognition engine isn't
> set up. Nothing is faked. Details: [docs/RECOGNITION_PROVIDERS.md](docs/RECOGNITION_PROVIDERS.md).

## Features

* **Tap to identify**: streams microphone audio to ShazamKit and stops at the first match (≤ 20 s).
* **Listening sessions**: start/pause/stop, persistent notification with a Stop button, continuous or
  battery-saving interval mode, duplicate-free timeline.
* **History**: grouped by day and session, search, date/favourite filters, favourites, delete, CSV/JSON export.
* **Track details**: artwork, album/year (optional MusicBrainz lookup), provenance, links to Apple Music,
  Shazam, YouTube and Spotify search, share.
* **Optional account**: history/favourites/settings sync, delete account and cloud data.
* **Honest results**: "No match" instead of guesses; providers are named on every result.
* **Privacy**: audio is processed in memory and never stored; see [docs/PRIVACY.md](docs/PRIVACY.md).

## Download

Prebuilt APKs are published on this repository's **Releases** page (`NanoTune-0.1.0-buildN.apk`, Android 8.0+).
Those builds are made by GitHub Actions **without** Apple's ShazamKit AAR (Apple doesn't allow redistributing
it), so they can't identify songs yet — see "Turn on recognition" below.

## Turn on recognition (owner steps)

1. **Apple**: join the Apple Developer Program (99 USD/year). Download the *ShazamKit SDK for Android* AAR
   from Apple, create a **Media ID** with ShazamKit enabled, then a **Media Services key** (`.p8`) — steps in
   [docs/RECOGNITION_PROVIDERS.md](docs/RECOGNITION_PROVIDERS.md).
2. **Server**: deploy the backend on your VPS with the key ([docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)) —
   `docker compose up -d --build` plus an HTTPS reverse proxy.
3. **App**: build with the AAR (`android/app/libs/shazamkit-android-release.aar`, then
   `./gradlew assembleRelease`, or let CI fetch it from a private URL —
   [docs/RELEASE.md](docs/RELEASE.md)), install, and set **Settings › NanoTune server** to your URL.

The Apple key stays on your server; the app only ever receives short-lived tokens.

## Repository layout

```
android/   Kotlin + Jetpack Compose app (AGP 9.5, Kotlin 2.4, min SDK 26, target SDK 37)
backend/   FastAPI service: ShazamKit token signing, accounts, sync, optional enhanced routing
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

* [Implementation plan & status](docs/IMPLEMENTATION_PLAN.md)
* [Architecture](docs/ARCHITECTURE.md)
* [Recognition providers, Apple setup, licence notes](docs/RECOGNITION_PROVIDERS.md)
* [Android compatibility](docs/ANDROID_COMPATIBILITY.md)
* [Deployment](docs/DEPLOYMENT.md) · [Release & signing](docs/RELEASE.md)
* [Privacy](docs/PRIVACY.md) · [Testing](docs/TESTING.md) · [Changelog](CHANGELOG.md)

NanoTune is a working name and is not affiliated with Apple or Shazam. "Shazam" and "Apple Music" are
trademarks of Apple Inc.
