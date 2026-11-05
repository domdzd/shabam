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

## Next (0.3)

* **Identify an audio file**: pick a file with the system picker, decode it on the phone (MediaExtractor +
  MediaCodec), resample to 16 kHz and run it through the same path as saved recordings. Shares the
  recordings code; useful for voice notes and videos.
* **Choose the part to identify**: a simple trim bar on a saved recording, so a 20 s clip with talking at
  the start can still match.
* **Encrypt saved recordings at rest** with the app's existing Android Keystore key (AES-GCM), decrypting
  only to play, share or identify.
* **Quick Settings tile / home-screen widget** for one-tap identify. Check Android 14+ rules for starting
  microphone use from a tile before building it.
* **Translations**: move UI strings from Kotlin into `strings.xml`, then add languages.
* **UI tests in CI**: Compose UI tests on an emulator (GitHub-hosted Linux runners support hardware
  acceleration) for the identify, recordings and consent flows.
* **Email verification and password reset** (SMTP), which are still unimplemented.

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
