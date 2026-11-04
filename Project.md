# NanoTune — Android Music Recognition & Discovery

> **Working project specification for Claude Code / Codex**  
> Version: 1.0  
> Status: Initial implementation brief  
> Primary goal: Build a genuinely useful Android music-identification app that starts free, works without user registration, and offers optional accounts for cloud history and enhanced recognition.

---

## 1. Project overview

Build **NanoTune**, a lightweight native Android application that listens to nearby music, identifies tracks, and keeps a personal discovery history.

The application must provide two equally accessible ways to identify music:

1. **Identify now** — the user taps a prominent button, the app listens for a short sample, and returns the likely track.
2. **Listening session** — the user explicitly starts a listening session; NanoTune continuously processes microphone audio and adds newly identified tracks to a timeline, while avoiding repeated entries for the same song.

The first usable version must be **free to use and must not require an account**. Account creation is optional. A signed-in user can enable cloud backup/synchronization and, when additional recognition providers have been configured, use an enhanced multi-provider recognition route.

Do not make a paid API a requirement for the initial release. Do not advertise that account creation improves recognition accuracy unless a real additional provider or additional matching process is implemented and measured. An account by itself cannot make a fingerprint more accurate.

### Product principles

- Free-first: no subscription, purchase, advertising SDK, or mandatory login in the initial release.
- Privacy-first: microphone access only while the user is actively identifying or has deliberately started a listening session.
- No surprise recording: never save raw microphone audio by default; process it in memory and discard it after recognition.
- Lightweight: native Android, modest dependencies, fast startup, low idle resource use.
- Honest results: show a clear no-match or uncertain result rather than inventing a song.
- Extensible: recognition providers must be replaceable/addable without rewriting the user interface.
- VPS is optional for the user experience but recommended for securely issuing ShazamKit developer tokens and for optional account/sync features.

---

## 2. Important technical decision: recognition provider

### Primary provider: Apple ShazamKit for Android

Investigate and integrate the official **ShazamKit Android SDK** as the primary recognition engine.

Official Android documentation:
- https://developer.apple.com/shazamkit/android/

The current Apple documentation describes an Android AAR, a Shazam catalogue, developer-token provider, single-session recognition, and continuous recognition using `StreamingSession`. It also documents microphone input as PCM 16-bit mono at supported sample rates including 48 kHz, 44.1 kHz, 32 kHz, and 16 kHz. The documented minimum Android SDK is 21; choose a more modern project minimum only after checking all dependency/device requirements.

**Critical setup requirement:** using Apple's Shazam catalogue requires an Apple Developer token. The token is signed using a Media Services private key associated with a Media ID. The private `.p8` key must never be embedded in the APK, committed to Git, or shipped to a device.

Apple setup documentation:
- https://developer.apple.com/help/account/capabilities/create-a-media-identifier-and-private-key/

Implementation requirements:

1. Verify the current ShazamKit Android SDK distribution, version, licence/terms, account requirements, token requirements, and whether any fees or usage restrictions apply before depending on it.
2. Do not assume the SDK is available through Maven Central. Apple documentation currently describes obtaining an AAR and adding it to the project locally. Follow the current official installation instructions.
3. Add a backend token endpoint that signs short-lived ShazamKit developer tokens using the server-side private key.
4. The Android app requests a token from the backend without requiring a user account. Apply server-side rate limits and sensible caching.
5. Cache the token on the device only until its actual expiry. Refresh it before expiry. Never log the token or private key.
6. Implement robust handling for expired/invalid tokens, no network, no match, API errors, rate limits, and SDK failures.
7. Keep ShazamKit behind an interface such as `MusicRecognitionProvider`; do not couple UI components directly to the SDK.

**Free-cost rule:** ShazamKit is the preferred first engine because it avoids a per-recognition commercial API in the app architecture. However, do not claim that it is unconditionally free for every possible use or distribution model until the current Apple terms and account requirements have been checked. Record the findings in `docs/RECOGNITION_PROVIDERS.md`.

### Secondary fallback: AcoustID + Chromaprint

Keep a secondary provider adapter possible for **AcoustID / Chromaprint**.

Official documentation:
- AcoustID Web Service: https://acoustid.org/webservice
- AcoustID licence: https://acoustid.org/license
- Chromaprint: https://acoustid.org/chromaprint

