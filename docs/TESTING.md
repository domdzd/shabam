# Testing

## Automated tests

| Suite | Command | Where it runs | Latest result |
|---|---|---|---|
| Backend (pytest, 52 tests) | `cd backend && pip install -r requirements-dev.txt && python -m pytest` | Locally and in `backend.yml` | **52 passed** (local, Python 3.13; CI green) |
| Backend Docker smoke test | builds the image, runs it read-only, checks health/config/token 503/register | `backend.yml` | **Passed** |
| Android unit tests (JUnit + Robolectric) | `cd android && ./gradlew testDebugUnitTest` | `android.yml` (count printed in the "Unit test summary" step) | **Passed** |
| Android lint | `./gradlew lintDebug` | `android.yml` | **0 errors** (remaining warnings are intentional dependency pins and debug-only network config) |

### What the backend tests cover
Health; capabilities; ShazamKit token = ES256 JWT with `kid`/`iss`/`iat`/`exp` verified against the signing
key, server-side caching and half-life re-signing, TTL bounds (Apple's 6-month limit), missing/invalid key
(503, no leaks); anonymous rate limits per install and per IP; registration/login/refresh/logout; Argon2id
hashes; generic auth errors; refresh rotation and reuse detection; expired tokens; forged access tokens;
login/registration rate limits; closed registration; accounts disabled without a secret; account deletion
removes all cloud data; sync round-trip, last-writer-wins, tombstones, account isolation, pagination without
gaps, batch and quota limits, field validation; enhanced routing: availability rules (needs ≥ 2 engines),
kill switch, auth, fallback vs. consensus, agreement/conflict reporting, provider errors/timeouts/no-match,
per-provider and per-user daily limits, WAV validation; concurrent pushes get unique, ordered sequence numbers; migrations create the schema. Providers in these tests
are in-process fakes — no live API is ever called.

### What the Android tests cover
`DuplicateFilter` (sliding cooldown, clock jumps, interval mode), `TrackKeys`, history grouping by day/session,
CSV escaping/formula-injection guard and JSON export, WAV header, resampler pass-band/alias rejection, level
meter, `IdentifySession` stop rules (first match, no-match, transient errors, single token refresh),
`TokenPolicy`, sync merge rules, and Room-backed `HistoryRepository` (track de-duplication and metadata merge,
search with literal `%`/`_`, favourites, date filters, deletion with and without sync, session counts and
dangling-session repair, LWW application of remote changes).

## Manual test plan (needs a device, the ShazamKit AAR and a configured server)

None of these could be run by the agent (no device, no AAR, no Apple key). Record outcomes here.

### Recognition
| Scenario | Expected | Result |
|---|---|---|
| Clean music from a speaker, 1–2 m | Match within a few seconds; correct title/artist/artwork | not run |
| Quiet music in a room | Match or honest "No match" | not run |
| Noisy environment (café, street) | Match or "No match", never a made-up song | not run |
| TV / laptop speaker | Match | not run |
| Live version / remix | Often "No match"; must not show a wrong studio match as certain | not run |
| Speech only / silence | "No match" after the time limit | not run |
| Airplane mode | "Can't reach the NanoTune server…" before recording | not run |
| Server down | Same, no recording | not run |
| Server without Apple key | "Your NanoTune server has no Apple ShazamKit key configured yet." | not run |
| Expired/invalid token (revoke key in Apple portal) | One automatic token refresh, then a clear error | not run |
| Server rate limit (set `TOKEN_RATE_PER_INSTALL_PER_HOUR=1`) | Clear "rate limiting" message | not run |

### App
| Scenario | Expected | Result |
|---|---|---|
| First launch, allow microphone | Explanation dialog → system prompt → identify starts | not run |
| First launch, deny microphone (twice) | Explanation; then "Open settings" | not run |
| Tap twice quickly | Only one attempt (second tap cancels) | not run |
| Leave the app while identifying | Listening stops; message shown | not run |
| Favourite / unfavourite (card, history, details) | State consistent everywhere | not run |
| Kill app, reopen | History persists | not run |
| Export CSV/JSON | File opens in a spreadsheet / text viewer | not run |
| Session start/pause/resume/stop from app | Notification updates; mic indicator matches | not run |
| Stop from notification | Session ends, notification disappears | not run |
| Same song for 10 min in a session | Stored once | not run |
| Revoke mic permission during a session | Process killed by Android; session closed on next launch | not run |
| Phone call during a session | "Microphone busy", resumes after the call | not run |
| Rotation, background/foreground | No crash, state kept | not run |
| Battery saver / Data saver | Session keeps notification; interval mode works | not run |

### Accounts & sync
| Scenario | Expected | Result |
|---|---|---|
| Register on phone A, identify songs, register-less use still works | Works without account; with account history uploads | not run |
| Sign in on phone B | History and favourites appear | not run |
| Delete a song on A | Disappears on B after sync | not run |
| Delete account | Cloud data gone; local history kept | not run |
