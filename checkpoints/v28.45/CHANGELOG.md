# Changelog

Development history through **v28.45**, checkpoint saved September 26, 2026. Versions v28.39-v28.41 extend v28.37; the separate v28.38 marker-spacing experiment remains excluded.

## v28.45 — Milestone: keyboard frequency entry

- Android keyboard check mark / Done submits MHz frequency and closes the keyboard. Physical Enter submits on release. Removed SET button.
- Invalid input stays editable. Submission is blocked during TX, disconnect and an active tuning request.
- Complete unmodified Android Studio source/project archived in `checkpoints/v28.45/project`, including Gradle wrapper, version catalog, resources and tests. Version 28.45 / code 2845.
- Debug APK build passed locally. Frequency conversion tests passed during v28.44; v28.42's 15 tests and lint passed. No new live radio/BLE validation is claimed for this milestone.

## v28.44 — MHz entry

- Bright-green top field uses six decimal places, e.g. `12.200000`, with a MHz label. Decimal input converts exactly to whole Hz; dot or comma accepted.
- Removed duplicate frequency display below the S-meter. Band buttons, steps and radio readbacks update the top MHz field.

## v28.43 — Frequency/control layout

- Larger bold bright-green frequency and a larger SET button (subsequently removed in v28.45).
- Connection light at far right; compact GOOD / FAIR / BAD labels, with a dash while disconnected/connecting.

## v28.42 — TX display, Functions and button PTT

- Large TX toggle beneath the top bar, visible on both main tabs; countdown shares the app's 180-second TX deadline.
- Functions tab: AGC Fast/Medium/Slow, AN/NB/NR/COMP on/off, FL1/FL2/FL3. Highlights reflect confirmed radio readback; filter changes preserve mode/DATA.
- Volume up/down or learned Android button events toggle TX/RX once per press; repeats do not toggle again. Foreground-only operation.
- Experimental PRYME/Zello-style BLE discovery and FFE0/FFE1 notification support, test-before-enable, disconnect unkey. Compatibility with the user's PTT-Z Mini is not yet verified; unknown services/payloads do not transmit.
- Preserved v28.41 authentication renewal and receive-audio implementation.

## v28.41 - Authentication renewal and complete project checkpoint

- Renew authenticated radio control sessions every 60 seconds using AUTH 0x05, in addition to the existing PKT0/PKT7 keepalives. Audio and serial streams remain open during renewal.
- Match renewal acknowledgements by command, sequence, session identifiers and authentication identifier. Log send, acknowledgement latency and missing acknowledgements after three seconds. A missing acknowledgement alone does not force a reconnect.
- Address the previously observed approximately 92-second audio/spectrum interruption and full reconnect cycle. A live v28.41 run lasted 17 minutes 18 seconds with 17 renewal acknowledgements, no renewal timeouts, no audio/network recovery events and no logged playback errors. The user reported no audible breakups during the 15-minute listening test. This supports the fix for that recurring fault; it does not guarantee immunity to unrelated network loss.
- Keep receive buffering, microphone TX, TX timeout, sliders, web links and existing recovery behavior unchanged.
- Preserve the complete Android project and source as a v28.41 checkpoint: minimum Android 8.0/API 26, versionName 28.41, versionCode 2841. No signing keys are published.
- Remove the default LAN address and username. LAN/WAN addresses, username, password, website names and website addresses start blank on a fresh installation. No phone preferences, diagnostic logs or personal configuration are bundled.
- Disable Android application backup in the release manifest. Existing settings can remain during a compatible in-place update; blank defaults do not erase user data.
- Include a complete feature/installation guide, Kotlin source, identical copy-and-paste text, manifest and checksums.
- Verification: successful release build and eight passing unit tests. The earlier live test used the same renewal implementation, before blank-default and backup-only packaging changes.

## v28.40 â€” Previous source release

