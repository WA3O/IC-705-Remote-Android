# IC-705 Remote Control v28.30 milestone

Saved: September 25, 2026.

## Confirmed working

WA3O confirmed that the Settings layout fix works on the phone. This milestone preserves the exact v28.30 source delivered and tested in this task.

## Change from v28.29

- Settings starts directly below the MAIN and SETTINGS tabs.
- Switching tabs hides the entire inactive scroll container, removing the empty main-screen area above Settings.
- Settings is scrollable so the fields remain accessible when the keyboard opens.
- The app retains its existing keyboard resize setting.

## Restore this version

Replace the contents of your Android project's MainActivity.kt with the contents of the MainActivity.kt in this milestone folder, then rebuild. Use this as a replacement, not an additional Kotlin source file defining the same class.

This is a source milestone, not a complete Android Studio project or an APK. No additional build or radio testing was performed while archiving it.

## Source integrity

- Original delivered filename: IC705_Remote_Control_v28.30_MainActivity.kt
- File size: 197145 bytes
- SHA-256: c0b06a7edaccc2d739a8e1980c157043e56e3cad8388e9ce83d6221214516fc3
