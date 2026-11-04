# Local libraries

Place Apple's **ShazamKit Android SDK** here as `shazamkit-android-release.aar`
(or point `-Pnanotune.shazamkitAar=/path/to/file.aar` at it).

* Download it from <https://developer.apple.com/download/all/?q=Android%20ShazamKit>
  (requires an Apple Account sign-in; the Shazam catalogue additionally requires an Apple
  Developer Program membership for the Media ID and private key).
* The file is ignored by Git (`.gitignore`) because it is licensed by Apple and must not be
  redistributed from this public repository.
* When present, Gradle compiles `src/shazamkit/kotlin` and adds the AAR plus the dependencies
  Apple documents for it. Without it the app builds with a stub that reports
  "ShazamKit not included in this build".
