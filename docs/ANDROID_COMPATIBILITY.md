# Android compatibility

## SDK levels

| Setting | Value | Why |
|---|---|---|
| `minSdk` | **26** (Android 8.0) | ShazamKit supports 21+, but current AndroidX/Compose libraries need 23+. 26 adds native notification channels, adaptive icons and `java.time` without desugaring, and still covers the vast majority of active devices. |
| `targetSdk` | **37** (Android 17) | Current stable platform. Reviewed Android 17 targeting changes (lock-free `MessageQueue`, immutable `static final` fields via reflection, widget bitmap limits, background-audio hardening): none affect NanoTune — sessions run in a while-in-use microphone foreground service. |
| `compileSdk` | **37** | Required by current AndroidX (core 1.19, Compose 1.12, Navigation 2.10) and Coil 3.6. |
| Build | AGP 9.5.0, Gradle 9.8.2, Kotlin 2.4.21 (AGP built-in Kotlin), JDK 17+ | Current stable versions on 2026-11-04. |

## Permissions

| Permission | Used for |
|---|---|
| `RECORD_AUDIO` (runtime) | Identify and listening sessions. Requested after an in-app explanation. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | The listening-session service (`foregroundServiceType="microphone"`). |
| `POST_NOTIFICATIONS` (runtime, Android 13+) | The ongoing session notification with Stop/Pause. Requested when starting a session; if refused the session still works and can be stopped in the app (Android then lists it only in the active-apps panel). |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Recognition, tokens, sync, metadata; Wi-Fi/mobile-data rules. |

## Foreground-service rules applied

* **Android 9+**: `FOREGROUND_SERVICE` declared.
* **Android 11+ (target 30+)**: microphone use from a foreground service requires the `microphone` service
  type — declared in the manifest and passed to `ServiceCompat.startForeground`.
* **Android 12+ (target 31+)**: foreground services can't be started from the background. NanoTune only
  starts the session service from the visible app (Session screen → Start), after the microphone permission
  is granted, so the while-in-use microphone capability applies.
* **Android 14+ (target 34+)**: type-specific permission `FOREGROUND_SERVICE_MICROPHONE` declared; the
  `RECORD_AUDIO` prerequisite is checked before starting.
* **Android 15+**: `BOOT_COMPLETED` may not start microphone services — irrelevant, NanoTune has no boot receiver.
* **Android 16+**: job quotas for jobs started from foreground services — NanoTune schedules no jobs.
* **Android 17**: background-audio hardening applies to apps *without* a while-in-use foreground service;
  sessions have one.

## Behaviour under platform limits

* `START_NOT_STICKY`: if Android kills the process, the session ends and is marked "not stopped by user"
  the next time the app starts. It is never restarted automatically.
* Revoking the microphone permission kills the process (Android behaviour); the session is closed on next
  launch. If the microphone can't be opened later, the session stops with an explanation.
* Microphone contention (calls, other recorders) is detected with
  `AudioRecordingConfiguration.isClientSilenced` (Android 10+) and shown as "microphone busy".
* Manual identification is cancelled when the app goes to the background, because Android delivers silence
  to background apps without a microphone foreground service.
* Audio capture prefers `MediaRecorder.AudioSource.UNPROCESSED` when the device reports support, else `MIC`;
  48 kHz → 44.1 kHz → 32 kHz → 16 kHz.
* Battery: continuous sessions keep the microphone and network active; interval mode (15 s windows every
  30 s – 5 min) reduces both. Sessions can pause automatically on mobile data.

## Tested devices

No physical device or emulator was available to the agent; nothing below has been run yet. Fill this in.

| Device | Android | Build | Identify | Session start/stop (app) | Stop from notification | Notes |
|---|---|---|---|---|---|---|
| _e.g. Pixel 8_ | _17_ | _0.1.0 build N_ | | | | |
| | | | | | | |

Automated coverage today: JVM unit tests and Robolectric (Room) tests in CI; see docs/TESTING.md.
