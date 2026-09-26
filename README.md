# IC-705 Remote Android

## Current checkpoint: v28.41

[Complete v28.41 project and source](checkpoints/v28.41/) | [Restore instructions](checkpoints/v28.41/README.md) | [Complete feature guide](FEATURES.md) | [Full changelog](CHANGELOG.md)

v28.41 renews authentication every minute to address the observed roughly 92-second audio/spectrum interruption. The live test recorded 17 successful renewals over 17 minutes 18 seconds, no recovery events, and no audible breakups reported by the user.

Includes receive audio, spectrum/waterfall, frequency and band controls, microphone TX with a 180-second limit, power/microphone/squelch sliders, LAN/WAN settings and four locally saved web links. Fresh-install connection fields and links are blank. The separate v28.38 marker-spacing experiment is not included.

This checkpoint preserves the complete current project/source and excludes private keys and machine-specific files. See restore instructions for local SDK and signing configuration. It contains no installable APK. Earlier published releases remain under [Releases](https://github.com/WA3O/IC-705-Remote-Android/releases).

Historical source alternatives outside the checkpoint project are archival and must not be compiled together as duplicate classes.
