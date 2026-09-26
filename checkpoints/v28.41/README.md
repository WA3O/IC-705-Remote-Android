# IC-705 Remote Android v28.41 checkpoint

This checkpoint preserves the exact current source, with no additional code edits during publication. It includes the authentication-renewal fix and the already-applied blank connection defaults/disabled backup setting.

## Files and restoration

- MainActivity.kt and MainActivity.txt: identical complete replacement source, including helpers.
- AndroidManifest.xml: current manifest with Internet/microphone permissions and backup disabled.
- project/: complete project source, resources, Gradle scripts/wrapper and unit tests.
- FEATURES.md: complete feature explanation.
- CHANGELOG.md: cumulative development history, including the excluded v28.38 experiment.
- checkpoint-manifest.json: project file hashes and source SHA-256.
- live-test-result.txt: observed radio test results.

Open project/ in Android Studio, configure your local Android SDK, and use your own signing key. Machine-specific local.properties, build caches/output, IDE state and all private keystores are deliberately excluded. The unchanged Gradle script references a local debug.keystore; supply your development keystore at the project root or configure your local signing settings. This checkpoint does not include an installable APK.

For an existing project, replace its entire MainActivity.kt with the supplied source; do not compile multiple copies. Package: com.example.ic_705remote2. Version name/code: 28.41/2841.

Fresh installations have blank LAN/WAN addresses, username, password and website entries. Existing local settings may persist on a compatible update. No phone preferences or raw diagnostic logs are included.

## Verification

Release build and eight unit tests passed. The same authentication-renewal implementation ran for 17 minutes 18 seconds with 17 acknowledgements, no timeouts or audio/network recovery events, and no reported breakups during the listening test. This supports the fix for the previously recurring roughly 92-second interruption; it does not eliminate unrelated network loss.

Source SHA-256: dea0b81e55ac1665612ed58ea7edfda3794c317974d9fa31dfdc3fce3511527c
