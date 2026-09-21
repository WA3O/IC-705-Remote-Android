# IC-705 Android Remote Project — Spectrum Deep-Dive Checkpoint
Date: 2026-09-21

## User requirement
- Preserve all working IC-705 functionality.
- Spectrum must display on Android.
- Spectrum OFF must stop spectrum/waveform data to conserve network traffic.
- Do not regress verified frequency, mode, audio, tuning, settings, or signal meter.
- Prefer protocol/source evidence over guessing.

## Verified working before spectrum issue
1. IC-705 RS-BA1 control handshake: AYT, READY, LOGIN, AUTH #1/#2, A8, CONNINFO.
2. CI-V frequency read.
3. CI-V frequency write: FE FE A4 E0 25 00 [5 BCD bytes] FD.
4. RX audio UDP 50003.
5. USB / LSB mode.
6. Active USB / LSB indicator.
7. +/-500 Hz tuning.
8. Band shortcut buttons.
9. Settings persistence for IP / username / password.
10. S-meter polling via CI-V 15 02.

## Current source checkpoint
- Canonical file: /mnt/data/MainActivity.kt
- Backup: /mnt/data/IC705_REMOTE_SPECTRUM_CHECKPOINT_2026-09-21_MainActivity.kt
- Package: com.example.ic_705remote2
- Android source path: app/src/main/java/com/example/ic_705remote2/MainActivity.kt

## Critical spectrum research findings
### Icom protocol
- CI-V command 27 00 is the IC-705 scope waveform data command.
- 27 10 controls scope ON/OFF.
- 27 11 controls scope wave-data output.
- IC-705 WLAN scope data is sent as one complete waveform line; USB may divide it.
- Waveform has 475 samples, values 0..160.
- CI-V Transceive should be ON for network scope/waterfall operation.

### wfview source finding — most important
wfview's LAN parser in src/radio/icomudpcivdata.cpp does NOT require a FE FE E0 A4 CI-V wrapper around the scope line.

It searches the C1 payload/datagram for:
    27 00 00

Then it finds the next FD and checks the scope-line length.
For IC-705-family LAN scope handling it recognizes a length of 490 bytes after the 27 00 marker (before FD), corresponding to the 475-point waveform structure.

wfview's source comments include a raw example beginning:
    DATA: 27 00 00 01 11 01 ... FD

The same source builds/sends the C1 LAN transport using a header containing remote/local IDs, reply/type C1, declared data length, and sequence number.

### Why the current Android parser likely fails
Current Kotlin path:
1. C1 UDP packet is received.
2. Current code correctly extracts the declared-length payload from the C1 transport.
3. `consumeSerialCivChunk()` then expects a conventional CI-V frame beginning with FE FE.
4. A raw LAN scope line beginning 27 00 therefore never enters the spectrum decoder.
5. The line eventually gets discarded as unrecognized data.

This is a much stronger explanation than the prior theory that the Android Canvas/scaling was the problem.

### Expected LAN scope sizes
Based on wfview source:
- raw scope line: 27 00 + 490 bytes through FD/line structure (exact accounting should be validated in the next code revision from the actual C1 declared length).
- With a 21-byte C1 transport header, a complete one-line scope datagram is expected to be around 514 bytes when the raw scope line itself is 493 bytes total.

Do NOT hard-code UDP packet size as the primary parser rule. Use the C1 declared-length field and locate/validate the raw 27 00 scope line inside that payload.

## Spectrum ON/OFF behavior supported by the research
ON:
1. Ensure CI-V Transceive ON (1A 05 01 31 01).
2. Send 27 10 01.
3. Send 27 11 01.
4. Do NOT continuously poll 27 00 if the radio is already streaming waveform lines automatically.

OFF:
1. Send 27 11 00.
2. Stop accepting/displaying spectrum data in the app.
3. Keep the radio's physical scope display alone unless the user later wants it controlled separately.

wfview documentation/source supports disabling waveform output to reduce CI-V/network traffic.

## Current spectrum UI
- Spectrum view is shown when spectrum starts.
- Spectrum view draws 475 samples.
- Sensitivity slider visible only while spectrum is ON.
- Spectrum OFF hides the Android spectrum view.

## Signal meter
- CI-V command: FE FE A4 E0 15 02 FD.
- IC-705 response contains two BCD bytes representing S-meter value.
- Current UI polls about every 500 ms.

## Next code change — do not change unrelated working paths
Replace only the serial scope demultiplexing logic so it handles BOTH:
A. raw LAN scope data beginning 27 00 inside a C1 payload (the important path), and
B. conventional FE FE...FD CI-V frames for normal radio commands.

The demultiplexer should inspect the C1 declared-length payload before trying to parse it as a conventional FE FE frame.

Recommended order:
1. If payload contains a valid raw scope signature 27 00 at/near start and a valid FD/expected scope length, route it directly to processSpectrumScopeFrame().
2. Only otherwise send the payload through the ordinary FE FE CI-V frame parser.
3. Preserve fragment buffering for both formats so no packet gets silently discarded.
4. Keep spectrum polling disabled; rely on IC-705 waveform output after 27 11 01 unless actual live capture proves otherwise.
5. Add diagnostics showing C1 packets, raw scope frames found, decoded frames, and last raw scope length.

## What would definitively prove each stage
- C1 packet counter increases: LAN transport is delivering packets.
- Raw-scope counter increases: Android is seeing 27 00 waveform lines.
- Raw scope length approximately matches wfview/IC-705 expected structure: parser framing is correct.
- Decoded frame counter increases: 475 samples successfully parsed.
- Canvas then has data: UI path is receiving real spectrum data.

If C1 packets increase but raw-scope counter is zero, the next investigation is exact payload bytes/declared length, not the Canvas.

## Sources used in deep dive
- Icom IC-705 CI-V Reference Guide / official Icom documentation: command 27 scope waveform, 475 points, WLAN vs USB scope transport behavior.
- wfview Getting Started / documentation: CI-V Transceive requirement and IC-705 network scope behavior.
- wfview source `src/radio/icomudpcivdata.cpp`: exact LAN C1 scope detection, raw `27 00` parsing, scope length handling, IC-705-specific processing.
- wfview source `src/radio/icomudpbase.cpp`: C1 UDP transport structure and sequence handling.

## Important conclusion
The strongest evidence currently points to a receive-side framing mismatch in Android, not an IC-705 limitation and not a spectrum rendering limitation. The IC-705 is capable of supplying the same scope stream wfview displays. The Android app must recognize the raw 27 00 waveform line inside the C1 transport before applying the normal FE FE CI-V parser.

No claim of hardware-tested 100% success is made until the revised Android build is actually run against the IC-705.