# NanoTune privacy notes

This describes what the app and the optional NanoTune server do with data. The in-app
**Settings › Privacy** screen carries the same information in short form.

## Microphone and audio

* The microphone is used **only to identify music**.
* **Identify (tap)**: recording starts only after you tap the button and stops at the first match, at the
  time limit (5–20 s, default 12 s), when you tap again, or as soon as the app leaves the screen.
* **Listening session**: the microphone is used only after you explicitly start a session, and only while
  its ongoing notification ("NanoTune is listening (microphone on)") is visible. Pause and Stop are
  always available in the notification and the app. Sessions never start on their own, never restart after
  you stop them, and are not started at boot. In interval mode the microphone is closed between windows.
* **Raw audio is processed in memory and never written to storage.** Buffers are overwritten with zeros
  after use.
* With the standard engine (Apple ShazamKit) the audio is converted **on the phone** into a compact,
  non-reversible signature; only that signature is sent to Apple's Shazam service. Your NanoTune server
  never receives audio for standard recognition.
* Android shows its own microphone indicator whenever the microphone is in use.

## Network access

* **Shazam catalogue (Apple)**: required for recognition; receives the audio signature and a developer
  token. Apple's privacy policy applies.
* **Your NanoTune server**: issues short-lived ShazamKit developer tokens (anonymous). Token requests carry
  a random per-install ID used only for fair rate limiting; it is not linked to an account and is reset by
  "Delete local data".
* **MusicBrainz / Cover Art Archive** (optional, on by default, can be limited to Wi-Fi or turned off):
  receives the ISRC code of a song that was already recognised, to look up album and release date.
* **Artwork hosts**: cover images are loaded from the URLs the engine returns.

## Optional account and sync

* An account is **optional** and never needed to identify music. Registering does not change recognition,
  does not start listening, and does not upload recordings.
* When signed in with sync on, your **history, favourites and a few preferences** (theme, listening and
  duplicate settings, metadata options) are stored on the NanoTune server you configured, so you can
  restore them on another phone. Device-specific settings (server address, consent) are not synced.
* Passwords are stored as Argon2id hashes. Refresh tokens are stored as SHA-256 hashes, rotate on every use
  and are revoked if a stolen one is replayed. On the phone, tokens are encrypted with an Android Keystore key.
* The server does **not** store audio, fingerprints, IP-based listening logs or analytics.

## Enhanced recognition (off by default)

Only when **all** of these are true: you are signed in, your server advertises additional engines, you
turned the feature on, and you accepted the disclosure. Then, if Shazam finds no match, the clip it just
heard (at most 12 s, resampled to 16 kHz mono) is uploaded to your server, which forwards it to the listed
engines and returns the result. The clip is processed in memory and not stored. Results show which engine
found them and whether engines disagreed. No such engine is enabled in the default server configuration.

## Backups

Android cloud backup is disabled (`allowBackup=false`, data-extraction rules exclude everything from cloud
backup). Device-to-device transfer excludes tokens and the install ID.

## Deletion

* **History › ⋮ › Delete** removes a single song; **Clear all history** removes all of them (from the cloud too
  when sync is on).
* **Settings › Delete local data** erases history, favourites, settings, cached tokens and images on the
  phone and signs out. It does not delete your cloud account.
* **Account › Delete account and cloud data** permanently deletes the account and everything stored for it
  on the server (password required). Local history stays on the phone.
* Server operators can delete everything by removing the `nanotune-data` volume.

## No tracking

No advertising, analytics or crash-reporting SDKs are included.
