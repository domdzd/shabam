# Recognition providers — findings, setup and limitations

_Research date: 2026-11-04 (server engines: 0.2.0)._ Everything marked **verified** was read from the official source in
this session; anything not verifiable is labelled as such._

## Summary

| Provider | Role in NanoTune | Cost to you | Works for microphone snippets | Status |
|---|---|---|---|---|
| **Apple ShazamKit for Android** (Shazam catalogue) | Primary engine, on device | Apple Developer Program, **99 USD/year**; no per-request fee is documented | Yes (designed for it) | Integrated; compiled when you add Apple's AAR; **not run** here (no AAR/key/device) |
| AcoustID + Chromaprint | Considered as free fallback | Free for non-commercial use (per the brief) | **No** — see analysis | Rejected; not implemented |
| **AudD** | Optional server engine (main engine without ShazamKit, identify later, fallback) | Paid; trial credits on sign-up | Yes | **Integrated (0.2.0)**, off by default; verified against AudD's official Go SDK; not run with a live key |
| **ACRCloud** | Optional server engine (same roles) | Paid; free trial | Yes | **Integrated (0.2.0)**, off by default; verified against ACRCloud's official examples; not run with a live key |
| Unofficial/reverse-engineered Shazam endpoints | — | — | — | Deliberately **not** used: not authorised by Apple |

There is no legitimate, free, key-less service that identifies arbitrary songs from a microphone
clip. A catalogue of millions of fingerprints is required, and the official free-per-request route
is ShazamKit, which requires a paid Apple Developer Program membership. Since 0.2.0 the owner can
instead (or additionally) attach **AudD** or **ACRCloud** on the server with their own keys; the APK from
the Releases page then identifies songs without ShazamKit.

## Apple ShazamKit for Android

### Verified facts

