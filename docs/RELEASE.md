# Building, signing and releasing

## CI builds (GitHub Actions)

`.github/workflows/android.yml` runs on every push that touches `android/`:
`testDebugUnitTest assembleDebug assembleRelease lintDebug`, prints a unit-test and lint summary, and uploads
the APKs as the `nanotune-apks` artifact. A GitHub **pre-release** with the APKs is published when:

* a tag `v*` is pushed (`git tag v0.1.1 && git push origin v0.1.1`), or
* the workflow is run manually (Actions › Android › Run workflow › *Publish a GitHub pre-release*), or
* a pushed commit message contains `[release]`.

`backend.yml` runs the backend tests and builds/smoke-tests the Docker image.

### Optional repository secrets / variables

| Name | Kind | Purpose |
|---|---|---|
| `NANOTUNE_KEYSTORE_BASE64` | secret | Base64 of your release keystore (`base64 -w0 release.jks`). |
| `NANOTUNE_KEYSTORE_PASSWORD`, `NANOTUNE_KEY_ALIAS`, `NANOTUNE_KEY_PASSWORD` | secrets | Keystore credentials. |
| `SHAZAMKIT_AAR_URL` | secret | A **private** HTTPS URL from which CI can download Apple's AAR (e.g. a pre-signed object-storage link). Never commit the AAR to this public repo. |
| `SHAZAMKIT_AAR_SHA256` | secret | SHA-256 of that AAR, verified after download. |
| `NANOTUNE_API_BASE_URL` | variable | Default server URL compiled into the app (users can still change it in Settings). |

Without a keystore secret, CI signs the release APK with a fallback key cached between runs. Android only
installs an update over an existing install if the signature matches, so set up your own release key before
sharing the app widely (if the cache expires you'll have to uninstall/reinstall).

### Create a release key

```bash
keytool -genkeypair -v -keystore nanotune-release.jks -alias nanotune \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 nanotune-release.jks   # → NANOTUNE_KEYSTORE_BASE64
```

Keep the keystore and passwords somewhere safe and offline; losing them means you can't update installs.

## Local build (Android Studio or command line)

Requirements: JDK 17+ (21 recommended), Android SDK with platform 37.

```bash
cd android
# optional: put Apple's AAR in place to build WITH recognition
cp ~/Downloads/shazamkit-android-release.aar app/libs/
./gradlew testDebugUnitTest assembleDebug
./gradlew assembleRelease \
  -Pnanotune.apiBaseUrl=https://api.example.com
# signed release: export NANOTUNE_KEYSTORE_FILE=... NANOTUNE_KEYSTORE_PASSWORD=... NANOTUNE_KEY_ALIAS=... NANOTUNE_KEY_PASSWORD=...
```

Gradle prints `NanoTune: building WITH ShazamKit` when it finds the AAR. Outputs:
`app/build/outputs/apk/release/app-release.apk` and `app/build/outputs/apk/debug/app-debug.apk`
(the debug build installs side by side as `io.github.domdzd.nanotune.debug` and may talk to plain-http test
servers such as `http://10.0.2.2:8000`; release builds are HTTPS-only).

Rebranding: change `app_name` in `res/values/strings.xml`, `nanotune.applicationId` in
`gradle.properties`, colours in `ui/theme/Theme.kt` and the launcher vector in `res/drawable/`.

## Installing (sideloading)

1. Download `NanoTune-<version>-buildN.apk` from the GitHub release on your phone.
2. Open it; allow "Install unknown apps" for your browser/files app when Android asks.
3. Open NanoTune → Settings → NanoTune server → enter your server URL.
4. Optional: verify the file against `SHA256SUMS.txt` from the release.

## Google Play (optional, later)

Not required. If you publish: use your own release key (Play App Signing), keep `targetSdk` current, fill the
Data safety form from docs/PRIVACY.md (microphone audio processed on device, signatures to Apple, optional
account data), declare the microphone foreground-service use, and review Apple's ShazamKit terms
(docs/RECOGNITION_PROVIDERS.md) — Play's policies require that you are allowed to ship the Apple SDK.
