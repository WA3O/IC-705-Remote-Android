# Changelog

Development history through **v28.40**, released September 25, 2026. v28.39 and v28.40 extend v28.37; the separate v28.38 marker-spacing experiment remains excluded.

## v28.40 — Current source release

- Replaced garbled TX labels with ASCII: TX / STOP, WAIT / STOP, STOPPING, and Power out: waiting...; connection/startup labels also use ASCII.
- Added WEB LINKS as a third Settings tab alongside IP / PASSWORD and RADIO SETTINGS.
- Added four website addresses, optional names, automatic local saving, and individual Open buttons. Addresses without a scheme use HTTPS; invalid addresses show an inline error.
- Opening a website uses the browser. Leaving the app stops TX; returning never resumes TX automatically.
- Automatic network recovery no longer restarts the yellow startup banner or opens a connection dialog. Manual CONNECT retains elapsed-time and audio/waterfall startup feedback.
- Failed automatic reconnection restores CONNECT and displays a brief connection-lost message. Existing audio-only recovery and diagnostic logging remain.
- Preserved the working microphone/TX implementation, red TX toggle, 180-second automatic TX stop, sliders, LAN/WAN settings and v28.37 display behavior.
- Android debug build passed; all five existing unit tests passed (four TX protocol tests and one existing project test). Live radio recovery was not tested during preparation.

## v28.39 — Microphone TX and radio controls

- Added a main-panel TX toggle beside CONNECT/DISCONNECT, red while selected, with radio TX-status confirmation and a second tap to request RX.
- Added Android microphone permission and 48 kHz mono PCM16 microphone streaming over WLAN. Temporarily selects WLAN modulation for DATA and non-DATA modes and restores the original radio routing after TX.
- Added a 180-second TX limit, plus stop requests on app backgrounding, disconnect, microphone failure, recovery and loss of TX status. TX never automatically resumes.
- Added RADIO SETTINGS with requested power from 0 to 10 W in 0.1 W steps, microphone audio from 0 to 100%, and squelch from 0 to 100%. Zero power inhibits app TX; zero microphone level mutes outgoing audio.
- Saves slider values locally; applies power and squelch with radio readback. Power and squelch are disabled during TX; microphone level remains adjustable.
- Mutes phone receive playback during TX and changes the S-meter to the radio's relative power-output percentage. This is not a calibrated watts measurement.
- Supports phone voice TX in LSB, USB, AM and FM. Temporarily sets WLAN MOD level to 50% and restores it after TX.
- User confirmed that the microphone fix works. Android compilation and TX protocol tests passed during development.

## v28.38 — Unreleased follow-up

- Marker spacing follows the selected display span: 10 kHz below 50 kHz; 50 kHz from 50 to under 100 kHz; 100 kHz for spans of 100 kHz or more.
- Removed the fixed ten-division vertical background grid to avoid conflicting with frequency markers.
- Retained the green tuned-frequency marker and v28.37 font size.

## v28.37 — Previous source release

- Reduced spectrum/waterfall frequency-label size by 50%, from 25.2 to 12.6 pixels.
- Retained all changes through v28.36. Frequency markers remain spaced every 10 kHz in this release; overlapping labels may be omitted.

## v28.36

- Reduced frequency-label size by 30%, from 36 to 25.2 pixels.

## v28.35

- Doubled frequency-label size from 18 to 36 pixels (subsequently adjusted in v28.36 and v28.37).
- Thickened vertical spectrum/waterfall grid lines from 1 to 2 pixels.
- Retained horizontal line widths and the green tuned-frequency marker width.

## v28.34

- Added a startup indicator with elapsed time and separate audio/waterfall waiting or received status. It explains that standby wake-up can take about ten seconds, keeps waiting beyond that time, and reports ready only after both streams arrive.
- Added horizontal swipe tuning: right increases frequency by 1 kHz, left decreases it by 1 kHz, once per completed swipe.
- Retained tap-to-tune; vertical drags, cancelled gestures and multi-touch do not tune. Existing tune-in-progress protection remains.
- Added absolute-frequency marks every 10 kHz, labeled in MHz, with overlap suppression on wider spans.
- Drew frequency markings and the green tuned marker over waterfall pixels to keep them visible.

## v28.33

- Added Disconnect / Standby choices: Disconnect only (leave radio power unchanged), Standby & disconnect, and Cancel.
- Both disconnect choices suppress automatic reconnection; only the standby choice requests power-off.
- Retained LAN/WAN selection and Connect/wake behavior. User reported the modification worked.

## v28.32

- Added separate LAN and WAN IP fields and a Use WAN checkbox (unchecked selects LAN).
- Saved both addresses and the selection; retained the previously saved IP as LAN on upgrade.
- Kept the same radio credentials for either address. Manual Connect uses the selection; automatic recovery keeps the active session's address.
- Added a visible message when the selected address is empty, without silently falling back to the other address.

## v28.31 — Confirmed milestone

- Integrated the Power Test's wake and standby commands into the existing remote-control session without opening a second session.
- Connect sends CI-V power-on before the initial frequency read and allows up to 15 seconds for readback.
- Disconnect checks the Power OFF Setting and requests standby only when Standby/Shutdown is selected, then releases the session.
- Reports standby as requested rather than inferring confirmation from a generic acknowledgement or missing reply.
- Suppresses automatic recovery during intentional disconnect; ordinary error cleanup does not request standby.
- Updated conventional CI-V framing to accept six-byte acknowledgements, split multiple frames and retain fragmented tails. Observes power-setting replies before raw spectrum assembly.
- User confirmed the software worked; preserved the exact source as a GitHub milestone with restore instructions and a checksum.

## v28.30 — Earlier confirmed milestone

- Settings fields begin directly below MAIN / SETTINGS tabs.
- Switching tabs hides the inactive scroll container, removing blank space.
- Settings scrolls when the keyboard opens and retains keyboard resize behavior.

## Earlier release context

- v28.29: compact frequency entry and SET button, network-status light/text, and title spacing refinements; retained waterfall-color, S-meter and 17m refinements.
- v28.20: wrapped scope start/stop/mode command bodies in complete CI-V frames for the serial C1 transport, retaining existing complete bandwidth frames.

## Installation and validation

This is a complete Kotlin source release, not a full Android Studio project or installable APK. Replace the entire existing MainActivity.kt with the supplied .kt or identical .txt; do not add a duplicate class. Package: com.example.ic_705remote2. Retain INTERNET and add RECORD_AUDIO permission plus the optional microphone feature shown in the included basic AndroidManifest.xml. Preserve your project's other manifest settings. If used, set Gradle versionName to 28.40 and versionCode to 2840.

The delivered v28.40 source compiled successfully and all five existing unit tests passed. User feedback confirms microphone operation; live radio recovery and the physical TX timeout have not been verified in this publication task. Actual RF output depends on radio mode, supply and hardware limits. RX and routing restoration commands require a reachable radio.

For wake/standby, select SET > Function > Power OFF Setting (for Remote Control) > Standby/Shutdown and keep WLAN/Network Control enabled. LAN/WAN selection does not configure a router or VPN.
