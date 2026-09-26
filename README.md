# IC-705-Remote-Android

## Latest source release: v28.40

[Download v28.40](https://github.com/WA3O/IC-705-Remote-Android/releases/tag/v28.40) | [Complete source](checkpoints/v28.40/MainActivity.kt) | [Copy-and-paste text](checkpoints/v28.40/MainActivity.txt) | [Full changelog](CHANGELOG.md)

Includes microphone TX, a red TX toggle with a 180-second limit, power/microphone/squelch sliders, four saved web links, ASCII labels, and quiet automatic stream recovery. Retains LAN/WAN selection, wake/standby controls, swipe tuning and v28.37 display behavior.

This is a source release, not an APK or full Android Studio project. Replace your existing MainActivity.kt; keep RECORD_AUDIO and INTERNET permissions. See the [release instructions](checkpoints/v28.40/README.md) and included basic manifest. Historical source files are archival alternatives, not additional classes to compile together.

The Android debug build and all five existing unit tests passed. The user confirmed microphone operation; live radio recovery remains untested. The separate v28.38 marker-spacing experiment is documented in the changelog and is not included.

Earlier releases and checkpoints remain available.