- Replaced garbled TX labels with ASCII: TX / STOP, WAIT / STOP, STOPPING, and Power out: waiting...; connection/startup labels also use ASCII.
- Added WEB LINKS as a third Settings tab alongside IP / PASSWORD and RADIO SETTINGS.
- Added four website addresses, optional names, automatic local saving, and individual Open buttons. Addresses without a scheme use HTTPS; invalid addresses show an inline error.
- Opening a website uses the browser. Leaving the app stops TX; returning never resumes TX automatically.
- Automatic network recovery no longer restarts the yellow startup banner or opens a connection dialog. Manual CONNECT retains elapsed-time and audio/waterfall startup feedback.
- Failed automatic reconnection restores CONNECT and displays a brief connection-lost message. Existing audio-only recovery and diagnostic logging remain.
- Preserved the working microphone/TX implementation, red TX toggle, 180-second automatic TX stop, sliders, LAN/WAN settings and v28.37 display behavior.
- Android debug build passed; all five existing unit tests passed (four TX protocol tests and one existing project test). Live radio recovery was not tested during preparation.

## v28.39 â€” Microphone TX and radio controls

- Added a main-panel TX toggle beside CONNECT/DISCONNECT, red while selected, with radio TX-status confirmation and a second tap to request RX.
- Added Android microphone permission and 48 kHz mono PCM16 microphone streaming over WLAN. Temporarily selects WLAN modulation for DATA and non-DATA modes and restores the original radio routing after TX.
- Added a 180-second TX limit, plus stop requests on app backgrounding, disconnect, microphone failure, recovery and loss of TX status. TX never automatically resumes.
- Added RADIO SETTINGS with requested power from 0 to 10 W in 0.1 W steps, microphone audio from 0 to 100%, and squelch from 0 to 100%. Zero power inhibits app TX; zero microphone level mutes outgoing audio.
- Saves slider values locally; applies power and squelch with radio readback. Power and squelch are disabled during TX; microphone level remains adjustable.
- Mutes phone receive playback during TX and changes the S-meter to the radio's relative power-output percentage. This is not a calibrated watts measurement.
- Supports phone voice TX in LSB, USB, AM and FM. Temporarily sets WLAN MOD level to 50% and restores it after TX.
- User confirmed that the microphone fix works. Android compilation and TX protocol tests passed during development.

## v28.38 â€” Unreleased follow-up

- Marker spacing follows the selected display span: 10 kHz below 50 kHz; 50 kHz from 50 to under 100 kHz; 100 kHz for spans of 100 kHz or more.
- Removed the fixed ten-division vertical background grid to avoid conflicting with frequency markers.
- Retained the green tuned-frequency marker and v28.37 font size.

## v28.37 â€” Previous source release

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

## v28.31 â€” Confirmed milestone

- Integrated the Power Test's wake and standby commands into the existing remote-control session without opening a second session.
- Connect sends CI-V power-on before the initial frequency read and allows up to 15 seconds for readback.
- Disconnect checks the Power OFF Setting and requests standby only when Standby/Shutdown is selected, then releases the session.
- Reports standby as requested rather than inferring confirmation from a generic acknowledgement or missing reply.
- Suppresses automatic recovery during intentional disconnect; ordinary error cleanup does not request standby.
- Updated conventional CI-V framing to accept six-byte acknowledgements, split multiple frames and retain fragmented tails. Observes power-setting replies before raw spectrum assembly.
- User confirmed the software worked; preserved the exact source as a GitHub milestone with restore instructions and a checksum.

## v28.30 â€” Earlier confirmed milestone

- Settings fields begin directly below MAIN / SETTINGS tabs.
- Switching tabs hides the inactive scroll container, removing blank space.
- Settings scrolls when the keyboard opens and retains keyboard resize behavior.

## Earlier release context

- v28.29: compact frequency entry and SET button, network-status light/text, and title spacing refinements; retained waterfall-color, S-meter and 17m refinements.
- v28.20: wrapped scope start/stop/mode command bodies in complete CI-V frames for the serial C1 transport, retaining existing complete bandwidth frames.

## Current installation and validation

See FEATURES.md for the complete feature guide and checkpoints/v28.41/README.md for restoring the project/source. Earlier validation notes above describe those historical versions. The separate v28.38 display experiment is not included in v28.41.