AcoustID's public web service states that free use is for non-commercial applications, imposes request-rate guidance, and requires an application API key. The database and software have their own licences; review them separately. Do not use the example/test API key from the documentation in a real deployment. Register a proper application key and keep service credentials on the backend where feasible.

Chromaprint is designed around audio fingerprint extraction and may not perform as well as ShazamKit for short, noisy snippets. Treat it as a fallback/experimental adapter, not as an equal-quality replacement. Test it using real short microphone recordings before exposing it as a user-facing choice.

### Other providers

Do not integrate paid providers in the initial release. Keep optional adapters possible for services such as AudD or ACRCloud, but:

- disabled by default;
- no trial quota must be presented as permanently free;
- no user is sent to a paid provider without clear disclosure;
- provider API keys stay on the backend;
- add provider-specific quotas, cost controls, and a kill switch before enabling one.

---

## 3. Free mode and optional account model

### Free mode — no account required

On first launch, users can use the core app immediately after granting microphone permission.

Free-mode capabilities:

- Single-tap song identification through the configured primary provider.
- Show title, artist, album/release metadata when available, artwork when available, and a match confidence/status where the provider exposes one.
- Local recognition history stored in Room/SQLite.
- Local favourites.
- Manual and automatic listening modes.
- Export local history to CSV or JSON.
- No account, subscription, or payment required.

If the backend is unavailable, show a clear service-unavailable state. Do not silently upload audio to another provider. A cached, unexpired ShazamKit token may be used if appropriate, but normal Shazam catalogue recognition still requires internet access.

### Optional free account

Account creation must be optional and must not block local use.

Account capabilities:

- Cloud backup and synchronization of recognition history.
- Restore history after reinstalling or changing phones.
- Synchronize favourites and user settings.
- View listening history on a future web dashboard.
- Access the **Enhanced Recognition** route when at least two usable recognition providers are configured on the backend.

Account registration must not automatically turn on continuous listening or upload raw microphone recordings.

For the first implementation, keep account features modest and secure. Use email + password with password hashing (Argon2id preferred) and short-lived access tokens plus refresh-token rotation, or another well-supported secure pattern. Do not implement custom cryptography. Provide logout, account deletion, and a clear privacy notice. Email verification and password reset should be implemented only when SMTP settings are configured; document the unavailable functions if SMTP is not configured.

### What “better results” means

The app must distinguish between:

- **Standard recognition:** primary ShazamKit provider.
- **Enhanced recognition:** server-side routing/fallback across multiple configured providers, then result reconciliation/deduplication.

Enhanced mode may improve the chance of finding a match when the primary engine fails, but it may also return conflicting results. It must show which provider(s) found the result and avoid pretending that every match is independently confirmed.

If only one provider is configured, the account can still provide sync and backup, but the UI must not claim recognition is enhanced. Show a neutral message such as “Cloud sync enabled; additional recognition engines are not configured yet.”

No paid provider may be activated without an explicit configuration flag, a documented cost/limit, and clear user-facing disclosure. The default deployment must have all paid providers disabled.

---

## 4. User experience and screens

Use a polished, modern dark interface with a restrained accent colour, clear typography, large touch targets, and subtle listening animations. Avoid copying Shazam's logo, branding, or exact visual identity. NanoTune is a working name; keep app name, package name, colours, and links easy to change.

### 4.1 Home / Identify

- Large central animated microphone/listening button.
- Primary action: “Tap to identify”.
- Secondary action to start a listening session.
- Clear recording/listening state and elapsed time.
- Permission explanation before requesting microphone permission.
- Result card with artwork, title, artist, album and release year when available.
- Actions: favourite, open YouTube search, open Spotify search, share result.
- Links should open external apps or browser search results; do not scrape or download streaming audio.

### 4.2 Listening session

- Explicit Start / Pause / Stop controls.
- A persistent Android foreground-service notification while active.
- A visible status showing that the microphone is in use.
- Session duration and number of tracks discovered.
- Duplicate prevention: do not add the same track repeatedly within a configurable cooldown window, initially 3–5 minutes.
- Do not auto-start after device reboot.
- Do not restart automatically after the user stops the session.
- Allow the user to stop the session from the notification.
- Provide settings for recognition interval/continuous streaming behaviour, battery use, and mobile-data use.

### 4.3 History

