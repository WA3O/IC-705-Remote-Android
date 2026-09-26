# IC-705 Remote Android

## Current milestone: v28.45

[Complete project and restore instructions](checkpoints/v28.45/) | [Feature guide](FEATURES.md) | [Changelog](CHANGELOG.md)

Enter MHz in the bright-green top field, e.g. **12.200000 MHz**, and tap the
keyboard check mark / Done to tune. Includes the large TX control and 180-second
countdown, Functions controls, phone-button PTT and experimental BLE button support.
The v28.41 authentication-renewal/audio fix is preserved.

The milestone contains the complete desktop project, including all Gradle build
files and wrapper, without private keys, machine-specific settings or caches.
Fresh-install connection credentials, addresses and web links are blank.
BLE support needs physical-device verification; details are in the feature guide.

The root project is the lightweight GitHub Actions build. The exact Android Studio
project is under `checkpoints/v28.45/project`. Earlier checkpoints are preserved.
[Earlier releases](https://github.com/WA3O/IC-705-Remote-Android/releases).
