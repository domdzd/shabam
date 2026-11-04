# NanoTune — implementation plan and status

Working checklist for the brief in [`Project.md`](../Project.md): what was built, what was verified, and
what still depends on credentials or hardware the agent did not have.

Legend: ✅ done and verified (tests/CI) · 🟡 done but not verifiable here (needs device/AAR/Apple key) ·
⏳ not done · ❌ rejected (with reason)

## Phase 0 — feasibility

| # | Item | Status | Notes |
|---|------|--------|-------|
| 0.1 | Inspect repo/toolchain, choose package, SDKs | ✅ | Empty repo. App ID `io.github.domdzd.nanotune` (configurable), `minSdk 26`, `targetSdk`/`compileSdk 37`. [ANDROID_COMPATIBILITY.md](ANDROID_COMPATIBILITY.md) |
| 0.2 | Read current ShazamKit Android docs | ✅ | SDK 2.1.1; full API reference read; adapter uses only documented calls. |
| 0.3 | How to obtain the AAR + deps | ✅ | AAR only after Apple sign-in; not on Maven. Deps: coroutines, OkHttp 4.12, Retrofit 2.11 + converter-gson. |
| 0.4 | Media ID / key / token rules / terms / cost | ✅ | Paid Apple Developer Program (99 USD/yr). ES256 JWT (`kid`; `iss`,`iat`,`exp` ≤ 6 months). Licence constraints recorded. [RECOGNITION_PROVIDERS.md](RECOGNITION_PROVIDERS.md) |
| 0.5 | PoC: record PCM and get one ShazamKit match | 🟡 | Capture + streaming code written (`src/shazamkit/`); never compiled against the real AAR or run — AAR, key and device unavailable. |
| 0.6 | Token from backend, no key in APK | ✅ | `POST /api/v1/shazamkit/token`; tests verify signature, header and claims with a throwaway P-256 key. |
| 0.7 | Record setup steps + blockers | ✅ | [RECOGNITION_PROVIDERS.md](RECOGNITION_PROVIDERS.md) |

**Blocker (documented):** identifying songs needs a commercial catalogue. The official route (ShazamKit) needs
Apple's licensed AAR and a paid Apple Developer membership — owner actions. No legitimate free, key-less
alternative works for microphone clips (AcoustID matches whole files). The app therefore shows an honest
"engine not included / not configured" state until the owner completes the Apple setup. The rest of the
product was built and tested so that only those steps remain.

## Phase 1 — minimum viable app

| Item | Status |
|------|--------|
| Kotlin + Compose app builds (CI, debug + R8 release) | ✅ |
| Identify screen, permission explanation, runtime permission | ✅ (code) · 🟡 on device |
| ShazamKit single-shot recognition (streaming, stops at first match) | 🟡 |
| Match / no-match / not-configured / error states | ✅ logic unit-tested · 🟡 UI on device |
| Track result card | ✅ |
| Local Room history | ✅ Robolectric-tested |
| No account required | ✅ |

## Phase 2 — local experience

| Item | Status |
|------|--------|
| Listening session (StreamingSession; continuous or interval) | 🟡 |
| Microphone foreground service + notification with Pause/Stop | ✅ code/manifest · 🟡 on device |
| Duplicate prevention (sliding cooldown, default 4 min, 3–10 configurable) | ✅ unit-tested |
| History by day/session, search, date + favourite filters | ✅ |
| Favourites | ✅ |
| Track details + Apple Music / Shazam / YouTube / Spotify links, share | ✅ |
| CSV/JSON export (SAF) | ✅ unit-tested |
| Settings, privacy and open-source notices screens | ✅ |
| MusicBrainz ISRC enrichment (optional, Wi-Fi-only, 1 req/s) | ✅ code · 🟡 not verified (MusicBrainz docs unreachable from sandbox) |

## Phase 3 — backend + optional account

| Item | Status |
|------|--------|
| FastAPI backend + Docker Compose | ✅ (Docker build + smoke test in CI) |
| ShazamKit token endpoint, caching, anonymous rate limits | ✅ tested |
| Register / login / refresh rotation + reuse detection / logout, Argon2id | ✅ tested |
| History / favourites / settings sync with tombstones | ✅ backend tested · ✅ app merge logic tested · 🟡 two-phone sync on devices |
| Account + cloud data deletion | ✅ tested |
| Deployment, backup/restore docs | ✅ [DEPLOYMENT.md](DEPLOYMENT.md) |
| Email verification / password reset | ⏳ not implemented (no SMTP flow); app and config report it as unavailable |

## Phase 4 — enhanced recognition

| Item | Status |
|------|--------|
| Server-side routing, timeouts, per-provider + per-user daily quotas, kill switch | ✅ tested with fake providers |
| Reconciliation + provenance (single / agree / conflict) | ✅ tested |
| Client gating (≥ 2 engines, signed in, explicit consent) + upload of a 16 kHz clip | ✅ code · 🟡 untested end to end |
| AudD / ACRCloud adapters | ⏳ not written — brief forbids paid providers in v1, and their docs were unreachable (no fabricated APIs). Plug-in point documented. |
| AcoustID/Chromaprint adapter | ❌ rejected — cannot match microphone snippets (see RECOGNITION_PROVIDERS.md) |

## Phase 5 — release preparation

| Item | Status |
|------|--------|
| App icon and branding (original vector; name/ID/colours in one place) | ✅ |
| Release build + signing instructions, CI releases | ✅ [RELEASE.md](RELEASE.md) |
| Backend backup/restore | ✅ |
| Privacy notes + licence notices (in app and docs) | ✅ |
| Device compatibility matrix | 🟡 template only — no devices available |
| APK sideloading guide | ✅ README / RELEASE.md |

## Next steps for the owner

1. Apple: membership, AAR download, Media ID + Media Services key.
2. Deploy the backend with the key; check `/api/v1/config`.
3. Build with the AAR (locally or via `SHAZAMKIT_AAR_URL`), install, set the server URL.
4. Run docs/TESTING.md on a real device; fix anything the first real ShazamKit compile reports.
5. Optionally add your own release keystore secrets so updates install over each other.
