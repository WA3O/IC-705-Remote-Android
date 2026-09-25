# Changelog

This log covers the changes developed in this task, with earlier release context from GitHub. The selected release is **v28.37**. The later v28.38 change below is documented but is **not included** in v28.37.

## v28.38 — Unreleased follow-up

- Marker spacing follows the selected display span: 10 kHz below 50 kHz; 50 kHz from 50 to under 100 kHz; 100 kHz for spans of 100 kHz or more.
- Removed the fixed ten-division vertical background grid to avoid conflicting with frequency markers.
- Retained the green tuned-frequency marker and v28.37 font size.

## v28.37 — Selected source release

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

This is a Kotlin source release, not an APK or complete Android Studio project. Replace the contents of the existing MainActivity.kt and rebuild; do not add a second class with the same name. Package: com.example.ic_705remote2. If Gradle defines versionName/versionCode, update those separately.

For wake/standby, use SET > Function > Power OFF Setting (for Remote Control) > Standby/Shutdown and keep WLAN/Network Control enabled. The radio must remain network-reachable. LAN/WAN selection does not configure a router or VPN.

User feedback confirmed working behavior during development. Source-level checks were performed; no additional Android compilation or physical-radio test was performed while preparing this release. No new code changes were made to the delivered v28.37 source for publication.
