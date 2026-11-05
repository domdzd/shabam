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
* **By default, raw audio is processed in memory and never written to storage.** Buffers are overwritten
  with zeros after use. The only exception is *Saved recordings* (below), which you have to turn on yourself.
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
* **Your server's recognition engines** (off by default, see below): receive a short audio clip through
  your NanoTune server, only after you agreed to the engines it lists.

## Optional account and sync

* An account is **optional** and never needed to identify music. Registering does not change recognition,
  does not start listening, and does not upload recordings.
* When signed in with sync on, your **history, favourites and a few preferences** (theme, listening and
  duplicate settings, metadata options) are stored on the NanoTune server you configured, so you can
  restore them on another phone. Device-specific settings (server address, consent) are not synced.
* Passwords are stored as Argon2id hashes. Refresh tokens are stored as SHA-256 hashes, rotate on every use
  and are revoked if a stolen one is replayed. On the phone, tokens are encrypted with an Android Keystore key.
* The server does **not** store audio, fingerprints, IP-based listening logs or analytics.

## Your server's recognition engines (off by default)

A NanoTune server can offer engines such as **AudD** or **ACRCloud** (the owner adds their own keys; none
is enabled in the default configuration). The app sends audio to them only when **all** of these are true:
the server lists the engines, you turned on *Settings › Use my server's recognition engines* (or tapped
*Allow* on the Home screen) and accepted a disclosure that **names each engine**, and the list of engines is
still the one you accepted (if it changes, the app asks again). Signing in is required unless the server
owner allows anonymous use.

What is sent: one clip of **at most 12 seconds**, resampled to 16 kHz mono WAV, uploaded over HTTPS to
your server, which forwards it to the engines and returns the result. The server processes the clip in
memory and does not store it. When it happens:

* **Main engine**: when this build has no on-device engine (no ShazamKit), or ShazamKit isn't usable.
* **Identify later**: when you tap *Identify* on a saved recording and ShazamKit (if present) found nothing.
* **Fallback ("enhanced")**: signed in only, after the Shazam catalogue found no match, when at least two
  engines exist in total (ShazamKit counts as one). ShazamKit's results are never sent to other engines.

Each provider's own privacy policy applies to the clip it receives; paid providers bill the server owner.
Results name the engine that found them.

To enforce daily limits the server counts requests per day: per engine, per account, and for anonymous
use per install ID and per IP address. Anonymous counters store only a **keyed hash** of the install ID and
IP address (never the raw values) and are deleted after the day ends. Deleting your account deletes its
counters.

## Saved recordings (off by default)

* Only after you turn on **Settings › Recordings › Keep recordings** and confirm a dialog that explains what
  is stored. Turning it off stops saving new recordings; existing ones stay until deleted or expired.
* What is kept: the audio NanoTune recorded while identifying (at most the listen limit, 5–20 s), by default
  only when the song **wasn't identified** (no match, an error, or offline); optionally *every recording*.
  Listening sessions never save audio.
* Where: plain WAV files (16 kHz mono) in the app's **private storage on this phone**. Other apps can't read
  them; they are **excluded from cloud backup and device-to-device transfer**, and never synced.
* Recordings can contain **conversations or other sounds** near you. Share them with care.
* They leave the phone only when you tap **Share** (the system share sheet, to the app you choose) or
  identify one with your server's engines (see above). Identifying with ShazamKit stays on-device apart
  from the usual signature.
* Automatic deletion after 7, 30 or 90 days (default 30) or never, plus a 200 MB cap (oldest go first).
* **Offline capture**: with recordings on and *Identify automatically when back online* on, tapping Identify
  while offline records the clip and identifies it once the phone is online again, in the background,
  without using the microphone. A notification tells you what was found.

## Backups

Android cloud backup is disabled (`allowBackup=false`, data-extraction rules exclude everything from cloud
backup). Device-to-device transfer excludes tokens, the install ID and saved recordings.

## Deletion

* **History › ⋮ › Delete** removes a single song; **Clear all history** removes all of them (from the cloud too
  when sync is on).
* **History › Recordings** deletes single recordings; **Delete all** (there or in Settings › Recordings)
  removes every saved recording.
* **Settings › Delete local data** erases history, favourites, settings, saved recordings, cached tokens and
  images on the phone and signs out. It does not delete your cloud account.
* **Account › Delete account and cloud data** permanently deletes the account and everything stored for it
  on the server (password required). Local history stays on the phone.
* Server operators can delete everything by removing the `nanotune-data` volume.

## No tracking

No advertising, analytics or crash-reporting SDKs are included.
