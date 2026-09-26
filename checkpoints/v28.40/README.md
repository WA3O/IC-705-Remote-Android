IC-705 Remote Android v28.40

Replace the entire MainActivity.kt with MainActivity.txt or MainActivity.kt.
Both files contain the same complete UTF-8 source, including TxWire.
Package: com.example.ic_705remote2

AndroidManifest.xml is included for reference and retains RECORD_AUDIO and
INTERNET permissions. No manifest change is needed from the working v28.39.

Changes:
- Plain ASCII TX / STOP, WAIT / STOP, STOPPING, and waiting labels.
- Settings > WEB LINKS: four optional names and website addresses, saved
  automatically on this phone. Open launches the browser; leaving the app
  stops TX and returning does not automatically resume TX.
- Automatic stream/network recovery does not restart the yellow startup
  banner or open a connection dialog. Manual CONNECT retains startup feedback.
- A failed network reconnect restores CONNECT and gives a brief failure message.

Preserved: microphone/TX implementation, red TX toggle, 180-second TX limit,
power/microphone/squelch sliders, and network settings.

Validation: see build-check.txt. Live radio/phone testing remains to be done.
