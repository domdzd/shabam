# Architecture

```
┌────────────────────────── Android app (Kotlin, Compose) ──────────────────────────┐
│                                                                                    │
│  UI (feature/*)  ──►  ViewModels  ──►  RecognitionEngine ──► MusicRecognitionProvider
│   Identify, Session,                     │   (tap to identify)      │  (interface)   │
│   History, Details,                      │                          ├─ ShazamKitProvider (AAR builds)
│   Settings, Account                      │                          └─ stub: "not included"
│                                          ▼                                         │
│  ListeningSessionService (FGS, mic) ─► same provider (StreamingSession)            │
│        │ notification: Pause / Stop      │ DuplicateFilter (sliding cooldown)      │
│        ▼                                 ▼                                         │
│  SessionStateHolder (StateFlow)    HistoryRepository ─► Room (tracks, recognitions,│
│                                          │              favourites, sessions)      │
│  SettingsRepository (DataStore)          ├─ MetadataEnricher ─► MusicBrainz (ISRC) │
│  CredentialStore (DataStore + Keystore)  └─ SyncRepository ─┐                      │
│                                                             │                      │
│  ShazamTokenSource ──────────────┐   AccountRepository ─────┤  EnhancedClient ─┐   │
└──────────────────────────────────┼──────────────────────────┼──────────────────┼───┘
                                   │ HTTPS (Retrofit/OkHttp)  │                  │
┌──────────────────────────────────▼──────────────────────────▼──────────────────▼───┐
│ NanoTune server (FastAPI, Docker, behind your reverse proxy)                        │
│  POST /shazamkit/token   – ES256 developer token (Apple .p8 key stays here)         │
│  /auth/* , /me           – optional accounts (Argon2id, rotating refresh tokens)    │
│  GET|PUT /sync           – LWW sync with tombstones, per-user cursor                │
│  POST /recognition/enhanced – optional, provider router (none enabled by default)  │
│  SQLite (SQLAlchemy + Alembic)                                                      │
└─────────────────────────────────────────────────────────────────────────────────────┘
          Shazam catalogue (Apple) ◄── audio *signature* sent by ShazamKit on the phone
```

## Android app

Single Gradle module (`android/app`) organised by layer, close to the brief's suggested layout:

| Package | Contents |
|---|---|
| `domain/model` | `AudioSample`, `RecognizedTrack`, `RecognitionOutcome` (Match / NoMatch / Unavailable / Error), history models, `TrackKeys` (identity vs. similarity keys). Pure Kotlin. |
| `domain/dedup` | `DuplicateFilter` — sliding cooldown so a song playing for minutes is stored once. |
| `domain/export`, `domain/history` | CSV (RFC 4180, formula-injection safe) / JSON export; day + session grouping. |
| `recognition` | `MusicRecognitionProvider` / `StreamingRecognitionProvider` contracts; `IdentifySession` (when a tap-to-identify attempt is finished); `RecognitionEngine` (orchestration); `EnhancedGate`; `MetadataEnricher`. |
| `recognition/shazamkit` | `ShazamTokenSource` (fetch/cache tokens), `TokenPolicy`; `ShazamKitBridge` lives in `src/shazamkit/kotlin` (real) or `src/noshazamkit/kotlin` (stub) and is selected at build time by the presence of the AAR. |
| `recognition/enhanced` | Uploads a 16 kHz WAV clip to the server route (opt-in only). |
| `core/audio` | `AudioCapture` (AudioRecord, UNPROCESSED/MIC, 48/44.1/32/16 kHz), `Pcm` (WAV, level meter, windowed-sinc resampler), dedicated audio thread. |
| `core/network`, `core/security` | Connectivity (metered/unmetered), Keystore AES-GCM cipher. |
| `data/local` | Room entities/DAOs per the brief (Track, Recognition, Favourite, ListeningSession) + sync bookkeeping (`updated_at`, `deleted_at`, `pending_sync`). |
| `data/remote` | Retrofit API + DTOs (wire format shared with `backend/app/schemas.py`), `BackendClient` (runtime-configurable server URL, safe error mapping), `MusicBrainzClient`. |
| `data/repository`, `data/settings`, `data/sync` | History, account (single-flight token refresh), sync (push then pull), server capabilities, DataStore settings, encrypted credentials, LWW merge rules. |
| `service` | `ListeningSessionService` (microphone FGS), notification, session state. |
| `feature/*`, `navigation`, `ui/theme` | Compose screens, type-safe navigation, theme (brand colours in one place). |

Manual DI: `AppContainer` builds the graph once in `NanoTuneApp`.

### Identify flow

1. Check the engine is usable **before** opening the microphone (ShazamKit bundled? server URL set? token
   obtainable?). If not, show a setup explanation — never record for nothing.
2. Open `AudioRecord`; open a ShazamKit `StreamingSession`; feed ~100 ms chunks on a dedicated audio thread.
3. `IdentifySession` finishes on the first match; tolerates transient errors; refreshes the token once on
   `UNAUTHORIZED`; at the deadline returns "no match" (or the last error if no attempt succeeded).
4. Optional enhanced fallback (only if eligible) uploads the in-memory clip.
5. Save the match (track de-duplicated by identity key), trigger enrichment and a debounced sync, wipe buffers.

### Listening session flow

`ListeningSessionService` loop: check pause → network policy (offline / mobile data) → provider availability
(backoff on outages) → listen window (continuous, or 15 s in interval mode) → on each match run
`DuplicateFilter` and store new songs with the session ID → in interval mode close the microphone until the
next window. Pause/Stop arrive as service intents from the UI or notification.

### Sync

Every local change sets `pending_sync`. Sync pushes pending rows in batches of ≤ 500 (`PUT /sync`), clears the
flag only if the row didn't change meanwhile, then pulls `GET /sync?since=<cursor>` until `has_more` is false,
applying last-writer-wins by `updated_at`. Deletions are tombstones while sync is active and are purged once
synced. Favourites sync by `track_key`; settings sync as one small document.

## Backend

`backend/app`: `config.py` (env settings), `shazamkit.py` (ES256 signer with cache), `security.py`
(Argon2id, HS256 access tokens, refresh token hashing), `ratelimit.py` (in-process sliding window),
`routers/` (public, auth, sync, recognition), `recognition/` (provider protocol, router with quotas and kill
switch, reconciliation, WAV validation), `models.py` + Alembic migrations, `errors.py` (uniform
`{"error": {"code", "message"}}` envelope; validation errors never echo submitted values).

Run with one Uvicorn worker (the rate limiter is in-process); SQLite in WAL mode is plenty for a personal
deployment. Move to PostgreSQL by changing `DATABASE_URL` if you need several workers.