- Chronological list grouped by day/session.
- Artwork thumbnail, track, artist, time discovered.
- Search and filter by artist/title/date.
- Favourite/unfavourite.
- Delete one item or clear all history.
- Export CSV and JSON.

### 4.4 Track details

- Title, artist, album, release date/year, duration, artwork, provider match source, and external links when available.
- Handle missing fields elegantly.
- Clearly distinguish recognized metadata from enrichment metadata.

### 4.5 Settings

- Theme: system / dark / light.
- Audio sample duration for manual identification, configurable within provider-supported limits.
- Automatic session options.
- Wi-Fi-only option for metadata/artwork enrichment.
- Optional account sign-in / registration.
- Account sync toggle.
- Privacy information and data deletion controls.
- App version and open-source notices.

### 4.6 Account

- Continue without account.
- Register, sign in, sign out.
- Sync status and last sync time.
- Delete account and cloud data.
- Never imply that registration is necessary to identify music.

---

## 5. Android technology stack

Use native Android development.

- Language: Kotlin.
- UI: Jetpack Compose + Material 3.
- Architecture: MVVM or clean, feature-oriented architecture; avoid unnecessary abstraction.
- Async work: Kotlin Coroutines and Flow.
- Local database: Room (SQLite).
- Preferences: DataStore.
- Networking: OkHttp + Retrofit or Ktor client; choose one and use consistently.
- Images: Coil.
- Dependency injection: prefer manual/lightweight DI initially; use Hilt only if it materially simplifies the project.
- Microphone capture: `AudioRecord` with PCM 16-bit mono in a provider-supported sample rate.
- Recognition: official ShazamKit Android AAR, behind a provider interface.
- Background listening: Android foreground service with the microphone service type and notification.
- Testing: JUnit, Kotlin test, Compose UI tests where appropriate, and instrumented audio/service tests.

Use current stable compatible dependency versions when implementation begins. Do not blindly copy old version numbers from examples. Pin versions through the Gradle version catalog (`gradle/libs.versions.toml`). Avoid dependencies that are abandoned or require a paid service.

### Suggested package structure

Use a conventional structure such as:

```text
app/src/main/java/<package>/
  core/
    audio/
    database/
    network/
    permissions/
    security/
    util/
  data/
    local/
    remote/
    repository/
  domain/
    model/
    repository/
    usecase/
  recognition/
    MusicRecognitionProvider.kt
    shazamkit/
    acoustid/
    routing/
  feature/
    home/
    listening/
    history/
    trackdetails/
    settings/
    account/
  service/
  navigation/
  ui/theme/
```

Adapt the structure if the actual Gradle project or SDK integration suggests a cleaner arrangement. Do not create empty layers purely to match this diagram.

---

## 6. Recognition interface and behaviour

Design a stable interface approximately like:

```kotlin
interface MusicRecognitionProvider {
    val id: String
    suspend fun identify(audio: AudioSample): RecognitionOutcome
}
```

The exact signatures can change to fit ShazamKit streaming/session APIs. The important requirement is that the UI and business logic depend on a provider abstraction, not directly on a provider SDK.

Suggested domain model:

```kotlin
data class AudioSample(
    val pcmData: ByteArray,
    val sampleRateHz: Int,
    val channels: Int = 1,
    val bitsPerSample: Int = 16,
    val durationMs: Long
)

data class RecognizedTrack(
    val providerTrackId: String? = null,
    val title: String,
    val artist: String,
    val album: String? = null,
    val releaseDate: String? = null,
    val artworkUrl: String? = null,
    val externalUrls: Map<String, String> = emptyMap()
)

sealed interface RecognitionOutcome {
    data class Match(
        val track: RecognizedTrack,
        val providerId: String,
        val confidence: Float? = null
    ) : RecognitionOutcome

    data object NoMatch : RecognitionOutcome
    data class Unavailable(val reason: String) : RecognitionOutcome
    data class Error(val safeMessage: String, val retryable: Boolean) : RecognitionOutcome
}
```

Treat audio sample memory carefully. Make samples short-lived, avoid copying them unnecessarily, clear references after use, and never persist raw audio unless a future feature explicitly asks for it and obtains informed consent.

### Manual identification flow

1. User taps Identify.
2. Check/request microphone permission.
3. Capture an audio sample using a supported PCM format.
4. Pass audio to ShazamKit signature/session workflow.
5. Return match/no-match/error to UI.
6. Save the structured recognition result locally.
7. Discard raw audio data.