* **SDK version**: 2.1.1 (the reference documentation at
  <https://developer.apple.com/shazamkit/android/> is generated for 2.1.1). Changelog: 2.0 Timed Media
  Items and Frequency Skew Ranges; 2.0.2 lowered `minSdkVersion` to 21; 2.1.0 added 16 KB page-size
  support and updated dependencies; 2.1.1 "Improves audio recognition. No API changes."
* **Distribution**: an **AAR file**, downloaded from Apple's developer site
  ("Download the ShazamKit SDK for Android" → `developer.apple.com/download/all/?q=Android%20ShazamKit`),
  which requires signing in with an Apple Account. It is **not** on Maven Central; Apple's instructions
  are to put it in a local `libs` directory.
* **Dependencies Apple says the AAR needs** (not bundled in it): `kotlinx-coroutines-core` and
  `kotlinx-coroutines-android` (1.8.0), `okhttp` 4.12.0, `retrofit` 2.11.0, `converter-gson` 2.11.0.
  NanoTune pins OkHttp 4.12.0 and Retrofit 2.11.0 for this reason and adds `converter-gson` only in
  ShazamKit builds.
* **Requirements**: `minSdkVersion 21`, Kotlin, `INTERNET` permission for the Shazam catalogue.
* **Audio**: PCM 16-bit **mono** at 48 000, 44 100, 32 000 or 16 000 Hz, from any source.
* **API used by NanoTune** (and nothing else):
  `ShazamKit.createShazamCatalog(DeveloperTokenProvider, Locale?)`,
  `ShazamKit.createSignatureGenerator(AudioSampleRateInHz)` → `SignatureGenerator.append/generateSignature`,
  `ShazamKit.createSession(Catalog)` → `Session.match(Signature)`,
  `ShazamKit.createStreamingSession(Catalog, AudioSampleRateInHz, readBufferSize)` →
  `StreamingSession.matchStream(bytes, length, timestampMs)` and `recognitionResults(): Flow<MatchResult>`,
  `MatchResult.Match / NoMatch / Error`, `MatchedMediaItem` (title, subtitle, artist, artworkURL,
  appleMusicURL, webURL, videoURL, isrc, genres, explicitContent, shazamID), `MatchError`,
  `ShazamKitException` / `InternalError`, `ShazamKitResult.Success / Failure`.
* `ShazamCatalog.minimumQuerySignatureDurationInMs` is 3000 ms; the maximum is exposed at runtime.
* ShazamKit gives **no confidence score**, **no album** and **no release date**. NanoTune therefore stores
  `confidence = null` and (optionally) looks up album/date by ISRC on MusicBrainz, marking those fields as
  enrichment.
* The streaming session "may report the same match multiple times in succession" — hence NanoTune's
  sliding duplicate cooldown.

### Developer token (verified)

From Apple's *Create a media identifier and private key* and *Generating Developer Tokens* pages:

1. In **Certificates, Identifiers & Profiles** → Identifiers → **+** → **Media IDs**, register a Media ID and
   enable ShazamKit (role: Account Holder or Admin).
2. Keys → **+** → enable **Media Services**, choose the Media ID, download the `.p8` private key
   (downloadable **once**). Note the 10-character **Key ID**.
3. A developer token is a JWT signed with **ES256**; header `alg` + `kid` (Key ID); claims `iss` (10-character
   Team ID), `iat`, `exp`, where `exp` must not be more than **15 777 000 s (6 months)** after now.
   The Media ID is not part of the token.

NanoTune's backend (`backend/app/shazamkit.py`) signs exactly that, with a default lifetime of 1 hour
(`SHAZAMKIT_TOKEN_TTL_SECONDS`, validated to 60 s … 15 777 000 s), caches the token server-side and
re-signs it when less than half its life remains. The app caches it (encrypted) until 5 minutes before
expiry and fetches a fresh one when ShazamKit reports `UNAUTHORIZED`. The `.p8` key never leaves the
server. Tests verify signature, header and claims with a throwaway P-256 key.

### Cost and account requirements (verified)

* Certificates, Identifiers & Profiles is only available to **Apple Developer Program** members
  ("To build and test more advanced capabilities and services, join the Apple Developer Program… you'll be
  able to access additional resources, like Certificates, Identifiers & Profiles").
* The program is **99 USD per membership year** (or local currency); fee waivers exist for some
  non-profits, educational institutions and government entities.
* No per-request price or public quota for the Shazam catalogue is documented. Treat it as
  "included with membership, subject to Apple's terms and unpublished limits".

So ShazamKit is **not unconditionally free**: it is free per recognition, but requires the membership.

### Licence terms that affect this app (verified, Apple Developer Program License Agreement, "ShazamKit")

* All use must follow the agreement, the Apple Music Identity Guidelines and the documentation.
* **If you display ShazamKit content for songs available on Apple Music, you must link to that content
  in Apple Music** (per the Apple Music Identity Guidelines,
  <https://marketing.services.apple/apple-music-identity-guidelines>). NanoTune shows a
  "Listen on Apple Music" link first whenever ShazamKit provides an Apple Music URL. Before publishing
  widely, review the guidelines and consider using Apple's official badge artwork.
* "You agree not to copy, modify, translate, create a derivative work of, publish or publicly display
  ShazamKit Content" except as permitted. NanoTune keeps a private, personal history on the phone and,
  only for signed-in users, in the owner's own server. **Review whether cloud sync/export fit your use**
  before distributing the app to others.
* "You may not use or compare the data provided by the ShazamKit APIs for the purpose of improving or
  creating another audio recognition service." NanoTune never sends ShazamKit results to other engines:
  enhanced recognition only runs **after ShazamKit found no match**, and only the audio clip (not ShazamKit
  data) is sent. Keep it that way if you add providers.
* Apps using ShazamKit "may not be designed or marketed for compliance purposes (e.g. music licensing and
  royalty auditing)". Don't market listening sessions as broadcast monitoring.
* Apple's HIG for ShazamKit: stop recording as soon as possible. Identify stops at the first match.

### Setup steps (owner actions — the agent could not do these)

1. Join the Apple Developer Program (99 USD/year) if you are not a member.
2. Download the ShazamKit Android AAR from Apple and place it at
   `android/app/libs/shazamkit-android-release.aar` (git-ignored; Apple's licence does not allow
   redistributing it from this public repo). Or pass `-Pnanotune.shazamkitAar=/path/to/file.aar`.
3. Create the Media ID and Media Services key (above). Put the key on your VPS as
   `deploy/secrets/apple_media_services.p8` (`chmod 400`), and set `APPLE_TEAM_ID`, `APPLE_KEY_ID`
   (and optionally `APPLE_MEDIA_ID`) in `.env`. Never paste the key into chat, Git or the app.
4. Deploy the backend (docs/DEPLOYMENT.md) and check `GET /api/v1/config` shows
   `"shazamkit": {"available": true, ...}`.
5. Build the APK with the AAR (locally with Android Studio, or in CI with the `SHAZAMKIT_AAR_URL` secret
   — docs/RELEASE.md), install it, and enter your server address in **Settings › NanoTune server**.
6. Run the recognition test plan in docs/TESTING.md and record the results there.

### Unresolved / unverified

* The ShazamKit adapter (`android/app/src/shazamkit/kotlin/.../ShazamKitBridge.kt`) is written only
  against Apple's published API reference. It has **never been compiled against the real AAR or run**,
  because the AAR is behind Apple's sign-in. The first ShazamKit build may need small fixes; the
  compiler will point at them.
* Compatibility of the AAR with R8 minification is unknown; `proguard-rules.pro` keeps
  `com.shazam.shazamkit.**` as a precaution.
* Whether the streaming session needs wall-clock or stream-position timestamps is not specified beyond
  "the timestamp … where in the stream the audio occurs"; NanoTune sends a contiguous stream clock
  (start time + audio position).
* Apple publishes no rate limits for the Shazam catalogue.

## AcoustID + Chromaprint (rejected)

The project brief suggested AcoustID as a free fallback. It was rejected after analysis:

* AcoustID is designed to identify **audio files**. Its lookup takes the Chromaprint fingerprint of a
  file **plus the duration of the whole file**, and matching is done against fingerprints computed from the
  start of each track. A 10-second microphone clip from the middle of a song has neither the right duration
  nor the right alignment, and room noise breaks Chromaprint's bit patterns. It would essentially never
  match, so offering it would be dishonest.
* Free use of the public AcoustID service is limited to non-commercial applications and requires an
  application API key (from the brief).
* The AcoustID, Chromaprint and MusicBrainz documentation sites could **not be fetched from the build
  sandbox** (blocked by its network policy), so the statements above come from the brief and general
  knowledge of how AcoustID works rather than from a fresh reading. Re-check
  <https://acoustid.org/webservice> before revisiting this decision; a possible honest use would be a
  future "identify an audio file" feature, not microphone recognition.

## Server engines: AudD and ACRCloud (0.2.0, off by default)

Both adapters live in `backend/app/recognition/providers/`. The providers' documentation sites were
**blocked** in the build sandbox, so each adapter was written against, and checked with, the provider's
**own published code** on GitHub (read in this session). Neither was run against the live service: no key
was available, and the brief forbids live keys in automated tests. The tests use recorded response shapes
through `httpx.MockTransport`.

### AudD (verified against `AudDMusic/audd-go`, the official Go SDK)

* Request: `POST https://api.audd.io/` as `multipart/form-data` with `api_token`, the audio as `file`,
  and optional `return` (NanoTune sends `apple_music,spotify` by default, `AUDD_RETURN`).
* Response: `{"status": "success" | "error", "result": {...} | null, "error": {"error_code", "error_message"}}`.
  `result: null` means no match. Fields used: `artist`, `title`, `album` and `release_date` (falling back
  to `apple_music.albumName` / `releaseDate`), `isrc` (top level or `apple_music.isrc`), Apple Music
  artwork (`{w}x{h}` → 600×600), `apple_music.url`, `spotify.id` (→ an `open.spotify.com` link) and
  `song_link`, whose thumbnail (`?thumb`) is the fallback artwork. AudD reports no confidence score.
* Error codes are mapped by class, as in the SDK: 900/901/903 bad token (not retried), 902 quota
  exhausted, 904/905 subscription problems, 300/400/500 unreadable audio, 611 rate limited (retry later),
  19/31337 blocked, 20 client needs an update, the rest invalid request. Code 51 ("deprecated parameter")
  with a result still counts as a match.
* AudD analyses only the **first 12 seconds** of a file, which matches NanoTune's 12-second upload cap.
* Settings: `AUDD_ENABLED=true`, `AUDD_API_TOKEN=…` (from the AudD dashboard), `AUDD_DAILY_LIMIT=<n>`
  (a hard cap you choose; 0 keeps it off), `AUDD_RETURN`.

### ACRCloud (verified against `acrcloud/webapi_example` and `acrcloud/acrcloud_scan_files_python`)

* Identify Protocol V1: `POST https://<your project host>/v1/identify` (the host is shown in the ACRCloud
  console, e.g. `identify-eu-west-1.acrcloud.com`) with form fields `access_key`, `sample_bytes`,
  `timestamp`, `signature`, `data_type=audio`, `signature_version=1` and the audio as file `sample`.
* Signature: `base64(HMAC-SHA1(access_secret, "POST\n/v1/identify\n<access_key>\naudio\n1\n<timestamp>"))`
  (a test checks it against an independent implementation of the published formula).
* Response: `status.code` 0 = match, **1001 = no result**, other codes = error (2000–3999 are client or
  credential problems and are not retried). The best match is `metadata.music[0]`: `title`,
  `artists[].name`, `album.name`, `release_date`, `score` (0–100, shown as confidence), `external_ids.isrc`
  (a string or a list), `external_metadata.spotify.track.id`, `external_metadata.youtube.vid`, `acrid`.
* `ACRCLOUD_HOST` must be a bare host name (a leading `https://` and a trailing `/` are stripped; anything
  with a path, port or other characters is rejected), and requests always go to `https://<host>/v1/identify`.
  Double-check it against the console, since the clip is sent to that host.
* Settings: `ACRCLOUD_ENABLED=true`, `ACRCLOUD_HOST`, `ACRCLOUD_ACCESS_KEY`, `ACRCLOUD_ACCESS_SECRET`,
  `ACRCLOUD_DAILY_LIMIT=<n>`.

### How the app uses server engines

* **Off unless the owner turns them on**: an engine is registered only with `*_ENABLED=true`, credentials
  **and** a positive `*_DAILY_LIMIT`. `PROVIDER_ORDER` (default `audd,acrcloud`) sets the order.
  `RECOGNITION_KILL_SWITCH=true` disables all of them at once.
* `/api/v1/config` lists the usable engines under `server_engines` (with their disclosure text). The app
  shows them by name and asks for consent. The consent is stored together with the exact list of engine
  IDs, and the app asks again if the list changes.
* **Main engine**: `POST /api/v1/recognition/identify`. Used when the build has no ShazamKit (or ShazamKit
  isn't usable) and for *Identify* on saved recordings. Signed-in users count against
  `ENHANCED_PER_USER_DAILY_LIMIT`. Anonymous use is allowed only with `SERVER_RECOGNITION_ANONYMOUS=true`,
  capped by `SERVER_RECOGNITION_PER_INSTALL_DAILY_LIMIT` and `SERVER_RECOGNITION_PER_IP_DAILY_LIMIT`.
* **Fallback ("enhanced")**: `POST /api/v1/recognition/enhanced`, for signed-in users after ShazamKit found
  no match, only when there are **at least two** engines in total, ShazamKit included (`ENHANCED_MODE=fallback` tries them in order,
  `consensus` asks all of them and reports agreement).
* Clips are at most `ENHANCED_MAX_UPLOAD_SECONDS` (≤ 12) of 16 kHz mono WAV, validated by the server,
  processed in memory and never stored. Each engine call has a timeout (`ENHANCED_PROVIDER_TIMEOUT_SECONDS`).

### Cost and terms (check before enabling)

* Both services are **paid** after their trial; prices and trial sizes change, so check the providers'
  current pricing pages. The daily limits are the safety net: when one is reached, that engine is skipped
  until the next UTC day.
* Read each provider's terms on caching and displaying results before you distribute the app to others.
* **ShazamKit licence**: Apple forbids using ShazamKit data to improve or create another recognition service
  and comparing it with other services for that purpose. NanoTune sends **only the audio clip** to server
  engines, only after ShazamKit returned no match (or when there is no ShazamKit), and never sends
  ShazamKit results anywhere. Keep it that way.

### Adding another provider

1. Read the provider's current API docs and terms (or its official SDK if the docs are unreachable); get a
   key; note the price and choose a hard daily limit.
2. Implement `ProviderAdapter` (`id`, `name`, `paid`, `disclosure`, `daily_limit`, `async identify(clip)`)
   with `httpx.AsyncClient`, mapping "no result" to `None` and outages to `ProviderError`.
3. Add `<NAME>_ENABLED`, credentials and `<NAME>_DAILY_LIMIT` settings (default disabled, limit 0) and
   register the adapter in `build_providers()` only when it is fully configured.
4. Write tests with recorded or mocked responses. **Never use live keys in automated tests.**

## MusicBrainz / Cover Art Archive (metadata enrichment)

* Used only to add album, release date and duration (by ISRC) and a fallback cover image. Optional,
  "Wi-Fi only" switch, cached per track, at most one request per second, User-Agent
  `NanoTune/<version> ( https://github.com/domdzd/shabam )` (configurable via `nanotune.musicBrainzContact`).
* The brief states non-commercial API use is free and asks for ≤ 1 request/second with a meaningful
  User-Agent. The MusicBrainz documentation could **not be fetched** from the build sandbox; the client
  uses the long-standing `/ws/2/isrc/{isrc}?inc=releases&fmt=json` lookup and Cover Art Archive
  `/release/{mbid}/front-250` URLs, parsed tolerantly so a format change simply yields no enrichment.
  Verify on a device.
