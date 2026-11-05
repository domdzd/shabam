# Roadmap

What could come next for NanoTune, roughly in priority order. Items marked **owner** need accounts, keys
or devices only the owner has. Nothing here is promised; each item should be checked against the
brief's privacy and "no fabricated APIs" rules before it is built.

## Now: make recognition real (owner)

1. **Pick an engine and verify end to end.** The fastest route is AudD or ACRCloud on your server (the
   Releases APK then identifies songs without ShazamKit). For the long term, ShazamKit has no per-request
   fee once you have the Apple membership. Then run the manual plan in [TESTING.md](TESTING.md) on a real
   phone and record the results.
2. **First ShazamKit build.** The adapter has never been compiled against Apple's AAR; expect small fixes.
3. **Own release keystore** (GitHub secrets), so updates install over each other (see [RELEASE.md](RELEASE.md)).

## Done in 0.3

* The Identify button always records and keeps the song, even without an engine; tap to stop early.
* Recordings encrypted at rest (Android Keystore, AES-GCM).
* Manage recordings: seekable player, rename, trim (keep or identify a part), share several, save to
  device, multi-select delete, filters, identify all.
* Identify an audio or video file (imported as a recording).
* Quick Settings tile, home-screen widget and launcher shortcut.
* Password reset and email verification by emailed code (needs SMTP on the server).

## Next (0.4)

* **Translations**: move UI strings from Kotlin into `strings.xml`, then add languages. Not done in 0.3: it
  touches every screen and is best done in one dedicated pass.
* **UI tests in CI**: Compose UI tests on an emulator (GitHub-hosted Linux runners support hardware
  acceleration) for the identify, recordings and consent flows.
* **Long imported files**: try several 12-second windows of a song (not just the loudest one) before giving up.
* **Recording quality options**: keep 48 kHz originals for recordings the user wants to share, not only the
  16 kHz copies used for identification.

## Later

* **Free engine for your own music**: a self-hosted fingerprint index of songs you own, so they can be
  recognised without any paid service. Open-source landmark fingerprinting projects exist (e.g.
  audfprint, Dejavu, Olaf, Panako); their licences, maintenance status and microphone robustness still
  need checking. This can't replace a commercial catalogue for songs you don't have.
* **Session recordings** (opt-in): keep the audio of session windows that found nothing. This needs a
  stricter consent and storage design, because sessions run for a long time.
* **Bulk export of recordings** (zip) and history export including links to recordings.
* **Server scaling**: move rate limits from memory to the database or Redis so the API can run several
  workers, and add PostgreSQL docs.
* **Play Store readiness**: privacy-policy URL, Data safety answers (microphone, optional audio upload,
  optional account), foreground-service declaration, and an accessibility pass (TalkBack, large fonts).

## Not planned

* Unofficial or reverse-engineered Shazam endpoints: not authorised by Apple.
* AcoustID for microphone clips: it matches whole files, not snippets (see
  [RECOGNITION_PROVIDERS.md](RECOGNITION_PROVIDERS.md)). It could make sense for "identify an audio file".
* Scraping lyrics or streaming services. Lyrics only through a licensed provider.
* Analytics, ads or crash-reporting SDKs.
