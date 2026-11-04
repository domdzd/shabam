# NanoTune — implementation plan and status

This is the working checklist for the brief in [`Project.md`](../Project.md). It records what was
built, what was verified, and what still depends on credentials or hardware that the agent did
not have.

Legend: ✅ done and verified · 🟡 done, not verifiable here (needs device/credentials) · ⏳ not started · ❌ rejected (with reason)

## Phase 0 — feasibility

| # | Item | Status | Notes |
|---|------|--------|-------|
| 0.1 | Inspect repo/toolchain, choose package, min/target SDK | ✅ | Empty repo. `minSdk 26`, `targetSdk 36`, `compileSdk 36.1`. See [ANDROID_COMPATIBILITY.md](ANDROID_COMPATIBILITY.md). |
| 0.2 | Read current ShazamKit Android docs | ✅ | SDK 2.1.1. Whole API reference was read; adapter uses only documented calls. |
| 0.3 | How to obtain the AAR + transitive deps | ✅ | AAR is only downloadable after Apple sign-in (`developer.apple.com/download/all/?q=Android%20ShazamKit`). Deps: coroutines, OkHttp 4.12, Retrofit 2.11 + converter-gson. |
| 0.4 | Media ID / private key / token rules / costs | ✅ | Requires **paid Apple Developer Program** (Certificates, Identifiers & Profiles). ES256 JWT, `kid` + `iss`/`iat`/`exp`, `exp` ≤ 15 777 000 s. See [RECOGNITION_PROVIDERS.md](RECOGNITION_PROVIDERS.md). |
| 0.5 | PoC: record PCM and get one ShazamKit match | 🟡 | Code written (`android/app/src/shazamkit/`), compiled only when the AAR is present. Not run: no AAR, no Apple key, no device. |
| 0.6 | Token from backend without key in APK | ✅ | `POST /api/v1/shazamkit/token` implemented and unit-tested with a throwaway P-256 key (signature + claims verified). |
| 0.7 | Record setup steps + blockers | ✅ | [RECOGNITION_PROVIDERS.md](RECOGNITION_PROVIDERS.md) |

**Blocker (documented, not hidden):** global music recognition needs a commercial catalogue. The
official, per-request-free route (ShazamKit) needs an Apple Developer Program membership and the
Apple-licensed AAR, neither of which the agent can obtain. No free, key-less, legitimate
alternative exists for microphone snippets — AcoustID matches whole files, not 10-second
microphone clips (analysis in RECOGNITION_PROVIDERS.md). The app therefore ships with an honest
“recognition engine not configured” state until the owner completes the Apple setup, and the
backend can optionally route to a paid provider that the owner explicitly enables.

## Phase 1 — minimum viable app

| Item | Status |
|------|--------|
| Kotlin + Compose app builds (CI) | ✅ |
| Home / Identify screen, permission explanation + runtime permission | ✅ |
| ShazamKit single-shot recognition (streaming session, stops at first match) | 🟡 |
| Match / no-match / error / not-configured states | ✅ |
| Track result card | ✅ |
| Local Room history | ✅ |
| No account required | ✅ |

## Phase 2 — local experience

| Item | Status |
|------|--------|
| Listening session (StreamingSession, continuous or interval mode) | 🟡 |
| Microphone foreground service + persistent notification with Stop/Pause | ✅ (code + manifest), 🟡 on-device |
| Duplicate prevention (configurable cooldown, default 4 min) | ✅ unit-tested |
| History grouped by day/session, search/filter, favourites | ✅ |
| Track details + YouTube/Spotify/Apple Music/share links | ✅ |
| CSV/JSON export (Storage Access Framework, no storage permission) | ✅ unit-tested |
| Settings, privacy and open-source notices screens | ✅ |
| MusicBrainz ISRC enrichment (optional, Wi-Fi-only switch, 1 req/s) | ✅ |

## Phase 3 — backend + optional account

| Item | Status |
|------|--------|
| FastAPI backend + Docker Compose | ✅ |
| Secure ShazamKit token endpoint + caching + anonymous rate limit | ✅ tested |
| Registration/login/refresh (rotation + reuse detection)/logout, Argon2id | ✅ tested |
| History/favourites/settings sync with tombstones | ✅ tested |
| Account + cloud data deletion | ✅ tested |
| Deployment docs | ✅ [DEPLOYMENT.md](DEPLOYMENT.md) |
| Email verification / password reset | ⏳ intentionally not implemented; documented as unavailable (no SMTP in default deploy) |

## Phase 4 — enhanced recognition

| Item | Status |
|------|--------|
| Server-side multi-provider routing, timeouts, daily quotas, kill switch | ✅ tested with mocked providers |
| Result reconciliation + provider provenance | ✅ tested |
| AudD / ACRCloud adapters (disabled by default, paid, explicit opt-in) | ✅ adapters + mocked tests; never called with live keys |
| AcoustID/Chromaprint adapter | ❌ rejected: AcoustID needs the whole-file duration and matches from the start of a track, so it cannot match microphone snippets. See RECOGNITION_PROVIDERS.md. |
| Enhanced UI only when ≥ 2 usable providers + signed in + user consent | ✅ |

## Phase 5 — release preparation

| Item | Status |
|------|--------|
| App icon and branding (original vector icon, easy to rename) | ✅ |
| Release build + signing instructions | ✅ [RELEASE.md](RELEASE.md) |
| Backend backup/restore | ✅ in DEPLOYMENT.md |
| Privacy policy + licence notices | ✅ |
| Device compatibility matrix | 🟡 template in ANDROID_COMPATIBILITY.md; no physical devices were available |
| APK sideloading guide | ✅ README |
