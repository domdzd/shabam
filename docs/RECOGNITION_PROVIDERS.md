# Recognition providers — findings, setup and limitations

_Research date: 2026-11-04. Everything marked **verified** was read from the official source in
this session; anything not verifiable is labelled as such._

## Summary

| Provider | Role in NanoTune | Cost to you | Works for microphone snippets | Status |
|---|---|---|---|---|
| **Apple ShazamKit for Android** (Shazam catalogue) | Primary engine, on device | Apple Developer Program, **99 USD/year**; no per-request fee is documented | Yes (designed for it) | Integrated; compiled when you add Apple's AAR; **not run** here (no AAR/key/device) |
| AcoustID + Chromaprint | Considered as free fallback | Free for non-commercial use (per the brief) | **No** — see analysis | Rejected; not implemented |
| AudD, ACRCloud, other paid APIs | Optional server-side "enhanced" engines | Paid / trial | Yes | Not integrated (brief forbids paid providers in the first release); plug-in point exists |
| Unofficial/reverse-engineered Shazam endpoints | — | — | — | Deliberately **not** used: not authorised by Apple |

There is no legitimate, free, key-less service that identifies arbitrary songs from a microphone
clip. A catalogue of millions of fingerprints is required, and the official free-per-request route
is ShazamKit, which requires a paid Apple Developer Program membership.

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

## Paid engines (AudD, ACRCloud, …) — not integrated

* The brief forbids paid providers in the initial release and requires, before enabling one: an explicit
  flag, a documented cost/limit, quotas, a kill switch and user disclosure.
* Their API documentation (docs.audd.io, docs.acrcloud.com) was **blocked** in the build sandbox, so no
  adapter was written: the brief says not to fabricate APIs.
* The backend already provides everything an adapter needs: `ProviderAdapter` protocol
  (`backend/app/recognition/base.py`), registry (`registry.py`), routing with per-provider and per-user
  daily quotas, timeouts, kill switch (`RECOGNITION_KILL_SWITCH`), fallback/consensus modes and
  reconciliation with provenance (`router.py`, `reconcile.py`), strict WAV validation (`audio.py`), and an
  authenticated upload route. The app shows the server's disclosure text and asks for consent first.

### Adding a provider adapter

1. Read the provider's current API docs and terms; get a key; note the price and set a hard daily limit.
2. Implement `ProviderAdapter` (`id`, `name`, `paid`, `disclosure`, `daily_limit`, `async identify(clip)`)
   using `httpx.AsyncClient`, mapping "no result" to `None` and outages to `ProviderError`.
3. Add `<NAME>_ENABLED`, `<NAME>_API_KEY`, `<NAME>_DAILY_LIMIT` settings (default disabled, limit 0) and
   register the adapter in `build_providers()` only when enabled with a positive limit.
4. Write tests with recorded/mocked responses — **never live keys in automated tests**.
5. Enhanced mode becomes available automatically when ShazamKit token signing plus at least one server
   engine are usable (≥ 2 engines), accounts are enabled and the kill switch is off.

## MusicBrainz / Cover Art Archive (metadata enrichment)

* Used only to add album, release date and duration (by ISRC) and a fallback cover image. Optional,
  "Wi-Fi only" switch, cached per track, at most one request per second, User-Agent
  `NanoTune/0.1.0 ( https://github.com/domdzd/shabam )` (configurable via `nanotune.musicBrainzContact`).
* The brief states non-commercial API use is free and asks for ≤ 1 request/second with a meaningful
  User-Agent. The MusicBrainz documentation could **not be fetched** from the build sandbox; the client
  uses the long-standing `/ws/2/isrc/{isrc}?inc=releases&fmt=json` lookup and Cover Art Archive
  `/release/{mbid}/front-250` URLs, parsed tolerantly so a format change simply yields no enrichment.
  Verify on a device.