### Automatic listening flow

Prefer ShazamKit's `StreamingSession` if the current SDK supports the desired continuous workflow. Feed microphone audio chunks to it and collect recognition results. Do not implement repeated full-file uploads in a tight loop.

If continuous SDK behaviour is unreliable, implement chunked recognition with configurable intervals and explicit start/stop rather than trying to keep a hot microphone running invisibly.

---

## 7. Android permissions and background limitations

The app should request only necessary permissions. At minimum, microphone recording and internet access will be required for recognition. Add notification permission handling where required by Android version.

Background microphone operation is restricted by modern Android versions. Follow current Android documentation; do not attempt to bypass platform restrictions.

Official references:
- https://developer.android.com/develop/background-work/services/fgs/changes
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

Requirements:

- Declare the foreground service type as `microphone` when using a microphone foreground service.
- Request the appropriate foreground-service permissions for target SDK.
- Start the microphone foreground service only from a valid user-visible context, normally while an Activity is visible and after permission is granted.
- Show an ongoing notification with a clear Stop action during listening sessions.
- Handle Android 13+ notification permission UX and current target SDK requirements.
- Handle permission revocation, microphone contention, phone calls/audio-focus changes, app process death, and service shutdown.
- Do not promise unrestricted background listening. Explain the OS limitations in the app.

Target a current stable Android SDK at build time and choose `minSdk` based on tested device support and dependencies. The ShazamKit documentation lists min SDK 21, but the app may choose a higher minimum if justified. Record this decision in `docs/ANDROID_COMPATIBILITY.md`.

---

## 8. VPS backend

The Android application should remain useful without an account. A backend is recommended for secure ShazamKit token signing, optional accounts, cloud synchronization, and enhanced provider routing.

### Preferred backend stack

- Python 3.12+ (or current stable supported version).
- FastAPI.
- SQLite initially for simple deployment, with SQLAlchemy/Alembic migrations; PostgreSQL can be introduced later if concurrency or scale requires it.
- Uvicorn behind the existing reverse proxy (Traefik or nginx).
- Docker Compose for deployment.
- HTTPS only for public endpoints.

Keep deployment lightweight and self-contained. Use environment variables and a `.env` file that is excluded from Git. Provide `.env.example` with placeholders only.

### Suggested deployment layout

Use an isolated project directory, for example:

```text
/srv/projects/nanotune/
  android/
  backend/
  docs/
  deploy/
  README.md
  Project.md
  .env.example
  .gitignore
```

The reverse-proxy hostname/path must be configurable. Do not hard-code a domain. Configure an example base URL such as `https://api.example.com` and document how to map it to the user's VPS.

### Backend responsibilities

1. Generate short-lived Apple/ShazamKit developer tokens using the private key stored only on the server.
2. Provide token issuance without requiring a user account for the no-login free mode.
3. Rate-limit anonymous token issuance and protect the service from abuse.
4. Provide optional account registration/login/refresh/logout.
5. Synchronize user history and favourites for signed-in users.
6. Route optional enhanced-recognition requests to configured providers.
7. Normalize provider responses to a common schema.
8. Record provider health and safe operational metrics without logging raw audio or secrets.

### Suggested API endpoints

```text
GET    /api/v1/health
GET    /api/v1/config
POST   /api/v1/shazamkit/token
POST   /api/v1/auth/register
POST   /api/v1/auth/login
POST   /api/v1/auth/refresh
POST   /api/v1/auth/logout
GET    /api/v1/me
GET    /api/v1/sync
PUT    /api/v1/sync
DELETE /api/v1/me
POST   /api/v1/recognition/enhanced   # only if/when server-side provider routing is needed
```

The initial app should use ShazamKit's Android SDK for audio fingerprinting/matching, not upload the raw recording to the VPS by default. The VPS issues a token and provides account/sync services. Only introduce an upload-based recognition route if a configured provider explicitly requires it and the user has been informed.

### Security requirements

