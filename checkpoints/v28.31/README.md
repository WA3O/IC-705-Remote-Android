# IC-705 Remote Control v28.31 milestone

Saved: September 25, 2026.

## Confirmed working

WA3O reported "This worked great!" after testing the v28.31 software with the
integrated Connect/wake and Disconnect/standby behavior. This milestone preserves
the exact delivered source without further code changes.

## Changes from v28.30

- Connect sends the power-on command over the existing connection and waits up
  to 15 seconds for frequency readback before continuing normal operation.
- Disconnect checks the Power OFF Setting, requests standby when configured,
  and releases the connection. Automatic recovery is suppressed during this
  intentional disconnect.
- Conventional CI-V framing accepts six-byte acknowledgements, separates
  multiple frames and retains partial tails.
- The app reports standby as requested rather than claiming confirmed power
  state solely from an acknowledgement.

## Radio setup

Select SET > Function > Power OFF Setting (for Remote Control) > Standby/Shutdown.
Keep WLAN and Network Control enabled and the radio network-reachable in standby.
If the setting cannot be verified or is Shutdown only, Disconnect closes the
connection without sending power-off and displays the reason.

## Restore this version

Replace your Android project's MainActivity.kt with [MainActivity.kt](MainActivity.kt)
from this folder and rebuild. Use it as a replacement, not an additional file
defining the same class. The package is com.example.ic_705remote2.

The activity displays v28.31. If the existing Gradle configuration defines
versionName/versionCode, set versionName to 28.31 and increment versionCode.
Those build files are not included in this source milestone.

This is a source milestone, not a complete Android Studio project or an APK.
The user confirmed the delivered software works. No additional compilation or
physical-radio testing was performed while archiving it.

## Source integrity

- Original delivered filename: MainActivity.kt
- File size: 208973 bytes
- SHA-256: b3899ae7918b6b095fb7d69b0c1637923750e44d462ef911f4215b6fd79a553d
