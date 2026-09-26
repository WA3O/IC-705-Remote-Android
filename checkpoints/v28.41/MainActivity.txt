package com.example.ic_705remote2
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
class MainActivity : Activity() {
    // v28.40 TX additions; the rest of this file is based on v28.37.
    private val radioWorker = Executors.newSingleThreadExecutor()
    private val txGate = Any()
    private val radioReplies = java.util.concurrent.LinkedBlockingQueue<ByteArray>(128)
    @Volatile private var txWanted = false
    @Volatile private var txBusy = false
    @Volatile private var radioTransmitting = false
    @Volatile private var foreground = false
    @Volatile private var txRecorder: android.media.AudioRecord? = null
    @Volatile private var lastPttReplyAt = 0L
    @Volatile private var txStatus = "RX"
    @Volatile private var txPowerTenths = 10
    @Volatile private var txMicPercent = 50
    @Volatile private var squelchPercent = 0
    private var txAudioSequence = 1
    private var txAudioInnerSequence = 0
    private lateinit var txButton: Button
    private lateinit var radioSettingsStatus: TextView
    private lateinit var powerSlider: SeekBar
    private lateinit var squelchSlider: SeekBar
    private val txTimeoutHandler = Handler(Looper.getMainLooper())
    private val txTimeout = Runnable { cancelTransmit("180-second TX limit reached") }
    @Volatile private var userDisconnectRequested = false
    private val powerDisconnectBusy = AtomicBoolean(false)
    private var disconnectChoiceDialog: AlertDialog? = null
    @Volatile private var awaitingPowerSetting = false
    private val powerReplies = java.util.concurrent.LinkedBlockingQueue<ByteArray>(16)
    private val powerFrameBuffer = ArrayList<Byte>()
    private val civFrameBuffer = ArrayList<Byte>()
    private val udpRecoveryGuard = AtomicBoolean(false)
    private val audioRecoveryGuard = AtomicBoolean(false)
    @Volatile private var automaticReconnectAttempts = 0
    @Volatile private var automaticReconnectRestoreScope = false
    companion object {
        private const val APP_VERSION = "v28.41"
        private const val MAX_AUTO_RECONNECT_ATTEMPTS = 3
        private const val NETWORK_RECONNECT_DELAY_MS = 100L
        private const val STREAM_STALL_TIMEOUT_MS = 600L
        private const val AUDIO_STALL_TIMEOUT_MS = 400L
        private const val AUDIO_RECOVERY_RETRY_COOLDOWN_MS = 750L
        private const val CONTROL_PORT = 50001
        private const val SERIAL_PORT = 50002
        private const val AUDIO_PORT = 50003
        private const val DEFAULT_RADIO_IP = ""
        private const val DEFAULT_USERNAME = ""
        private const val APP_NAME = "icom-pc"
        private const val MODEL_NAME = "IC-705"
        private const val SOCKET_TIMEOUT_MS = 1500
        private const val FREQUENCY_TIMEOUT_MS = 5000L
        private const val TEST_WRITE_FREQUENCY_HZ = 7_138_000L
        private const val LOGIN_TIMEOUT_MS = 4000
        private const val AUTH_TIMEOUT_MS = 5000
        private const val CONNINFO_TIMEOUT_MS = 5000
        private const val AUDIO_SAMPLE_RATE = 48000
        private const val TX_BUFFER_LENGTH_MS = 300
        private const val SCOPE_POLL_INTERVAL_MS = 500L
        private const val SIGNAL_METER_POLL_INTERVAL_MS = 500L

        private const val PREFS_NAME = "ic705_remote_settings"
        // Keep the existing IP key as LAN so upgrades retain the saved address.
        private const val PREF_IP = "ic705_ip"
        private const val PREF_WAN_IP = "ic705_wan_ip"
        private const val PREF_USE_WAN = "ic705_use_wan"
        private const val PREF_USERNAME = "ic705_username"
        private const val PREF_PASSWORD = "ic705_password"
        private const val PREF_SPECTRUM_WINDOW_SIZE_DP = "spectrum_window_size_dp"
        private const val PREF_WATERFALL_PALETTE = "waterfall_palette"
    }
    private lateinit var settingsIpEdit: EditText
    private lateinit var settingsWanIpEdit: EditText
    private lateinit var settingsUseWanCheck: CheckBox
    private lateinit var settingsUsernameEdit: EditText
    private lateinit var settingsPasswordEdit: EditText
    private lateinit var mainPane: LinearLayout
    private lateinit var settingsPane: LinearLayout
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var linkQualityLight: View
    private lateinit var linkQualityText: TextView
    private lateinit var startupStatus: TextView
    @Volatile private var startupStartedAt = 0L
    @Volatile private var startupWaiting = false
    private lateinit var frequencyEdit: EditText
    private lateinit var frequencyLabelView: TextView
    private lateinit var setFrequencyButton: Button
    private lateinit var down500Button: Button
    private lateinit var up500Button: Button
    private lateinit var down1kButton: Button
    private lateinit var up1kButton: Button
    private lateinit var usbButton: Button
    private lateinit var lsbButton: Button
    private lateinit var sensitivitySeekBar: SeekBar
    private lateinit var sensitivityLabel: TextView
    private lateinit var sensitivityRow: LinearLayout
    private lateinit var bandwidthLabel: TextView
    private lateinit var spectrumSizeLabel: TextView
    private lateinit var noiseFloorLabel: TextView
    private lateinit var scopeInfoText: TextView
    private lateinit var scopeToggleButton: Button
    private lateinit var scopeView: SpectrumWaterfallView
    private lateinit var signalMeterLabel: TextView
    private lateinit var signalMeterBar: ProgressBar
    private var controlSocket: DatagramSocket? = null
    private var serialSocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var receiverThread: Thread? = null
    private var serialReceiverThread: Thread? = null
    private var sessionThread: Thread? = null
    private var scheduler: ScheduledExecutorService? = null
    private var serialScheduler: ScheduledExecutorService? = null
    private var audioScheduler: ScheduledExecutorService? = null
    private var signalMeterScheduler: ScheduledExecutorService? = null
    private var audioReceiverThread: Thread? = null
    @Volatile
    private var running = false
    @Volatile
    private var connected = false
    @Volatile
    private var authOk = false
    @Volatile
    private var gotA8 = false
    @Volatile
    private var connInfoOk = false
    @Volatile
    private var teardownStarted = false
    private var localSid: Int = 0
    private var remoteSid: Int = 0
    private var serialLocalSid: Int = 0
    private var serialRemoteSid: Int = 0
    private var audioLocalSid: Int = 0
    private var audioRemoteSid: Int = 0
    private var serialOuterSequence: Int = 1
    private var serialPkt7Sequence: Int = 2
    private var serialCivSequence: Int = 1
    private var audioPkt7Sequence: Int = 1
    private var audioPkt7InnerSequence: Int = 0x8304
    private var outerSequence: Int = 1
    // Guard renewal state with sendLock, shared with control packet sequencing.
    private var renewalRequest: ByteArray? = null
    private var renewalSentAt = 0L
    private var nextRenewalAt = 0L
    private var authInnerSequence: Int = 0
    private var pkt7Sequence: Int = 2
    private var pkt7InnerSequence: Int = 0x8304
    private val authId = ByteArray(6)
    private val a8ReplyId = ByteArray(16)
    private val secureRandom = SecureRandom()
    private val sendLock = Any()
    private val trackedPackets = ConcurrentHashMap<Int, ByteArray>()
    private val serialTrackedPackets = ConcurrentHashMap<Int, ByteArray>()
    // Track incoming UDP sequence gaps independently for the audio and CI-V streams.
    // The IC-705 can retransmit a missing stream packet when asked with type 0x01.
    private data class ReceiveSequenceState(
        var newest: Int? = null,
        val missing: LinkedHashMap<Int, Int> = LinkedHashMap()
    )
    private val audioReceiveSequenceState = ReceiveSequenceState()
    private val serialReceiveSequenceState = ReceiveSequenceState()
    private val maxTrackedReceiveGaps = 16
    private val maxReceiveRetransmitRequests = 4
    @Volatile
    private var serialOpen = false
    @Volatile
    private var audioRunning = false
    private var audioTrack: AudioTrack? = null
    private var audioHaveSequence = false
    private var audioLastSequence = 0
    private var audioPacketCount = 0L
    private var audioDebugPacketCount = 0
    @Volatile private var audioWriteCalls = 0L
    @Volatile private var audioWriteBytes = 0L
    @Volatile private var audioWriteErrors = 0L
    @Volatile private var audioLastPacketAtMs = 0L
    @Volatile private var audioLastWriteAtMs = 0L
    @Volatile private var scopeLastTransportAtMs = 0L
    @Volatile private var scopeLastRawFrameAtMs = 0L
    @Volatile private var scopeLastDecodedAtMs = 0L
    private val streamDiagnosticHandler = Handler(Looper.getMainLooper())
    private val streamStallWatchdog = object : Runnable {
        override fun run() {
            if (!running) return
            updateStartupStatus()
            val now = System.currentTimeMillis()
            requestNextMissingPacket(audioReceiveSequenceState, audioSocket, audioLocalSid, audioRemoteSid, "audio")
            requestNextMissingPacket(serialReceiveSequenceState, serialSocket, serialLocalSid, serialRemoteSid, "serial")
            val audioReferenceAt = if (audioLastPacketAtMs > 0L) audioLastPacketAtMs else audioStreamExpectedSinceAtMs
            val audioAge = if (audioReferenceAt > 0L) now - audioReferenceAt else Long.MAX_VALUE
            val serialAge = if (scopeLastTransportAtMs > 0L) now - scopeLastTransportAtMs else Long.MAX_VALUE
            updateLinkQuality(audioAge, serialAge)
            // Escalate only when both receive streams are genuinely silent. This
            // clock uses last received audio payload, never a new PKT3/PKT6 handshake.
            if (!txBusy && (!radioTransmitting || now - lastPttReplyAt > 3000L) && connected && serialOpen && audioStreamExpected && scopeStarted &&
                audioAge >= STREAM_STALL_TIMEOUT_MS && serialAge >= STREAM_STALL_TIMEOUT_MS) {
                beginNetworkRecovery("audio and serial/scope streams silent for ${maxOf(audioAge, serialAge)} ms")
                return
            }
            if (!txBusy && (!radioTransmitting || now - lastPttReplyAt > 3000L) && connected && audioStreamExpected && !audioRecoveryGuard.get() &&
                now >= audioRecoveryNotBeforeMs &&
                ((audioRunning && audioAge >= AUDIO_STALL_TIMEOUT_MS) || !audioRunning)) {
                beginAudioStreamRecovery(if (audioRunning) "audio packets silent for ${audioAge} ms" else "audio stream is inactive")
            }
            streamDiagnosticHandler.postDelayed(this, 100L)
        }
    }
    private val streamDiagnosticTask = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            appendLog(
                "STREAM DIAG $APP_VERSION connected=$connected serialOpen=$serialOpen " +
                        "audioRunning=$audioRunning audioRxPackets=$audioPacketCount " +
                        "audioRxAgo=${diagnosticAge(now, audioLastPacketAtMs)} " +
                        "audioWriteCalls=$audioWriteCalls audioWriteBytes=$audioWriteBytes " +
                        "audioWriteErrors=$audioWriteErrors " +
                        "audioWriteAgo=${diagnosticAge(now, audioLastWriteAtMs)} " +
                        "scopeStarted=$scopeStarted c1Packets=$scopeTransportPackets " +
                        "c1Ago=${diagnosticAge(now, scopeLastTransportAtMs)} " +
                        "rawScopeFrames=$scopeRaw27Frames " +
                        "rawScopeAgo=${diagnosticAge(now, scopeLastRawFrameAtMs)} " +
                        "decodedScopeFrames=$scopeDecodedFrames " +
                        "decodedScopeAgo=${diagnosticAge(now, scopeLastDecodedAtMs)}"
            )
            streamDiagnosticHandler.postDelayed(this, 5000L)
        }
    }
    private fun diagnosticAge(now: Long, then: Long): String =
        if (then <= 0L) "never" else "${(now - then).coerceAtLeast(0L)}ms"
    @Volatile
    private var frequencyReceived = false
    @Volatile
    private var frequencyWriteResponseReceived = false
    @Volatile
    private var frequencyWriteRejected = false
    private var frequencyHz: Long = 0L
    @Volatile private var activeRadioIp: String = ""
    @Volatile private var activeUsername: String = ""
    @Volatile private var activePassword: String = ""
    @Volatile private var audioStreamExpected = false
    @Volatile private var audioStreamExpectedSinceAtMs = 0L
    @Volatile private var audioRecoveryNotBeforeMs = 0L
    @Volatile private var autoSidebandChangePending = false
    @Volatile private var lastLinkQualityLabel = ""
    private val autoSidebandLock = Any()
    @Volatile
    private var signalMeterValue = 0
    @Volatile
    private var scopeStarted = false
    @Volatile
    private var scopePolling = false
    private var scopePollThread: Thread? = null
    @Volatile
    private var scopeTransportPackets = 0L
    @Volatile
    private var scopeCivFrames = 0L
    @Volatile
    private var scopeRaw27Frames = 0L
    @Volatile
    private var scopeDecodedFrames = 0L
    @Volatile
    private var requestedDisplayBandwidthKHz = 75
    @Volatile
    private var waterfallPaletteIndex = 0
    @Volatile
    private var requestedSpectrumWindowSizeDp = 300
    @Volatile
    private var scopeUiUpdateCounter = 0L
    @Volatile
    private var scopePollCount = 0L
    @Volatile
    private var scopeAwaitingResponse = false
    @Volatile
    private var scopeLastUdpBytes = 0
    @Volatile
    private var scopeLastCivBytes = 0
    private var scopeAssemblyMainSub = 0
    private var scopeAssemblyMode = 0
    private var scopeAssemblyCenterHz = 0L
    private var scopeAssemblySpanHz = 0L
    private var scopeAssemblyOutOfRange = false
    private val scopeAssemblyWaveform = ByteArrayOutputStream()
    private val scopeRawReceiveBuffer = ByteArrayOutputStream()
    @Volatile
    private var activeModeCode: Int = -1

    @Volatile
    private var modeReceived = false
    private fun buildWebLinksSettings(parent: LinearLayout) {
        val preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        parent.addView(TextView(this).apply {
            text = "Save up to four websites. Changes are saved automatically on this phone. Opening a website stops TX."
            textSize = 14f
            setPadding(0, dp(12), 0, dp(8))
        })
        for (index in 1..4) {
            val nameKey = "web_link_${index}_name"
            val addressKey = "web_link_${index}_address"
            parent.addView(TextView(this).apply {
                text = "Link $index"
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(14), 0, dp(4))
            })
            val nameEdit = EditText(this).apply {
                hint = "Website name (optional)"
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setText(preferences.getString(nameKey, "") ?: "")
                contentDescription = "Link $index website name"
                minimumHeight = dp(48)
            }
            val addressEdit = EditText(this).apply {
                hint = "https://example.com"
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setText(preferences.getString(addressKey, "") ?: "")
                contentDescription = "Link $index website address"
                minimumHeight = dp(48)
            }
            parent.addView(nameEdit, LinearLayout.LayoutParams(-1, -2))
            parent.addView(addressEdit, LinearLayout.LayoutParams(-1, -2))
            val openButton = Button(this).apply {
                text = "OPEN WEBSITE"
                minimumHeight = dp(48)
            }
            fun updateOpenButton() {
                val name = nameEdit.text.toString().trim()
                openButton.text = if (name.isEmpty()) "OPEN WEBSITE $index" else "OPEN $name"
                openButton.isEnabled = addressEdit.text.toString().isNotBlank()
            }
            fun saveAsTyped(edit: EditText, key: String) {
                edit.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        preferences.edit().putString(key, s?.toString() ?: "").apply()
                        updateOpenButton()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
            saveAsTyped(nameEdit, nameKey)
            saveAsTyped(addressEdit, addressKey)
            updateOpenButton()
            openButton.setOnClickListener {
                val input = addressEdit.text.toString().trim()
                val candidate = if (input.contains("://")) input else "https://$input"
                val uri = try { java.net.URI(candidate) } catch (_: Exception) { null }
                val scheme = uri?.scheme?.lowercase(java.util.Locale.ROOT)
                if (uri == null || scheme !in listOf("http", "https") || uri.host.isNullOrBlank() ||
                    uri.rawUserInfo != null || input.any { it.isWhitespace() || it.isISOControl() }) {
                    addressEdit.error = "Enter a valid website address, such as https://example.com"
                    addressEdit.requestFocus()
                    return@setOnClickListener
                }
                try {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(uri.toASCIIString())))
                } catch (_: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(this, "No browser is available to open this website.",
                        android.widget.Toast.LENGTH_LONG).show()
                } catch (_: SecurityException) {
                    android.widget.Toast.makeText(this, "Android could not open this website.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
            parent.addView(openButton, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun buildRadioSettings(parent: LinearLayout) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        txPowerTenths = prefs.getInt("tx_power_tenths", 10).coerceIn(0, 100)
        txMicPercent = prefs.getInt("tx_mic_percent", 50).coerceIn(0, 100)
        squelchPercent = prefs.getInt("tx_squelch_percent", 0).coerceIn(0, 100)
        fun slider(title: String, maximum: Int, initial: Int,
                   display: (Int) -> String, changed: (Int) -> Unit,
                   released: () -> Unit = {}): SeekBar {
            val label = TextView(this).apply { textSize = 17f; setPadding(0, dp(16), 0, 0) }
            parent.addView(label)
            val seek = SeekBar(this).apply { max = maximum; progress = initial; minimumHeight = dp(48) }
            label.text = "$title: ${display(initial)}"
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {
                    label.text = "$title: ${display(p)}"
                    if (user) changed(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { released() }
            })
            parent.addView(seek, LinearLayout.LayoutParams(-1, dp(48)))
            return seek
        }
        powerSlider = slider("TX power", 100, txPowerTenths,
            { "%.1f W requested".format(java.util.Locale.US, it / 10.0) }, {
                txPowerTenths = it
                prefs.edit().putInt("tx_power_tenths", it).apply()
                if (it == 0) cancelTransmit("TX disabled at 0 W")
            }, { applyRadioSliders() })
        slider("Mic TX audio", 100, txMicPercent, { "$it%" }, {
            txMicPercent = it
            prefs.edit().putInt("tx_mic_percent", it).apply()
        })
        squelchSlider = slider("Squelch", 100, squelchPercent, { "$it%" }, {
            squelchPercent = it
            prefs.edit().putInt("tx_squelch_percent", it).apply()
        }, { applyRadioSliders() })
        parent.addView(TextView(this).apply {
            text = "Power uses the nominal 10 W scale; actual output is limited by the radio, supply and mode. 0 W disables TX. Mic audio controls the phone's outgoing audio level (0% = mute). Power and squelch apply on release while connected, and before TX."
            textSize = 13f
            setPadding(0, dp(12), 0, dp(12))
        })
        radioSettingsStatus = TextView(this).apply { text = "Connect to apply radio settings."; textSize = 14f }
        parent.addView(radioSettingsStatus)
    }

    private fun radioNotice(message: String) {
        appendLog("TX: $message")
        runOnUiThread { if (::radioSettingsStatus.isInitialized) radioSettingsStatus.text = message }
    }

    // Explicit readback/retry instead of retaining TX commands for later UDP replay.
    private fun sendRadioCommand(vararg payload: Int) {
        synchronized(sendLock) {
            check(serialSocket?.isClosed == false && serialOpen) { "Radio control unavailable" }
            val frame = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xA4.toByte(), 0xE0.toByte()) +
                payload.map { it.toByte() }.toByteArray() + byteArrayOf(0xFD.toByte())
            val packet = buildCivPacket(frame)
            packet[6] = txByte(serialOuterSequence)
            packet[7] = txByte(serialOuterSequence ushr 8)
            serialOuterSequence = (serialOuterSequence + 1) and 65535
            sendSerialRaw(packet)
        }
    }
    private fun txByte(value: Int) = (value and 255).toByte()
    private fun levelBcd(value: Int): IntArray = TxWire.levelBcd(value)
    private fun readRadioValue(vararg prefix: Int): ByteArray {
        radioReplies.clear()
        repeat(3) {
            check(running && serialOpen) { "Radio disconnected" }
            sendRadioCommand(*prefix)
            val deadline = android.os.SystemClock.elapsedRealtime() + 650L
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val f = radioReplies.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (f.size > 5 + prefix.size && prefix.indices.all { (f[4 + it].toInt() and 255) == prefix[it] })
                    return f.copyOfRange(4 + prefix.size, f.size - 1)
            }
        }
        error("Radio did not answer ${prefix.joinToString(" ") { "%02X".format(it) }}")
    }
    private fun writeRadioValue(prefix: IntArray, value: IntArray) {
        val sid = serialLocalSid
        repeat(3) {
            check(running && serialOpen && sid == serialLocalSid) { "Radio session changed" }
            sendRadioCommand(*(prefix + value))
            Thread.sleep(40)
            val actual = readRadioValue(*prefix)
            if (actual.contentEquals(value.map { it.toByte() }.toByteArray())) return
        }
        error("Radio setting was not confirmed: ${prefix.joinToString(" ") { "%02X".format(it) }}")
    }
    private fun applyRadioSliders() {
        if (!connected || txBusy) return
        val sid = serialLocalSid
        val power = txPowerTenths
        val sql = squelchPercent
        radioWorker.execute {
            if (!connected || sid != serialLocalSid || txBusy) return@execute
            try {
                if (power > 0) writeRadioValue(intArrayOf(0x14, 0x0A), levelBcd((power * 255.0 / 100).roundToInt()))
                writeRadioValue(intArrayOf(0x14, 0x03), levelBcd((sql * 255.0 / 100).roundToInt()))
                // WLAN AF SQL ON makes the squelch audible in the phone's receive stream.
                writeRadioValue(intArrayOf(0x1A, 0x05, 0x01, 0x15), intArrayOf(1))
                radioNotice("Power and squelch settings confirmed by radio.")
            } catch (e: Exception) { radioNotice("Settings not confirmed: ${e.message}") }
        }
    }

    private fun refreshTxUi() {
        runOnUiThread {
            if (!::txButton.isInitialized) return@runOnUiThread
            txButton.text = when {
                txBusy && !txWanted -> "STOPPING"
                radioTransmitting -> "TX / STOP"
                txWanted -> "WAIT / STOP"
                else -> "TX"
            }
            txButton.contentDescription = "Transmit toggle. $txStatus"
            txButton.isEnabled = txWanted || radioTransmitting ||
                (!txBusy && connected && audioRunning && !userDisconnectRequested && foreground)
            txButton.backgroundTintList = ColorStateList.valueOf(
                if (radioTransmitting || txWanted) Color.RED else if (txBusy) Color.rgb(180, 110, 0) else Color.rgb(70, 70, 70))
            if (::powerSlider.isInitialized) powerSlider.isEnabled = !txBusy && !radioTransmitting
            if (::squelchSlider.isInitialized) squelchSlider.isEnabled = !txBusy && !radioTransmitting
            if (::setFrequencyButton.isInitialized) {
                setTuneControlsEnabled(connected && !txBusy && !radioTransmitting)
                enableBandButtons(connected && !txBusy && !radioTransmitting)
                usbButton.isEnabled = connected && !txBusy && !radioTransmitting
                lsbButton.isEnabled = connected && !txBusy && !radioTransmitting
            }
        }
    }

    private fun toggleTransmit() {
        if (txWanted || txBusy || radioTransmitting) { cancelTransmit("RX requested"); return }
        if (!connected || !audioRunning || !foreground || userDisconnectRequested) return
        if (activeModeCode !in intArrayOf(0x00, 0x01, 0x02, 0x05)) {
            radioNotice("Select USB, LSB, AM or FM for phone microphone TX.")
            return
        }
        if (txPowerTenths == 0) { radioNotice("Raise TX power above 0 W before transmitting."); return }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 705)
            return
        }
        txWanted = true
        txBusy = true
        txStatus = "Preparing microphone"
        refreshTxUi()
        val sid = serialLocalSid
        radioWorker.execute { runTransmit(sid) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 705) {
            val message = if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
                "Microphone allowed. Tap TX again when ready." else "Microphone permission is required for TX."
            radioNotice(message)
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun sendPttOff() {
        synchronized(txGate) {
            if (serialOpen && serialSocket?.isClosed == false) {
                try { sendRadioCommand(0x1C, 0x00, 0x00) } catch (e: Exception) { radioNotice("RX command failed: ${e.message}") }
            }
        }
    }
    private fun cancelTransmit(reason: String) {
        txWanted = false
        try { txRecorder?.stop() } catch (_: Exception) {}
        if (txBusy || radioTransmitting) {
            txStatus = reason
            // Keep network I/O off Android's UI thread.
            Thread({ sendPttOff() }, "IC705-Unkey").start()
        }
        if (reason == "180-second TX limit reached") runOnUiThread {
            android.widget.Toast.makeText(this, "TX stopped: 180-second limit", android.widget.Toast.LENGTH_LONG).show()
        }
        refreshTxUi()
    }

    @Suppress("MissingPermission")
    private fun runTransmit(sid: Int) {
        var recorder: android.media.AudioRecord? = null
        val restore = ArrayList<Pair<IntArray, IntArray>>()
        var keyed = false
        fun valid() = txWanted && foreground && running && connected && serialOpen &&
            audioRunning && !userDisconnectRequested && sid == serialLocalSid && !audioRecoveryGuard.get() && !udpRecoveryGuard.get()
        fun route(prefix: IntArray, desired: IntArray) {
            check(valid()) { "TX cancelled" }
            val original = readRadioValue(*prefix).map { it.toInt() and 255 }.toIntArray()
            check(valid()) { "TX cancelled" }
            restore.add(prefix to original)
            writeRadioValue(prefix, desired)
        }
        try {
            check(valid()) { "TX cancelled" }
            check(readRadioValue(0x1C, 0x00).firstOrNull() == 0.toByte()) { "Radio is already transmitting" }
            val minimum = android.media.AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Phone does not support 48 kHz microphone capture" }
            recorder = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum * 2, 7680))
            check(recorder.state == android.media.AudioRecord.STATE_INITIALIZED) { "Microphone could not initialize" }
            txRecorder = recorder
            writeRadioValue(intArrayOf(0x14, 0x0A), levelBcd((txPowerTenths * 255.0 / 100).roundToInt()))
            writeRadioValue(intArrayOf(0x14, 0x03), levelBcd((squelchPercent * 255.0 / 100).roundToInt()))
            route(intArrayOf(0x1A, 0x05, 0x01, 0x18), intArrayOf(3)) // DATA OFF MOD = WLAN
            route(intArrayOf(0x1A, 0x05, 0x01, 0x19), intArrayOf(3)) // DATA MOD = WLAN
            route(intArrayOf(0x1A, 0x05, 0x01, 0x17), levelBcd(128)) // WLAN MOD = 50%
            synchronized(txGate) {
                check(valid()) { "TX cancelled" }
                recorder.startRecording()
                check(recorder.recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING) { "Microphone failed to start" }
                audioTrack?.setVolume(0f)
                keyed = true
                sendRadioCommand(0x1C, 0x00, 0x01)
                txTimeoutHandler.postDelayed(txTimeout, 180_000L)
            }
            check(readRadioValue(0x1C, 0x00).firstOrNull() == 1.toByte()) { "Radio did not confirm TX" }
            txStatus = "Transmitting"
            radioNotice("TX confirmed; phone microphone streaming.")
            refreshTxUi()
            val samples = ShortArray(960) // 20 ms, mono PCM16, little endian.
            val pcm = ByteArray(1920)
            val started = android.os.SystemClock.elapsedRealtime()
            while (valid()) {
                check(android.os.SystemClock.elapsedRealtime() - started < 180_000L) { "Three-minute TX timeout" }
                check(System.currentTimeMillis() - lastPttReplyAt < 3000L) { "TX status link lost" }
                check(radioTransmitting) { "Radio returned to RX" }
                var used = 0
                while (used < samples.size && valid()) {
                    val n = recorder.read(samples, used, samples.size - used, android.media.AudioRecord.READ_BLOCKING)
                    check(n > 0) { "Microphone read stopped ($n)" }
                    used += n
                }
                if (!valid()) break
                val gain = txMicPercent / 100.0
                for (i in samples.indices) {
                    val sample = (samples[i] * gain).roundToInt().coerceIn(-32768, 32767)
                    pcm[i * 2] = txByte(sample)
                    pcm[i * 2 + 1] = txByte(sample shr 8)
                }
                sendTxAudio(pcm, 0, 1364)
                sendTxAudio(pcm, 1364, 556)
            }
        } catch (e: Exception) {
            if (txWanted) radioNotice("TX stopped: ${e.message}")
        } finally {
            txTimeoutHandler.removeCallbacks(txTimeout)
            txWanted = false
            if (keyed || radioTransmitting) sendPttOff()
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            txRecorder = null
            if (sid == serialLocalSid && running && serialOpen) {
                try {
                    if (keyed) {
                        var confirmed = false
                        repeat(3) {
                            if (!confirmed) {
                                sendPttOff()
                                confirmed = readRadioValue(0x1C, 0x00).firstOrNull() == 0.toByte()
                            }
                        }
                        check(confirmed) { "RX could not be confirmed; check radio" }
                    }
                    for ((prefix, value) in restore.asReversed()) writeRadioValue(prefix, value)
                    radioNotice("RX; original radio microphone routing restored.")
                } catch (e: Exception) { radioNotice("RX/restore not confirmed: ${e.message}") }
            } else if (keyed) radioNotice("Connection lost; RX and microphone routing could not be confirmed. Check radio.")
            try { audioTrack?.setVolume(1f) } catch (_: Exception) {}
            audioRecoveryNotBeforeMs = System.currentTimeMillis() + 1500L
            txBusy = false
            txStatus = if (radioTransmitting) "RX not confirmed" else "RX"
            refreshTxUi()
        }
    }

    // WLAN audio layout verified against kappanhang's audioStream protocol.
    private fun sendTxAudio(pcm: ByteArray, offset: Int, length: Int) {
        synchronized(txGate) {
            if (!txWanted || !audioRunning) return
            val packet = TxWire.audioPacket(pcm, offset, length, txAudioSequence,
                txAudioInnerSequence, audioLocalSid, audioRemoteSid)
            txAudioSequence = (txAudioSequence + 1) and 65535
            txAudioInnerSequence = (txAudioInnerSequence + 1) and 65535
            sendAudioRaw(packet)
        }
    }

    private fun handleTxReply(civ: ByteArray) {
        if (civ.size < 6 || civ[0] != 0xFE.toByte() || civ[1] != 0xFE.toByte() ||
            civ[2] != 0xE0.toByte() || civ[3] != 0xA4.toByte() || civ.last() != 0xFD.toByte()) return
        radioReplies.offer(civ.copyOf())
        if (civ.size == 8 && civ[4] == 0x1C.toByte() && civ[5] == 0.toByte() && civ[6].toInt() in 0..1) {
            val before = radioTransmitting
            radioTransmitting = civ[6] == 1.toByte()
            lastPttReplyAt = System.currentTimeMillis()
            if (before != radioTransmitting) runOnUiThread {
                signalMeterBar.progress = 0
                signalMeterLabel.text = if (radioTransmitting) "Power out: waiting..." else "Signal: S0"
            }
            refreshTxUi()
        }
        if (civ.size == 9 && civ[4] == 0x15.toByte() && civ[5] == 0x11.toByte() && radioTransmitting) {
            val a = civ[6].toInt() and 255
            val b = civ[7].toInt() and 255
            if ((a and 15) > 9 || (a ushr 4) > 9 || (b and 15) > 9 || (b ushr 4) > 9) return
            val raw = (a ushr 4) * 1000 + (a and 15) * 100 + (b ushr 4) * 10 + (b and 15)
            // Icom Po meter anchors: 0=0%, 143=50%, 213=100%.
            val percent = TxWire.powerPercent(raw)
            runOnUiThread {
                if (radioTransmitting) {
                    signalMeterLabel.text = "Power out: $percent%"
                    signalMeterBar.progress = (percent * 255 / 100)
                    signalMeterBar.progressTintList = ColorStateList.valueOf(Color.rgb(240, 90, 20))
                }
            }
        }
    }

    override fun onResume() { super.onResume(); foreground = true; refreshTxUi() }
    override fun onPause() { foreground = false; cancelTransmit("App left foreground"); super.onPause() }
    override fun onDestroy() {
        cancelTransmit("App closed")
        radioWorker.shutdown()
        userDisconnectRequested = true
        Thread({ stopSession() }, "IC705-Close").start()
        super.onDestroy()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lock the IC-705 Remote app to portrait orientation.
        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        title = "IC-705 Remote Control $APP_VERSION"
        buildUserInterface()
    }
    private fun buildUserInterface() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        // Keep the controls close beneath the black title bar and leave room
        // for the connection controls on shorter screens.
        root.setPadding(12, dp(104), 12, dp(12))

        // Keep the live network light and its status text beside the frequency controls.
        linkQualityLight = View(this)
        linkQualityLight.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(110, 110, 110))
        }
        linkQualityText = TextView(this)
        linkQualityText.text = "NETWORK: DISCONNECTED"
        linkQualityText.textSize = 10f
        linkQualityText.setPadding(dp(4), 0, 0, 0)

        // Keep frequency entry above the tab buttons and outside the scrolling
        // MAIN pane so it remains visible when the on-screen keyboard opens.
        val frequencyRow = LinearLayout(this)
        frequencyRow.orientation = LinearLayout.HORIZONTAL
        frequencyRow.gravity = Gravity.CENTER_VERTICAL

        frequencyEdit = EditText(this)
        frequencyEdit.setSingleLine(true)
        frequencyEdit.hint = "Frequency Hz"
        frequencyEdit.textSize = 16f
        frequencyEdit.inputType =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        frequencyRow.addView(
            frequencyEdit,
            LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        setFrequencyButton = Button(this)
        setFrequencyButton.text = "SET"
        setFrequencyButton.isEnabled = false
        setFrequencyButton.minWidth = 0
        setFrequencyButton.minimumWidth = 0
        setFrequencyButton.setPadding(
            dp(6),
            setFrequencyButton.paddingTop,
            dp(6),
            setFrequencyButton.paddingBottom
        )
        frequencyRow.addView(
            setFrequencyButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(4)
            }
        )
        frequencyRow.addView(
            linkQualityLight,
            LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            }
        )
        frequencyRow.addView(
            linkQualityText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            frequencyRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val tabRow = LinearLayout(this)
        tabRow.orientation = LinearLayout.HORIZONTAL
        tabRow.gravity = Gravity.CENTER

        val mainTabButton = Button(this)
        mainTabButton.text = "MAIN"

        val settingsTabButton = Button(this)
        settingsTabButton.text = "SETTINGS"

        tabRow.addView(
            mainTabButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        tabRow.addView(
            settingsTabButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        root.addView(
            tabRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        mainPane = LinearLayout(this)
        mainPane.orientation = LinearLayout.VERTICAL

        settingsPane = LinearLayout(this)
        settingsPane.orientation = LinearLayout.VERTICAL
        settingsPane.gravity = Gravity.TOP
        val settingsTabs = LinearLayout(this)
        val networkSettingsPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val radioSettingsPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val webLinksPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val networkTab = Button(this).apply { text = "IP / PASSWORD" }
        val radioTab = Button(this).apply { text = "RADIO SETTINGS" }
        val webLinksTab = Button(this).apply { text = "WEB LINKS" }
        for (button in listOf(networkTab, radioTab, webLinksTab)) {
            button.textSize = 12f
            button.minWidth = 0
            button.minimumWidth = 0
            button.setPadding(dp(4), 0, dp(4), 0)
            settingsTabs.addView(button, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        settingsPane.addView(settingsTabs)
        settingsPane.addView(networkSettingsPane)
        settingsPane.addView(radioSettingsPane)
        settingsPane.addView(webLinksPane)
        buildRadioSettings(radioSettingsPane)
        buildWebLinksSettings(webLinksPane)
        fun selectSettingsTab(selected: LinearLayout) {
            networkSettingsPane.visibility = if (selected === networkSettingsPane) View.VISIBLE else View.GONE
            radioSettingsPane.visibility = if (selected === radioSettingsPane) View.VISIBLE else View.GONE
            webLinksPane.visibility = if (selected === webLinksPane) View.VISIBLE else View.GONE
        }
        networkTab.setOnClickListener { selectSettingsTab(networkSettingsPane) }
        radioTab.setOnClickListener { selectSettingsTab(radioSettingsPane) }
        webLinksTab.setOnClickListener { selectSettingsTab(webLinksPane) }

        fun addLabel(
            parent: LinearLayout,
            text: String
        ) {
            val label = TextView(this)
            label.text = text
            label.textSize = 16f
            label.setPadding(0, 6, 0, 2)
            parent.addView(
                label,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        fun addEditField(
            parent: LinearLayout,
            value: String,
            password: Boolean = false
        ): EditText {
            val edit = EditText(this)
            edit.setSingleLine(true)
            edit.setText(value)
            if (password) {
                edit.inputType =
                    InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            parent.addView(
                edit,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            return edit
        }

        // ----------------------------
        // SETTINGS TAB
        // ----------------------------
        val preferences =
            getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        val savedIp =
            preferences.getString(
                PREF_IP,
                DEFAULT_RADIO_IP
            ) ?: DEFAULT_RADIO_IP

        val savedUsername =
            preferences.getString(
                PREF_USERNAME,
                DEFAULT_USERNAME
            ) ?: DEFAULT_USERNAME

        val savedPassword =
            preferences.getString(
                PREF_PASSWORD,
                ""
            ) ?: ""

        requestedSpectrumWindowSizeDp = preferences.getInt(PREF_SPECTRUM_WINDOW_SIZE_DP, 300).coerceIn(140, 600)
        waterfallPaletteIndex = preferences.getInt(PREF_WATERFALL_PALETTE, 0).coerceIn(0, 4)

        addLabel(networkSettingsPane, "LAN IP Address (local network)")
        settingsIpEdit = addEditField(networkSettingsPane, savedIp)
        settingsIpEdit.inputType = InputType.TYPE_CLASS_PHONE

        addLabel(networkSettingsPane, "WAN IP Address (remote network)")
        settingsWanIpEdit = addEditField(networkSettingsPane,
            preferences.getString(PREF_WAN_IP, "") ?: "")
        settingsWanIpEdit.inputType = InputType.TYPE_CLASS_PHONE

        settingsUseWanCheck = CheckBox(this).apply {
            text = "Use WAN IP (unchecked = LAN)"
            isChecked = preferences.getBoolean(PREF_USE_WAN, false)
            minHeight = dp(48)
        }
        networkSettingsPane.addView(settingsUseWanCheck,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        val addressSelectionLabel = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        }
        networkSettingsPane.addView(addressSelectionLabel)
        fun updateAddressSelectionLabel() {
            addressSelectionLabel.text = if (settingsUseWanCheck.isChecked)
                "Next connection: WAN" else "Next connection: LAN"
        }
        settingsUseWanCheck.setOnCheckedChangeListener { _, _ ->
            updateAddressSelectionLabel()
        }
        updateAddressSelectionLabel()

        addLabel(networkSettingsPane, "Network User Name")
        settingsUsernameEdit = addEditField(networkSettingsPane, savedUsername)

        addLabel(networkSettingsPane, "Network Password")
        settingsPasswordEdit =
            addEditField(
                networkSettingsPane,
                savedPassword,
                password = true
            )

        val settingsNote = TextView(this)
        settingsNote.text =
            "Both addresses and your selection are saved on this device. Check WAN for remote access; leave it unchecked for LAN. Changes apply the next time you connect."
        settingsNote.textSize = 13f
        settingsNote.setPadding(0, 8, 0, 8)
        networkSettingsPane.addView(
            settingsNote,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val saveSettingsButton = Button(this)
        saveSettingsButton.text = "SAVE SETTINGS"
        networkSettingsPane.addView(
            saveSettingsButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        saveSettingsButton.setOnClickListener {
            saveNetworkSettings()
            mainTabButton.performClick()
        }

        // ----------------------------
        // MAIN TAB - SPECTRUM FIRST
        // ----------------------------
        val scopeControlRow = LinearLayout(this)
        scopeControlRow.orientation = LinearLayout.HORIZONTAL
        scopeControlRow.gravity = Gravity.CENTER_VERTICAL

        scopeToggleButton = Button(this)
        scopeToggleButton.text = "SPECTRUM ON"
        scopeToggleButton.isEnabled = false

        val waterfallColorButton = Button(this)
        waterfallColorButton.text = "COLOR: ${waterfallPaletteName(waterfallPaletteIndex)}"
        waterfallColorButton.setOnClickListener {
            val choices = arrayOf("Blue", "Green", "Amber", "Purple", "Grayscale")
            AlertDialog.Builder(this)
                .setTitle("Select waterfall color")
                .setSingleChoiceItems(choices, waterfallPaletteIndex) { dialog, which ->
                    waterfallPaletteIndex = which.coerceIn(0, choices.lastIndex)
                    scopeView.setWaterfallPalette(waterfallPaletteIndex)
                    waterfallColorButton.text = "COLOR: ${waterfallPaletteName(waterfallPaletteIndex)}"
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putInt(PREF_WATERFALL_PALETTE, waterfallPaletteIndex)
                        .apply()
                    dialog.dismiss()
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }

        scopeControlRow.addView(
            scopeToggleButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        scopeControlRow.addView(
            waterfallColorButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        mainPane.addView(
            scopeControlRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        spectrumSizeLabel = TextView(this)
        spectrumSizeLabel.text = "Spectrum window size: ${requestedSpectrumWindowSizeDp} dp high"
        spectrumSizeLabel.textSize = 14f
        mainPane.addView(
            spectrumSizeLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val spectrumSizeSeekBar = SeekBar(this)
        spectrumSizeSeekBar.min = 140
        spectrumSizeSeekBar.max = 600
        spectrumSizeSeekBar.progress = requestedSpectrumWindowSizeDp
        spectrumSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                requestedSpectrumWindowSizeDp = progress.coerceIn(140, 600)
                spectrumSizeLabel.text = "Spectrum window size: ${requestedSpectrumWindowSizeDp} dp high"
                if (::scopeView.isInitialized) {
                    scopeView.layoutParams = scopeView.layoutParams.apply {
                        height = dp(requestedSpectrumWindowSizeDp)
                    }
                    scopeView.minimumHeight = dp(140)
                    scopeView.requestLayout()
                }
                if (fromUser) {
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putInt(PREF_SPECTRUM_WINDOW_SIZE_DP, requestedSpectrumWindowSizeDp)
                        .apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        mainPane.addView(
            spectrumSizeSeekBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        scopeView = SpectrumWaterfallView(this)
        scopeView.setWaterfallPalette(waterfallPaletteIndex)
        scopeView.minimumHeight = dp(140)
        scopeView.onStepFrequency = { deltaHz ->
            if (connected && !userDisconnectRequested && !frequencyTuneBusy)
                shiftFrequencyBy(deltaHz, "Waterfall swipe 1 kHz")
        }
        scopeView.onTuneFrequency = tap@ { targetHz ->
            if (!connected || userDisconnectRequested || frequencyTuneBusy) return@tap
            frequencyEdit.setText(targetHz.toString())
            tuneFrequencyTo(targetHz, "Spectrum tap")
        }
        mainPane.addView(
            scopeView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(requestedSpectrumWindowSizeDp)
            )
        )
        scopeView.visibility = View.GONE

        sensitivityRow = LinearLayout(this)
        sensitivityRow.visibility = View.GONE
        sensitivityRow.orientation = LinearLayout.HORIZONTAL
        sensitivityRow.gravity = Gravity.CENTER_VERTICAL

        sensitivityLabel = TextView(this)
        sensitivityLabel.text = "Sensitivity: 1.00x"
        sensitivityLabel.textSize = 15f
        sensitivityRow.addView(
            sensitivityLabel,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        sensitivitySeekBar = SeekBar(this)
        sensitivitySeekBar.min = 25
        sensitivitySeekBar.max = 400
        sensitivitySeekBar.progress = 100
        sensitivityRow.addView(
            sensitivitySeekBar,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                2.5f
            )
        )
        mainPane.addView(
            sensitivityRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val bandwidthRow = LinearLayout(this)
        bandwidthRow.orientation = LinearLayout.HORIZONTAL
        bandwidthRow.gravity = Gravity.CENTER_VERTICAL

        bandwidthLabel = TextView(this)
        bandwidthLabel.text = "Frequency span: 75 kHz"
        bandwidthLabel.textSize = 14f
        bandwidthRow.addView(
            bandwidthLabel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        )

        val bandwidthSeekBar = SeekBar(this)
        bandwidthSeekBar.min = 5
        bandwidthSeekBar.max = 1000
        bandwidthSeekBar.progress = requestedDisplayBandwidthKHz
        bandwidthSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val khz = progress.coerceIn(5, 1000)
                requestedDisplayBandwidthKHz = khz
                bandwidthLabel.text = "Frequency span: ${khz} kHz"
                if (::scopeView.isInitialized) scopeView.setDisplayBandwidthKHz(khz)
                if (fromUser && running && serialOpen && scopeStarted) {
                    try {
                        sendScopeBandwidthForDisplay(khz)
                    } catch (e: Exception) {
                        appendLog("SPECTRUM BANDWIDTH ERROR: ${e.message}")
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        bandwidthRow.addView(
            bandwidthSeekBar,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.5f)
        )
        mainPane.addView(
            bandwidthRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val noiseFloorRow = LinearLayout(this)
        noiseFloorRow.orientation = LinearLayout.HORIZONTAL
        noiseFloorRow.gravity = Gravity.CENTER_VERTICAL

        noiseFloorLabel = TextView(this)
        noiseFloorLabel.text = "Noise floor: 0"
        noiseFloorLabel.textSize = 14f
        noiseFloorRow.addView(
            noiseFloorLabel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        )

        val noiseFloorSeek = SeekBar(this)
        noiseFloorSeek.min = 0
        noiseFloorSeek.max = 60
        noiseFloorSeek.progress = 0
        noiseFloorSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                noiseFloorLabel.text = "Noise floor: $progress"
                if (::scopeView.isInitialized) {
                    scopeView.setNoiseFloor(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        noiseFloorRow.addView(
            noiseFloorSeek,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.5f)
        )
        mainPane.addView(
            noiseFloorRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        // ----------------------------
        // SIGNAL METER
        // ----------------------------
        val signalMeterRow = LinearLayout(this)
        signalMeterRow.orientation = LinearLayout.HORIZONTAL
        signalMeterRow.gravity = Gravity.CENTER_VERTICAL

        signalMeterLabel = TextView(this)
        signalMeterLabel.text = "Signal: S0"
        signalMeterLabel.textSize = 16f
        signalMeterRow.addView(
            signalMeterLabel,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.2f
            )
        )

        signalMeterBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        )
        signalMeterBar.max = 255
        signalMeterBar.progress = 0
        signalMeterBar.progressTintList = ColorStateList.valueOf(Color.rgb(0, 190, 0))
        signalMeterRow.addView(
            signalMeterBar,
            LinearLayout.LayoutParams(
                0,
                dp(36),
                2.8f
            )
        )

        mainPane.addView(
            signalMeterRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        frequencyLabelView = TextView(this)
        frequencyLabelView.text = "Frequency: waiting for IC-705"
        val frequencyLabel = frequencyLabelView
        frequencyLabel.textSize = 15f
        mainPane.addView(
            frequencyLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val tuningRow = LinearLayout(this)
        tuningRow.orientation = LinearLayout.HORIZONTAL
        tuningRow.gravity = Gravity.CENTER

        down500Button = Button(this)
        down500Button.text = "−500 Hz"
        down500Button.isEnabled = false

        up500Button = Button(this)
        up500Button.text = "+500 Hz"
        up500Button.isEnabled = false

        down1kButton = Button(this)
        down1kButton.text = "−1 kHz"
        down1kButton.isEnabled = false

        up1kButton = Button(this)
        up1kButton.text = "+1 kHz"
        up1kButton.isEnabled = false

        tuningRow.addView(
            down500Button,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        tuningRow.addView(
            up500Button,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        tuningRow.addView(
            down1kButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        tuningRow.addView(
            up1kButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        mainPane.addView(
            tuningRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val modeRow = LinearLayout(this)
        modeRow.orientation = LinearLayout.HORIZONTAL
        modeRow.gravity = Gravity.CENTER

        usbButton = Button(this)
        usbButton.text = "USB"
        usbButton.isEnabled = false

        lsbButton = Button(this)
        lsbButton.text = "LSB"
        lsbButton.isEnabled = false

        modeRow.addView(
            usbButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        modeRow.addView(
            lsbButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        mainPane.addView(
            modeRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // ----------------------------
        // BAND QUICK-SELECT BUTTONS
        // ----------------------------
        addBandButtonRow(
            mainPane,
            listOf(
                "160m\n1.900" to 1_900_000L,
                "80m\n3.800" to 3_800_000L,
                "40m\n7.150" to 7_150_000L,
                "30m\n10.125" to 10_125_000L,
                "20m\n14.185" to 14_185_000L
            )
        )

        addBandButtonRow(
            mainPane,
            listOf(
                "17m\n18.150" to 18_150_000L,
                "15m\n21.300" to 21_300_000L,
                "12m\n24.950" to 24_950_000L,
                "10m\n28.400" to 28_400_000L
            )
        )

        val connectionRow = LinearLayout(this)
        connectionRow.orientation = LinearLayout.HORIZONTAL
        connectionRow.gravity = Gravity.CENTER

        connectButton = Button(this)
        connectButton.text = "CONNECT"
        connectButton.backgroundTintList = ColorStateList.valueOf(Color.rgb(0, 150, 0))

        disconnectButton = Button(this)
        disconnectButton.text = "DISCONNECTED"
        disconnectButton.isEnabled = false
        disconnectButton.backgroundTintList = ColorStateList.valueOf(Color.rgb(110, 110, 110))

        connectionRow.addView(
            connectButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        connectionRow.addView(
            disconnectButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        txButton = Button(this).apply {
            text = "TX"
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(3), 0, dp(3), 0)
            isEnabled = false
            setOnClickListener { toggleTransmit() }
        }
        connectButton.textSize = 11f
        disconnectButton.textSize = 11f
        connectionRow.addView(txButton, LinearLayout.LayoutParams(dp(88), dp(52)))
        // Fixed main-panel row above the scrolling spectrum and controls.
        root.addView(
            connectionRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        startupStatus = TextView(this).apply {
            textSize = 15f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        mainPane.addView(startupStatus, 0)

        val mainScrollView = ScrollView(this)
        mainScrollView.isFillViewport = true
        mainScrollView.addView(
            mainPane,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            mainScrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        // Each tab owns its scroll container so the hidden tab takes no space.
        val settingsScrollView = ScrollView(this)
        settingsScrollView.isFillViewport = true
        settingsScrollView.visibility = View.GONE
        settingsScrollView.addView(
            settingsPane,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
        root.addView(
            settingsScrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
        applyDarkTheme(root)
        updateConnectionButtons(connecting = false, isConnected = false)

        mainTabButton.setOnClickListener {
            connectionRow.visibility = View.VISIBLE
            mainScrollView.visibility = View.VISIBLE
            settingsScrollView.visibility = View.GONE
        }

        settingsTabButton.setOnClickListener {
            connectionRow.visibility = View.GONE
            mainScrollView.visibility = View.GONE
            settingsScrollView.visibility = View.VISIBLE
        }

        connectButton.setOnClickListener {
            userDisconnectRequested = false
            startSession()
        }
        disconnectButton.setOnClickListener {
            showDisconnectChoice()
        }
        scopeToggleButton.setOnClickListener {
            if (scopeStarted) {
                stopSpectrumScope()
            } else {
                startSpectrumScope()
            }
        }
        setFrequencyButton.setOnClickListener {
            setFrequencyFromUi()
        }
        down500Button.setOnClickListener {
            shiftFrequencyBy(-500L, "500 Hz")
        }
        up500Button.setOnClickListener {
            shiftFrequencyBy(500L, "500 Hz")
        }
        down1kButton.setOnClickListener {
            shiftFrequencyBy(-1000L, "1 kHz")
        }
        up1kButton.setOnClickListener {
            shiftFrequencyBy(1000L, "1 kHz")
        }
        usbButton.setOnClickListener {
            setOperatingModeFromUi(
                modeName = "USB",
                modeCode = 0x01
            )
        }
        lsbButton.setOnClickListener {
            setOperatingModeFromUi(
                modeName = "LSB",
                modeCode = 0x00
            )
        }

        sensitivitySeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    val gain = progress / 100.0f
                    sensitivityLabel.text =
                        String.format(
                            java.util.Locale.US,
                            "Sensitivity: %.2fx",
                            gain
                        )
                    if (::scopeView.isInitialized) {
                        scopeView.setSensitivity(gain)
                    }
                }
                override fun onStartTrackingTouch(
                    seekBar: SeekBar?
                ) {
                }
                override fun onStopTrackingTouch(
                    seekBar: SeekBar?
                ) {
                }
            }
        )

        scopeView.setSensitivity(1.0f)
        mainTabButton.performClick()
    }

    private fun applyDarkTheme(root: View) {
        val background = Color.rgb(16, 20, 24)
        val surface = Color.rgb(36, 45, 53)
        val foreground = Color.rgb(238, 243, 246)
        val secondary = Color.rgb(176, 190, 197)
        val accent = Color.rgb(64, 196, 255)
        root.setBackgroundColor(background)
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()

        fun style(view: View) {
            when (view) {
                is CheckBox -> {
                    view.setTextColor(foreground)
                    view.buttonTintList = ColorStateList.valueOf(accent)
                }
                is Button -> {
                    view.setTextColor(foreground)
                    view.backgroundTintList = ColorStateList.valueOf(surface)
                }
                is EditText -> {
                    view.setTextColor(foreground)
                    view.setHintTextColor(secondary)
                    view.backgroundTintList = ColorStateList.valueOf(accent)
                }
                is TextView -> view.setTextColor(foreground)
                is SeekBar -> {
                    view.progressTintList = ColorStateList.valueOf(accent)
                    view.thumbTintList = ColorStateList.valueOf(accent)
                }
                is ProgressBar -> view.progressTintList = ColorStateList.valueOf(accent)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) style(view.getChildAt(index))
            }
        }
        style(root)
        if (::signalMeterBar.isInitialized) {
            signalMeterBar.progressTintList = ColorStateList.valueOf(Color.rgb(0, 190, 0))
        }
    }

    private fun addBandButtonRow(
        parent: LinearLayout,
        bands: List<Pair<String, Long>>
    ) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER

        for ((label, hz) in bands) {
            val button = Button(this)
            button.text = label
            button.isEnabled = false
            button.setOnClickListener {
                frequencyEdit.setText(hz.toString())
                tuneFrequencyTo(hz, label.replace('\n', ' '))
            }

            row.addView(
                button,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
        }

        parent.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        bandButtonRows.add(row)
    }

    private val bandButtonRows = ArrayList<LinearLayout>()

    private fun enableBandButtons(enabled: Boolean) {
        for (row in bandButtonRows) {
            for (i in 0 until row.childCount) {
                row.getChildAt(i).isEnabled = enabled
            }
        }
    }

    private fun saveNetworkSettings() {
        val ip =
            settingsIpEdit.text.toString().trim()

        val username =
            settingsUsernameEdit.text.toString().trim()

        val password =
            settingsPasswordEdit.text.toString()

        getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putString(PREF_IP, ip)
            .putString(PREF_WAN_IP, settingsWanIpEdit.text.toString().trim())
            .putBoolean(PREF_USE_WAN, settingsUseWanCheck.isChecked)
            .putString(PREF_USERNAME, username)
            .putString(PREF_PASSWORD, password)
            .apply()
    }

    private fun updateConnectionButtons(connecting: Boolean, isConnected: Boolean) {
        refreshTxUi()
        runOnUiThread {
            if (powerDisconnectBusy.get()) {
                connectButton.isEnabled = false
                disconnectButton.isEnabled = false
                disconnectButton.text = "DISCONNECTING..."
                return@runOnUiThread
            }
            if (connecting) {
                connectButton.text = "CONNECTING..."
                connectButton.isEnabled = false
                connectButton.backgroundTintList = ColorStateList.valueOf(Color.rgb(210, 160, 0))
                disconnectButton.text = "CANCEL / DISCONNECT"
                disconnectButton.isEnabled = true
                disconnectButton.backgroundTintList = ColorStateList.valueOf(Color.rgb(170, 40, 40))
            } else if (isConnected) {
                connectButton.text = "CONNECTED"
                connectButton.isEnabled = false
                connectButton.backgroundTintList = ColorStateList.valueOf(Color.rgb(0, 150, 0))
                disconnectButton.text = "DISCONNECT / STANDBY"
                disconnectButton.isEnabled = true
                disconnectButton.backgroundTintList = ColorStateList.valueOf(Color.rgb(190, 35, 35))
            } else {
                connectButton.text = "CONNECT"
                connectButton.isEnabled = true
                connectButton.backgroundTintList = ColorStateList.valueOf(Color.rgb(0, 150, 0))
                disconnectButton.text = "DISCONNECTED"
                disconnectButton.isEnabled = false
                disconnectButton.backgroundTintList = ColorStateList.valueOf(Color.rgb(110, 110, 110))
            }
        }
    }

    private fun updateLinkQuality(audioAgeMs: Long, scopeAgeMs: Long) {
        if (!::linkQualityLight.isInitialized || !::linkQualityText.isInitialized) return
        val (label, color) = when {
            !running -> "DISCONNECTED" to Color.rgb(110, 110, 110)
            !connected -> "CONNECTING" to Color.rgb(220, 175, 0)
            audioRunning && scopeStarted && audioAgeMs <= 500L && scopeAgeMs <= 500L ->
                "GOOD" to Color.rgb(0, 190, 0)
            audioAgeMs <= 1500L && scopeAgeMs <= 1500L ->
                "FAIR" to Color.rgb(230, 175, 0)
            else -> "POOR" to Color.rgb(220, 35, 35)
        }
        if (label == lastLinkQualityLabel) return
        lastLinkQualityLabel = label
        runOnUiThread {
            if (::linkQualityLight.isInitialized) {
                linkQualityLight.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            }
            if (::linkQualityText.isInitialized) linkQualityText.text = "NETWORK: $label"
        }
    }

    private fun updateStartupStatus() {
        if (!::startupStatus.isInitialized) return
        if (!running || userDisconnectRequested || !startupWaiting) {
            startupStatus.visibility = View.GONE
            return
        }
        val audioReady = audioLastWriteAtMs > 0L
        val spectrumReady = scopeLastDecodedAtMs > 0L
        val seconds = ((android.os.SystemClock.elapsedRealtime() - startupStartedAt) / 1000L).coerceAtLeast(0L)
        val ready = connected && audioReady && spectrumReady
        val message = if (ready) "Radio ready - audio and waterfall received"
        else "Radio starting - ${seconds}s elapsed\n" +
            "Audio: ${if (audioReady) "received" else "waiting"} / " +
            "Waterfall: ${if (spectrumReady) "received" else "waiting"}\n" +
            if (seconds < 10L) "Waking from standby can take about 10 seconds."
            else "Still waiting for radio data..."
        startupStatus.visibility = View.VISIBLE
        if (startupStatus.text.toString() != message) startupStatus.text = message
        startupStatus.setTextColor(if (ready) Color.rgb(90, 230, 130) else Color.rgb(255, 210, 90))
        if (ready) startupWaiting = false
    }

    private fun startSession(preserveScreen: Boolean = false) {
        if (userDisconnectRequested || powerDisconnectBusy.get()) return
        if (running) {
            appendLog("Session already running.")
            return
        }
        // Recovery retains the address of the active session, even if the
        // selection is edited while connected.
        val radioIp = if (preserveScreen) activeRadioIp else
            (if (settingsUseWanCheck.isChecked) settingsWanIpEdit else settingsIpEdit)
                .text.toString().trim()

        val username = if (preserveScreen) activeUsername else
            settingsUsernameEdit.text.toString().trim()

        val password = if (preserveScreen) activePassword else
            settingsPasswordEdit.text.toString()

        if (!preserveScreen) saveNetworkSettings()
        if (radioIp.isEmpty()) {
            val message = if (!preserveScreen && settingsUseWanCheck.isChecked)
                "Enter the WAN IP address in Settings, or uncheck WAN to use LAN."
            else "Enter the LAN IP address in Settings."
            appendLog("ERROR: $message")
            runOnUiThread {
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        if (username.isEmpty()) {
            appendLog("ERROR: Enter the network username.")
            return
        }
        if (password.isEmpty()) {
            appendLog("ERROR: Enter the network password.")
            return
        }
        activeRadioIp = radioIp
        activeUsername = username
        activePassword = password
        audioStreamExpected = false
        audioStreamExpectedSinceAtMs = 0L
        audioRecoveryNotBeforeMs = 0L
        audioRecoveryGuard.set(false)
        autoSidebandChangePending = false
        if (!preserveScreen) clearLog()
        appendLog("App version: $APP_VERSION")
        synchronized(powerFrameBuffer) { powerFrameBuffer.clear() }
        synchronized(civFrameBuffer) { civFrameBuffer.clear() }
        powerReplies.clear()
        startupStartedAt = android.os.SystemClock.elapsedRealtime()
        // Automatic recovery keeps the main screen quiet; only CONNECT shows startup progress.
        startupWaiting = !preserveScreen
        running = true
        streamDiagnosticHandler.removeCallbacks(streamStallWatchdog)
        streamDiagnosticHandler.postDelayed(streamStallWatchdog, 250L)
        streamDiagnosticHandler.removeCallbacks(streamDiagnosticTask)
        streamDiagnosticHandler.postDelayed(streamDiagnosticTask, 5000L)
        connected = false
        authOk = false
        gotA8 = false
        connInfoOk = false
        teardownStarted = false
        outerSequence = 1
        authInnerSequence = 0
        pkt7Sequence = 2
        pkt7InnerSequence = 0x8304
        serialLocalSid = 0
        serialRemoteSid = 0
        serialOuterSequence = 1
        serialPkt7Sequence = 2
        serialCivSequence = 1
        serialOpen = false
        audioRunning = false
        resetReceiveSequenceState(audioReceiveSequenceState)
        resetReceiveSequenceState(serialReceiveSequenceState)
        audioHaveSequence = false
        audioLastSequence = 0
        audioPacketCount = 0L
        audioDebugPacketCount = 0
        audioWriteCalls = 0L
        audioWriteBytes = 0L
        audioWriteErrors = 0L
        audioLastPacketAtMs = 0L
        audioStreamExpectedSinceAtMs = 0L
        audioRecoveryNotBeforeMs = 0L
        audioLastWriteAtMs = 0L
        frequencyReceived = false
        frequencyWriteResponseReceived = false
        frequencyWriteRejected = false
        frequencyHz = 0L
        signalMeterValue = 0
        scopeTransportPackets = 0L
        scopeCivFrames = 0L
        scopeRaw27Frames = 0L
        scopeDecodedFrames = 0L
        scopeLastTransportAtMs = 0L
        scopeLastRawFrameAtMs = 0L
        scopeLastDecodedAtMs = 0L
        scopePollCount = 0L
        scopeAwaitingResponse = false
        scopeLastUdpBytes = 0
        scopeLastCivBytes = 0
        scopeAwaitingResponse = false
        scopeStarted = false
        stopScopePolling()
        resetScopeAssembly()
        if (!preserveScreen && ::scopeView.isInitialized) {
            scopeView.clearScope()
            scopeView.visibility = View.GONE
        }
        if (!preserveScreen && ::sensitivityRow.isInitialized) {
            sensitivityRow.visibility = View.GONE
        }
        activeModeCode = -1
        modeReceived = false
        trackedPackets.clear()
        serialTrackedPackets.clear()
        if (!preserveScreen) updateConnectionButtons(connecting = true, isConnected = false)
        if (!preserveScreen) setFrequencyButton.isEnabled = false
        if (!preserveScreen && ::scopeToggleButton.isInitialized) {
            scopeToggleButton.isEnabled = false
            scopeToggleButton.text = "SPECTRUM ON"
            if (::scopeView.isInitialized) {
                scopeView.visibility = View.GONE
            }
            if (::sensitivityRow.isInitialized) {
                sensitivityRow.visibility = View.GONE
            }
        }
        if (!preserveScreen) enableBandButtons(false)
        runOnUiThread { updateStartupStatus() }
        sessionThread = Thread {
            try {
                runSession(radioIp, username, password, preserveScreen)
            } catch (e: Exception) {
                if (userDisconnectRequested) return@Thread
                appendLog("SESSION ERROR: ${e.message}")
                try {
                    stopSession(preserveScreen)
                } catch (disconnectError: Exception) {
                    appendLog("DISCONNECT ERROR: ${disconnectError.message}")
                    cleanupSocketOnly(preserveScreen)
                }
                if (preserveScreen && !userDisconnectRequested) {
                    showRecoveryFailure()
                }
            } finally {
                if (!userDisconnectRequested && !connected && running) {
                    cleanupSocketOnly(preserveScreen)
                }
            }
        }
        sessionThread?.start()
    }
    private fun runSession(
        radioIp: String,
        username: String,
        password: String,
        preserveScreen: Boolean = false
    ) {
        appendLog("================================")
        appendLog("IC-705 REMOTE CONNECTION")
        appendLog("================================")
        appendLog("Radio IP: $radioIp")
        appendLog("Control Port: $CONTROL_PORT")
        appendLog("")
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("0.0.0.0", CONTROL_PORT))
        socket.connect(InetSocketAddress(radioIp, CONTROL_PORT))
        socket.soTimeout = SOCKET_TIMEOUT_MS
        controlSocket = socket
        val localAddressBytes = socket.localAddress.address
        if (localAddressBytes.size < 4) {
            throw Exception("Could not determine local IPv4 address.")
        }
        val b2 = localAddressBytes[localAddressBytes.size - 2].toInt() and 0xFF
        val b3 = localAddressBytes[localAddressBytes.size - 1].toInt() and 0xFF
        localSid =
            (b2 shl 24) or
                    (b3 shl 16) or
                    (CONTROL_PORT and 0xFFFF)
        appendLog("Local IP: ${socket.localAddress.hostAddress}")
        appendLog("Local UDP Port: $CONTROL_PORT")
        appendLog("Local SID: ${intToHex(localSid)}")
        appendLog("")
        appendLog("STEP 1: AYT")
        sendRaw(buildAyt(localSid))
        Thread.sleep(50)
        sendRaw(buildAyt(localSid))
        val pkt4 = receiveExpected(
            expectedLength = 16,
            timeoutMs = 3000
        ) {
            it.size >= 8 &&
                    it[0].toInt() and 0xFF == 0x10 &&
                    it[4].toInt() and 0xFF == 0x04 &&
                    it[5].toInt() and 0xFF == 0x00
        }
        if (pkt4 == null) {
            throw Exception("No I-AM-HERE response from IC-705.")
        }
        remoteSid = readBeInt(pkt4, 8)
        appendLog("I-AM-HERE received")
        appendLog("Remote SID: ${intToHex(remoteSid)}")
        appendLog("")
        appendLog("STEP 2: READY")
        sendRaw(buildReady(localSid, remoteSid))
        Thread.sleep(50)
        sendRaw(buildReady(localSid, remoteSid))
        val readyReply = receiveExpected(
            expectedLength = 16,
            timeoutMs = 3000
        ) {
            it.size >= 8 &&
                    it[0].toInt() and 0xFF == 0x10 &&
                    it[4].toInt() and 0xFF == 0x06 &&
                    it[5].toInt() and 0xFF == 0x00
        }
        if (readyReply == null) {
            throw Exception("No READY response from IC-705.")
        }
        appendLog("READY response received")
        appendLog("")
        appendLog("STEP 3: LOGIN")
        val loginPacket = buildLogin(username, password)
        sendTracked(loginPacket)
        val loginReply = receiveExpected(
            expectedLength = 96,
            timeoutMs = LOGIN_TIMEOUT_MS
        ) {
            it.size >= 8 &&
                    it[0].toInt() and 0xFF == 0x60 &&
                    it[1].toInt() and 0xFF == 0x00 &&
                    it[2].toInt() and 0xFF == 0x00 &&
                    it[3].toInt() and 0xFF == 0x00
        }
        if (loginReply == null) {
            throw Exception("LOGIN timeout - IC-705 did not answer.")
        }
        appendLog("LOGIN response received")
        appendLog("LOGIN length: ${loginReply.size}")
        if (loginReply.size >= 52) {
            val status = hex(loginReply, 48, 4)
            appendLog("LOGIN status bytes 48-51: $status")
            if (
                (loginReply[48].toInt() and 0xFF) == 0xFF &&
                (loginReply[49].toInt() and 0xFF) == 0xFF &&
                (loginReply[50].toInt() and 0xFF) == 0xFF &&
                (loginReply[51].toInt() and 0xFF) == 0xFE
            ) {
                throw Exception("LOGIN rejected: invalid username/password.")
            }
        }
        if (loginReply.size < 32) {
            throw Exception("LOGIN response too short.")
        }
        System.arraycopy(loginReply, 26, authId, 0, 6)
        appendLog("LOGIN ACCEPTED")
        appendLog("Auth ID: ${hex(authId)}")
        startReceiverThread()
        startKeepAlive()
        appendLog("")
        appendLog("STEP 4: AUTH #1")
        sendTracked(buildAuth(0x02))
        appendLog("AUTH #1 sent")
        appendLog("")
        appendLog("STEP 5: AUTH #2")
        sendTracked(buildAuth(0x05))
        appendLog("AUTH #2 sent")
        val authDeadline = System.currentTimeMillis() + AUTH_TIMEOUT_MS
        while (
            running &&
            !(authOk && gotA8) &&
            System.currentTimeMillis() < authDeadline
        ) {
            Thread.sleep(50)
        }
        if (!authOk) {
            throw Exception("AUTH #2 response was not received.")
        }
        appendLog("AUTH #2 SUCCESS")
        if (!gotA8) {
            throw Exception("A8 capabilities packet was not received.")
        }
        appendLog("A8 CAPABILITIES RECEIVED")
        appendLog("A8 Reply ID: ${hex(a8ReplyId)}")
        appendLog("")
        appendLog("STEP 6: CONNINFO")
        sendTracked(buildConnInfo(username))
        val connInfoDeadline =
            System.currentTimeMillis() + CONNINFO_TIMEOUT_MS
        while (
            running &&
            !connInfoOk &&
            System.currentTimeMillis() < connInfoDeadline
        ) {
            Thread.sleep(50)
        }
        if (!connInfoOk) {
            throw Exception("CONNINFO response was not successful.")
        }
        appendLog("")
        appendLog("STEP 7: OPEN SERIAL / CI-V STREAM")
        openSerialStream(radioIp)
        appendLog("SERIAL STREAM OPEN")
        serialOpen = true
        appendLog("")
        check(!userDisconnectRequested) { "Connection cancelled." }
        appendLog("POWER: requesting wake-up before the initial frequency read.")
        sendPowerCommand(0x18, 0x01)
        appendLog("STEP 8: READ FREQUENCY / CONFIRM WAKE-UP")
        sendCivReadFrequency()
        val frequencyDeadline =
            System.currentTimeMillis() + 15000L
        var nextFrequencyRetryAt =
            System.currentTimeMillis() + 1000L
        while (
            running &&
            !userDisconnectRequested && !frequencyReceived &&
            System.currentTimeMillis() < frequencyDeadline
        ) {
            val now = System.currentTimeMillis()
            if (now >= nextFrequencyRetryAt && !frequencyReceived) {
                appendLog("CI-V frequency response not received yet; retrying read.")
                sendCivReadFrequency()
                nextFrequencyRetryAt = now + 1000L
            }
            Thread.sleep(50)
        }
        if (!frequencyReceived) {
            throw Exception("CI-V frequency response was not received.")
        }
        check(!userDisconnectRequested && running) { "Connection cancelled." }
        connected = true
        applyRadioSliders()
        automaticReconnectAttempts = 0
        runOnUiThread {
            if (userDisconnectRequested) return@runOnUiThread
            if (!preserveScreen) updateConnectionButtons(connecting = false, isConnected = true)
            setFrequencyButton.isEnabled = true
            down500Button.isEnabled = true
            up500Button.isEnabled = true
            down1kButton.isEnabled = true
            up1kButton.isEnabled = true
            usbButton.isEnabled = true
            lsbButton.isEnabled = true
            enableBandButtons(true)
            scopeToggleButton.isEnabled = true
            if (!preserveScreen) {
                scopeToggleButton.text = "SPECTRUM ON"
                scopeView.visibility = View.GONE
                sensitivityRow.visibility = View.GONE
                signalMeterLabel.text = "Signal: S0"
                signalMeterBar.progress = 0
                signalMeterBar.progressTintList = ColorStateList.valueOf(Color.rgb(0, 190, 0))
            }
        }
        startSignalMeterPolling()
        appendLog("")
        appendLog("================================")
        appendLog("CI-V FREQUENCY READ SUCCESS")
        appendLog("================================")
        appendLog("Frequency: ${formatFrequency(frequencyHz)}")
        applyAutomaticSideband(frequencyHz)

        runOnUiThread {
            frequencyEdit.setText(
                frequencyHz.toString()
            )
            frequencyLabelView.text = "Frequency: ${formatFrequency(frequencyHz)}"
        }

        appendLog("")
        appendLog("Control + serial/CI-V session is established.")
        appendLog("Initial IC-705 frequency loaded into SET FREQUENCY field.")

        if (userDisconnectRequested) return
        // Establish audio first, matching the working attached spectrum app.
        // Start the scope only after the audio handshake has finished.
        audioStreamExpected = true
        audioStreamExpectedSinceAtMs = System.currentTimeMillis()
        audioRecoveryGuard.set(true)
        try {
            openAudioReceive(radioIp)
        } catch (e: Exception) {
            appendLog("AUDIO START FAILED: ${e.message}; quick stream recovery will retry.")
        } finally {
            audioRecoveryGuard.set(false)
        }

        if (userDisconnectRequested) return
        appendLog("Starting Spectrum automatically in CENTER mode after audio setup.")
        try {
            startSpectrumScope()
        } catch (e: Exception) {
            appendLog("SPECTRUM AUTO-START FAILED: ${e.message}")
        }

        while (running && !userDisconnectRequested) {
            Thread.sleep(250)
        }
    }
    private fun openSerialStream(radioIp: String) {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("0.0.0.0", SERIAL_PORT))
        socket.connect(InetSocketAddress(radioIp, SERIAL_PORT))
        socket.soTimeout = SOCKET_TIMEOUT_MS
        serialSocket = socket
        resetReceiveSequenceState(serialReceiveSequenceState)
        val localAddressBytes = socket.localAddress.address
        if (localAddressBytes.size < 4) {
            throw Exception("Could not determine local IPv4 address for serial stream.")
        }
        val b2 =
            localAddressBytes[localAddressBytes.size - 2].toInt() and 0xFF
        val b3 =
            localAddressBytes[localAddressBytes.size - 1].toInt() and 0xFF
        serialLocalSid =
            (b2 shl 24) or
                    (b3 shl 16) or
                    (SERIAL_PORT and 0xFFFF)
        appendLog("Serial local IP: ${socket.localAddress.hostAddress}")
        appendLog("Serial local UDP port: $SERIAL_PORT")
        appendLog("Serial local SID: ${intToHex(serialLocalSid)}")
        appendLog("Serial STEP 1: AYT")
        sendSerialRaw(buildSerialAyt(serialLocalSid))
        Thread.sleep(50)
        sendSerialRaw(buildSerialAyt(serialLocalSid))
        val pkt4 = receiveSerialExpected(
            expectedLength = 16,
            timeoutMs = 3000
        ) {
            it.size >= 8 &&
                    it[0].toInt() and 0xFF == 0x10 &&
                    it[4].toInt() and 0xFF == 0x04 &&
                    it[5].toInt() and 0xFF == 0x00
        }
        if (pkt4 == null) {
            throw Exception("No serial I-AM-HERE response from IC-705.")
        }
        serialRemoteSid = readBeInt(pkt4, 8)
        appendLog("Serial I-AM-HERE received")
        appendLog("Serial remote SID: ${intToHex(serialRemoteSid)}")
        appendLog("Serial STEP 2: READY")
        sendSerialRaw(
            buildSerialReady(
                serialLocalSid,
                serialRemoteSid
            )
        )
        Thread.sleep(50)
        sendSerialRaw(
            buildSerialReady(
                serialLocalSid,
                serialRemoteSid
            )
        )
        val readyReply = receiveSerialExpected(
            expectedLength = 16,
            timeoutMs = 3000
        ) {
            it.size >= 8 &&
                    it[0].toInt() and 0xFF == 0x10 &&
                    it[4].toInt() and 0xFF == 0x06 &&
                    it[5].toInt() and 0xFF == 0x00
        }
        if (readyReply == null) {
            throw Exception("No serial READY response from IC-705.")
        }
        appendLog("Serial READY response received")
        startSerialReceiverThread()
        startSerialKeepAlive()
        sendSerialTracked(buildSerialOpen())
        Thread.sleep(100)
        appendLog("Serial OPEN packet sent")
    }
    private fun buildSerialAyt(
        sid: Int
    ): ByteArray {
        val packet = ByteArray(16)
        packet[0] = 0x10.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x03
        packet[5] = 0x00
        packet[6] = 0x00
        packet[7] = 0x00
        writeBeInt(packet, 8, sid)
        writeBeInt(packet, 12, 0)
        return packet
    }
    private fun buildSerialReady(
        localSid: Int,
        remoteSid: Int
    ): ByteArray {
        val packet = ByteArray(16)
        packet[0] = 0x10.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x06
        packet[5] = 0x00
        packet[6] = 0x01
        packet[7] = 0x00
        writeBeInt(packet, 8, localSid)
        writeBeInt(packet, 12, remoteSid)
        return packet
    }
    private fun buildSerialOpen(): ByteArray {
        val packet = ByteArray(22)
        packet[0] = 0x16.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x00
        packet[5] = 0x00
        writeBeInt(packet, 8, serialLocalSid)
        writeBeInt(packet, 12, serialRemoteSid)
        packet[16] = 0xC0.toByte()
        packet[17] = 0x01
        packet[18] = 0x00
        packet[19] =
            ((serialCivSequence ushr 8) and 0xFF).toByte()
        packet[20] =
            (serialCivSequence and 0xFF).toByte()
        packet[21] = 0x05
        serialCivSequence =
            (serialCivSequence + 1) and 0xFFFF
        return packet
    }
    private fun buildSerialClose(): ByteArray {
        val packet = ByteArray(22)
        packet[0] = 0x16.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x00
        packet[5] = 0x00
        writeBeInt(packet, 8, serialLocalSid)
        writeBeInt(packet, 12, serialRemoteSid)
        packet[16] = 0xC0.toByte()
        packet[17] = 0x01
        packet[18] = 0x00
        packet[19] =
            ((serialCivSequence ushr 8) and 0xFF).toByte()
        packet[20] =
            (serialCivSequence and 0xFF).toByte()
        packet[21] = 0x00
        serialCivSequence =
            (serialCivSequence + 1) and 0xFFFF
        return packet
    }
    private fun automaticallyWriteTestFrequency(
        radioIp: String
    ) {
        if (!running || !serialOpen) {
            appendLog("ERROR: CI-V serial stream is not open.")
            return
        }

        val hz = TEST_WRITE_FREQUENCY_HZ

        appendLog("")
        appendLog("================================")
        appendLog("AUTOMATIC FREQUENCY WRITE TEST")
        appendLog("================================")
        appendLog("Target: ${formatFrequency(hz)}")

        frequencyWriteResponseReceived = false
        frequencyWriteRejected = false

        try {
            sendCivSetFrequency(hz)
        } catch (e: Exception) {
            appendLog("WRITE SEND FAILED: ${e.message}")
            appendLog("Closing IC-705 connection.")
            stopSession()
            return
        }

        val writeDeadline =
            System.currentTimeMillis() + FREQUENCY_TIMEOUT_MS

        while (
            running &&
            !frequencyWriteResponseReceived &&
            !frequencyWriteRejected &&
            System.currentTimeMillis() < writeDeadline
        ) {
            Thread.sleep(50)
        }

        if (!running) {
            return
        }

        if (frequencyWriteRejected) {
            appendLog("IC-705 REJECTED THE FREQUENCY WRITE (CI-V NAK).")
            appendLog("Closing IC-705 connection.")
            stopSession()
            return
        }

        if (!frequencyWriteResponseReceived) {
            appendLog("WRITE FAILED: No CI-V 25 00 response received.")
            appendLog("Closing IC-705 connection.")
            stopSession()
            return
        }

        appendLog("CI-V FREQUENCY SET RESPONSE RECEIVED")
        appendLog("Radio reported: ${formatFrequency(frequencyHz)}")

        Thread.sleep(100)

        if (!running) {
            return
        }

        appendLog("READING FREQUENCY BACK FOR FINAL VERIFICATION")

        frequencyReceived = false

        try {
            sendCivReadFrequency()
        } catch (e: Exception) {
            appendLog("VERIFY READ SEND FAILED: ${e.message}")
            appendLog("Closing IC-705 connection.")
            stopSession()
            return
        }

        val verifyDeadline =
            System.currentTimeMillis() + FREQUENCY_TIMEOUT_MS

        while (
            running &&
            !frequencyReceived &&
            System.currentTimeMillis() < verifyDeadline
        ) {
            Thread.sleep(50)
        }

        if (!running) {
            return
        }

        if (!frequencyReceived) {
            appendLog("VERIFY FAILED: No frequency response.")
            appendLog("Closing IC-705 connection.")
            stopSession()
            return
        }

        if (frequencyHz == hz) {
            appendLog("================================")
            appendLog("FREQUENCY WRITE VERIFIED")
            appendLog("IC-705 NOW REPORTS: ${formatFrequency(frequencyHz)}")
            appendLog("================================")
            appendLog("Spectrum-only test: RX audio remains OFF.")
        } else {
            appendLog("================================")
            appendLog("FREQUENCY VERIFY MISMATCH")
            appendLog("REQUESTED: ${formatFrequency(hz)}")
            appendLog("RADIO REPORTS: ${formatFrequency(frequencyHz)}")
            appendLog("Closing IC-705 connection.")
            appendLog("================================")
            stopSession()
        }
    }

    private fun shiftFrequencyBy(
        deltaHz: Long,
        stepLabel: String
    ) {
        if (!running || !serialOpen) {
            appendLog("ERROR: CI-V serial stream is not open.")
            return
        }

        val currentHz = frequencyHz
        if (currentHz <= 0L) {
            appendLog("ERROR: Current IC-705 frequency is not known yet.")
            return
        }

        val newHz = currentHz + deltaHz
        if (newHz < 1_000L || newHz > 9_999_999_999L) {
            appendLog("ERROR: $stepLabel step would move outside the supported range.")
            return
        }

        if (frequencyTuneBusy) {
            appendLog("$stepLabel TUNE IGNORED: previous tune is still being confirmed")
            return
        }
        frequencyTuneBusy = true
        runOnUiThread { setTuneControlsEnabled(false) }
        appendLog("$stepLabel TUNE REQUEST: ${formatFrequency(currentHz)} -> ${formatFrequency(newHz)}")

        Thread {
            try {
                frequencyWriteResponseReceived = false
                frequencyWriteRejected = false
                sendCivSetFrequency(newHz)
                appendLog("$stepLabel CI-V frequency-set command sent: ${formatFrequency(newHz)}")
                val ackDeadline = System.currentTimeMillis() + 1000L
                while (running && !frequencyWriteResponseReceived && !frequencyWriteRejected &&
                    System.currentTimeMillis() < ackDeadline) Thread.sleep(25L)

                if (frequencyWriteRejected) {
                    appendLog("$stepLabel TUNE REJECTED by IC-705")
                } else {
                    frequencyReceived = false
                    sendCivReadFrequency()
                    val readDeadline = System.currentTimeMillis() + 1200L
                    while (running && !frequencyReceived && System.currentTimeMillis() < readDeadline) Thread.sleep(25L)
                    if (frequencyReceived) {
                        val actualHz = frequencyHz
                        appendLog(if (actualHz == newHz) {
                            "$stepLabel TUNE VERIFIED: ${formatFrequency(actualHz)}"
                        } else {
                            "$stepLabel TUNE MISMATCH: requested ${formatFrequency(newHz)}, radio reports ${formatFrequency(actualHz)}"
                        })
                        runOnUiThread {
                            frequencyEdit.setText(actualHz.toString())
                            frequencyLabelView.text = "Frequency: ${formatFrequency(actualHz)}"
                            if (::scopeView.isInitialized) scopeView.centerFrequencyHz = actualHz
                        }
                    } else {
                        appendLog("$stepLabel TUNE UNCONFIRMED: no frequency readback; UI left at radio's last reported frequency")
                    }
                }
            } catch (e: Exception) {
                if (running) appendLog("$stepLabel TUNE ERROR: ${e.message}")
            } finally {
                frequencyTuneBusy = false
                if (running) runOnUiThread { setTuneControlsEnabled(true) }
            }
        }.start()
    }

    @Volatile private var frequencyTuneBusy = false

    private fun setTuneControlsEnabled(enabled: Boolean) {
        setFrequencyButton.isEnabled = enabled && running && serialOpen
        down500Button.isEnabled = enabled && running && serialOpen
        up500Button.isEnabled = enabled && running && serialOpen
        down1kButton.isEnabled = enabled && running && serialOpen
        up1kButton.isEnabled = enabled && running && serialOpen
    }

    private fun setFrequencyFromUi() {
        if (!running || !serialOpen) {
            appendLog("ERROR: CI-V serial stream is not open.")
            return
        }
        val text = frequencyEdit.text.toString().trim()
        if (text.isEmpty()) {
            appendLog("ERROR: Enter a frequency in Hz.")
            return
        }
        val hz = try { text.toLong() } catch (_: NumberFormatException) {
            appendLog("ERROR: Frequency must be a whole number of Hz.")
            return
        }
        if (hz < 1_000L || hz > 9_999_999_999L) {
            appendLog("ERROR: Frequency is outside the supported range.")
            return
        }
        tuneFrequencyTo(hz, "Manual frequency")
    }

    private fun tuneFrequencyTo(targetHz: Long, sourceLabel: String) {
        if (!running || !serialOpen) {
            appendLog("$sourceLabel: CI-V serial stream is not open.")
            return
        }
        if (targetHz < 1_000L || targetHz > 9_999_999_999L) {
            appendLog("$sourceLabel: requested frequency is outside the supported range.")
            return
        }
        if (frequencyTuneBusy) {
            appendLog("$sourceLabel: previous frequency change is still in progress; tap again shortly.")
            return
        }
        frequencyTuneBusy = true
        runOnUiThread {
            setTuneControlsEnabled(false)
            enableBandButtons(false)
        }
        appendLog("$sourceLabel SELECTED: requesting ${formatFrequency(targetHz)}")
        Thread {
            try {
                frequencyWriteResponseReceived = false
                frequencyWriteRejected = false
                sendCivSetFrequency(targetHz)
                val ackDeadline = System.currentTimeMillis() + 1500L
                while (running && !frequencyWriteResponseReceived && !frequencyWriteRejected &&
                    System.currentTimeMillis() < ackDeadline) Thread.sleep(25L)
                if (!running) return@Thread
                if (frequencyWriteRejected) {
                    appendLog("$sourceLabel REJECTED: IC-705 returned CI-V NAK.")
                    return@Thread
                }
                if (!frequencyWriteResponseReceived) {
                    appendLog("$sourceLabel: no CI-V ACK received; checking the radio frequency readback anyway.")
                }
                frequencyReceived = false
                sendCivReadFrequency()
                val readDeadline = System.currentTimeMillis() + 1500L
                while (running && !frequencyReceived && System.currentTimeMillis() < readDeadline) Thread.sleep(25L)
                if (!running) return@Thread
                if (!frequencyReceived) {
                    appendLog("$sourceLabel NOT CONFIRMED: IC-705 frequency readback timed out.")
                    return@Thread
                }
                val actualHz = frequencyHz
                if (actualHz == targetHz) {
                    appendLog("$sourceLabel CONFIRMED: radio is tuned to ${formatFrequency(actualHz)}.")
                } else {
                    appendLog("$sourceLabel MISMATCH: requested ${formatFrequency(targetHz)}, radio reports ${formatFrequency(actualHz)}.")
                }
            } catch (e: Exception) {
                if (running) appendLog("$sourceLabel ERROR: ${e.message}")
            } finally {
                frequencyTuneBusy = false
                if (running) runOnUiThread {
                    setTuneControlsEnabled(true)
                    enableBandButtons(true)
                }
            }
        }.start()
    }

    // =================================================================
    // IC-705 RECEIVE AUDIO - UDP 50003
    // Reference wire format: nonoo/kappanhang audiostream.go
    // 48 kHz, 16-bit, mono PCM. Audio payload begins at byte 24.
    // =================================================================

    private fun resolveLocalIpv4ForRadio(
        radioIp: String
    ): ByteArray {
        val probe =
            DatagramSocket()

        return try {
            probe.connect(
                InetSocketAddress(
                    radioIp,
                    AUDIO_PORT
                )
            )

            val addr =
                probe.localAddress.address

            if (addr.size < 4) {
                throw Exception(
                    "Local address is not IPv4."
                )
            }

            addr
        } finally {
            try {
                probe.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun recordAudioPacket(
        direction: String,
        label: String,
        data: ByteArray
    ) {
        appendLog(
            "$direction $label len=${data.size}: ${hex(data)}"
        )
    }

    private fun openAudioReceive(
        radioIp: String,
        fastRecovery: Boolean = false
    ) {
        if (!running) {
            throw Exception("Session is not running.")
        }

        if (audioRunning) {
            appendLog("Receive audio is already active.")
            return
        }

        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(
            InetSocketAddress(
                "0.0.0.0",
                AUDIO_PORT
            )
        )
        socket.connect(
            InetSocketAddress(
                radioIp,
                AUDIO_PORT
            )
        )
        socket.soTimeout = SOCKET_TIMEOUT_MS

        audioSocket = socket
        resetReceiveSequenceState(audioReceiveSequenceState)

        appendLog(
            "Audio socket local port: ${socket.localPort}"
        )
        appendLog(
            "Audio socket remote: $radioIp:$AUDIO_PORT"
        )

        val localAddressBytes =
            resolveLocalIpv4ForRadio(radioIp)

        if (localAddressBytes.size < 4) {
            throw Exception(
                "Could not determine local IPv4 address for audio."
            )
        }

        audioLocalSid =
            ((localAddressBytes[0].toInt() and 0xFF) shl 24) or
                    ((localAddressBytes[1].toInt() and 0xFF) shl 16) or
                    ((localAddressBytes[2].toInt() and 0xFF) shl 8) or
                    (localAddressBytes[3].toInt() and 0xFF)

        audioLocalSid =
            (audioLocalSid shl 16) or
                    (AUDIO_PORT and 0xFFFF)

        appendLog(
            "Audio local IPv4: " +
                    "${localAddressBytes[0].toInt() and 0xFF}." +
                    "${localAddressBytes[1].toInt() and 0xFF}." +
                    "${localAddressBytes[2].toInt() and 0xFF}." +
                    "${localAddressBytes[3].toInt() and 0xFF}"
        )

        appendLog("")
        appendLog("================================")
        appendLog("IC-705 RECEIVE AUDIO")
        appendLog("================================")
        appendLog("Audio UDP local port: $AUDIO_PORT")
        appendLog(
            "Audio local SID: ${intToHex(audioLocalSid)}"
        )

        // Stream start is the same packet-3 / packet-4 / packet-6
        // handshake used by kappanhang's audio stream.
        appendLog("AUDIO STEP 1: PKT3")

        val audioPkt3 = buildAudioPkt3()

        recordAudioPacket(
            "TX",
            "PKT3 #1",
            audioPkt3
        )
        sendAudioRaw(audioPkt3)

        Thread.sleep(if (fastRecovery) 10L else 50L)

        recordAudioPacket(
            "TX",
            "PKT3 #2",
            audioPkt3
        )
        sendAudioRaw(audioPkt3)

        val pkt4 =
            receiveAudioExpected(
                expectedLength = 16,
                timeoutMs = if (fastRecovery) 800 else 3000
            ) {
                it.size >= 16 &&
                        (it[0].toInt() and 0xFF) == 0x10 &&
                        (it[4].toInt() and 0xFF) == 0x04 &&
                        (it[5].toInt() and 0xFF) == 0x00
            }

        if (pkt4 == null) {
            throw Exception(
                "No audio PKT4 response from IC-705."
            )
        }

        recordAudioPacket(
            "RX",
            "PKT4",
            pkt4
        )

        audioRemoteSid =
            readBeInt(
                pkt4,
                8
            )

        appendLog("AUDIO PKT4 received")
        appendLog(
            "Audio remote SID: ${intToHex(audioRemoteSid)}"
        )

        appendLog("AUDIO STEP 2: PKT6")

        val audioPkt6 = buildAudioPkt6()

        recordAudioPacket(
            "TX",
            "PKT6 #1",
            audioPkt6
        )
        sendAudioRaw(audioPkt6)

        Thread.sleep(if (fastRecovery) 10L else 50L)

        recordAudioPacket(
            "TX",
            "PKT6 #2",
            audioPkt6
        )
        sendAudioRaw(audioPkt6)

        appendLog(
            "WAITING FOR AUDIO PKT6 RESPONSE"
        )

        val pkt6 =
            waitForAudioPkt6Response(if (fastRecovery) 1000 else 5000)

        if (pkt6 == null) {
            throw Exception(
                "No audio PKT6 response from IC-705 after 5 seconds."
            )
        }

        recordAudioPacket(
            "RX",
            "PKT6 ACCEPTED",
            pkt6
        )

        appendLog(
            "AUDIO PKT6 RESPONSE ACCEPTED - CONTINUING TO AUDIO"
        )
        setupAudioTrack()

        audioHaveSequence = false
        audioLastSequence = 0
        audioPacketCount = 0
        audioDebugPacketCount = 0
        audioRunning = true
        txAudioSequence = 1
        txAudioInnerSequence = 0
        refreshTxUi()

        startAudioReceiverThread()
        startAudioKeepAlive()

        appendLog(
            "RECEIVE AUDIO ACTIVE - 48 kHz / 16-bit / MONO"
        )
        appendLog(
            "Waiting for IC-705 audio packets on UDP $AUDIO_PORT..."
        )
        appendLog(
            "EXPECTED RX TYPES: 6C 05 (1364-byte PCM) or 44 02 (556-byte PCM)"
        )
    }

    private fun setupAudioTrack() {
        val minBuffer =
            AudioTrack.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        if (minBuffer <= 0) {
            throw Exception(
                "Android could not create an audio output buffer."
            )
        }

        val bufferSize =
            maxOf(
                minBuffer * 4,
                3840
            )

        val attributes =
            AudioAttributes.Builder()
                .setUsage(
                    AudioAttributes.USAGE_MEDIA
                )
                .setContentType(
                    AudioAttributes.CONTENT_TYPE_MUSIC
                )
                .build()

        val format =
            AudioFormat.Builder()
                .setSampleRate(
                    AUDIO_SAMPLE_RATE
                )
                .setEncoding(
                    AudioFormat.ENCODING_PCM_16BIT
                )
                .setChannelMask(
                    AudioFormat.CHANNEL_OUT_MONO
                )
                .build()

        val track =
            AudioTrack.Builder()
                .setAudioAttributes(
                    attributes
                )
                .setAudioFormat(
                    format
                )
                .setTransferMode(
                    AudioTrack.MODE_STREAM
                )
                .setBufferSizeInBytes(
                    bufferSize
                )
                .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw Exception(
                "Android AudioTrack initialization failed."
            )
        }

        try {
            track.play()
        } catch (e: Exception) {
            track.release()
            throw Exception(
                "Could not start Android audio output: ${e.message}"
            )
        }

        audioTrack = track
    }

    private fun startAudioReceiverThread() {
        if (audioReceiverThread?.isAlive == true) {
            return
        }

        audioReceiverThread =
            Thread {
                audioReceiveLoop()
            }.apply {
                name = "IC705-Audio-RX"
                isDaemon = true
                start()
            }
    }

    private fun audioReceiveLoop() {
        val buffer =
            ByteArray(1600)

        while (running && audioRunning) {
            val socket =
                audioSocket
                    ?: break

            try {
                val packet =
                    DatagramPacket(
                        buffer,
                        buffer.size
                    )

                socket.receive(packet)

                val data =
                    packet.data.copyOf(
                        packet.length
                    )

                processAudioPacket(data)

            } catch (
                _: java.net.SocketTimeoutException
            ) {
                // Normal. Lets the loop notice shutdown.
            } catch (e: Exception) {
                if (
                    running &&
                    audioRunning
                ) {
                    appendLog(
                        "AUDIO RECEIVE ERROR: ${e.message}"
                    )
                    beginAudioStreamRecovery("audio receive error: ${e.message}")
                }

                break
            }
        }
    }

    private fun processAudioPacket(
        data: ByteArray
    ) {
        // Radio ping packets are handled here too.
        if (
            data.size == 21 &&
            (data[4].toInt() and 0xFF) == 0x07 &&
            (data[5].toInt() and 0xFF) == 0x00
        ) {
            val direction =
                data[16].toInt() and 0xFF

            if (direction == 0x00) {
                try {
                    val reply =
                        buildAudioPkt7Reply(
                            readLeShort(
                                data,
                                6
                            ),
                            data.copyOfRange(
                                17,
                                21
                            )
                        )

                    recordAudioPacket(
                        "TX",
                        "PKT7 REPLY",
                        reply
                    )

                    sendAudioRaw(reply)
                } catch (e: Exception) {
                    if (running) {
                        appendLog(
                            "AUDIO PKT7 REPLY ERROR: ${e.message}"
                        )
                    }
                }
            }

            return
        }

        if (data.size < 24) {
            return
        }

        val isPart1 =
            data.size >= 24 &&
                    (data[0].toInt() and 0xFF) == 0x6C &&
                    (data[1].toInt() and 0xFF) == 0x05 &&
                    data[2] == 0.toByte() &&
                    data[3] == 0.toByte() &&
                    data[4] == 0.toByte() &&
                    data[5] == 0.toByte()

        val isPart2 =
            data.size >= 24 &&
                    (data[0].toInt() and 0xFF) == 0x44 &&
                    (data[1].toInt() and 0xFF) == 0x02 &&
                    data[2] == 0.toByte() &&
                    data[3] == 0.toByte() &&
                    data[4] == 0.toByte() &&
                    data[5] == 0.toByte()

        if (!isPart1 && !isPart2) {
            if (audioDebugPacketCount < 8) {
                appendLog(
                    "AUDIO RX NON-AUDIO PACKET len=${data.size}: " +
                            "${hex(data, 0, minOf(data.size, 32))}"
                )
                audioDebugPacketCount++
            }
            return
        }

        val sequence =
            readLeShort(
                data,
                6
            )

        noteIncomingSequence(audioReceiveSequenceState, sequence)

        // Drop duplicate/out-of-order packets.
        // For a first pass we prioritize getting clean audio through
        // before adding the full retransmission/jitter-buffer layer.
        if (audioHaveSequence) {
            val expected =
                (audioLastSequence + 1) and 0xFFFF

            if (sequence == audioLastSequence) {
                return
            }

            if (sequence != expected) {
                appendLog(
                    "AUDIO PACKET GAP: expected " +
                            "$expected, received $sequence"
                )
            }

            val forwardDistance =
                (sequence - audioLastSequence) and 0xFFFF

            if (forwardDistance > 0x8000) {
                return
            }
        }

        audioHaveSequence = true
        audioLastSequence = sequence

        val pcm =
            data.copyOfRange(
                24,
                data.size
            )

        if (pcm.isEmpty()) {
            return
        }

        if (audioPacketCount == 0L) {
            recordAudioPacket(
                "RX",
                "FIRST AUDIO PAYLOAD",
                data
            )
            appendLog(
                "FIRST AUDIO PACKET RX: seq=$sequence " +
                        "type=${if (isPart1) "1364-byte" else "556-byte"} " +
                        "PCM=${pcm.size} bytes"
            )
        }

        audioPacketCount++
        audioLastPacketAtMs = System.currentTimeMillis()
        audioRecoveryNotBeforeMs = 0L

        val track =
            audioTrack
                ?: return

        try {
            val written =
                track.write(
                    pcm,
                    0,
                    pcm.size,
                    AudioTrack.WRITE_BLOCKING
                )
            audioWriteCalls++
            audioLastWriteAtMs = System.currentTimeMillis()
            if (written > 0) audioWriteBytes += written.toLong()

            if (audioPacketCount <= 3L) {
                appendLog(
                    "AUDIO PLAYBACK WRITE: $written bytes"
                )
            }

            if (
                written < 0 &&
                running &&
                audioRunning
            ) {
                throw Exception(
                    "AudioTrack.write() returned $written"
                )
            }
        } catch (e: Exception) {
            audioWriteErrors++
            if (running && audioRunning) {
                appendLog(
                    "AUDIO PLAYBACK ERROR: ${e.message}"
                )
                beginAudioStreamRecovery("audio playback error: ${e.message}")
            }
        }
    }

    private fun startAudioKeepAlive() {
        audioScheduler?.shutdownNow()

        audioPkt7Sequence = 1
        audioPkt7InnerSequence = 0x8304

        audioScheduler =
            Executors.newSingleThreadScheduledExecutor()

        // kappanhang uses a 3-second interval for stream PKT7.
        audioScheduler?.scheduleAtFixedRate(
            {
                if (
                    running &&
                    audioRunning
                ) {
                    try {
                        sendAudioPkt7()
                    } catch (e: Exception) {
                        if (
                            running &&
                            audioRunning
                        ) {
                            appendLog(
                                "AUDIO PKT7 SEND ERROR: ${e.message}"
                            )
                        }
                    }
                }
            },
            100,
            3000,
            TimeUnit.MILLISECONDS
        )
    }

    private fun sendAudioPkt7() {
        val packet =
            ByteArray(21)

        packet[0] = 0x15.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x07
        packet[5] = 0x00

        packet[6] =
            (audioPkt7Sequence and 0xFF).toByte()

        packet[7] =
            ((audioPkt7Sequence ushr 8) and 0xFF).toByte()

        audioPkt7Sequence =
            (audioPkt7Sequence + 1) and 0xFFFF

        writeBeInt(
            packet,
            8,
            audioLocalSid
        )

        writeBeInt(
            packet,
            12,
            audioRemoteSid
        )

        packet[16] = 0x00

        packet[17] =
            secureRandom.nextInt(256).toByte()

        packet[18] =
            (audioPkt7InnerSequence and 0xFF).toByte()

        packet[19] =
            ((audioPkt7InnerSequence ushr 8) and 0xFF).toByte()

        packet[20] = 0x06

        audioPkt7InnerSequence =
            (audioPkt7InnerSequence + 1) and 0xFFFF

        recordAudioPacket(
            "TX",
            "PKT7",
            packet
        )
        sendAudioRaw(packet)
    }

    private fun buildAudioPkt3(): ByteArray {
        val packet =
            ByteArray(16)

        packet[0] = 0x10.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x03
        packet[5] = 0x00
        packet[6] = 0x00
        packet[7] = 0x00

        writeBeInt(
            packet,
            8,
            audioLocalSid
        )

        writeBeInt(
            packet,
            12,
            0
        )

        return packet
    }

    private fun buildAudioPkt6(): ByteArray {
        val packet =
            ByteArray(16)

        packet[0] = 0x10.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x06
        packet[5] = 0x00
        packet[6] = 0x01
        packet[7] = 0x00

        writeBeInt(
            packet,
            8,
            audioLocalSid
        )

        writeBeInt(
            packet,
            12,
            audioRemoteSid
        )

        return packet
    }

    private fun buildAudioPkt7Reply(
        sequence: Int,
        replyId: ByteArray
    ): ByteArray {
        val packet =
            ByteArray(21)

        packet[0] = 0x15.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x07
        packet[5] = 0x00

        packet[6] =
            (sequence and 0xFF).toByte()

        packet[7] =
            ((sequence ushr 8) and 0xFF).toByte()

        writeBeInt(
            packet,
            8,
            audioRemoteSid
        )

        writeBeInt(
            packet,
            12,
            audioLocalSid
        )

        packet[16] = 0x01

        for (i in 0 until 4) {
            packet[17 + i] =
                replyId[i]
        }

        return packet
    }

    private fun waitForAudioPkt6Response(
        timeoutMs: Int
    ): ByteArray? {
        val socket =
            audioSocket
                ?: return null

        val deadline =
            System.currentTimeMillis() + timeoutMs

        val buffer =
            ByteArray(1600)

        while (
            running &&
            System.currentTimeMillis() < deadline
        ) {
            val remaining =
                deadline -
                        System.currentTimeMillis()

            if (remaining <= 0) {
                break
            }

            try {
                socket.soTimeout =
                    minOf(
                        SOCKET_TIMEOUT_MS,
                        remaining.toInt()
                    )

                val packet =
                    DatagramPacket(
                        buffer,
                        buffer.size
                    )

                socket.receive(packet)

                val data =
                    packet.data.copyOf(
                        packet.length
                    )

                if (data.size <= 32) {
                    recordAudioPacket(
                        "RX",
                        "PKT6 WAIT",
                        data
                    )
                }

                // Exact IC-705 / RS-BA1 PKT6 response:
                // 10 00 00 00 06 00 01 00 ...
                if (
                    data.size == 16 &&
                    (data[0].toInt() and 0xFF) == 0x10 &&
                    (data[1].toInt() and 0xFF) == 0x00 &&
                    (data[2].toInt() and 0xFF) == 0x00 &&
                    (data[3].toInt() and 0xFF) == 0x00 &&
                    (data[4].toInt() and 0xFF) == 0x06 &&
                    (data[5].toInt() and 0xFF) == 0x00 &&
                    (data[6].toInt() and 0xFF) == 0x01 &&
                    (data[7].toInt() and 0xFF) == 0x00
                ) {
                    appendLog(
                        "EXACT PKT6 MATCH FOUND"
                    )
                    return data
                }

                // IC-705 PKT7 request. Reply immediately exactly as
                // kappanhang does, then keep waiting for PKT6.
                if (
                    data.size == 21 &&
                    (data[1].toInt() and 0xFF) == 0x00 &&
                    (data[2].toInt() and 0xFF) == 0x00 &&
                    (data[3].toInt() and 0xFF) == 0x00 &&
                    (data[4].toInt() and 0xFF) == 0x07 &&
                    (data[5].toInt() and 0xFF) == 0x00 &&
                    (data[16].toInt() and 0xFF) == 0x00
                ) {
                    val reply =
                        buildAudioPkt7Reply(
                            readLeShort(
                                data,
                                6
                            ),
                            data.copyOfRange(
                                17,
                                21
                            )
                        )

                    recordAudioPacket(
                        "TX",
                        "PKT7 REPLY DURING PKT6 WAIT",
                        reply
                    )

                    sendAudioRaw(reply)

                    continue
                }

            } catch (
                _: java.net.SocketTimeoutException
            ) {
                // Keep looping until the full deadline.
            }
        }

        return null
    }

    private fun receiveAudioExpected(
        expectedLength: Int,
        timeoutMs: Int,
        matcher: (ByteArray) -> Boolean
    ): ByteArray? {
        val socket =
            audioSocket
                ?: return null

        val deadline =
            System.currentTimeMillis() +
                    timeoutMs

        val buffer =
            ByteArray(1600)

        while (
            running &&
            System.currentTimeMillis() < deadline
        ) {
            val remaining =
                deadline -
                        System.currentTimeMillis()

            if (remaining <= 0) {
                break
            }

            try {
                socket.soTimeout =
                    minOf(
                        SOCKET_TIMEOUT_MS,
                        remaining.toInt()
                    )

                val packet =
                    DatagramPacket(
                        buffer,
                        buffer.size
                    )

                socket.receive(packet)

                val data =
                    packet.data.copyOf(
                        packet.length
                    )

                if (data.size <= 32) {
                    recordAudioPacket(
                        "RX",
                        "HANDSHAKE",
                        data
                    )
                }

                if (
                    data.size == expectedLength &&
                    matcher(data)
                ) {
                    return data
                }

                if (
                    data.size == 21 &&
                    (data[4].toInt() and 0xFF) == 0x07 &&
                    (data[5].toInt() and 0xFF) == 0x00
                ) {
                    val direction =
                        data[16].toInt() and 0xFF

                    if (direction == 0x00) {
                        try {
                            sendAudioRaw(
                                buildAudioPkt7Reply(
                                    readLeShort(
                                        data,
                                        6
                                    ),
                                    data.copyOfRange(
                                        17,
                                        21
                                    )
                                )
                            )
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (
                _: java.net.SocketTimeoutException
            ) {
            }
        }

        return null
    }

    private fun beginAudioStreamRecovery(reason: String) {
        cancelTransmit("Audio stream recovery")
        if (!running || userDisconnectRequested || !connected || !audioStreamExpected) return
        if (!audioRecoveryGuard.compareAndSet(false, true)) return
        audioRecoveryNotBeforeMs = System.currentTimeMillis() + AUDIO_RECOVERY_RETRY_COOLDOWN_MS
        appendLog("AUDIO RECOVERY: $reason; restarting UDP audio only, keeping radio control and spectrum session alive.")
        Thread {
            var recovered = false
            try {
                audioRunning = false
                audioScheduler?.shutdownNow()
                audioScheduler = null
                val oldSocket = audioSocket
                if (oldSocket != null && !oldSocket.isClosed && audioLocalSid != 0 && audioRemoteSid != 0) {
                    try {
                        val disconnect = ByteArray(16)
                        disconnect[0] = 0x10
                        disconnect[4] = 0x05
                        writeBeInt(disconnect, 8, audioLocalSid)
                        writeBeInt(disconnect, 12, audioRemoteSid)
                        oldSocket.send(DatagramPacket(disconnect, disconnect.size))
                    } catch (_: Exception) { }
                }
                try { oldSocket?.close() } catch (_: Exception) { }
                audioSocket = null
                try { audioReceiverThread?.join(200L) } catch (_: InterruptedException) { }
                audioReceiverThread = null
                try { audioTrack?.pause() } catch (_: Exception) { }
                try { audioTrack?.flush() } catch (_: Exception) { }
                try { audioTrack?.stop() } catch (_: Exception) { }
                try { audioTrack?.release() } catch (_: Exception) { }
                audioTrack = null
                audioHaveSequence = false
                resetReceiveSequenceState(audioReceiveSequenceState)
                if (!running) return@Thread
                openAudioReceive(activeRadioIp, fastRecovery = true)
                recovered = audioRunning
                if (recovered) appendLog("AUDIO RECOVERY: audio stream re-established without disconnecting the radio control session.")
            } catch (e: Exception) {
                if (running) appendLog("AUDIO RECOVERY: quick restart failed: ${e.message}; another audio-only retry will follow.")
            } finally {
                if (!recovered) {
                    audioRunning = false
                    try { audioSocket?.close() } catch (_: Exception) { }
                    audioSocket = null
                    try { audioTrack?.release() } catch (_: Exception) { }
                    audioTrack = null
                }
                audioRecoveryGuard.set(false)
            }
        }.apply {
            name = "IC705-Audio-Recovery"
            isDaemon = true
            start()
        }
    }

    // Recreate the UDP session when Android invalidates its active Wi-Fi route.
    private fun handleUdpSendFailure(stream: String, error: Exception) {
        if (!running) return
        val detail = error.message.orEmpty()
        if (!detail.contains("EPERM", ignoreCase = true) &&
            !detail.contains("Operation not permitted", ignoreCase = true)
        ) return
        if (stream.equals("audio", ignoreCase = true)) {
            beginAudioStreamRecovery("Android blocked audio UDP send (EPERM)")
            return
        }
        beginNetworkRecovery("Android blocked $stream UDP send (EPERM)")
    }

    // A failed reconnect must release the preserved CONNECTED controls so the
    // user can retry. Temporary recoveries never show a connection dialog.
    private fun showRecoveryFailure() {
        runOnUiThread {
            if (userDisconnectRequested || running || connected) return@runOnUiThread
            updateConnectionButtons(connecting = false, isConnected = false)
            updateLinkQuality(Long.MAX_VALUE, Long.MAX_VALUE)
            setTuneControlsEnabled(false)
            enableBandButtons(false)
            usbButton.isEnabled = false
            lsbButton.isEnabled = false
            scopeToggleButton.isEnabled = false
            android.widget.Toast.makeText(this,
                "Connection lost. Check the network, then press CONNECT.",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun beginNetworkRecovery(reason: String) {
        cancelTransmit("Network recovery")
        if (!running || userDisconnectRequested) return
        if (!udpRecoveryGuard.compareAndSet(false, true)) return

        automaticReconnectRestoreScope = scopeStarted
        appendLog(
            "NETWORK RECOVERY: $reason; stall threshold=${STREAM_STALL_TIMEOUT_MS} ms, " +
                    "reconnect pause=${NETWORK_RECONNECT_DELAY_MS} ms; releasing the old radio session."
        )
        running = false
        Thread({
            try {
                stopSession(preserveScreen = true)
            } catch (e: Exception) {
                appendLog("NETWORK RECOVERY: cleanup warning: ${e.message}")
            }

            if (automaticReconnectAttempts >= MAX_AUTO_RECONNECT_ATTEMPTS) {
                appendLog("NETWORK RECOVERY: stopped after $MAX_AUTO_RECONNECT_ATTEMPTS attempts. Restore Wi-Fi, then press CONNECT.")
                udpRecoveryGuard.set(false)
                if (!userDisconnectRequested) showRecoveryFailure()
                return@Thread
            }

            automaticReconnectAttempts++
            val attempt = automaticReconnectAttempts
            try {
                Thread.sleep(NETWORK_RECONNECT_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            udpRecoveryGuard.set(false)
            if (!running && !userDisconnectRequested) {
                appendLog("NETWORK RECOVERY: reconnect attempt $attempt/$MAX_AUTO_RECONNECT_ATTEMPTS")
                startSession(preserveScreen = true)
            }
        }, "IC705-Network-Recovery").apply {
            isDaemon = true
            start()
        }
    }

    private fun sendAudioRaw(
        data: ByteArray
    ) {
        val socket =
            audioSocket
                ?: throw Exception(
                    "Audio socket is closed."
                )

        synchronized(sendLock) {
            val packet =
                DatagramPacket(
                    data,
                    data.size
                )

            try {
                socket.send(packet)
            } catch (e: Exception) {
                handleUdpSendFailure("audio", e)
                throw e
            }
        }
    }

    // =================================================================
    // OPERATING MODE
    // IC-705 CI-V command 06:
    // FE FE A4 E0 06 <MODE> <FILTER> FD
    //
    // Icom IC-705 codes:
    //   LSB = 00
    //   USB = 01
    // FIL1 = 01
    // =================================================================

    private fun updateModeButtons(
        modeCode: Int
    ) {
        runOnUiThread {
            val activeTint =
                ColorStateList.valueOf(
                    Color.rgb(0, 190, 0)
                )

            // The unchecked sideband button uses the same surface as band buttons.
            val inactiveTint = ColorStateList.valueOf(Color.rgb(36, 45, 53))

            when (modeCode) {
                0x01 -> {
                    usbButton.text = "USB  ✓"
                    lsbButton.text = "LSB"
                    usbButton.backgroundTintList = activeTint
                    lsbButton.backgroundTintList = inactiveTint
                }

                0x00 -> {
                    usbButton.text = "USB"
                    lsbButton.text = "LSB  ✓"
                    usbButton.backgroundTintList = inactiveTint
                    lsbButton.backgroundTintList = activeTint
                }

                else -> {
                    usbButton.text = "USB"
                    lsbButton.text = "LSB"
                    usbButton.backgroundTintList = inactiveTint
                    lsbButton.backgroundTintList = inactiveTint
                }
            }
        }
    }

    private fun modeNameFromCode(
        modeCode: Int
    ): String {
        return when (modeCode) {
            0x00 -> "LSB"
            0x01 -> "USB"
            0x02 -> "AM"
            0x03 -> "CW"
            0x04 -> "RTTY"
            0x05 -> "FM"
            0x06 -> "WFM"
            0x07 -> "CW-R"
            0x08 -> "RTTY-R"
            0x17 -> "DV"
            else -> "UNKNOWN"
        }
    }

    private fun setOperatingModeFromUi(
        modeName: String,
        modeCode: Int
    ) {
        if (!running || !serialOpen) {
            appendLog(
                "ERROR: CI-V serial stream is not open."
            )
            return
        }

        val civ =
            byteArrayOf(
                0xFE.toByte(),
                0xFE.toByte(),
                0xA4.toByte(),
                0xE0.toByte(),
                0x06.toByte(),
                modeCode.toByte(),
                0x01.toByte(),
                0xFD.toByte()
            )

        appendLog("")
        appendLog("MODE CHANGE REQUEST: $modeName")
        appendLog(
            "CI-V TX MODE: ${hex(civ)}"
        )

        try {
            sendSerialTracked(
                buildCivPacket(civ)
            )

            appendLog(
                "CI-V MODE COMMAND SENT: $modeName"
            )

            modeReceived = false

            // Ask the IC-705 for the selected VFO mode so the serial
            // response is visible and we verify the command path.
            Thread {
                try {
                    Thread.sleep(150)

                    if (!running || !serialOpen) {
                        return@Thread
                    }

                    sendCivReadMode()

                } catch (e: Exception) {
                    if (running) {
                        appendLog(
                            "MODE READBACK ERROR: ${e.message}"
                        )
                    }
                }
            }.start()

        } catch (e: Exception) {
            appendLog(
                "MODE CHANGE FAILED: ${e.message}"
            )
            appendLog(
                "Closing IC-705 connection."
            )
            stopSession()
        }
    }

    private fun sendCivReadMode() {
        // CI-V 26 00 = read selected/main VFO mode.
        val civ =
            byteArrayOf(
                0xFE.toByte(),
                0xFE.toByte(),
                0xA4.toByte(),
                0xE0.toByte(),
                0x26.toByte(),
                0x00.toByte(),
                0xFD.toByte()
            )

        appendLog(
            "CI-V TX MODE READ: ${hex(civ)}"
        )

        sendSerialTracked(
            buildCivPacket(civ)
        )
    }

    private fun sendCivSetFrequency(hz: Long) {
        val civ = ByteArray(12)
        civ[0] = 0xFE.toByte()
        civ[1] = 0xFE.toByte()
        civ[2] = 0xA4.toByte()
        civ[3] = 0xE0.toByte()
        civ[4] = 0x25.toByte()
        civ[5] = 0x00
        encodeIcomFrequency(hz, civ, 6)
        civ[11] = 0xFD.toByte()

        appendLog("CI-V TX SET MAIN VFO: ${hex(civ)}")
        appendLog("CI-V WRITE COMMAND: 25 00")

        sendSerialTracked(
            buildCivPacket(civ)
        )
    }
    private fun encodeIcomFrequency(
        hz: Long,
        output: ByteArray,
        offset: Int
    ) {
        var value = hz
        for (i in 0 until 5) {
            val twoDigits = (value % 100L).toInt()
            val low = twoDigits % 10
            val high = twoDigits / 10
            output[offset + i] =
                ((high shl 4) or low).toByte()
            value /= 100L
        }
        if (value != 0L) {
            throw IllegalArgumentException(
                "Frequency does not fit the 5-byte CI-V BCD field."
            )
        }
    }
    private fun sendCivReadFrequency() {
        val civ = byteArrayOf(
            0xFE.toByte(),
            0xFE.toByte(),
            0xA4.toByte(),
            0xE0.toByte(),
            0x03.toByte(),
            0xFD.toByte()
        )
        appendLog("CI-V TX: ${hex(civ)}")
        sendSerialTracked(buildCivPacket(civ))
    }
    @Synchronized
    private fun buildCivPacket(
        civ: ByteArray
    ): ByteArray {
        if (civ.isEmpty() || civ.size > 0xFFFF) {
            throw IllegalArgumentException(
                "Invalid CI-V frame length: ${civ.size}"
            )
        }

        // Icom Ethernet / RS-BA1 CI-V data packet:
        // 0x00..0x03  total packet length, little-endian
        // 0x08..0x0B  sender SID, big-endian
        // 0x0C..0x0F  receiver SID, big-endian
        // 0x10        C1 transport type
        // 0x11..0x12  CI-V length, little-endian
        // 0x13..0x14  CI-V sequence, big-endian
        // 0x15..      CI-V bytes
        val packet =
            ByteArray(
                21 + civ.size
            )

        writeLeInt(
            packet,
            0,
            packet.size
        )

        writeBeInt(
            packet,
            8,
            serialLocalSid
        )

        writeBeInt(
            packet,
            12,
            serialRemoteSid
        )

        packet[16] =
            0xC1.toByte()

        writeLeShort(
            packet,
            17,
            civ.size
        )

        packet[19] =
            ((serialCivSequence ushr 8) and 0xFF).toByte()

        packet[20] =
            (serialCivSequence and 0xFF).toByte()

        serialCivSequence =
            (serialCivSequence + 1) and 0xFFFF

        System.arraycopy(
            civ,
            0,
            packet,
            21,
            civ.size
        )

        return packet
    }

    private fun startSerialReceiverThread() {
        if (serialReceiverThread?.isAlive == true) {
            return
        }
        serialReceiverThread = Thread {
            serialReceiverLoop()
        }
        serialReceiverThread?.start()
    }
    private fun serialReceiverLoop() {
        val buffer = ByteArray(4096)
        while (running) {
            val socket = serialSocket ?: break
            try {
                val packet = DatagramPacket(
                    buffer,
                    buffer.size
                )
                socket.receive(packet)
                val data =
                    packet.data.copyOf(packet.length)
                processSerialIncomingPacket(data)
            } catch (e: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                if (running) {
                    appendLog("SERIAL RECEIVE ERROR: ${e.message}")
                }
                break
            }
        }
    }
    private fun processSerialIncomingPacket(
        data: ByteArray
    ) {
        if (data.size < 8) {
            return
        }

        val byte4 =
            data[4].toInt() and 0xFF
        val byte5 =
            data[5].toInt() and 0xFF

        if (
            data.size == 21 &&
            byte4 == 0x07 &&
            byte5 == 0x00
        ) {
            val direction =
                data[16].toInt() and 0xFF

            if (direction == 0x00) {
                try {
                    sendSerialRaw(
                        buildSerialPkt7Reply(
                            readLeShort(data, 6),
                            data.copyOfRange(17, 21)
                        )
                    )
                } catch (e: Exception) {
                    if (running) {
                        appendLog(
                            "SERIAL PKT7 reply error: ${e.message}"
                        )
                    }
                }
            }
            return
        }

        if (
            data.size >= 16 &&
            data[0].toInt() and 0xFF == 0x10 &&
            byte4 == 0x01 &&
            byte5 == 0x00
        ) {
            val requestedSeq =
                readLeShort(data, 6)
            retransmitSerial(requestedSeq)
            return
        }

        if (
            data.size >= 22 &&
            (data[16].toInt() and 0xFF) == 0xC1
        ) {
            noteIncomingSequence(serialReceiveSequenceState, readLeShort(data, 6))
            scopeTransportPackets++
            scopeLastTransportAtMs = System.currentTimeMillis()
            scopeLastUdpBytes = data.size

            val payloadStart = 21
            val available = data.size - payloadStart
            if (available <= 0 || data.size < 19) return

            val declaredLength = readLeShort(data, 17)
            if (declaredLength <= 0 || declaredLength > available) {
                appendLog("SERIAL C1 invalid length: declared=$declaredLength available=$available udp=${data.size}")
                return
            }

            val chunk = data.copyOfRange(payloadStart, payloadStart + declaredLength)
            collectPowerReplies(chunk)
            scopeLastCivBytes = chunk.size

            // IC-705 WLAN scope: raw 27 00 ... FD record embedded anywhere in C1.
            var marker = -1
            for (i in 0 until maxOf(0, chunk.size - 2)) {
                if ((chunk[i].toInt() and 0xFF) == 0x27 &&
                    (chunk[i + 1].toInt() and 0xFF) == 0x00 &&
                    (chunk[i + 2].toInt() and 0xFF) == 0x00) {
                    marker = i
                    break
                }
            }
            if (marker >= 0) {
                var fd = -1
                for (i in marker + 3 until chunk.size) {
                    if ((chunk[i].toInt() and 0xFF) == 0xFD) { fd = i; break }
                }
                if (fd > marker) {
                    val rawScope = chunk.copyOfRange(marker, fd + 1)
                    if (rawScope.size >= 493) {
                        scopeRaw27Frames++
                        scopeLastRawFrameAtMs = System.currentTimeMillis()
                        scopeCivFrames++
                        processSpectrumScopeFrame(rawScope.copyOfRange(2, rawScope.size - 1))
                        return
                    }
                }
            }

            consumeSerialCivChunk(chunk)
            return
        }
    }

    // =================================================================
    // IC-705 SPECTRUM
    // Spectrum-only display. No waterfall buffer or waterfall drawing.
    //
    // Native center-mode spans: 2.5/5/10/25/50/100/250/500 kHz.
    // The requested 75 kHz display is a centered software crop of the
    // 100 kHz radio waveform.
    // =================================================================

    private fun startSpectrumScope() {
        if (!running || !serialOpen) {
            return
        }

        if (scopeStarted) {
            return
        }

        stopScopePolling()
        resetScopeAssembly()

        scopeTransportPackets = 0L
        scopeCivFrames = 0L
        scopeRaw27Frames = 0L
        scopeDecodedFrames = 0L
        scopeLastTransportAtMs = 0L
        scopeLastRawFrameAtMs = 0L
        scopeLastDecodedAtMs = 0L
        scopePollCount = 0L
        scopeAwaitingResponse = false
        scopeLastUdpBytes = 0
        scopeLastCivBytes = 0

        // Mark the receiver active and display the scope first. The first
        // waveform frames can arrive immediately after the radio's ON command.
        scopeStarted = true
        runOnUiThread {
            scopeView.visibility = View.VISIBLE
            sensitivityRow.visibility = View.VISIBLE
            scopeToggleButton.text = "SPECTRUM OFF"
        }
        updateScopeInfo("Spectrum: turning scope ON before setting CENTER mode...")

        try {
            // Reset stale radio-side scope state after a radio reboot, then
            // re-enable the scope and waveform before configuring CENTER.
            appendLog("SCOPE START: clearing prior scope state.")
            sendScopeCommand(byteArrayOf(0x27, 0x11, 0x00))
            Thread.sleep(80)
            sendScopeCommand(byteArrayOf(0x27, 0x10, 0x00))
            Thread.sleep(150)

            // Enable scope status and waveform output before configuring CENTER.
            appendLog("SCOPE START: enabling scope status (CI-V 27 10 01).")
            sendScopeCommand(byteArrayOf(0x27, 0x10, 0x01))
            Thread.sleep(150)
            appendLog("SCOPE START: enabling waveform data (CI-V 27 11 01).")
            sendScopeCommand(byteArrayOf(0x27, 0x11, 0x01))
            Thread.sleep(150)
            updateScopeInfo("Spectrum: ON — applying CENTER mode...")

            appendLog("SCOPE MODE: selecting CENTER (CI-V 27 14 00 00).")
            val centerCommands = arrayOf(
                byteArrayOf(0x27, 0x12, 0x00),
                byteArrayOf(0x27, 0x13, 0x00),
                byteArrayOf(0x27, 0x14, 0x00, 0x00),
                buildScopeSpanCommand(100_000L)
            )
            for (cmd in centerCommands) {
                sendScopeCommand(cmd)
                Thread.sleep(80)
            }
            // No repeated 27 00 polling; the radio streams frames after both ON commands.
        } catch (e: Exception) {
            appendLog("SCOPE START/MODE ERROR: ${e.message}")
            updateScopeInfo("Spectrum: ON — setup failed; press SPECTRUM OFF then ON to retry")
            return
        }

        updateScopeInfo("Spectrum: ON — CENTER mode, receiving IC-705 scope frames")
    }

    private fun waterfallPaletteName(index: Int): String =
        arrayOf("Blue", "Green", "Amber", "Purple", "Grayscale")[index.coerceIn(0, 4)]

    private fun startSignalMeterPolling() {
        signalMeterScheduler?.shutdownNow()
        signalMeterScheduler =
            Executors.newSingleThreadScheduledExecutor()

        signalMeterScheduler?.scheduleAtFixedRate(
            {
                if (running && serialOpen) {
                    try {
                        sendRadioCommand(0x1C, 0x00)
                        if (radioTransmitting) sendRadioCommand(0x15, 0x11) else sendCivReadSignalMeter()
                        if (txWanted && lastPttReplyAt > 0L && System.currentTimeMillis() - lastPttReplyAt > 3000L)
                            cancelTransmit("TX status link lost")
                    } catch (e: Exception) {
                        if (running) {
                            appendLog(
                                "S-METER READ ERROR: ${e.message}"
                            )
                        }
                    }
                }
            },
            100,
            SIGNAL_METER_POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopSignalMeterPolling() {
        signalMeterScheduler?.shutdownNow()
        signalMeterScheduler = null
    }

    private fun sendCivReadSignalMeter() {
        val civ = byteArrayOf(
            0xFE.toByte(),
            0xFE.toByte(),
            0xA4.toByte(),
            0xE0.toByte(),
            0x15.toByte(),
            0x02.toByte(),
            0xFD.toByte()
        )
        sendSerialTracked(
            buildCivPacket(civ)
        )
    }

    private fun stopScopePolling() {
        scopePolling = false
        scopeAwaitingResponse = false

        try {
            scopePollThread?.interrupt()
        } catch (_: Exception) {
        }

        scopePollThread = null
    }

    private fun stopSpectrumScope() {
        stopScopePolling()

        try {
            // Stop only waveform data output. Leave the physical IC-705
            // scope display itself untouched.
            if (running && serialOpen) {
                sendScopeCommand(
                    byteArrayOf(
                        0x27.toByte(),
                        0x11.toByte(),
                        0x00.toByte()
                    )
                )
            }
        } catch (_: Exception) {
        }

        scopeStarted = false
        resetScopeAssembly()

        runOnUiThread {
            if (::scopeView.isInitialized) {
                scopeView.clearScope()
                scopeView.visibility = View.GONE
            }

            if (::sensitivityRow.isInitialized) {
                sensitivityRow.visibility = View.GONE
            }

            if (::scopeInfoText.isInitialized) {
                scopeInfoText.text =
                    "Spectrum: OFF — scope display removed to conserve data"
            }

            if (::scopeToggleButton.isInitialized) {
                scopeToggleButton.text = "SPECTRUM ON"
            }
        }
    }

    private fun sendScopeBandwidthForDisplay(displayKHz: Int) {
        val requestedTotalHz = displayKHz.toLong() * 1000L
        val halfSpan = when {
            requestedTotalHz <= 5_000L -> 2_500L
            requestedTotalHz <= 10_000L -> 5_000L
            requestedTotalHz <= 20_000L -> 10_000L
            requestedTotalHz <= 50_000L -> 25_000L
            requestedTotalHz <= 100_000L -> 50_000L
            requestedTotalHz <= 200_000L -> 100_000L
            requestedTotalHz <= 500_000L -> 250_000L
            else -> 500_000L
        }
        sendScopeCommand(buildScopeSpanCommand(halfSpan))
    }

    private fun buildScopeSpanCommand(halfSpanHz: Long): ByteArray {
        val civ = ByteArray(13)
        civ[0] = 0xFE.toByte()
        civ[1] = 0xFE.toByte()
        civ[2] = 0xA4.toByte()
        civ[3] = 0xE0.toByte()
        civ[4] = 0x27.toByte()
        civ[5] = 0x15.toByte()
        civ[6] = 0x00.toByte()
        encodeIcomFrequency(halfSpanHz, civ, 7)
        civ[12] = 0xFD.toByte()
        return civ
    }

    private fun sendScopeCommand(
        command: ByteArray
    ) {
        if (!running || !serialOpen) {
            return
        }

        // Scope toggles/mode selections are CI-V command bodies (27 xx ...),
        // while the bandwidth builder already returns a complete CI-V frame.
        // Match the tester: wrap command bodies in FE FE A4 E0 ... FD before
        // placing them in the serial C1 transport envelope.
        val isCompleteCivFrame = command.size >= 6 &&
                (command[0].toInt() and 0xFF) == 0xFE &&
                (command[1].toInt() and 0xFF) == 0xFE &&
                (command[2].toInt() and 0xFF) == 0xA4 &&
                (command[3].toInt() and 0xFF) == 0xE0 &&
                (command.last().toInt() and 0xFF) == 0xFD
        val civ = if (isCompleteCivFrame) {
            command
        } else {
            ByteArray(command.size + 5).also { frame ->
                frame[0] = 0xFE.toByte()
                frame[1] = 0xFE.toByte()
                frame[2] = 0xA4.toByte()
                frame[3] = 0xE0.toByte()
                command.copyInto(frame, 4)
                frame[frame.lastIndex] = 0xFD.toByte()
            }
        }
        appendLog("SCOPE CI-V TX: ${hex(civ)}")
        sendSerialTracked(
            buildCivPacket(civ)
        )
    }

    private fun resetScopeAssembly() {
        scopeAssemblyMainSub = 0
        scopeAssemblyMode = 0
        scopeAssemblyCenterHz = 0L
        scopeAssemblySpanHz = 0L
        scopeAssemblyOutOfRange = false
        scopeAssemblyWaveform.reset()
        synchronized(scopeCivReceiveBuffer) {
            scopeCivReceiveBuffer.reset()
        }
        synchronized(scopeRawReceiveBuffer) {
            scopeRawReceiveBuffer.reset()
        }
    }

    private val scopeCivReceiveBuffer =
        ByteArrayOutputStream()

    private fun consumeSerialCivChunk(
        chunk: ByteArray
    ) {
        if (chunk.isEmpty()) {
            return
        }

        val startsRawScopeFrame =
            chunk.size >= 2 &&
                    (chunk[0].toInt() and 0xFF) == 0x27 &&
                    (chunk[1].toInt() and 0xFF) == 0x00

        // The IC-705 WLAN scope line is a raw 27 00 ... FD frame inside the
        // C1 transport. Do NOT discard an incomplete first chunk. A line can
        // be fragmented by the transport/network, so accumulate until FD.
        if (startsRawScopeFrame || scopeRawReceiveBuffer.size() > 0) {
            synchronized(scopeRawReceiveBuffer) {
                if (startsRawScopeFrame) {
                    scopeRawReceiveBuffer.reset()
                }

                scopeRawReceiveBuffer.write(
                    chunk,
                    0,
                    chunk.size
                )

                while (true) {
                    val bytes = scopeRawReceiveBuffer.toByteArray()
                    if (bytes.size < 2) {
                        return
                    }

                    // Find the first FD after a valid 27 00 start. Spectrum
                    // waveform bytes are 0..160, so FD cannot occur inside
                    // waveform data.
                    var endIndex = -1
                    for (i in 2 until bytes.size) {
                        if ((bytes[i].toInt() and 0xFF) == 0xFD) {
                            endIndex = i
                            break
                        }
                    }

                    if (endIndex < 0) {
                        updateScopeInfo(
                            "Spectrum: buffering 27 00 scope line — ${bytes.size} bytes"
                        )
                        return
                    }

                    val frame = bytes.copyOfRange(0, endIndex + 1)
                    val remaining = bytes.copyOfRange(endIndex + 1, bytes.size)
                    scopeRawReceiveBuffer.reset()
                    if (remaining.isNotEmpty()) {
                        scopeRawReceiveBuffer.write(
                            remaining,
                            0,
                            remaining.size
                        )
                    }

                    if (
                        frame.size >= 4 &&
                        (frame[0].toInt() and 0xFF) == 0x27 &&
                        (frame[1].toInt() and 0xFF) == 0x00
                    ) {
                        scopeCivFrames++
                        val payload =
                            frame.copyOfRange(
                                2,
                                frame.size - 1
                            )
                        processSpectrumScopeFrame(payload)
                    }

                    if (scopeRawReceiveBuffer.size() == 0) {
                        return
                    }
                }
            }
        }

        // Split complete frames individually; retain a fragmented tail.
        synchronized(civFrameBuffer) {
            for (b in chunk) {
                civFrameBuffer.add(b)
                if (b == 0xFD.toByte()) {
                    val start = (0 until civFrameBuffer.size - 1).firstOrNull {
                        civFrameBuffer[it] == 0xFE.toByte() &&
                            civFrameBuffer[it + 1] == 0xFE.toByte()
                    }
                    if (start != null && civFrameBuffer.size - start >= 6) {
                        processCivFrame(civFrameBuffer.subList(start, civFrameBuffer.size).toByteArray())
                    }
                    civFrameBuffer.clear()
                }
                if (civFrameBuffer.size > 8192) civFrameBuffer.clear()
            }
        }
    }

    private fun processSpectrumScopeFrame(
        payload: ByteArray
    ) {
        // payload begins immediately after raw 27 00 and does not include
        // the final FD. For LAN scope data this contains the header plus 475
        // waveform bytes.
        if (payload.size < 15) {
            appendLog(
                "SCOPE 27 00 too short: payload=${payload.size}"
            )
            return
        }

        val mainSub =
            payload[0].toInt() and 0xFF
        val divCurrent =
            payload[1].toInt() and 0xFF
        val divMaximum =
            payload[2].toInt() and 0xFF
        val scopeMode =
            payload[3].toInt() and 0xFF

        if (divMaximum <= 1) {
            // LAN: all waveform data arrives together.
            if (scopeMode == 0) {
                if (payload.size < 15 + 475) {
                    appendLog(
                        "SCOPE LAN frame incomplete: payload=${payload.size}"
                    )
                    return
                }

                val centerHz =
                    decodeIcomFrequency(payload, 4)
                val spanHz =
                    decodeIcomFrequency(payload, 9)
                val outOfRange =
                    (payload[14].toInt() and 0xFF) != 0

                if (outOfRange) {
                    updateScopeInfo(
                        "Spectrum: IC-705 reports scope OUT OF RANGE"
                    )
                    return
                }

                val waveform =
                    payload.copyOfRange(
                        15,
                        minOf(15 + 475, payload.size)
                    )

                if (waveform.size < 475) {
                    appendLog(
                        "SCOPE waveform short: ${waveform.size}/475"
                    )
                    return
                }

                displayScopeWaveform(
                    centerHz,
                    spanHz,
                    waveform
                )
                return
            }

            // Fixed-mode fallback: edge frequencies are at the same positions.
            if (payload.size < 15 + 475) {
                appendLog(
                    "SCOPE fixed frame incomplete: payload=${payload.size}"
                )
                return
            }

            val lowerHz =
                decodeIcomFrequency(payload, 4)
            val upperHz =
                decodeIcomFrequency(payload, 9)
            val outOfRange =
                (payload[14].toInt() and 0xFF) != 0

            if (outOfRange) {
                updateScopeInfo(
                    "Spectrum: IC-705 reports scope OUT OF RANGE"
                )
                return
            }

            val waveform =
                payload.copyOfRange(
                    15,
                    minOf(15 + 475, payload.size)
                )

            if (waveform.size < 475) {
                return
            }

            val centerHz =
                (lowerHz + upperHz) / 2L
            val spanHz =
                kotlin.math.abs(upperHz - lowerHz)

            displayScopeWaveform(
                centerHz,
                spanHz,
                waveform
            )
            return
        }

        // USB-style divided scope frames are also accepted, although the
        // IC-705 LAN reference specifies division maximum = 01 on LAN.
        if (divCurrent == 1) {
            if (payload.size < 15) {
                return
            }

            scopeAssemblyMainSub = mainSub
            scopeAssemblyMode = scopeMode
            scopeAssemblyOutOfRange =
                (payload[14].toInt() and 0xFF) != 0
            scopeAssemblyWaveform.reset()

            if (scopeMode == 0) {
                scopeAssemblyCenterHz =
                    decodeIcomFrequency(payload, 4)
                scopeAssemblySpanHz =
                    decodeIcomFrequency(payload, 9)
            } else {
                val lowerHz =
                    decodeIcomFrequency(payload, 4)
                val upperHz =
                    decodeIcomFrequency(payload, 9)
                scopeAssemblyCenterHz =
                    (lowerHz + upperHz) / 2L
                scopeAssemblySpanHz =
                    kotlin.math.abs(upperHz - lowerHz)
            }
            return
        }

        if (scopeAssemblyOutOfRange) {
            return
        }

        if (divCurrent >= 2) {
            if (payload.size > 3) {
                scopeAssemblyWaveform.write(
                    payload,
                    3,
                    payload.size - 3
                )
            }
        }

        if (divCurrent >= divMaximum) {
            val waveform =
                scopeAssemblyWaveform.toByteArray()

            if (waveform.size >= 475) {
                displayScopeWaveform(
                    scopeAssemblyCenterHz,
                    scopeAssemblySpanHz,
                    waveform.copyOf(475)
                )
            }

            resetScopeAssembly()
        }
    }

    private fun displayScopeWaveform(centerHz: Long, spanHz: Long, waveform: ByteArray) {
        if (waveform.size < 475) return
        val displayKhz = requestedDisplayBandwidthKHz.coerceIn(5, 1000)
        val totalSpanHz = if (spanHz > 0L) spanHz * 2L else 100_000L
        val visibleHz = minOf(displayKhz.toLong() * 1000L, totalSpanHz)
        val count = (475.0 * visibleHz.toDouble() / totalSpanHz.toDouble()).toInt().coerceIn(24, 475)
        val startIndex = ((475 - count) / 2).coerceAtLeast(0)
        val samples = IntArray(count)
        for (i in 0 until count) samples[i] = waveform[startIndex + i].toInt() and 0xFF
        scopeDecodedFrames++
        scopeLastDecodedAtMs = System.currentTimeMillis()
        scopeUiUpdateCounter++
        if (::scopeView.isInitialized) scopeView.updateSpectrum(centerHz, totalSpanHz, samples)
        if (scopeUiUpdateCounter % 10L == 0L) {
            val viewText = String.format(java.util.Locale.US, "%.1f kHz", visibleHz / 1000.0)
            updateScopeInfo("Spectrum: LIVE — frames=$scopeDecodedFrames center=${formatFrequency(centerHz)} view=$viewText C1=$scopeTransportPackets")
        }
    }

    private fun updateScopeInfo(
        text: String
    ) {
        if (!::scopeInfoText.isInitialized) {
            return
        }

        runOnUiThread {
            if (::scopeInfoText.isInitialized) {
                scopeInfoText.text = text
            }
        }
    }

    private fun updateSignalMeter(value: Int) {
        if (radioTransmitting || txWanted) return
        val clamped = value.coerceIn(0, 255)
        val meterText = if (clamped <= 120) {
            val sLevel = kotlin.math.min(9, (clamped * 9 + 60) / 120)
            "Signal: S$sLevel"
        } else {
            val dbAboveS9 = ((((clamped - 120) * 40f) / 135f / 5f)
                .roundToInt().coerceIn(1, 8)) * 5
            "Signal: S9 +$dbAboveS9"
        }


        runOnUiThread {
            if (radioTransmitting || txWanted) return@runOnUiThread
            if (::signalMeterLabel.isInitialized) {
                signalMeterLabel.text = meterText
            }
            if (::signalMeterBar.isInitialized) {
                signalMeterBar.progress = clamped
                signalMeterBar.progressTintList = ColorStateList.valueOf(
                    if (clamped > 120) Color.rgb(220, 0, 0) else Color.rgb(0, 190, 0)
                )
            }
        }
    }

    private fun processCivFrame(
        civ: ByteArray
    ) {
        if (civ.size < 2) {
            return
        }

        handleTxReply(civ)
        appendLog("CI-V FRAME RX: ${hex(civ)}")

        // Raw IC-705 WLAN scope form: 27 00 ... FD. The user's working
        // capture proves this exact form is what arrives inside C1.
        if (civ.size >= 493 &&
            (civ[0].toInt() and 0xFF) == 0x27 &&
            (civ[1].toInt() and 0xFF) == 0x00 &&
            (civ[civ.size - 1].toInt() and 0xFF) == 0xFD) {
            scopeCivFrames++
            scopeRaw27Frames++
            scopeLastRawFrameAtMs = System.currentTimeMillis()
            processSpectrumScopeFrame(civ.copyOfRange(2, civ.size - 1))
            return
        }

        // Full framed scope form: FE FE E0 A4 27 00 ... FD.
        if (civ.size >= 493 &&
            (civ[0].toInt() and 0xFF) == 0xFE &&
            (civ[1].toInt() and 0xFF) == 0xFE &&
            (civ[2].toInt() and 0xFF) == 0xE0 &&
            (civ[3].toInt() and 0xFF) == 0xA4 &&
            (civ[4].toInt() and 0xFF) == 0x27 &&
            (civ[5].toInt() and 0xFF) == 0x00 &&
            (civ[civ.size - 1].toInt() and 0xFF) == 0xFD) {
            scopeCivFrames++
            processSpectrumScopeFrame(civ.copyOfRange(6, civ.size - 1))
            return
        }

        // CI-V echo of our frequency-set command.
        if (
            civ.size >= 12 &&
            (civ[0].toInt() and 0xFF) == 0xFE &&
            (civ[1].toInt() and 0xFF) == 0xFE &&
            (civ[2].toInt() and 0xFF) == 0xA4 &&
            (civ[3].toInt() and 0xFF) == 0xE0 &&
            (civ[4].toInt() and 0xFF) == 0x25 &&
            (civ[5].toInt() and 0xFF) == 0x00 &&
            (civ[civ.size - 1].toInt() and 0xFF) == 0xFD
        ) {
            appendLog("CI-V SET MAIN VFO echo received")
            return
        }

        // IC-705 selected/main VFO frequency response:
        // FE FE E0 A4 25 00 [5 BCD bytes] FD
        if (
            civ.size >= 12 &&
            (civ[0].toInt() and 0xFF) == 0xFE &&
            (civ[1].toInt() and 0xFF) == 0xFE &&
            (civ[2].toInt() and 0xFF) == 0xE0 &&
            (civ[3].toInt() and 0xFF) == 0xA4 &&
            (civ[4].toInt() and 0xFF) == 0x25 &&
            (civ[5].toInt() and 0xFF) == 0x00 &&
            (civ[civ.size - 1].toInt() and 0xFF) == 0xFD
        ) {
            val hz = decodeIcomFrequency(civ, 6)

            if (hz > 0L) {
                frequencyHz = hz
                frequencyWriteResponseReceived = true
                displayRadioFrequency(hz)
                appendLog(
                    "IC-705 MAIN VFO RESPONSE: ${formatFrequency(hz)}"
                )
            } else {
                appendLog("CI-V MAIN VFO frequency decode failed.")
            }
            return
        }

        // Selected/main VFO mode response:
        // FE FE E0 A4 26 00 <MODE> <DATA> <FILTER> FD
        if (
            civ.size >= 10 &&
            (civ[0].toInt() and 0xFF) == 0xFE &&
            (civ[1].toInt() and 0xFF) == 0xFE &&
            (civ[2].toInt() and 0xFF) == 0xE0 &&
            (civ[3].toInt() and 0xFF) == 0xA4 &&
            (civ[4].toInt() and 0xFF) == 0x26 &&
            (civ[5].toInt() and 0xFF) == 0x00 &&
            (civ[civ.size - 1].toInt() and 0xFF) == 0xFD
        ) {
            val modeCode =
                civ[6].toInt() and 0xFF

            val dataMode =
                if (civ.size >= 9) {
                    civ[7].toInt() and 0xFF
                } else {
                    0
                }

            val filterCode =
                if (civ.size >= 10) {
                    civ[8].toInt() and 0xFF
                } else {
                    0
                }

            val modeName =
                modeNameFromCode(modeCode)

            activeModeCode = modeCode
            autoSidebandChangePending = false
            modeReceived = true
            updateModeButtons(modeCode)

            appendLog(
                "IC-705 MODE READBACK: $modeName " +
                        "(${String.format("%02X", modeCode)}) " +
                        "DATA=${String.format("%02X", dataMode)} " +
                        "FILTER=${String.format("%02X", filterCode)}"
            )

            appendLog(
                "ACTIVE MODE: $modeName"
            )

            return
        }

        // IC-705 S-meter response:
        // FE FE E0 A4 15 02 [2 BCD bytes] FD
        if (
            civ.size >= 9 &&
            (civ[0].toInt() and 0xFF) == 0xFE &&
            (civ[1].toInt() and 0xFF) == 0xFE &&
            (civ[2].toInt() and 0xFF) == 0xE0 &&
            (civ[3].toInt() and 0xFF) == 0xA4 &&
            (civ[4].toInt() and 0xFF) == 0x15 &&
            (civ[5].toInt() and 0xFF) == 0x02 &&
            (civ[civ.size - 1].toInt() and 0xFF) == 0xFD
        ) {
            val highBcd = civ[6].toInt() and 0xFF
            val lowBcd = civ[7].toInt() and 0xFF
            val high = ((highBcd ushr 4) and 0x0F) * 10 + (highBcd and 0x0F)
            val low = ((lowBcd ushr 4) and 0x0F) * 10 + (lowBcd and 0x0F)
            val value = (high * 100 + low).coerceIn(0, 255)
            signalMeterValue = value
            updateSignalMeter(value)
            return
        }

        // ------------------------------------------------------------
        // Existing proven frequency-read response:
        // ------------------------------------------------------------
        // FE FE E0 A4 03 [5 BCD bytes] FD
        if (
            civ.size >= 11 &&
            (civ[0].toInt() and 0xFF) == 0xFE &&
            (civ[1].toInt() and 0xFF) == 0xFE &&
            (civ[2].toInt() and 0xFF) == 0xE0 &&
            (civ[3].toInt() and 0xFF) == 0xA4 &&
            (civ[4].toInt() and 0xFF) == 0x03 &&
            (civ[civ.size - 1].toInt() and 0xFF) == 0xFD
        ) {
            val hz = decodeIcomFrequency(civ, 5)

            if (hz > 0L) {
                frequencyHz = hz
                frequencyReceived = true
                displayRadioFrequency(hz)
                appendLog(
                    "IC-705 FREQUENCY READ: ${formatFrequency(hz)}"
                )
            } else {
                appendLog("CI-V frequency decode failed")
            }
            return
        }

        // Proper Icom CI-V ACK: FE FE E0 A4 FB FD
        if (
            civ.size == 6 &&
            (civ[0].toInt() and 0xFF) == 0xFE &&
            (civ[1].toInt() and 0xFF) == 0xFE &&
            (civ[2].toInt() and 0xFF) == 0xE0 &&
            (civ[3].toInt() and 0xFF) == 0xA4 &&
            (civ[4].toInt() and 0xFF) == 0xFB &&
            (civ[5].toInt() and 0xFF) == 0xFD
        ) {
            appendLog("CI-V ACK received")
            frequencyWriteResponseReceived = true
            return
        }

        // Proper Icom CI-V NAK: FE FE E0 A4 FA FD
        if (
            civ.size == 6 &&
            (civ[0].toInt() and 0xFF) == 0xFE &&
            (civ[1].toInt() and 0xFF) == 0xFE &&
            (civ[2].toInt() and 0xFF) == 0xE0 &&
            (civ[3].toInt() and 0xFF) == 0xA4 &&
            (civ[4].toInt() and 0xFF) == 0xFA &&
            (civ[5].toInt() and 0xFF) == 0xFD
        ) {
            appendLog("CI-V NAK received")
            frequencyWriteRejected = true
            return
        }

        appendLog("CI-V unhandled frame")
    }

    private fun decodeIcomFrequency(
        civ: ByteArray,
        offset: Int
    ): Long {
        if (offset + 5 > civ.size) {
            return 0L
        }
        var value = 0L
        var multiplier = 1L
        for (i in 0 until 5) {
            val b =
                civ[offset + i].toInt() and 0xFF
            val low = b and 0x0F
            val high = (b ushr 4) and 0x0F
            if (low > 9 || high > 9) {
                return 0L
            }
            value +=
                (low + high * 10L) * multiplier
            multiplier *= 100L
        }
        return value
    }

    private fun displayRadioFrequency(hz: Long) {
        runOnUiThread {
            if (::frequencyLabelView.isInitialized) {
                frequencyLabelView.text = "Frequency: ${formatFrequency(hz)}"
            }
            if (::frequencyEdit.isInitialized && !frequencyEdit.hasFocus()) {
                frequencyEdit.setText(hz.toString())
            }
            if (::scopeView.isInitialized) scopeView.centerFrequencyHz = hz
        }
        if (connected) applyAutomaticSideband(hz)
    }

    private fun applyAutomaticSideband(hz: Long) {
        if (userDisconnectRequested) return
        val targetMode = when {
            hz > 12_000_000L -> 0x01
            hz < 11_990_000L -> 0x00
            else -> return
        }
        if (!running || !serialOpen || activeModeCode == targetMode) return
        synchronized(autoSidebandLock) {
            if (autoSidebandChangePending || activeModeCode == targetMode) return
            autoSidebandChangePending = true
        }
        val name = if (targetMode == 0x01) "USB" else "LSB"
        appendLog("AUTO MODE: ${formatFrequency(hz)} selects $name.")
        setOperatingModeFromUi(name, targetMode)
        Thread {
            try { Thread.sleep(1500L) } catch (_: InterruptedException) { }
            synchronized(autoSidebandLock) { autoSidebandChangePending = false }
        }.start()
    }
    private fun formatFrequency(
        hz: Long
    ): String {
        val mhz = hz / 1_000_000L
        val remainder =
            hz % 1_000_000L
        return String.format(
            java.util.Locale.US,
            "%d.%06d MHz",
            mhz,
            remainder
        )
    }
    private fun startSerialKeepAlive() {
        serialScheduler?.shutdownNow()
        serialScheduler =
            Executors.newScheduledThreadPool(2)
        serialScheduler?.scheduleAtFixedRate(
            {
                if (running) {
                    try {
                        sendSerialPkt7()
                    } catch (e: Exception) {
                        if (running) {
                            appendLog(
                                "SERIAL PKT7 SEND ERROR: ${e.message}"
                            )
                        }
                    }
                }
            },
            100,
            100,
            TimeUnit.MILLISECONDS
        )
        serialScheduler?.scheduleAtFixedRate(
            {
                if (running) {
                    try {
                        sendSerialPkt0()
                    } catch (e: Exception) {
                        if (running) {
                            appendLog(
                                "SERIAL PKT0 SEND ERROR: ${e.message}"
                            )
                        }
                    }
                }
            },
            100,
            100,
            TimeUnit.MILLISECONDS
        )
    }
    private fun sendSerialPkt0() {
        val packet = ByteArray(16)
        packet[0] = 0x10.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x00
        packet[5] = 0x00
        synchronized(sendLock) {
            packet[6] =
                (serialOuterSequence and 0xFF).toByte()
            packet[7] =
                ((serialOuterSequence ushr 8) and 0xFF).toByte()
            serialOuterSequence =
                (serialOuterSequence + 1) and 0xFFFF
        }
        writeBeInt(
            packet,
            8,
            serialLocalSid
        )
        writeBeInt(
            packet,
            12,
            serialRemoteSid
        )
        sendSerialRaw(packet)
    }
    private fun sendSerialPkt7() {
        val packet = ByteArray(21)
        packet[0] = 0x15.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x07
        packet[5] = 0x00
        packet[6] =
            (serialPkt7Sequence and 0xFF).toByte()
        packet[7] =
            ((serialPkt7Sequence ushr 8) and 0xFF).toByte()
        serialPkt7Sequence =
            (serialPkt7Sequence + 1) and 0xFFFF
        writeBeInt(
            packet,
            8,
            serialLocalSid
        )
        writeBeInt(
            packet,
            12,
            serialRemoteSid
        )
        packet[16] = 0x00
        packet[17] = 0x00
        packet[18] = 0x00
        packet[19] = 0x00
        packet[20] = 0x06
        sendSerialRaw(packet)
    }
    private fun buildSerialPkt7Reply(
        sequence: Int,
        replyId: ByteArray
    ): ByteArray {
        val packet = ByteArray(21)
        packet[0] = 0x15.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x07
        packet[5] = 0x00
        packet[6] =
            (sequence and 0xFF).toByte()
        packet[7] =
            ((sequence ushr 8) and 0xFF).toByte()
        writeBeInt(
            packet,
            8,
            serialRemoteSid
        )
        writeBeInt(
            packet,
            12,
            serialLocalSid
        )
        packet[16] = 0x01
        for (i in 0 until 4) {
            packet[17 + i] =
                replyId.getOrElse(i) { 0 }
        }
        return packet
    }
    private fun sendSerialTracked(
        packet: ByteArray
    ) {
        synchronized(sendLock) {
            packet[6] =
                (serialOuterSequence and 0xFF).toByte()
            packet[7] =
                ((serialOuterSequence ushr 8) and 0xFF).toByte()
            val sequence =
                serialOuterSequence
            serialOuterSequence =
                (serialOuterSequence + 1) and 0xFFFF
            serialTrackedPackets[sequence] =
                packet.copyOf()
            sendSerialRaw(packet)
        }
    }
    private fun retransmitSerial(
        sequence: Int
    ) {
        val packet =
            serialTrackedPackets[sequence]
                ?: return
        try {
            sendSerialRaw(packet)
            Thread.sleep(10)
            sendSerialRaw(packet)
        } catch (e: Exception) {
            if (running) {
                appendLog(
                    "Serial retransmit error: ${e.message}"
                )
            }
        }
    }
    private fun sendSerialRaw(
        data: ByteArray
    ) {
        val socket =
            serialSocket
                ?: throw Exception(
                    "Serial socket is closed."
                )
        synchronized(sendLock) {
            val packet =
                DatagramPacket(
                    data,
                    data.size
                )
            try {
                socket.send(packet)
            } catch (e: Exception) {
                handleUdpSendFailure("serial", e)
                throw e
            }
        }
    }
    private fun receiveSerialExpected(
        expectedLength: Int,
        timeoutMs: Int,
        matcher: (ByteArray) -> Boolean
    ): ByteArray? {
        val socket =
            serialSocket
                ?: return null
        val end =
            System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(4096)
        while (
            running &&
            System.currentTimeMillis() < end
        ) {
            val remaining =
                end - System.currentTimeMillis()
            if (remaining <= 0) {
                break
            }
            try {
                socket.soTimeout =
                    minOf(
                        SOCKET_TIMEOUT_MS,
                        remaining.toInt()
                    )
                val packet =
                    DatagramPacket(
                        buffer,
                        buffer.size
                    )
                socket.receive(packet)
                val data =
                    packet.data.copyOf(packet.length)
                if (
                    data.size == expectedLength &&
                    matcher(data)
                ) {
                    return data
                }
                if (
                    data.size == 21 &&
                    data[4].toInt() and 0xFF == 0x07
                ) {
                    val direction =
                        data[16].toInt() and 0xFF
                    if (direction == 0x00) {
                        try {
                            sendSerialRaw(
                                buildSerialPkt7Reply(
                                    readLeShort(data, 6),
                                    data.copyOfRange(17, 21)
                                )
                            )
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: java.net.SocketTimeoutException) {
            }
        }
        return null
    }
    private fun startReceiverThread() {
        if (receiverThread?.isAlive == true) {
            return
        }
        receiverThread = Thread {
            receiverLoop()
        }
        receiverThread?.start()
    }
    private fun receiverLoop() {
        val buffer = ByteArray(4096)
        while (running) {
            val socket = controlSocket ?: break
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val data = packet.data.copyOf(packet.length)
                processIncomingPacket(data)
            } catch (e: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                if (running) {
                    appendLog("RECEIVE ERROR: ${e.message}")
                }
                break
            }
        }
    }
    private fun processIncomingPacket(data: ByteArray) {
        if (data.size < 8) {
            return
        }
        val byte4 = data[4].toInt() and 0xFF
        val byte5 = data[5].toInt() and 0xFF
        if (
            data.size == 21 &&
            byte4 == 0x07 &&
            byte5 == 0x00
        ) {
            val direction = data[16].toInt() and 0xFF
            if (direction == 0x00) {
                val incomingSequence = readLeShort(data, 6)
                val replyId = data.copyOfRange(17, 21)
                try {
                    sendRaw(
                        buildPkt7Reply(
                            incomingSequence,
                            replyId
                        )
                    )
                } catch (e: Exception) {
                    appendLog("PKT7 reply error: ${e.message}")
                }
            }
            return
        }
        if (
            data.size >= 16 &&
            data[0].toInt() and 0xFF == 0x10 &&
            byte4 == 0x01 &&
            byte5 == 0x00
        ) {
            val requestedSeq = readLeShort(data, 6)
            retransmit(requestedSeq)
            return
        }
        if (
            data.size == 168 &&
            data[0].toInt() and 0xFF == 0xA8 &&
            data[1].toInt() and 0xFF == 0x00
        ) {
            if (!gotA8) {
                System.arraycopy(
                    data,
                    66,
                    a8ReplyId,
                    0,
                    16
                )
                gotA8 = true
                appendLog("A8 capabilities received")
                appendLog("A8 Reply ID: ${hex(a8ReplyId)}")
            }
            return
        }
        if (
            data.size == 64 &&
            data[0].toInt() and 0xFF == 0x40 &&
            data[1].toInt() and 0xFF == 0x00
        ) {
            val magic = data[21].toInt() and 0xFF
            if (magic == 0x05) {
                synchronized(sendLock) {
                    val request = renewalRequest
                    if (request != null && AuthRenewalReply.matches(data, request)) {
                        val elapsed = android.os.SystemClock.elapsedRealtime() - renewalSentAt
                        renewalRequest = null
                        appendLog("AUTH RENEWAL ACK: seq=${readLeShort(data, 23)} elapsed=${elapsed}ms")
                    } else if (!authOk) {
                        authOk = true
                        appendLog("AUTH #2 SUCCESS")
                    } else {
                        appendLog("AUTH response: no matching pending renewal; seq=${readLeShort(data, 23)}")
                    }
                }
            }
            return
        }
        if (
            data.size == 144 &&
            data[0].toInt() and 0xFF == 0x90
        ) {
            val result = data[96].toInt() and 0xFF
            if (result == 1) {
                if (data.size >= 16) {
                    remoteSid = readBeInt(data, 8)
                    localSid = readBeInt(data, 12)
                }
                if (data.size >= 32) {
                    System.arraycopy(
                        data,
                        26,
                        authId,
                        0,
                        6
                    )
                }
                connInfoOk = true
                appendLog("CONNINFO SUCCESS")
                if (data.size >= 72) {
                    val model = readNullTerminatedAscii(
                        data,
                        64,
                        8
                    )
                    if (model.isNotEmpty()) {
                        appendLog("Radio model: $model")
                    }
                }
            } else {
                appendLog("CONNINFO response result = $result")
            }
            return
        }
        if (
            data.size == 80 &&
            byte4 == 0x50 &&
            byte5 == 0x00
        ) {
            val status48to51 =
                if (data.size >= 52) {
                    hex(data, 48, 4)
                } else {
                    ""
                }
            if (status48to51.isNotEmpty()) {
                appendLog("STATUS: $status48to51")
            }
            return
        }
    }
    private fun startKeepAlive() {
        scheduler?.shutdownNow()
        scheduler = Executors.newScheduledThreadPool(2)
        val sessionSocket = controlSocket
        synchronized(sendLock) {
            renewalRequest = null
            renewalSentAt = 0L
            nextRenewalAt = android.os.SystemClock.elapsedRealtime() + 60_000L
        }
        // PKT0/PKT7 keepalives do not renew the authenticated session.
        // Reuse AUTH 0x05 every minute without reopening audio or serial streams.
        scheduler?.scheduleAtFixedRate({
            synchronized(sendLock) {
                if (running && connected && authOk && !userDisconnectRequested &&
                    !teardownStarted && controlSocket === sessionSocket) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (renewalRequest != null && now - renewalSentAt >= 3_000L) {
                        appendLog("AUTH RENEWAL TIMEOUT: no matching ACK within 3000ms; streams left running")
                        renewalRequest = null
                    }
                    if (now >= nextRenewalAt) {
                        nextRenewalAt = now + 60_000L
                        try {
                            val request = buildAuth(0x05)
                            renewalRequest = request.copyOf()
                            renewalSentAt = now
                            sendTracked(request)
                            appendLog("AUTH RENEWAL SENT: seq=${readLeShort(request, 23)}")
                        } catch (e: Exception) {
                            renewalRequest = null
                            appendLog("AUTH RENEWAL SEND ERROR: ${e.message}")
                        }
                    }
                }
            }
        }, 1L, 1L, TimeUnit.SECONDS)
        scheduler?.scheduleAtFixedRate(
            {
                if (running) {
                    try {
                        sendPkt7()
                    } catch (e: Exception) {
                        if (running) {
                            appendLog("PKT7 SEND ERROR: ${e.message}")
                        }
                    }
                }
            },
            100,
            100,
            TimeUnit.MILLISECONDS
        )
        scheduler?.scheduleAtFixedRate(
            {
                if (running) {
                    try {
                        sendPkt0()
                    } catch (e: Exception) {
                        if (running) {
                            appendLog("PKT0 SEND ERROR: ${e.message}")
                        }
                    }
                }
            },
            100,
            100,
            TimeUnit.MILLISECONDS
        )
    }
    private fun sendPkt0() {
        val packet = ByteArray(16)
        packet[0] = 0x10.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x00
        packet[5] = 0x00
        synchronized(sendLock) {
            packet[6] = (outerSequence and 0xFF).toByte()
            packet[7] = ((outerSequence ushr 8) and 0xFF).toByte()
            outerSequence = (outerSequence + 1) and 0xFFFF
        }
        writeBeInt(packet, 8, localSid)
        writeBeInt(packet, 12, remoteSid)
        sendRaw(packet)
    }
    private fun sendPkt7() {
        val packet = ByteArray(21)
        packet[0] = 0x15.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x07
        packet[5] = 0x00
        packet[6] = (pkt7Sequence and 0xFF).toByte()
        packet[7] = ((pkt7Sequence ushr 8) and 0xFF).toByte()
        pkt7Sequence = (pkt7Sequence + 1) and 0xFFFF
        writeBeInt(packet, 8, localSid)
        writeBeInt(packet, 12, remoteSid)
        packet[16] = 0x00
        packet[17] = secureRandom.nextInt(256).toByte()
        packet[18] = (pkt7InnerSequence and 0xFF).toByte()
        packet[19] = ((pkt7InnerSequence ushr 8) and 0xFF).toByte()
        packet[20] = 0x06
        pkt7InnerSequence =
            (pkt7InnerSequence + 1) and 0xFFFF
        sendRaw(packet)
    }
    private fun buildPkt7Reply(
        sequence: Int,
        replyId: ByteArray
    ): ByteArray {
        val packet = ByteArray(21)
        packet[0] = 0x15.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x07
        packet[5] = 0x00
        packet[6] = (sequence and 0xFF).toByte()
        packet[7] = ((sequence ushr 8) and 0xFF).toByte()
        writeBeInt(packet, 8, remoteSid)
        writeBeInt(packet, 12, localSid)
        packet[16] = 0x01
        for (i in 0 until 4) {
            packet[17 + i] = replyId[i]
        }
        return packet
    }
    private fun buildAyt(
        localSid: Int
    ): ByteArray {
        val packet = ByteArray(16)
        packet[0] = 0x10.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x03
        packet[5] = 0x00
        packet[6] = 0x00
        packet[7] = 0x00
        writeBeInt(packet, 8, localSid)
        writeBeInt(packet, 12, 0)
        return packet
    }
    private fun buildReady(
        localSid: Int,
        remoteSid: Int
    ): ByteArray {
        val packet = ByteArray(16)
        packet[0] = 0x10.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x06
        packet[5] = 0x00
        packet[6] = 0x01
        packet[7] = 0x00
        writeBeInt(packet, 8, localSid)
        writeBeInt(packet, 12, remoteSid)
        return packet
    }
    private fun buildLogin(
        username: String,
        password: String
    ): ByteArray {
        val packet = ByteArray(128)
        packet[0] = 0x80.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x00
        packet[5] = 0x00
        packet[6] = 0x00
        packet[7] = 0x00
        writeBeInt(packet, 8, localSid)
        writeBeInt(packet, 12, remoteSid)
        packet[16] = 0x00
        packet[17] = 0x00
        packet[18] = 0x00
        packet[19] = 0x70.toByte()
        packet[20] = 0x01
        packet[21] = 0x00
        packet[22] = 0x00
        packet[23] =
            (authInnerSequence and 0xFF).toByte()
        packet[24] =
            ((authInnerSequence ushr 8) and 0xFF).toByte()
        packet[25] = 0x00
        val authStartId = ByteArray(2)
        secureRandom.nextBytes(authStartId)
        packet[26] = authStartId[0]
        packet[27] = authStartId[1]
        val encodedUsername = passcode(username)
        val encodedPassword = passcode(password)
        System.arraycopy(
            encodedUsername,
            0,
            packet,
            64,
            16
        )
        System.arraycopy(
            encodedPassword,
            0,
            packet,
            80,
            16
        )
        val appBytes = APP_NAME.toByteArray(Charsets.US_ASCII)
        for (i in appBytes.indices) {
            if (96 + i < packet.size) {
                packet[96 + i] = appBytes[i]
            }
        }
        authInnerSequence =
            (authInnerSequence + 1) and 0xFFFF
        return packet
    }
    private fun buildAuth(
        magic: Int
    ): ByteArray {
        val packet = ByteArray(64)
        packet[0] = 0x40.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x00
        packet[5] = 0x00
        packet[6] = 0x00
        packet[7] = 0x00
        writeBeInt(packet, 8, localSid)
        writeBeInt(packet, 12, remoteSid)
        packet[16] = 0x00
        packet[17] = 0x00
        packet[18] = 0x00
        packet[19] = 0x30
        packet[20] = 0x01
        packet[21] = magic.toByte()
        packet[22] = 0x00
        packet[23] =
            (authInnerSequence and 0xFF).toByte()
        packet[24] =
            ((authInnerSequence ushr 8) and 0xFF).toByte()
        packet[25] = 0x00
        System.arraycopy(
            authId,
            0,
            packet,
            26,
            6
        )
        authInnerSequence =
            (authInnerSequence + 1) and 0xFFFF
        return packet
    }
    private fun buildConnInfo(
        username: String
    ): ByteArray {
        val packet = ByteArray(144)
        packet[0] = 0x90.toByte()
        packet[1] = 0x00
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = 0x00
        packet[5] = 0x00
        packet[6] = 0x00
        packet[7] = 0x00
        writeBeInt(packet, 8, localSid)
        writeBeInt(packet, 12, remoteSid)
        packet[16] = 0x00
        packet[17] = 0x00
        packet[18] = 0x00
        packet[19] = 0x80.toByte()
        packet[20] = 0x01
        packet[21] = 0x03
        packet[22] = 0x00
        packet[23] =
            (authInnerSequence and 0xFF).toByte()
        packet[24] =
            ((authInnerSequence ushr 8) and 0xFF).toByte()
        packet[25] = 0x00
        System.arraycopy(
            authId,
            0,
            packet,
            26,
            6
        )
        System.arraycopy(
            a8ReplyId,
            0,
            packet,
            32,
            16
        )
        val modelBytes =
            MODEL_NAME.toByteArray(Charsets.US_ASCII)
        for (i in modelBytes.indices) {
            if (64 + i < 72) {
                packet[64 + i] = modelBytes[i]
            }
        }
        val encodedUsername = passcode(username)
        System.arraycopy(
            encodedUsername,
            0,
            packet,
            96,
            16
        )
        packet[112] = 0x01
        packet[113] = 0x01
        packet[114] = 0x04
        packet[115] = 0x04
        writeBeInt(
            packet,
            116,
            AUDIO_SAMPLE_RATE
        )
        writeBeInt(
            packet,
            120,
            AUDIO_SAMPLE_RATE
        )
        writeBeInt(
            packet,
            124,
            SERIAL_PORT
        )
        writeBeInt(
            packet,
            128,
            AUDIO_PORT
        )
        writeBeInt(
            packet,
            132,
            TX_BUFFER_LENGTH_MS
        )
        packet[136] = 0x01
        authInnerSequence =
            (authInnerSequence + 1) and 0xFFFF
        return packet
    }
    private fun sendTracked(packet: ByteArray) {
        synchronized(sendLock) {
            packet[6] =
                (outerSequence and 0xFF).toByte()
            packet[7] =
                ((outerSequence ushr 8) and 0xFF).toByte()
            val sequence = outerSequence
            outerSequence =
                (outerSequence + 1) and 0xFFFF
            trackedPackets[sequence] =
                packet.copyOf()
            sendRaw(packet)
        }
    }
    private fun noteIncomingSequence(state: ReceiveSequenceState, sequence: Int) {
        synchronized(state) {
            val previous = state.newest
            if (previous == null) {
                state.newest = sequence
                state.missing.remove(sequence)
                return
            }
            val distance = (sequence - previous) and 0xFFFF
            if (distance == 0) return
            if (distance > 0x8000) {
                state.missing.remove(sequence)
                return
            }
            if (distance > 1) {
                if (distance <= maxTrackedReceiveGaps + 1) {
                    for (step in 1 until distance) {
                        val missingSequence = (previous + step) and 0xFFFF
                        if (state.missing.size < maxTrackedReceiveGaps) {
                            if (!state.missing.containsKey(missingSequence)) {
                                state.missing[missingSequence] = 0
                            }
                        }
                    }
                } else {
                    // A large jump usually means a stream restart or substantial loss.
                    state.missing.clear()
                }
            }
            state.missing.remove(sequence)
            state.newest = sequence
        }
    }

    private fun requestNextMissingPacket(
        state: ReceiveSequenceState,
        socket: DatagramSocket?,
        localStreamId: Int,
        remoteStreamId: Int,
        streamName: String
    ) {
        if (!running || socket == null || socket.isClosed || localStreamId == 0 || remoteStreamId == 0) return
        val requestedSequence = synchronized(state) {
            val entry = state.missing.entries.firstOrNull { it.value < maxReceiveRetransmitRequests }
                ?: return@synchronized null
            state.missing[entry.key] = entry.value + 1
            entry.key
        } ?: return

        // Wait for packet reordering, then request this missing sequence using IC-705 type 0x01.
        val request = ByteArray(16)
        writeLeInt(request, 0, request.size)
        request[4] = 0x01
        request[5] = 0x00
        writeLeShort(request, 6, requestedSequence)
        writeBeInt(request, 8, localStreamId)
        writeBeInt(request, 12, remoteStreamId)
        try {
            socket.send(DatagramPacket(request, request.size))
            appendLog("$streamName UDP gap: requested missing sequence $requestedSequence")
        } catch (e: Exception) {
            handleUdpSendFailure(streamName, e)
            if (running) appendLog("$streamName retransmit request failed: ${e.message}")
        }
    }

    private fun resetReceiveSequenceState(state: ReceiveSequenceState) {
        synchronized(state) {
            state.newest = null
            state.missing.clear()
        }
    }

    private fun retransmit(sequence: Int) {
        val packet = trackedPackets[sequence]
            ?: return
        try {
            sendRaw(packet)
            Thread.sleep(10)
            sendRaw(packet)
        } catch (e: Exception) {
            if (running) {
                appendLog(
                    "Retransmit error: ${e.message}"
                )
            }
        }
    }
    private fun sendRaw(data: ByteArray) {
        val socket = controlSocket
            ?: throw Exception("Control socket is closed.")
        synchronized(sendLock) {
            val packet = DatagramPacket(
                data,
                data.size
            )
            try {
                socket.send(packet)
            } catch (e: Exception) {
                handleUdpSendFailure("control", e)
                throw e
            }
        }
    }
    private fun receiveExpected(
        expectedLength: Int,
        timeoutMs: Int,
        matcher: (ByteArray) -> Boolean
    ): ByteArray? {
        val socket = controlSocket
            ?: return null
        val end =
            System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(4096)
        while (
            running &&
            System.currentTimeMillis() < end
        ) {
            val remaining =
                end - System.currentTimeMillis()
            if (remaining <= 0) {
                break
            }
            try {
                socket.soTimeout =
                    minOf(
                        SOCKET_TIMEOUT_MS,
                        remaining.toInt()
                    )
                val packet = DatagramPacket(
                    buffer,
                    buffer.size
                )
                socket.receive(packet)
                val data =
                    packet.data.copyOf(packet.length)
                if (
                    data.size == expectedLength &&
                    matcher(data)
                ) {
                    return data
                }
                if (
                    data.size == 21 &&
                    data[4].toInt() and 0xFF == 0x07
                ) {
                    val direction =
                        data[16].toInt() and 0xFF
                    if (direction == 0x00) {
                        try {
                            sendRaw(
                                buildPkt7Reply(
                                    readLeShort(data, 6),
                                    data.copyOfRange(17, 21)
                                )
                            )
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: java.net.SocketTimeoutException) {
            }
        }
        return null
    }
    private fun showDisconnectChoice() {
        if (powerDisconnectBusy.get() || disconnectChoiceDialog?.isShowing == true) return
        disconnectChoiceDialog = AlertDialog.Builder(this)
            .setTitle("Disconnect / Standby")
            .setItems(arrayOf(
                "Disconnect only (leave radio power unchanged)",
                "Standby & disconnect (turn radio screen off)"
            )) { _, choice ->
                requestDisconnect(standby = choice == 1)
            }
            .setNegativeButton("Cancel", null)
            .create()
        disconnectChoiceDialog?.setOnDismissListener { disconnectChoiceDialog = null }
        disconnectChoiceDialog?.show()
    }

    // Only the explicit Standby choice requests radio power-off. Disconnect
    // only, error cleanup and network recovery leave power unchanged.
    private fun requestDisconnect(standby: Boolean) {
        cancelTransmit("Disconnect requested")
        if (!powerDisconnectBusy.compareAndSet(false, true)) return
        userDisconnectRequested = true
        updateStartupStatus()
        updateConnectionButtons(connecting = false, isConnected = false)
        setTuneControlsEnabled(false)
        enableBandButtons(false)
        usbButton.isEnabled = false
        lsbButton.isEnabled = false
        scopeToggleButton.isEnabled = false
        streamDiagnosticHandler.removeCallbacks(streamStallWatchdog)
        stopSignalMeterPolling()
        stopScopePolling()
        Thread({
            var result = if (standby)
                "Disconnected; standby was not sent because the control link was unavailable."
            else "Disconnected; radio power unchanged."
            try {
                // Allow TX to unkey and restore routing before standby/teardown.
                val txStopDeadline = android.os.SystemClock.elapsedRealtime() + 12000L
                while (txBusy && android.os.SystemClock.elapsedRealtime() < txStopDeadline) Thread.sleep(20)
                sendPttOff()
                // Let startup/audio handshakes finish before closing their sockets.
                sessionThread?.join()
                if (running && serialOpen) {
                    stopSpectrumScope()
                    stopSignalMeterPolling()
                    if (standby) result = requestRadioStandby()
                }
            } catch (e: Exception) {
                result = if (standby) "Standby not confirmed: ${e.message}"
                    else "Disconnect warning: ${e.message}; no standby command sent."
            } finally {
                try { stopSession() } catch (e: Exception) {
                    appendLog("CLEANUP: ${e.message}")
                    cleanupSocketOnly()
                }
                connected = false
                running = false
                powerDisconnectBusy.set(false)
                val message = result
                appendLog(message)
                runOnUiThread {
                    updateConnectionButtons(connecting = false, isConnected = false)
                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }, "IC705-Standby-Disconnect").start()
    }

    private fun sendPowerCommand(vararg payload: Int) {
        val civ = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xA4.toByte(), 0xE0.toByte()) +
            payload.map { it.toByte() }.toByteArray() + byteArrayOf(0xFD.toByte())
        appendLog("POWER CI-V TX: ${hex(civ)}")
        sendSerialTracked(buildCivPacket(civ))
    }

    private fun collectPowerReplies(chunk: ByteArray) {
        synchronized(powerFrameBuffer) {
            for (b in chunk) {
                powerFrameBuffer.add(b)
                if (b == 0xFD.toByte()) {
                    val start = (0 until powerFrameBuffer.size - 1).firstOrNull {
                        powerFrameBuffer[it] == 0xFE.toByte() && powerFrameBuffer[it + 1] == 0xFE.toByte()
                    }
                    if (start != null) {
                        val f = powerFrameBuffer.subList(start, powerFrameBuffer.size).toByteArray()
                        if (awaitingPowerSetting && f.size == 10 &&
                            f[2] == 0xE0.toByte() && f[3] == 0xA4.toByte() &&
                            f[4] == 0x1A.toByte() && f[5] == 5.toByte() &&
                            f[6] == 0.toByte() && f[7] == 0x73.toByte()) {
                            powerReplies.offer(f)
                        }
                    }
                    powerFrameBuffer.clear()
                }
                if (powerFrameBuffer.size > 8192) powerFrameBuffer.clear()
            }
        }
    }

    private fun requestRadioStandby(): String {
        powerReplies.clear()
        awaitingPowerSetting = true
        var setting: ByteArray? = null
        try {
            repeat(3) {
                if (setting == null && running) {
                    sendPowerCommand(0x1A, 0x05, 0x00, 0x73)
                    setting = powerReplies.poll(1200, TimeUnit.MILLISECONDS)
                }
            }
        } finally { awaitingPowerSetting = false }
        if (setting == null) return "Disconnected; standby NOT sent: Power OFF Setting could not be read."
        if (setting!![8] != 1.toByte()) return "Standby NOT sent. Select SET > Function > Power OFF Setting (for Remote Control) > Standby/Shutdown."
        sendPowerCommand(0x18, 0x00)
        // FB acknowledgements contain no command ID and may belong to earlier
        // scope/mode writes. Do not claim they prove the radio entered standby.
        Thread.sleep(1500)
        return "Standby requested; connection closed. Check the radio screen to confirm."
    }

    private fun stopSession(preserveScreen: Boolean = false) {
        txWanted = false
        try { txRecorder?.stop() } catch (_: Exception) {}
        sendPttOff()
        radioTransmitting = false
        refreshTxUi()
        streamDiagnosticHandler.removeCallbacks(streamStallWatchdog)
        streamDiagnosticHandler.removeCallbacks(streamDiagnosticTask)
        if (preserveScreen || userDisconnectRequested) {
            stopScopePolling()
            scopeStarted = false
            resetScopeAssembly()
        } else {
            try {
                stopSpectrumScope()
            } catch (_: Exception) {
            }
        }


        if (teardownStarted) {
            return
        }
        teardownStarted = true
        running = false
        appendLog("")
        appendLog(if (preserveScreen) "BACKGROUND RECOVERY: releasing old radio session..." else "DISCONNECTING...")
        scheduler?.shutdownNow()
        scheduler = null
        serialScheduler?.shutdownNow()
        serialScheduler = null
        stopSignalMeterPolling()

        // ------------------------------------------------------------
        // Audio stream shutdown first
        // ------------------------------------------------------------

        audioRunning = false

        audioScheduler?.shutdownNow()
        audioScheduler = null

        try {
            audioTrack?.pause()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.flush()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }

        audioTrack = null

        val audio =
            audioSocket

        if (
            audio != null &&
            !audio.isClosed
        ) {
            try {
                val audioDisconnect =
                    ByteArray(16)

                audioDisconnect[0] =
                    0x10.toByte()
                audioDisconnect[1] = 0x00
                audioDisconnect[2] = 0x00
                audioDisconnect[3] = 0x00
                audioDisconnect[4] = 0x05
                audioDisconnect[5] = 0x00
                audioDisconnect[6] = 0x00
                audioDisconnect[7] = 0x00

                writeBeInt(
                    audioDisconnect,
                    8,
                    audioLocalSid
                )

                writeBeInt(
                    audioDisconnect,
                    12,
                    audioRemoteSid
                )

                sendAudioRaw(
                    audioDisconnect
                )

                Thread.sleep(50)

                sendAudioRaw(
                    audioDisconnect
                )

                appendLog(
                    "Audio disconnect sent twice"
                )
            } catch (e: Exception) {
                appendLog(
                    "Audio disconnect warning: ${e.message}"
                )
            }
        }

        try {
            audioSocket?.close()
        } catch (_: Exception) {
        }

        audioSocket = null
        audioReceiverThread = null
        audioHaveSequence = false
        audioPacketCount = 0L

        val serial = serialSocket
        if (serial != null && !serial.isClosed) {
            try {
                if (serialOpen) {
                    sendSerialTracked(buildSerialClose())
                    appendLog("Serial close sent")
                    Thread.sleep(100L)
                }
                val serialDisconnect = ByteArray(16)
                serialDisconnect[0] = 0x10.toByte()
                serialDisconnect[1] = 0x00
                serialDisconnect[2] = 0x00
                serialDisconnect[3] = 0x00
                serialDisconnect[4] = 0x05
                serialDisconnect[5] = 0x00
                serialDisconnect[6] = 0x00
                serialDisconnect[7] = 0x00
                writeBeInt(
                    serialDisconnect,
                    8,
                    serialLocalSid
                )
                writeBeInt(
                    serialDisconnect,
                    12,
                    serialRemoteSid
                )
                sendSerialRaw(serialDisconnect)
                Thread.sleep(50L)
                sendSerialRaw(serialDisconnect)
                appendLog("Serial disconnect sent twice")
            } catch (e: Exception) {
                appendLog(
                    "Serial disconnect warning: ${e.message}"
                )
            }
        }
        try {
            serialSocket?.close()
        } catch (_: Exception) {
        }
        serialSocket = null
        serialReceiverThread = null
        serialOpen = false
        val socket = controlSocket
        if (socket != null && !socket.isClosed) {
            try {
                if (
                    remoteSid != 0 &&
                    authId.any { it.toInt() != 0 }
                ) {
                    val deauth =
                        buildAuth(0x01)
                    sendTracked(deauth)
                    appendLog("De-auth sent")
                    Thread.sleep(500L)
                }
            } catch (e: Exception) {
                appendLog(
                    "De-auth warning: ${e.message}"
                )
            }
            try {
                val disconnect =
                    ByteArray(16)
                disconnect[0] = 0x10.toByte()
                disconnect[1] = 0x00
                disconnect[2] = 0x00
                disconnect[3] = 0x00
                disconnect[4] = 0x05
                disconnect[5] = 0x00
                disconnect[6] = 0x00
                disconnect[7] = 0x00
                writeBeInt(
                    disconnect,
                    8,
                    localSid
                )
                writeBeInt(
                    disconnect,
                    12,
                    remoteSid
                )
                sendRaw(disconnect)
                Thread.sleep(50)
                sendRaw(disconnect)
                appendLog("Disconnect packet sent twice")
            } catch (e: Exception) {
                appendLog(
                    "Disconnect warning: ${e.message}"
                )
            }
        }
        connected = false
        audioStreamExpected = false
        audioStreamExpectedSinceAtMs = 0L
        cleanupSocketOnly(preserveScreen)
        if (!preserveScreen) runOnUiThread {
            updateConnectionButtons(connecting = false, isConnected = false)
            updateLinkQuality(Long.MAX_VALUE, Long.MAX_VALUE)
            setFrequencyButton.isEnabled = false
            down500Button.isEnabled = false
            up500Button.isEnabled = false
            down1kButton.isEnabled = false
            up1kButton.isEnabled = false
            usbButton.isEnabled = false
            lsbButton.isEnabled = false
            enableBandButtons(false)
        }
        appendLog("Connection closed.")
        appendLog("")
    }
    private fun cleanupSocketOnly(preserveScreen: Boolean = false) {
        startupWaiting = false
        runOnUiThread { if (::startupStatus.isInitialized) startupStatus.visibility = View.GONE }
        stopSignalMeterPolling()
        stopScopePolling()
        try {
            serialScheduler?.shutdownNow()
        } catch (_: Exception) {
        }
        serialScheduler = null

        audioRunning = false

        try {
            audioScheduler?.shutdownNow()
        } catch (_: Exception) {
        }

        audioScheduler = null

        try {
            audioTrack?.pause()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.flush()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }

        audioTrack = null

        try {
            audioSocket?.close()
        } catch (_: Exception) {
        }

        audioSocket = null

        try {
            audioReceiverThread?.interrupt()
        } catch (_: Exception) {
        }

        audioReceiverThread = null

        try {
            serialSocket?.close()
        } catch (_: Exception) {
        }
        serialSocket = null
        serialOpen = false
        try {
            controlSocket?.close()
        } catch (_: Exception) {
        }
        controlSocket = null
        try {
            receiverThread?.interrupt()
        } catch (_: Exception) {
        }
        try {
            serialReceiverThread?.interrupt()
        } catch (_: Exception) {
        }
        receiverThread = null
        serialReceiverThread = null
        if (!preserveScreen) runOnUiThread {
            updateConnectionButtons(connecting = false, isConnected = false)
            updateLinkQuality(Long.MAX_VALUE, Long.MAX_VALUE)
            if (::scopeToggleButton.isInitialized) {
                scopeToggleButton.isEnabled = false
                scopeToggleButton.text = "SPECTRUM ON"
            }
        }
    }
    private fun passcode(text: String): ByteArray {
        val sequence = intArrayOf(
            0x47, 0x5D, 0x4C, 0x42, 0x66, 0x20, 0x23, 0x46,
            0x4E, 0x57, 0x45, 0x3D, 0x67, 0x76, 0x60, 0x41,
            0x62, 0x39, 0x59, 0x2D, 0x68, 0x7E, 0x7C, 0x65,
            0x7D, 0x49, 0x29, 0x72, 0x73, 0x78, 0x21, 0x6E,
            0x5A, 0x5E, 0x4A, 0x3E, 0x71, 0x2C, 0x2A, 0x54,
            0x3C, 0x3A, 0x63, 0x4F, 0x43, 0x75, 0x27, 0x79,
            0x5B, 0x35, 0x70, 0x48, 0x6B, 0x56, 0x6F, 0x34,
            0x32, 0x6C, 0x30, 0x61, 0x6D, 0x7B, 0x2F, 0x4B,
            0x64, 0x38, 0x2B, 0x2E, 0x50, 0x40, 0x3F, 0x55,
            0x33, 0x37, 0x25, 0x77, 0x24, 0x26, 0x74, 0x6A,
            0x28, 0x53, 0x4D, 0x69, 0x22, 0x5C, 0x44, 0x31,
            0x36, 0x58, 0x3B, 0x7A, 0x51, 0x5F, 0x52
        )
        val result = ByteArray(16)
        val bytes =
            text.toByteArray(Charsets.ISO_8859_1)
        val count =
            minOf(bytes.size, 16)
        for (i in 0 until count) {
            var p =
                (bytes[i].toInt() and 0xFF) + i
            if (p > 126) {
                p = 32 + (p % 127)
            }
            if (p < 32 || p > 126) {
                throw IllegalArgumentException(
                    "Username/password contains an unsupported character."
                )
            }
            result[i] =
                sequence[p - 32].toByte()
        }
        return result
    }
    private fun writeLeShort(
        array: ByteArray,
        offset: Int,
        value: Int
    ) {
        array[offset] =
            (value and 0xFF).toByte()

        array[offset + 1] =
            ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeLeInt(
        array: ByteArray,
        offset: Int,
        value: Int
    ) {
        array[offset] =
            (value and 0xFF).toByte()

        array[offset + 1] =
            ((value ushr 8) and 0xFF).toByte()

        array[offset + 2] =
            ((value ushr 16) and 0xFF).toByte()

        array[offset + 3] =
            ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeBeInt(
        array: ByteArray,
        offset: Int,
        value: Int
    ) {
        array[offset] =
            ((value ushr 24) and 0xFF).toByte()
        array[offset + 1] =
            ((value ushr 16) and 0xFF).toByte()
        array[offset + 2] =
            ((value ushr 8) and 0xFF).toByte()
        array[offset + 3] =
            (value and 0xFF).toByte()
    }
    private fun readBeInt(
        array: ByteArray,
        offset: Int
    ): Int {
        return (
                ((array[offset].toInt() and 0xFF) shl 24) or
                        ((array[offset + 1].toInt() and 0xFF) shl 16) or
                        ((array[offset + 2].toInt() and 0xFF) shl 8) or
                        (array[offset + 3].toInt() and 0xFF)
                )
    }
    private fun readLeShort(
        array: ByteArray,
        offset: Int
    ): Int {
        return (
                (array[offset].toInt() and 0xFF) or
                        ((array[offset + 1].toInt() and 0xFF) shl 8)
                )
    }
    private fun intToHex(value: Int): String {
        return String.format(
            "%08X",
            value
        )
    }
    private fun hex(
        data: ByteArray
    ): String {
        val sb = StringBuilder()
        for (b in data) {
            sb.append(
                String.format(
                    "%02X ",
                    b.toInt() and 0xFF
                )
            )
        }
        return sb.toString().trim()
    }
    private fun hex(
        data: ByteArray,
        offset: Int,
        length: Int
    ): String {
        if (
            offset < 0 ||
            length < 0 ||
            offset + length > data.size
        ) {
            return ""
        }
        val sb = StringBuilder()
        for (
        i in offset until offset + length
        ) {
            sb.append(
                String.format(
                    "%02X ",
                    data[i].toInt() and 0xFF
                )
            )
        }
        return sb.toString().trim()
    }
    private fun readNullTerminatedAscii(
        data: ByteArray,
        offset: Int,
        maxLength: Int
    ): String {
        if (
            offset < 0 ||
            offset >= data.size
        ) {
            return ""
        }
        val end =
            minOf(
                data.size,
                offset + maxLength
            )
        val bytes = ArrayList<Byte>()
        for (i in offset until end) {
            if (data[i].toInt() == 0) {
                break
            }
            bytes.add(data[i])
        }
        return bytes.toByteArray()
            .toString(Charsets.US_ASCII)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun clearLog() {
        Log.d("IC705Remote", "UI log cleared")
    }

    private fun appendLog(
        message: String
    ) {
        Log.d("IC705Remote", message)
    }

    private class SpectrumWaterfallView(context: Context) : View(context) {
        companion object { private const val WATERFALL_ROWS = 100 }
        var onTuneFrequency: ((Long) -> Unit)? = null
        var onStepFrequency: ((Long) -> Unit)? = null
        private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var touchStartX = 0f
        private var touchStartY = 0f
        private var touchActive = false
        private var touchMoved = false
        private val spectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0,255,180); style = Paint.Style.STROKE; strokeWidth = 3f }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(55,70,75); style = Paint.Style.STROKE; strokeWidth = 1f }
        private val verticalGridPaint = Paint(gridPaint).apply { strokeWidth = 2f }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12.6f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL) }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(160,220,255); textSize = 24f; typeface = Typeface.DEFAULT_BOLD }
        private val tunedFrequencyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0,255,0); style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true }
        private var spectrum = IntArray(356)
        private val waterfall = java.util.ArrayDeque<IntArray>()
        @Volatile private var sensitivity = 1.0f
        @Volatile private var noiseFloor = 0
        @Volatile private var waterfallPalette = 0
        @Volatile private var displayBandwidthKHz = 75
        @Volatile var centerFrequencyHz = 0L
        @Volatile private var totalSpanFrequencyHz = 100_000L
        init { setBackgroundColor(Color.rgb(4,8,10)) }
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x; touchStartY = event.y
                    touchActive = true; touchMoved = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    touchActive = false // Multi-touch must not tune accidentally.
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.x - touchStartX) > touchSlop ||
                        kotlin.math.abs(event.y - touchStartY) > touchSlop) touchMoved = true
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (!touchActive) return true
                    touchActive = false
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY
                    if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        // One 1 kHz step per completed horizontal swipe.
                        onStepFrequency?.invoke(if (dx > 0f) 1000L else -1000L)
                        return true
                    }
                    if (touchMoved || kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) return true
                    val center = centerFrequencyHz
                    val widthPx = width.toFloat()
                    if (center > 0L && widthPx > 0f && totalSpanFrequencyHz > 0L) {
                        val visibleHz = minOf(displayBandwidthKHz.toLong() * 1000L, totalSpanFrequencyHz)
                        val offsetHz = ((event.x / widthPx).coerceIn(0f, 1f) - 0.5f) * visibleHz
                        val rawTarget = center + offsetHz.toLong()
                        val tunedTarget = (((rawTarget + 500L) / 1000L) * 1000L).coerceAtLeast(1_000L)
                        onTuneFrequency?.invoke(tunedTarget)
                        performClick()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    touchActive = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return true
        }
        fun clearScope() { synchronized(this) { spectrum=IntArray(356); waterfall.clear(); centerFrequencyHz=0L; totalSpanFrequencyHz=100_000L }; postInvalidate() }
        fun setSensitivity(gain: Float) { sensitivity=gain.coerceIn(0.25f,4f); postInvalidate() }
        fun setNoiseFloor(v:Int) { noiseFloor=v.coerceIn(0,60); postInvalidate() }
        fun setWaterfallPalette(index:Int) { waterfallPalette=index.coerceIn(0,4); postInvalidate() }
        fun setDisplayBandwidthKHz(v:Int) { displayBandwidthKHz=v.coerceIn(5,1000); postInvalidate() }
        fun updateSpectrum(centerHz:Long,totalSpanHz:Long,samples:IntArray) {
            centerFrequencyHz=centerHz
            totalSpanFrequencyHz=if(totalSpanHz>0) totalSpanHz else 100_000L
            synchronized(this) {
                spectrum=samples.copyOf()
                if (waterfall.size >= WATERFALL_ROWS) waterfall.removeLast()
                waterfall.addFirst(samples.copyOf())
            }
            postInvalidate()
        }
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas); val w=width.toFloat(); val h=height.toFloat(); if(w<=0f||h<=0f)return
            canvas.drawColor(Color.rgb(4,8,10)); val spectrumHeight=maxOf(140f,h*0.45f)
            canvas.drawText("${displayBandwidthKHz} kHz SPECTRUM / WATERFALL",12f,30f,titlePaint)
            for(i in 0..10){val x=w*i/10f;canvas.drawLine(x,40f,x,spectrumHeight,verticalGridPaint)}
            for(i in 0..4){val y=40f+(spectrumHeight-55f)*i/4f;canvas.drawLine(0f,y,w,y,gridPaint)}
            val samples=synchronized(this){spectrum.copyOf()}; if(samples.size>=2){
                val path=Path(); val denom=maxOf(1,160-noiseFloor)
                for(i in samples.indices){val x=i.toFloat()/(samples.size-1).toFloat()*(w-1f); val adjusted=maxOf(0,samples[i]-noiseFloor); val level=((adjusted.toFloat()/denom)*sensitivity).coerceIn(0f,1f); val y=46f+(spectrumHeight-70f)*(1f-level); if(i==0)path.moveTo(x,y) else path.lineTo(x,y)}
                canvas.drawPath(path,spectrumPaint)
            }
            // Bold green tuned-frequency marker.
            val tunedHz = centerFrequencyHz
            val spanHz = totalSpanFrequencyHz
            if (tunedHz > 0L && spanHz > 0L) {
                val markerX = w / 2f
                canvas.drawLine(markerX, 40f, markerX, h, tunedFrequencyPaint)
            }
            canvas.drawLine(0f,spectrumHeight,w,spectrumHeight,gridPaint)
            val rows=synchronized(this){waterfall.toList()}
            val rowHeight=maxOf(1f,(h-spectrumHeight-2f)/WATERFALL_ROWS.toFloat())
            for(r in rows.indices) drawWaterfallRow(canvas,rows[r],spectrumHeight+r*rowHeight,w,rowHeight)
            drawFrequencyLabels(canvas,w,spectrumHeight)
            if (centerFrequencyHz > 0L) canvas.drawLine(w / 2f, 40f, w / 2f, h, tunedFrequencyPaint)
        }
        private fun drawWaterfallRow(canvas:Canvas,row:IntArray,y:Float,width:Float,rowHeight:Float){
            if(row.isEmpty())return
            val denom=maxOf(1,160-noiseFloor)
            for(i in row.indices){
                val adjusted=maxOf(0,row[i]-noiseFloor)
                val level=((adjusted.toFloat()/denom)*sensitivity).coerceIn(0f,1f)
                val color=when(waterfallPalette){
                    1 -> Color.rgb((4+65*level).toInt(),(10+235*level).toInt(),(6+80*level).toInt())
                    2 -> Color.rgb((10+245*level).toInt(),(4+175*level).toInt(),(2+30*level).toInt())
                    3 -> Color.rgb((18+205*level).toInt(),(4+45*level).toInt(),(24+225*level).toInt())
                    4 -> Color.rgb((12+243*level).toInt(),(12+243*level).toInt(),(12+243*level).toInt())
                    else -> Color.rgb((1+20*level).toInt(),(7+48*level).toInt(),(30+160*level).toInt())
                }
                val paint=Paint().apply{this.color=color}
                val x0=i.toFloat()/row.size*width
                val x1=(i+1).toFloat()/row.size*width
                canvas.drawRect(x0,y,x1+1f,y+rowHeight+1f,paint)
            }
        }
        private fun drawFrequencyLabels(canvas: Canvas, width: Float, spectrumHeight: Float) {
            val center = centerFrequencyHz
            if (center <= 0L || width <= 0f) return
            val visibleHz = minOf(displayBandwidthKHz.toLong() * 1000L, totalSpanFrequencyHz)
            if (visibleHz <= 0L) return
            val leftHz = center - visibleHz / 2L
            val rightHz = leftHz + visibleHz
            var tickHz = ((leftHz + 9_999L) / 10_000L) * 10_000L
            var previousLabelRight = -1f
            while (tickHz <= rightHz) {
                val x = ((tickHz - leftHz).toDouble() / visibleHz * width).toFloat()
                canvas.drawLine(x, 40f, x, height.toFloat(), verticalGridPaint)
                canvas.drawLine(x, spectrumHeight - 8f, x, spectrumHeight, tunedFrequencyPaint)
                val label = String.format(java.util.Locale.US, "%.3f", tickHz / 1_000_000.0)
                val labelWidth = textPaint.measureText(label)
                val labelX = (x - labelWidth / 2f).coerceIn(0f, maxOf(0f, width - labelWidth))
                // All 10 kHz ticks remain; omit overlapping text on wide spans.
                if (labelX >= previousLabelRight + 6f || previousLabelRight < 0f) {
                    canvas.drawText(label, labelX, spectrumHeight - 12f, textPaint)
                    previousLabelRight = labelX + labelWidth
                }
                tickHz += 10_000L
            }
            if (visibleHz < 10_000L) {
                val label = formatMHz(center)
                canvas.drawText(label, maxOf(0f, (width - textPaint.measureText(label)) / 2f), spectrumHeight - 12f, textPaint)
            }
        }
        private fun formatMHz(hz:Long):String=String.format(java.util.Locale.US,"%.6f",hz/1_000_000.0)
    }


}

// Pure wire-format helpers, also exercised by local JVM tests.
internal object TxWire {
    fun levelBcd(value: Int): IntArray {
        val n = value.coerceIn(0, 255)
        return intArrayOf(n / 100, ((n % 100 / 10) shl 4) or (n % 10))
    }
    fun powerPercent(raw: Int): Int =
        (if (raw <= 143) raw * 50.0 / 143 else 50 + (raw - 143) * 50.0 / 70)
            .roundToInt().coerceIn(0, 100)
    fun audioPacket(pcm: ByteArray, offset: Int, length: Int, sequence: Int,
                    innerSequence: Int, localSid: Int, remoteSid: Int): ByteArray {
        require(length == 1364 || length == 556)
        require(offset >= 0 && offset + length <= pcm.size)
        val p = ByteArray(24 + length)
        for (i in 0..3) p[i] = (p.size ushr (8 * i)).toByte()
        p[6] = sequence.toByte(); p[7] = (sequence ushr 8).toByte()
        for (i in 0..3) {
            p[8 + i] = (localSid ushr (24 - 8 * i)).toByte()
            p[12 + i] = (remoteSid ushr (24 - 8 * i)).toByte()
        }
        p[16] = 0x80.toByte()
        p[18] = (innerSequence ushr 8).toByte(); p[19] = innerSequence.toByte()
        p[22] = (length ushr 8).toByte(); p[23] = length.toByte()
        System.arraycopy(pcm, offset, p, 24, length)
        return p
    }
}


// Match only a response for this session and this renewal, not an old startup ACK.
internal object AuthRenewalReply {
    fun matches(reply: ByteArray, request: ByteArray): Boolean {
        if (reply.size != 64 || request.size != 64) return false
        if ((reply[0].toInt() and 255) != 0x40 || (1..5).any { reply[it] != 0.toByte() }) return false
        if (reply[19] != 0x30.toByte() || reply[20] != 0x02.toByte() || reply[21] != 0x05.toByte()) return false
        if (reply[23] != request[23] || reply[24] != request[24]) return false
        // Radio reply reverses the client/radio session identifiers.
        for (i in 0..3) {
            if (reply[8 + i] != request[12 + i] || reply[12 + i] != request[8 + i]) return false
        }
        for (i in 26..31) if (reply[i] != request[i]) return false
        return true
    }
}