- Never commit `.p8`, API tokens, passwords, signing keys, or production `.env` files.
- Use environment variables or mounted secrets for all server credentials.
- Restrict private-key filesystem permissions.
- Never return Apple's private signing key to the mobile app.
- Avoid placing permanent service API keys in the APK.
- Use TLS for all non-local backend communication.
- Hash passwords with Argon2id or a well-maintained library's secure default.
- Use short-lived access tokens and refresh-token rotation/revocation.
- Add rate limiting to registration, login, token issuance, and provider endpoints.
- Validate request sizes and types.
- Use generic auth error messages that do not leak account existence unnecessarily.
- Add CORS restrictions only if browser clients are introduced; CORS is not an API security mechanism.
- Provide account deletion and cloud-data deletion.
- Do not store microphone audio, derived fingerprints, or detailed listening logs on the backend unless specifically required and transparently disclosed.

---

## 9. Artwork and metadata enrichment

Use track metadata supplied by the recognition provider first. If additional metadata is needed, make enrichment optional and cache results responsibly.

Possible public metadata source:
- MusicBrainz API: https://musicbrainz.org/doc/MusicBrainz_API
- Cover Art Archive: https://musicbrainz.org/doc/Cover_Art_Archive/API

MusicBrainz states that non-commercial API use is free, requires a meaningful User-Agent, and asks clients to stay at or below one request per second. Respect current terms, rate limits, and any commercial-use restrictions. Cover Art Archive artwork is not guaranteed to exist for every release.

Use a User-Agent such as `NanoTune/0.1.0 (contact: configured-contact-address)` and make the contact value configurable. Do not perform a metadata lookup for every screen redraw. Cache results by stable identifier where possible.

Do not scrape Spotify, YouTube, or other services. For external playback/discovery, open official search/deep links or documented public APIs. The app must still work when artwork or enrichment metadata is missing.

---

## 10. Local data model

Use Room entities similar to:

### TrackEntity

- `id` — local UUID.
- `providerTrackId` — nullable provider ID.
- `title`.
- `artist`.
- `album` — nullable.
- `releaseDate` — nullable.
- `artworkUrl` — nullable.
- `externalUrlsJson` — nullable.
- `createdAt`.
- `updatedAt`.

### RecognitionEntity

- `id` — local UUID.
- `trackId` — nullable relation when no match exists only if retaining failed attempts is enabled; default should not persist no-match attempts.
- `recognizedAt`.
- `providerId`.
- `sessionId` — nullable.
- `matchConfidence` — nullable.
- `recognitionMode` — `MANUAL` or `SESSION`.

### FavouriteEntity

- `trackId`.
- `createdAt`.

### ListeningSessionEntity

- `id`.
- `startedAt`.
- `endedAt` — nullable while active.
- `trackCount`.
- `userStopped`.

Store user settings separately using DataStore. Keep the schema migration-ready.

---

## 11. Privacy and user trust

Create `docs/PRIVACY.md` before implementing microphone features.

The in-app privacy explanation must state:

- Microphone access is used to identify music.
- Manual mode records only while the user initiates identification.
- Listening-session mode keeps the microphone active only after the user explicitly starts it and while the foreground notification is visible.
- Raw audio is processed transiently and is not saved or uploaded by default.
- ShazamKit requires internet access for the Shazam catalogue.
- Account registration is optional.
- Signed-in history/favourites are synchronized to the configured NanoTune server.
- Any future third-party recognition provider and any audio upload must be disclosed before activation.

Add a “Delete local data” action and a separate “Delete account and cloud data” action. Do not use analytics or crash-reporting SDKs in the first version unless explicitly approved and disclosed.

---

## 12. Testing strategy

Build and test incrementally. Do not attempt to implement the whole product in one pass.

### Recognition tests

- Clean music from a speaker.
- Music playing quietly in a room.
- Music in a noisy environment.
- Music from a TV or laptop speaker.
- A live recording or remix where the provider may not find a match.
- Speech/no music.
- Silence.
- Network offline.
- Expired/invalid developer token.
- Provider rate limit / server error.

Never assert that every song must be identified. Verify sensible outcomes and accurate metadata when a provider does return a match.

### App tests

- First launch with permission granted/denied.
- Manual identify start/cancel/complete.
- Double tap protection.
- Add/remove favourite.
- History persistence after process restart.
- Export history.
- Listening session start/stop from UI and notification.
- Duplicate prevention.
- Permission revoked while session active.
- Device rotation and app background/foreground.
- Battery/data saver conditions where test devices permit.

### Backend tests

