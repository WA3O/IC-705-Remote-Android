# IC-705 Remote Android — v28.45 milestone

The exact source and portable Android Studio project are in [project/](project/).
Open that folder as a project, select your Android SDK locally, sync and build.
The wrapper pins Gradle 9.6.0; the saved daemon criteria request Java 25 and
the version catalog pins AGP 9.4.1. Let Android Studio obtain required components.

Included: root/app Gradle scripts, settings, properties, complete `gradle/`
folder (including wrapper JAR), both wrapper launchers, manifest, resources,
source and tests. VersionName 28.45 / versionCode 2845.

Excluded: `.gradle`, `.idea`, `.kotlin`, build outputs, `local.properties`,
signing keys, device preferences and diagnostic captures. Android generates
a local debug key on the build machine. Use your own signing configuration
for distribution; keys are not part of a GitHub checkpoint.

The repository root retains its existing lightweight CI build (Gradle 8.9 / Java 17)
with the same v28.45 app source and manifest. For the exact desktop project,
use this checkpoint's project folder. Historical MainActivity alternatives
are archival, not additional source files to compile together.

See [changelog](CHANGELOG.md) and [feature guide](FEATURES.md). The latest debug
build passed. BLE hardware compatibility and a new live-radio test remain
unverified. No published APK release is created by this source milestone.
Connection addresses, credentials and web-link defaults remain blank.
