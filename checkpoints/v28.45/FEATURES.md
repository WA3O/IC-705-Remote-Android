# IC-705 Remote Android - v28.45 feature guide

## Connection and settings

The app controls an Icom IC-705 through its network interface, using UDP control, serial/CI-V and audio ports 50001, 50002 and 50003. It supports a saved LAN address and a separate WAN address; the Use WAN checkbox selects which address CONNECT uses. Username and password are entered in Settings > IP / PASSWORD. Fresh installations contain no connection credentials or website configuration. Settings entered afterward are stored locally by the app. The password field is masked; this is not a claim of encrypted preference storage.

CONNECT establishes the session, requests radio wake-up and reads the frequency. Startup feedback shows elapsed time and whether audio and waterfall data have arrived. Waking a reachable radio from standby can take several seconds. Automatic recovery retains the active session address even if settings are edited while connected.

DISCONNECT offers disconnect-only (leave radio power unchanged), standby-and-disconnect, or cancel. Standby requires the radio's Power OFF Setting (for Remote Control) to be Standby/Shutdown, with WLAN and Network Control enabled. The app reports standby as requested, not proven by a missing response. LAN/WAN selection does not create a VPN or configure port forwarding.

## Receive audio and connection stability

Receive audio uses 48 kHz, 16-bit mono PCM. The right-side connection light shows GOOD, FAIR or BAD based on the streams; a dash is shown while connecting/disconnected. The app requests missing transport packets and can restart audio separately from control/spectrum, escalating to full session recovery when both streams stall.

v28.41 renews authentication every 60 seconds. This addressed the recurring approximately 92-second interruption in the observed test: 17 minutes 18 seconds on one session, 17 acknowledgements, no logged recoveries, and no audible breakups reported by the user during the 15-minute listening test. Buffering is unchanged. Temporary automatic recovery does not restart the yellow startup banner or open a connection dialog. A failed full reconnect restores CONNECT with a short message.

## Frequency and mode controls

Enter MHz in the bright-green top field (for example 12.200000 MHz) and tap the keyboard check mark / Done; readback verifies tuning. The keyboard closes on valid submission. There is no SET button or duplicate frequency line under the S-meter. Step controls move by 500 Hz or 1 kHz. Tap the spectrum/waterfall to tune, or swipe horizontally once per gesture: right adds 1 kHz and left subtracts 1 kHz. Vertical, cancelled and multi-touch gestures do not tune.

USB and LSB buttons provide sideband selection. Automatic sideband selection follows the app's frequency mapping. Quick band buttons cover 160m, 80m, 40m, 30m, 20m, 17m, 15m, 12m and 10m, using preset frequencies. These presets do not establish operating privileges or band-plan suitability.

## Spectrum and waterfall

The scope starts automatically in CENTER mode after audio setup and can be toggled on/off. Spectrum and waterfall share the tuned-frequency marker and frequency labels. The displayed span control covers 5-1000 kHz; the app chooses a supported radio span to cover the requested display span. Display sensitivity adjusts from 0.25x to 4.00x. The saved spectrum-window height ranges from 140 to 600 dp.

Waterfall palettes are Blue, Green, Amber, Purple and Grayscale, with saved selection. Absolute-frequency markers remain every 10 kHz, with overlap suppression where needed, thicker vertical lines and the v28.37 label size. The separate v28.38 adaptive 50/100 kHz marker-spacing experiment is not included.

## Microphone transmission

A fixed main-panel TX toggle sits beside connection controls and turns red while selected. Tap again to request RX. Radio status confirms TX. The first request needs Android microphone permission; after granting it, tap TX again.

Phone microphone TX supports LSB, USB, AM and FM. It sends 48 kHz mono PCM16 through WLAN modulation, temporarily selecting WLAN routing for DATA and non-DATA inputs and temporarily setting WLAN MOD level to 50%; prior routing/level is restored after TX when the control link is available. Phone receive playback is muted during TX. CW, RTTY and DV microphone transmission are outside this implementation.

TX automatically stops after 180 seconds. App backgrounding, disconnect, microphone failure, recovery or loss of TX status requests RX. Returning to the app or reconnecting never automatically resumes TX. A disconnected network cannot guarantee delivery of an RX or routing-restoration command to the radio.

## Radio sliders and meters

Settings > RADIO SETTINGS includes:

- Requested power: 0-10 W in 0.1 W increments using the nominal 10 W scale. Actual RF output depends on radio limits, mode and supply. Zero inhibits app TX; it is not a guarantee that the minimum radio power command produces zero RF.
- Microphone TX audio: 0-100% software scaling; zero mutes outgoing audio. Adjustable while transmitting.
- Squelch: 0-100%, including WLAN AF squelch for phone receive audio.

Slider values are saved locally. Power and squelch are applied with radio readback on connection, slider release and before TX, and are disabled during TX. The receive S-meter changes to relative Power out percentage while radio TX is reported. It is not a calibrated wattmeter.

## Web links

Settings > WEB LINKS has four optional website names and addresses, each with an Open button. Edits save automatically on the device. An address without a scheme uses HTTPS; invalid or non-HTTP(S) addresses show an error. Links open in an installed browser. Leaving the app stops TX; returning does not restart it. Fresh installations have all four entries blank.

## Interface, diagnostics and limits

The app uses a dark portrait interface, independent scrolling for MAIN and SETTINGS, keyboard resize behavior, and ASCII TX/status labels. Logcat tag IC705Remote includes stream statistics, recovery events and authentication-renewal sent/ACK/timeout messages. Logs can contain radio addresses and session identifiers; they are not included in this release.

This is a direct radio-control app, not a radio simulator or network-setup service. It requires a reachable IC-705 and appropriate radio network settings. The app does not provide background microphone transmission. The successful live test validates the recurring session-expiry symptom on the tested setup, not every possible network or device condition.

## TX countdown and Functions

The large top TX toggle stays visible on MAIN and SETTINGS. The remaining-seconds display uses the same deadline as the app's 180-second RX request. External radio TX has no app countdown. An RX request cannot guarantee unkey if the network link is unavailable.

Settings > Functions cycles AGC Fast/Medium/Slow and toggles AN (automatic notch), NB (noise blanker), NR (noise reduction) and COMP. FL1/FL2/FL3 select existing filter presets while preserving mode and DATA state. Green indicates confirmed on/selected. Unavailable settings show a dash. Readback occurs after changes and every ten seconds while Functions is visible in RX; REFRESH also reads states. Availability depends on radio mode.

## Phone and Bluetooth PTT

Volume up/down or a learned Android headset/media/function/gamepad key can toggle TX with one press and RX with the next. Holding a key does not repeat toggles. Mapping is remembered, initially disabled; the mapped volume key stops adjusting volume while the app has focus. Existing microphone permission, mode, connection and TX timeout checks apply. Leaving the app requests RX.

Experimental PRYME PTT-Z Mini / BLE support scans for a button and tests notifications before enabling TX. The supported protocol is FFE0/FFE1, one-byte 01 press / 00 release; other firmware remains disabled. Physical PRYME compatibility has not been verified. Press/release twice in test mode, then explicitly enable BLE PTT. Leaving the app disconnects BLE; reconnect and retest. A BLE disconnect cancels BLE-started TX. Android 12+ requires Nearby Devices permission; older versions require Location for discovery.