- Health endpoint.
- Developer token generation and expiry validation.
- Missing/invalid secrets.
- Anonymous rate limiting.
- Registration/login/refresh/logout.
- Sync isolation between accounts.
- Account deletion.
- Provider-disabled behaviour.
- Enhanced routing with one provider, multiple providers, conflicting results, timeouts, and rate limits.

Include unit tests for normalizing provider results and merging results. Never use live paid API keys in automated tests.

---

## 13. Development phases and acceptance criteria

### Phase 0 — Technical feasibility spike

Before implementing the full UI:

1. Inspect the current Android project/toolchain and choose package name, min SDK, and target SDK.
2. Read the latest official ShazamKit Android documentation.
3. Verify how to obtain the Android AAR and required transitive dependencies.
4. Verify Apple Media ID/private-key setup, developer-token generation, account requirements, and applicable terms/costs.
5. Create a tiny proof-of-concept that records PCM from the microphone and obtains one ShazamKit match.
6. Prove that a token can be securely obtained from a local/VPS backend without putting the private key in the APK.
7. Record exact setup steps and unresolved limitations in `docs/RECOGNITION_PROVIDERS.md`.

**Do not proceed to extensive app implementation until this spike either succeeds or documents a concrete blocker and a tested free alternative.** Do not fabricate SDK methods or invent a dependency coordinate.

### Phase 1 — Minimum viable app

Deliver:

- Kotlin + Compose app builds successfully.
- Home/Identify screen.
- Runtime microphone permission flow.
- ShazamKit single-shot recognition.
- Match/no-match/error states.
- Track result card.
- Local Room history.
- No account required.

Acceptance: installable debug APK can identify several test tracks in ordinary conditions and saves successful results locally.

### Phase 2 — Finished local experience

Deliver:

- Listening session using ShazamKit StreamingSession if stable.
- Microphone foreground service and persistent notification.
- Start/stop controls and duplicate prevention.
- History search/filter.
- Favourites.
- Track details and external search links.
- CSV/JSON export.
- Settings and privacy screens.

Acceptance: listening can be started by the user, remains visibly active, and can always be stopped from the app or notification.

### Phase 3 — VPS backend and optional account

Deliver:

- FastAPI backend and Docker deployment.
- Secure ShazamKit token endpoint.
- Anonymous token endpoint rate limiting.
- Optional registration/login.
- History/favourites sync.
- Account deletion and cloud data deletion.
- Configuration and deployment documentation.

Acceptance: an anonymous user can identify tracks without creating an account; a signed-in user can synchronize history between two installations.

### Phase 4 — Enhanced recognition

Deliver only after the base app is stable:

- AcoustID/Chromaprint adapter if current service terms and real-world tests support it.
- Server-side multi-provider routing.
- Provider health, timeout and quota controls.
- Result reconciliation and provider provenance.
- Clear enhanced-mode UI.

Acceptance: enhanced mode is only advertised when at least two usable providers are configured, and it never silently calls a paid provider.

### Phase 5 — Release preparation

- App icon and final branding.
- Release build and signing instructions.
- Backend backup/restore instructions.
- Privacy policy and licence notices.
- Device compatibility test matrix.
- Installation guide for APK sideloading.
- Optional future Google Play preparation, without assuming Play Store publication is required.

---

## 14. Configuration and secrets

Create `.env.example` with placeholders such as:

```dotenv
APP_ENV=production
APP_BASE_URL=https://api.example.com
DATABASE_URL=sqlite:////data/nanotune.db

# Apple Media Services / ShazamKit token signing
APPLE_TEAM_ID=
APPLE_MEDIA_ID=
APPLE_KEY_ID=
APPLE_PRIVATE_KEY_PATH=/run/secrets/apple_media_services.p8
SHAZAMKIT_TOKEN_TTL_SECONDS=3600

# Optional account email functions
SMTP_HOST=
SMTP_PORT=587
SMTP_USERNAME=
SMTP_PASSWORD=
SMTP_FROM=

# Optional providers: disabled by default
ACOUSTID_ENABLED=false
ACOUSTID_CLIENT_KEY=
AUDD_ENABLED=false
AUDD_API_TOKEN=
ACRCLOUD_ENABLED=false
ACRCLOUD_HOST=
ACRCLOUD_ACCESS_KEY=
ACRCLOUD_ACCESS_SECRET=
```

Correct variable naming and token lifetime according to the current Apple requirements during implementation. Do not assume the example TTL is accepted without checking the current token rules. Confirm the allowed token lifetime against the current Apple requirements before using the example value.

Secrets must be injected at runtime. `.env.example` is safe to commit; real `.env` files and key files must be ignored by Git.

---

## 15. Documentation to maintain

Create and keep these documents current:

- `README.md` — project summary, quick start, build/run.
- `docs/ARCHITECTURE.md` — app/backend architecture and data flow.
- `docs/RECOGNITION_PROVIDERS.md` — provider setup, pricing/terms checks, limitations and test results.
- `docs/ANDROID_COMPATIBILITY.md` — min/target SDK, tested Android versions/devices, foreground-service constraints.
- `docs/DEPLOYMENT.md` — VPS Docker deployment, reverse proxy and TLS.
- `docs/PRIVACY.md` — audio handling, account data, sync, deletion.
- `docs/TESTING.md` — reproducible test scenarios and outcomes.
- `CHANGELOG.md` — notable changes.

---

## 16. Agent working instructions

Claude Code / Codex should follow these rules:

1. **Inspect before changing.** If the directory already contains files, inspect them and preserve useful work.
2. **Work in small phases.** Complete and test Phase 0 before building the rest.
3. **Do not fabricate APIs.** Read current official documentation and inspect the actual SDK/AAR before writing integration code.
4. **No hidden costs.** Do not add paid providers, subscriptions, cloud products, or paid SDKs without explicit approval.
5. **No secrets in source control.** Add `.gitignore` before creating credentials/config files.
6. **No unnecessary raw audio storage.** Audio is ephemeral by default.
7. **Use the VPS only where useful.** The no-account app must remain simple; the backend is for token signing, optional accounts/sync and optional provider routing.
8. **Keep the UI responsive.** Audio and network work must not run on the main thread.
9. **Handle failure paths.** Include permission denial, no match, offline, provider failure, token expiry, and rate limiting.
10. **Build and test after each meaningful change.** Report exact commands and results.
11. **Maintain documentation.** Update docs as decisions are made, especially any provider licensing, cost, account, or OS compatibility findings.
12. **Do not claim success without evidence.** If recognition could not be tested because credentials or a device are unavailable, say so and provide the exact setup needed.

### First tasks for the agent

Start by:

1. Listing the current directory and checking whether it is empty or contains an existing Android project.
2. Creating a short implementation checklist in `docs/IMPLEMENTATION_PLAN.md`.
3. Completing Phase 0 feasibility research and proof-of-concept.
4. Asking the owner only for credentials or manual account actions that cannot be completed by the agent. Never ask the owner to paste private keys into chat; explain how to place them in a secure server-side file.
5. Then proceed to Phase 1.

---

## 17. Official technical references

Use these as starting points and re-check them before implementation because SDKs and platform rules can change:

- Apple ShazamKit for Android: https://developer.apple.com/shazamkit/android/
- Apple Media ID and private key: https://developer.apple.com/help/account/capabilities/create-a-media-identifier-and-private-key/
- Android foreground-service changes: https://developer.android.com/develop/background-work/services/fgs/changes
- Android foreground-service background-start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- AcoustID Web Service: https://acoustid.org/webservice
- AcoustID licence: https://acoustid.org/license
- Chromaprint: https://acoustid.org/chromaprint
- MusicBrainz API: https://musicbrainz.org/doc/MusicBrainz_API
- MusicBrainz rate limiting: https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting
- Cover Art Archive API: https://musicbrainz.org/doc/Cover_Art_Archive/API

---

## 18. Definition of done for the first public-quality version

The first public-quality version is complete when:

- The app installs and launches on the declared Android versions.
- A user can identify a song without creating an account.
- Recognition results are real provider responses, never mocked in production.
- Track history and favourites persist locally.
- Manual and user-started listening-session modes work, subject to Android's documented restrictions.
- The app visibly indicates when the microphone is active and offers an immediate stop action.
- Raw audio is not saved or uploaded by default.
- Optional account sync works and is not required for core identification.
- Enhanced recognition is only displayed when additional functioning providers are configured and permitted.
- The VPS can be deployed from documented Docker Compose instructions.
- No credentials or private keys are present in the APK or Git repository.
- Privacy, provider terms, limitations, and setup instructions are documented.

**Build the smallest reliable free version first. Add accounts and enhanced recognition only after the single-provider, no-account experience is working end to end.**
