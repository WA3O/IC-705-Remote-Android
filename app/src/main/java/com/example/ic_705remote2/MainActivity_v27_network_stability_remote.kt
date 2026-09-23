package com.example.ic_705remote2
import android.app.Activity
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
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
import kotlin.math.roundToInt
class MainActivity : Activity() {
    companion object {
        private const val CONTROL_PORT = 50001
        private const val SERIAL_PORT = 50002
        private const val AUDIO_PORT = 50003
        private const val DEFAULT_RADIO_IP = "192.168.4.98"
        private const val DEFAULT_USERNAME = "wa3o"
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
        private const val NETWORK_WATCHDOG_INTERVAL_MS = 1000L
        private const val NETWORK_WARN_AFTER_MS = 5000L
        private const val NETWORK_RESTART_AFTER_MS = 8000L
        private const val NETWORK_HISTORY_MAX_SAMPLES = 300
        private const val NETWORK_EVENT_MAX_ENTRIES = 200

        // v27.3: allow short UDP jitter/silence before local stream recovery.
        private const val AUDIO_RECOVERY_AFTER_MS = 2500L
        private const val AUDIO_RECOVERY_COOLDOWN_MS = 6000L
        private const val SCOPE_RECOVERY_AFTER_MS = 3500L
        private const val SCOPE_RECOVERY_COOLDOWN_MS = 7000L

        private const val PREFS_NAME = "ic705_remote_settings"
        private const val PREF_IP = "ic705_ip"
        private const val PREF_USERNAME = "ic705_username"
        private const val PREF_PASSWORD = "ic705_password"
    }
    private lateinit var settingsIpEdit: EditText
    private lateinit var settingsUsernameEdit: EditText
    private lateinit var settingsPasswordEdit: EditText
    private lateinit var mainPane: LinearLayout
    private lateinit var settingsPane: LinearLayout
    private lateinit var networkPane: LinearLayout
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
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
    private lateinit var noiseFloorLabel: TextView
    private lateinit var scopeInfoText: TextView
    private lateinit var scopeToggleButton: Button
    private lateinit var scopeView: SpectrumWaterfallView
    private lateinit var signalMeterLabel: TextView
    private lateinit var signalMeterBar: ProgressBar
    private lateinit var networkQualitySummary: TextView
    private lateinit var networkHistoryText: TextView
    private lateinit var networkQualityIndicator: TextView
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
    private var networkWatchdogScheduler: ScheduledExecutorService? = null
    private var audioReceiverThread: Thread? = null
    @Volatile private var lastControlPacketAt = 0L
    @Volatile private var lastSerialPacketAt = 0L
    @Volatile private var lastAudioPacketAt = 0L
    @Volatile private var lastAudioPcmAt = 0L
    @Volatile private var lastScopeFrameAt = 0L
    @Volatile private var networkWarningLogged = false

    @Volatile private var audioExpected = false
    @Volatile private var audioRecoveryInProgress = false
    @Volatile private var scopeRecoveryInProgress = false
    @Volatile private var lastAudioRecoveryAt = 0L
    @Volatile private var lastScopeRecoveryAt = 0L

    private data class NetworkQualitySample(
        val timestampMs: Long,
        val controlAgeMs: Long,
        val serialAgeMs: Long,
        val audioAgeMs: Long,
        val scopeAgeMs: Long,
        val quality: Int,
        val status: String
    )
    private val networkHistory = ArrayDeque<NetworkQualitySample>()
    private val networkEvents = ArrayDeque<String>()
    private val networkHistoryLock = Any()
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
    private var authInnerSequence: Int = 0
    private var pkt7Sequence: Int = 2
    private var pkt7InnerSequence: Int = 0x8304
    private val authId = ByteArray(6)
    private val a8ReplyId = ByteArray(16)
    private val secureRandom = SecureRandom()
    private val sendLock = Any()
    private val trackedPackets = ConcurrentHashMap<Int, ByteArray>()
    private val serialTrackedPackets = ConcurrentHashMap<Int, ByteArray>()
    @Volatile
    private var serialOpen = false
    @Volatile
    private var audioRunning = false
    private var audioTrack: AudioTrack? = null
    private var audioHaveSequence = false
    private var audioLastSequence = 0
    private var audioPacketCount = 0L
    private var audioDebugPacketCount = 0
    @Volatile
    private var frequencyReceived = false
    @Volatile
    private var frequencyWriteResponseReceived = false
    @Volatile
    private var frequencyWriteRejected = false
    private var frequencyHz: Long = 0L
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        buildUserInterface()
    }
    private fun buildUserInterface() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(8, 0, 8, 8)
        root.setBackgroundColor(Color.rgb(18, 18, 18))

        // Extra top space requested for the Samsung display.
        // This moves the complete application UI down without changing
        // the working controls or IC-705 protocol code.
        val topSpacer = View(this)
        // Compact title banner at the very top of the screen.
        val titleBanner = TextView(this)
        titleBanner.text = "IC-705 Remote Control  •  v27.3"
        titleBanner.textSize = 18f
        titleBanner.setTextColor(Color.WHITE)
        titleBanner.setBackgroundColor(Color.BLACK)
        titleBanner.gravity = Gravity.CENTER
        val titleBannerParams =
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(32)
            )
        // Put the entire application UI immediately below the title banner.
        // No large top spacer is used, so all controls move upward.
        titleBannerParams.topMargin = dp(36)
        root.addView(titleBanner, titleBannerParams)

        // Very small live network-quality indicator.
        networkQualityIndicator = TextView(this)
        networkQualityIndicator.text = "●"
        networkQualityIndicator.textSize = 11f
        networkQualityIndicator.gravity = Gravity.CENTER
        networkQualityIndicator.setTextColor(Color.RED)
        networkQualityIndicator.contentDescription = "Network quality"
        root.addView(
            networkQualityIndicator,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(14)
            )
        )

        // Keep only a small gap below the banner.
        root.addView(
            topSpacer,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8)
            )
        )

        // Fixed tab bar: it is outside the scrolling pages so it cannot
        // disappear above the top of a small Samsung display.
        val tabRow = LinearLayout(this)
        tabRow.orientation = LinearLayout.HORIZONTAL
        tabRow.gravity = Gravity.CENTER_VERTICAL
        tabRow.setBackgroundColor(Color.rgb(35, 35, 35))

        val mainTabButton = Button(this)
        mainTabButton.text = "MAIN"
        mainTabButton.textSize = 16f
        mainTabButton.minHeight = dp(56)

        val settingsTabButton = Button(this)
        settingsTabButton.text = "SETTINGS"
        settingsTabButton.textSize = 16f
        settingsTabButton.minHeight = dp(56)

        val networkTabButton = Button(this)
        networkTabButton.text = "NETWORK"
        networkTabButton.textSize = 16f
        networkTabButton.minHeight = dp(56)

        tabRow.addView(
            mainTabButton,
            android.widget.LinearLayout.LayoutParams(0, dp(56), 1f)
        )
        tabRow.addView(
            settingsTabButton,
            android.widget.LinearLayout.LayoutParams(0, dp(56), 1f)
        )
        tabRow.addView(
            networkTabButton,
            android.widget.LinearLayout.LayoutParams(0, dp(56), 1f)
        )

        mainPane = LinearLayout(this)
        mainPane.orientation = LinearLayout.VERTICAL
        // Extra top space for Samsung displays so Spectrum controls/sliders
        // are clearly visible below the phone's status area.
        mainPane.setPadding(4, 4, 4, 16)
        mainPane.setBackgroundColor(Color.rgb(18, 18, 18))

        settingsPane = LinearLayout(this)
        settingsPane.orientation = LinearLayout.VERTICAL
        settingsPane.setPadding(4, 4, 4, 16)

        networkPane = LinearLayout(this)
        networkPane.orientation = LinearLayout.VERTICAL
        networkPane.setPadding(4, 4, 4, 16)

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
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
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
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
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

        addLabel(settingsPane, "IC-705 IP Address")
        settingsIpEdit = addEditField(settingsPane, savedIp)
        settingsIpEdit.inputType = InputType.TYPE_CLASS_PHONE

        addLabel(settingsPane, "Network User Name")
        settingsUsernameEdit = addEditField(settingsPane, savedUsername)

        addLabel(settingsPane, "Network Password")
        settingsPasswordEdit =
            addEditField(
                settingsPane,
                savedPassword,
                password = true
            )

        val settingsNote = TextView(this)
        settingsNote.text =
            "Saved locally on this Android device. Enter once, then use CONNECT from MAIN."
        settingsNote.textSize = 13f
        settingsNote.setPadding(0, 8, 0, 8)
        settingsPane.addView(
            settingsNote,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val saveSettingsButton = Button(this)
        saveSettingsButton.text = "SAVE SETTINGS"
        settingsPane.addView(
            saveSettingsButton,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        saveSettingsButton.setOnClickListener {
            saveNetworkSettings()
            mainTabButton.performClick()
        }

        // ----------------------------
        // NETWORK QUALITY HISTORY TAB
        // ----------------------------
        val networkTitle = TextView(this)
        networkTitle.text = "NETWORK QUALITY HISTORY"
        networkTitle.textSize = 20f
        networkPane.addView(networkTitle)

        networkQualitySummary = TextView(this)
        networkQualitySummary.text = "Quality: waiting for connection"
        networkQualitySummary.textSize = 17f
        networkPane.addView(networkQualitySummary)

        networkHistoryText = TextView(this)
        networkHistoryText.text = "No network samples yet."
        networkHistoryText.textSize = 12f
        networkHistoryText.typeface = Typeface.MONOSPACE
        networkHistoryText.setTextIsSelectable(true)
        val networkHistoryScroll = ScrollView(this)
        networkHistoryScroll.addView(networkHistoryText)

        val networkButtonRow = LinearLayout(this)
        networkButtonRow.orientation = LinearLayout.HORIZONTAL

        val copyNetworkButton = Button(this)
        copyNetworkButton.text = "COPY REPORT"
        copyNetworkButton.setOnClickListener { copyNetworkDiagnosticReport() }
        networkButtonRow.addView(copyNetworkButton, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 2f))

        val clearNetworkButton = Button(this)
        clearNetworkButton.text = "CLEAR"
        clearNetworkButton.setOnClickListener { clearNetworkHistory() }
        networkButtonRow.addView(clearNetworkButton, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        networkPane.addView(networkButtonRow)
        networkPane.addView(networkHistoryScroll, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(500)))

        // ----------------------------
        // MAIN TAB - SPECTRUM FIRST
        // ----------------------------
        val scopeControlRow = LinearLayout(this)
        scopeControlRow.orientation = LinearLayout.HORIZONTAL
        scopeControlRow.gravity = Gravity.CENTER_VERTICAL

        scopeToggleButton = Button(this)
        scopeToggleButton.text = "SPECTRUM ON"
        scopeToggleButton.isEnabled = false

        scopeControlRow.addView(
            scopeToggleButton,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        mainPane.addView(
            scopeControlRow,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        scopeView = SpectrumWaterfallView(this)
        scopeView.minimumHeight = dp(300)
        // Keep the spectrum/waterfall at a real height.  The previous
        // weight-based height could collapse it inside the ScrollView and
        // make the controls below it appear to disappear.
        mainPane.addView(
            scopeView,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(300)
            )
        )
        scopeView.visibility = View.GONE

        // Spectrum / waterfall display-size control.  This was accidentally
        // omitted in the previous layout revision.
        val scopeSizeRow = LinearLayout(this)
        scopeSizeRow.orientation = LinearLayout.HORIZONTAL
        scopeSizeRow.gravity = Gravity.CENTER_VERTICAL

        val scopeSizeLabel = TextView(this)
        scopeSizeLabel.text = "Spectrum / Waterfall Size: 25 px"
        scopeSizeLabel.textSize = 14f
        scopeSizeRow.addView(
            scopeSizeLabel,
            android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        )

        val scopeSizeSeek = SeekBar(this)
        scopeSizeSeek.min = 0
        scopeSizeSeek.max = 600
        scopeSizeSeek.progress = 25
        scopeSizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newHeight = progress.coerceIn(0, 600)
                scopeSizeLabel.text = "Spectrum / Waterfall Size: ${newHeight} px"
                scopeView.minimumHeight = dp(newHeight)
                scopeView.layoutParams = scopeView.layoutParams.apply { this.height = dp(newHeight) }
                scopeView.requestLayout()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        scopeSizeRow.addView(
            scopeSizeSeek,
            android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 2.5f)
        )
        mainPane.addView(
            scopeSizeRow,
            android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        sensitivityRow = LinearLayout(this)
        sensitivityRow.visibility = View.GONE
        sensitivityRow.orientation = LinearLayout.HORIZONTAL
        sensitivityRow.gravity = Gravity.CENTER_VERTICAL

        sensitivityLabel = TextView(this)
        sensitivityLabel.text = "Sensitivity: 1.00x"
        sensitivityLabel.textSize = 15f
        sensitivityRow.addView(
            sensitivityLabel,
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        sensitivitySeekBar = SeekBar(this)
        sensitivitySeekBar.min = 25
        sensitivitySeekBar.max = 400
        sensitivitySeekBar.progress = 100
        sensitivityRow.addView(
            sensitivitySeekBar,
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                2.5f
            )
        )
        mainPane.addView(
            sensitivityRow,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val bandwidthRow = LinearLayout(this)
        bandwidthRow.orientation = LinearLayout.HORIZONTAL
        bandwidthRow.gravity = Gravity.CENTER_VERTICAL

        bandwidthLabel = TextView(this)
        bandwidthLabel.text = "Bandwidth: 75 kHz"
        bandwidthLabel.textSize = 14f
        bandwidthRow.addView(
            bandwidthLabel,
            android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        )

        val bandwidthSeekBar = SeekBar(this)
        bandwidthSeekBar.min = 5
        bandwidthSeekBar.max = 1000
        bandwidthSeekBar.progress = requestedDisplayBandwidthKHz
        bandwidthSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val khz = progress.coerceIn(5, 1000)
                requestedDisplayBandwidthKHz = khz
                bandwidthLabel.text = "Bandwidth: ${khz} kHz"
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
            android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 2.5f)
        )
        mainPane.addView(
            bandwidthRow,
            android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val noiseFloorRow = LinearLayout(this)
        noiseFloorRow.orientation = LinearLayout.HORIZONTAL
        noiseFloorRow.gravity = Gravity.CENTER_VERTICAL

        noiseFloorLabel = TextView(this)
        noiseFloorLabel.text = "Noise floor: 0"
        noiseFloorLabel.textSize = 14f
        noiseFloorRow.addView(
            noiseFloorLabel,
            android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        )

        val noiseFloorSeek = SeekBar(this)
        noiseFloorSeek.min = 0
        noiseFloorSeek.max = 100
        noiseFloorSeek.progress = 50
        noiseFloorSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val noiseFloor = progress - 50
                noiseFloorLabel.text =
                    if (noiseFloor > 0) "Noise floor: +$noiseFloor"
                    else "Noise floor: $noiseFloor"
                if (::scopeView.isInitialized) {
                    scopeView.setNoiseFloor(noiseFloor)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        noiseFloorRow.addView(
            noiseFloorSeek,
            android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 2.5f)
        )
        mainPane.addView(
            noiseFloorRow,
            android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        // ----------------------------
        // SIGNAL METER
        // ----------------------------
        val signalMeterRow = LinearLayout(this)
        signalMeterRow.orientation = LinearLayout.HORIZONTAL
        signalMeterRow.gravity = Gravity.CENTER_VERTICAL

        signalMeterLabel = TextView(this)
        signalMeterLabel.text = "Signal: S0 (0)"
        signalMeterLabel.textSize = 16f
        signalMeterRow.addView(
            signalMeterLabel,
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
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
            android.widget.LinearLayout.LayoutParams(
                0,
                28,
                2.8f
            )
        )

        mainPane.addView(
            signalMeterRow,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        frequencyLabelView = TextView(this)
        frequencyLabelView.text = "Frequency: waiting for IC-705"
        val frequencyLabel = frequencyLabelView
        frequencyLabel.textSize = 15f
        mainPane.addView(
            frequencyLabel,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val frequencyRow = LinearLayout(this)
        frequencyRow.orientation = LinearLayout.HORIZONTAL
        frequencyRow.gravity = Gravity.CENTER_VERTICAL

        frequencyEdit = EditText(this)
        frequencyEdit.setSingleLine(true)
        frequencyEdit.hint = "Waiting for IC-705 frequency..."
        frequencyEdit.inputType =
            InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
        frequencyRow.addView(
            frequencyEdit,
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                2f
            )
        )

        setFrequencyButton = Button(this)
        setFrequencyButton.text = "SET FREQUENCY"
        setFrequencyButton.isEnabled = false
        frequencyRow.addView(
            setFrequencyButton,
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1.25f
            )
        )
        mainPane.addView(
            frequencyRow,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
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
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        tuningRow.addView(
            up500Button,
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        tuningRow.addView(
            down1kButton,
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        tuningRow.addView(
            up1kButton,
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        mainPane.addView(
            tuningRow,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
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
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        modeRow.addView(
            lsbButton,
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        mainPane.addView(
            modeRow,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
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

        disconnectButton = Button(this)
        disconnectButton.text = "DISCONNECT"
        disconnectButton.isEnabled = false

        connectionRow.addView(
            connectButton,
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        connectionRow.addView(
            disconnectButton,
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        // Scrollable page areas. The MAIN / SETTINGS tab bar stays fixed.
        val mainScroll = ScrollView(this)
        mainScroll.isFillViewport = true
        mainScroll.addView(
            mainPane,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val networkScroll = ScrollView(this)
        networkScroll.isFillViewport = true
        networkScroll.visibility = View.GONE
        networkScroll.addView(networkPane)

        val settingsScroll = ScrollView(this)
        settingsScroll.isFillViewport = true
        settingsScroll.visibility = View.GONE
        settingsScroll.addView(
            settingsPane,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            mainScroll,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // Keep CONNECT / DISCONNECT and the MAIN / SETTINGS tabs together.
        // They stay directly below the main scrolling content instead of
        // being forced all the way to the bottom of the Samsung display.
        root.addView(
            connectionRow,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            tabRow,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        )

        root.addView(
            settingsScroll,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(
            networkScroll,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        mainTabButton.setOnClickListener {
            mainScroll.visibility = View.VISIBLE
            settingsScroll.visibility = View.GONE
            networkScroll.visibility = View.GONE
            connectionRow.visibility = View.VISIBLE
            mainTabButton.alpha = 1.0f
            settingsTabButton.alpha = 0.65f
            networkTabButton.alpha = 0.65f
        }

        settingsTabButton.setOnClickListener {
            mainScroll.visibility = View.GONE
            settingsScroll.visibility = View.VISIBLE
            networkScroll.visibility = View.GONE
            connectionRow.visibility = View.GONE
            mainTabButton.alpha = 0.65f
            settingsTabButton.alpha = 1.0f
            networkTabButton.alpha = 0.65f
        }

        networkTabButton.setOnClickListener {
            mainScroll.visibility = View.GONE
            settingsScroll.visibility = View.GONE
            networkScroll.visibility = View.VISIBLE
            connectionRow.visibility = View.GONE
            mainTabButton.alpha = 0.65f
            settingsTabButton.alpha = 0.65f
            networkTabButton.alpha = 1.0f
            refreshNetworkHistoryUi()
        }

        connectButton.setOnClickListener {
            startSession()
        }
        disconnectButton.setOnClickListener {
            stopSession()
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
            if (running && serialOpen) {
                shiftFrequencyBy(-500L, "500 Hz")
            } else {
                appendLog("ERROR: Connect to the IC-705 before tuning.")
            }
        }
        up500Button.setOnClickListener {
            if (running && serialOpen) {
                shiftFrequencyBy(500L, "500 Hz")
            } else {
                appendLog("ERROR: Connect to the IC-705 before tuning.")
            }
        }
        down1kButton.setOnClickListener {
            if (running && serialOpen) {
                shiftFrequencyBy(-1000L, "1 kHz")
            } else {
                appendLog("ERROR: Connect to the IC-705 before tuning.")
            }
        }
        up1kButton.setOnClickListener {
            if (running && serialOpen) {
                shiftFrequencyBy(1000L, "1 kHz")
            } else {
                appendLog("ERROR: Connect to the IC-705 before tuning.")
            }
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

        // Dark theme text: keep all button, label, and settings text
        // readable against the dark background.
        applyDarkTextColors(root)
        updateConnectionButtonColors(false)
        updateNetworkQualityIndicator(0, "BAD")

        mainTabButton.performClick()
    }

    private fun applyDarkTextColors(view: View) {
        when (view) {
            is Button -> {
                view.setTextColor(Color.WHITE)
                view.backgroundTintList = ColorStateList.valueOf(Color.BLACK)
            }
            is EditText -> {
                view.setTextColor(Color.WHITE)
                view.setHintTextColor(Color.LTGRAY)
            }
            is TextView -> {
                view.setTextColor(Color.WHITE)
            }
        }

        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyDarkTextColors(view.getChildAt(i))            }
        }
    }

    private fun updateConnectionButtonColors(isConnected: Boolean) {
        if (!::connectButton.isInitialized || !::disconnectButton.isInitialized) return

        if (isConnected) {
            // Connected: CONNECT becomes green and DISCONNECT becomes red.
            connectButton.backgroundTintList =
                ColorStateList.valueOf(Color.rgb(0, 150, 0))
            disconnectButton.backgroundTintList =
                ColorStateList.valueOf(Color.rgb(190, 0, 0))
        } else {
            // Disconnected: CONNECT becomes red and DISCONNECT returns to black.
            connectButton.backgroundTintList =
                ColorStateList.valueOf(Color.rgb(190, 0, 0))
            disconnectButton.backgroundTintList =
                ColorStateList.valueOf(Color.BLACK)
        }

        connectButton.setTextColor(Color.WHITE)
        disconnectButton.setTextColor(Color.WHITE)
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
                // Update the app's working frequency immediately when a band
                // button is pressed. This lets the 500 Hz / 1 kHz tuning
                // buttons use the new band immediately, without requiring
                // another manual SET FREQUENCY press.
                frequencyHz = hz

                frequencyEdit.setText(hz.toString())

                if (::frequencyLabelView.isInitialized) {
                    frequencyLabelView.text =
                        "Frequency: ${formatFrequency(hz)}"
                }

                if (::scopeView.isInitialized) {
                    scopeView.centerFrequencyHz = hz
                }

                setFrequencyFromUi()
            }

            row.addView(
                button,
                android.widget.LinearLayout.LayoutParams(
                    0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
        }

        parent.addView(
            row,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
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
            .putString(PREF_USERNAME, username)
            .putString(PREF_PASSWORD, password)
            .apply()
    }

    private fun startSession() {
        if (running) {
            appendLog("Session already running.")
            return
        }
        val radioIp =
            settingsIpEdit.text.toString().trim()

        val username =
            settingsUsernameEdit.text.toString().trim()

        val password =
            settingsPasswordEdit.text.toString()

        saveNetworkSettings()
        if (radioIp.isEmpty()) {
            appendLog("ERROR: Enter the IC-705 IP address.")
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
        clearLog()
        running = true
        connected = false
        clearNetworkHistory()
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
        audioHaveSequence = false
        audioLastSequence = 0
        audioPacketCount = 0L
        audioDebugPacketCount = 0
        frequencyReceived = false
        frequencyWriteResponseReceived = false
        frequencyWriteRejected = false
        frequencyHz = 0L
        signalMeterValue = 0
        scopeTransportPackets = 0L
        scopeCivFrames = 0L
        scopeRaw27Frames = 0L
        scopeDecodedFrames = 0L
        scopePollCount = 0L
        scopeAwaitingResponse = false
        scopeLastUdpBytes = 0
        scopeLastCivBytes = 0
        scopeAwaitingResponse = false
        stopScopePolling()
        resetScopeAssembly()
        if (::scopeView.isInitialized) {
            scopeView.clearScope()
            scopeView.visibility = View.GONE
        }
        if (::sensitivityRow.isInitialized) {
            sensitivityRow.visibility = View.GONE
        }
        activeModeCode = -1
        modeReceived = false
        trackedPackets.clear()
        serialTrackedPackets.clear()
        connectButton.isEnabled = false
        disconnectButton.isEnabled = true
        setFrequencyButton.isEnabled = false
        if (::scopeToggleButton.isInitialized) {
            scopeToggleButton.isEnabled = false
            scopeToggleButton.text = "SPECTRUM ON"
            if (::scopeView.isInitialized) {
                scopeView.visibility = View.GONE
            }
            if (::sensitivityRow.isInitialized) {
                sensitivityRow.visibility = View.GONE
            }
        }
        enableBandButtons(false)
        sessionThread = Thread {
            try {
                runSession(radioIp, username, password)
            } catch (e: Exception) {
                appendLog("SESSION ERROR: ${e.message}")
                try {
                    stopSession()
                } catch (disconnectError: Exception) {
                    appendLog("DISCONNECT ERROR: ${disconnectError.message}")
                    cleanupSocketOnly()
                }
            } finally {
                if (!connected && running) {
                    cleanupSocketOnly()
                }
            }
        }
        sessionThread?.start()
    }
    private fun runSession(
        radioIp: String,
        username: String,
        password: String
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
        appendLog("STEP 8: READ FREQUENCY")
        sendCivReadFrequency()
        val frequencyDeadline =
            System.currentTimeMillis() + 5000L
        while (
            running &&
            !frequencyReceived &&
            System.currentTimeMillis() < frequencyDeadline
        ) {
            Thread.sleep(50)
        }
        if (!frequencyReceived) {
            throw Exception("CI-V frequency response was not received.")
        }
        connected = true
        startNetworkWatchdog()
        runOnUiThread {
            setFrequencyButton.isEnabled = true
            down500Button.isEnabled = true
            up500Button.isEnabled = true
            down1kButton.isEnabled = true
            up1kButton.isEnabled = true
            usbButton.isEnabled = true
            lsbButton.isEnabled = true
            enableBandButtons(true)
            scopeToggleButton.isEnabled = true
            scopeToggleButton.text = "SPECTRUM ON"
            scopeView.visibility = View.GONE
            sensitivityRow.visibility = View.GONE
            signalMeterLabel.text = "Signal: S0 (0)"
            signalMeterBar.progress = 0
            signalMeterBar.progressTintList = ColorStateList.valueOf(Color.rgb(0, 190, 0))
            updateConnectionButtonColors(true)
        }
        startSignalMeterPolling()
        appendLog("")
        appendLog("================================")
        appendLog("CI-V FREQUENCY READ SUCCESS")
        appendLog("================================")
        appendLog("Frequency: ${formatFrequency(frequencyHz)}")

        runOnUiThread {
            frequencyEdit.setText(
                frequencyHz.toString()
            )
            frequencyLabelView.text = "Frequency: ${formatFrequency(frequencyHz)}"
        }

        appendLog("")
        appendLog("Control + serial/CI-V session is established.")
        appendLog("Initial IC-705 frequency loaded into SET FREQUENCY field.")

        // Restore the previously verified IC-705 RX audio path.
        // Audio is independent UDP 50003 and is started once after the
        // initial frequency read. Repeated band changes reuse this stream.
        audioExpected = true
        lastAudioPcmAt = 0L
        try {
            openAudioReceive(radioIp)
        } catch (e: Exception) {
            appendLog("AUDIO START FAILED: ${e.message}")
        }

        // Start the IC-705 spectrum automatically after the control,
        // serial/CI-V, and audio paths are established.
        //
        // startSpectrumScope() sends the verified six-command SPECTRUM ON
        // sequence:
        //   27 10 01
        //   27 11 01
        //   27 12 00
        //   27 13 00
        //   27 14 00 00
        //   27 15 00 <half-span>
        //
        // No 27 00 polling is used. The IC-705 sends 27 00 scope frames
        // automatically after the scope is enabled.
        try {
            startSpectrumScope()
        } catch (e: Exception) {
            appendLog("SPECTRUM AUTO-START FAILED: ${e.message}")
            updateScopeInfo(
                "Spectrum: automatic start failed — press SPECTRUM ON"
            )
        }

        while (running) {
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

        // Fast tuning: do not wait for a CI-V readback or ACK before updating
        // the Android UI. The serial receiver continues to process the radio
        // response asynchronously. This makes the 500 Hz / 1 kHz buttons feel
        // like a real tuning control instead of a request/verify operation.
        frequencyHz = newHz

        runOnUiThread {
            frequencyEdit.setText(newHz.toString())
            frequencyLabelView.text = "Frequency: ${formatFrequency(newHz)}"
            if (::scopeView.isInitialized) {
                scopeView.centerFrequencyHz = newHz
            }
            setFrequencyButton.isEnabled = false
            down500Button.isEnabled = false
            up500Button.isEnabled = false
            down1kButton.isEnabled = false
            up1kButton.isEnabled = false
        }

        appendLog("$stepLabel TUNE: ${formatFrequency(currentHz)} -> ${formatFrequency(newHz)}")

        Thread {
            try {
                frequencyWriteResponseReceived = false
                frequencyWriteRejected = false
                sendCivSetFrequency(newHz)
                appendLog("$stepLabel TUNE SENT: ${formatFrequency(newHz)}")

                // Only a short UI lockout prevents accidental double taps.
                // Do not wait seconds for radio verification.
                Thread.sleep(100)
            } catch (e: Exception) {
                if (running) {
                    appendLog("$stepLabel TUNE ERROR: ${e.message}")
                }
            } finally {
                if (running) {
                    runOnUiThread {
                        setFrequencyButton.isEnabled = true
                        down500Button.isEnabled = true
                        up500Button.isEnabled = true
                        down1kButton.isEnabled = true
                        up1kButton.isEnabled = true
                    }
                }
            }
        }.start()
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

        runOnUiThread { setFrequencyButton.isEnabled = false }
        frequencyWriteResponseReceived = false
        frequencyWriteRejected = false

        Thread {
            try {
                sendCivSetFrequency(hz)
                val deadline = System.currentTimeMillis() + FREQUENCY_TIMEOUT_MS
                while (running && !frequencyWriteResponseReceived && !frequencyWriteRejected && System.currentTimeMillis() < deadline) Thread.sleep(50)
                if (!running) return@Thread
                if (frequencyWriteRejected) {
                    appendLog("FREQUENCY WRITE REJECTED: IC-705 returned CI-V NAK. Connection left open.")
                    return@Thread
                }
                if (!frequencyWriteResponseReceived) {
                    appendLog("FREQUENCY WRITE: ACK delayed/not seen. Connection left open.")
                    return@Thread
                }
                frequencyHz = hz
                runOnUiThread {
                    frequencyEdit.setText(hz.toString())
                    if (::scopeView.isInitialized) scopeView.centerFrequencyHz = hz
                }
                appendLog("FREQUENCY WRITE VERIFIED BY CI-V ACK: ${formatFrequency(hz)}")
            } catch (e: Exception) {
                if (running) appendLog("FREQUENCY WRITE ERROR: ${e.message}")
            } finally {
                if (running) runOnUiThread { setFrequencyButton.isEnabled = true }
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
        radioIp: String
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

        Thread.sleep(50)

        recordAudioPacket(
            "TX",
            "PKT3 #2",
            audioPkt3
        )
        sendAudioRaw(audioPkt3)

        val pkt4 =
            receiveAudioExpected(
                expectedLength = 16,
                timeoutMs = 3000
            ) {
                it.size >= 16 &&
                        (it[0].toInt() and 0xFF) == 0x10 &&
                        (it[4].toInt() and 0xFF) == 0x04 &&                        (it[5].toInt() and 0xFF) == 0x00
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

        Thread.sleep(50)

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
            waitForAudioPkt6Response(5000)

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
                lastAudioPacketAt = System.currentTimeMillis()
                networkWarningLogged = false

                val data =
                    packet.data.copyOf(
                        packet.length
                    )

                processAudioPacket(data)

            } catch (
                _: java.net.SocketTimeoutException
            ) {
            } catch (e: Exception) {
                if (running && audioRunning) {
                    appendLog("AUDIO RECEIVE ERROR: ${e.message}")
                    if (!socket.isClosed) {
                        try { Thread.sleep(50) } catch (_: InterruptedException) {}
                        continue
                    }
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

        // Count only real PCM audio as healthy audio. PKT7 keepalives
        // do not reset the audio-quality timer.
        lastAudioPcmAt = System.currentTimeMillis()

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
            if (running && audioRunning) {
                appendLog(
                    "AUDIO PLAYBACK ERROR: ${e.message}"
                )
                addNetworkEvent(
                    "AUDIO PLAYBACK ERROR: ${e.message}"
                )
                // Keep control/CI-V alive. The watchdog performs local
                // audio-stream recovery instead of tearing down the session.
                audioRunning = false
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

            socket.send(packet)
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
                ColorStateList.valueOf(Color.BLACK)

            val inactiveTint =
                ColorStateList.valueOf(Color.BLACK)

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
                lastSerialPacketAt = System.currentTimeMillis()
                networkWarningLogged = false
                val data =
                    packet.data.copyOf(packet.length)
                processSerialIncomingPacket(data)
            } catch (e: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                if (running) {
                    appendLog("SERIAL RECEIVE ERROR: ${e.message}")
                    if (!socket.isClosed) {
                        try { Thread.sleep(50) } catch (_: InterruptedException) {}
                        continue
                    }
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
            scopeTransportPackets++
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
            scopeLastCivBytes = chunk.size

            // IC-705 WLAN scope: the proven diagnostic program receives a
            // complete 27 00 ... FD scope record inside each C1 packet.
            // The complete raw scope record is exactly 493 bytes:
            //   27 00 + 490-byte payload + FD
            // Do not use the surrounding FE FE E0 A4 bytes for the length.
            var marker = -1
            for (i in 0 until chunk.size - 1) {
                if ((chunk[i].toInt() and 0xFF) == 0x27 &&
                    (chunk[i + 1].toInt() and 0xFF) == 0x00) {
                    marker = i
                    break
                }
            }

            if (marker >= 0) {
                val expectedEnd = marker + 493

                if (expectedEnd <= chunk.size &&
                    (chunk[expectedEnd - 1].toInt() and 0xFF) == 0xFD) {

                    val rawScope = chunk.copyOfRange(marker, expectedEnd)

                    scopeRaw27Frames++
                    scopeCivFrames++
                    processSpectrumScopeFrame(
                        rawScope.copyOfRange(2, rawScope.size - 1)
                    )
                    return
                }

                // Fallback for a fragmented/variable transport packet.
                // This is not used by the known-good IC-705 LAN capture,
                // but preserves the existing buffering path.
                var fd = -1
                for (i in marker + 2 until chunk.size) {
                    if ((chunk[i].toInt() and 0xFF) == 0xFD) {
                        fd = i
                        break
                    }
                }
                if (fd >= 0) {
                    val rawScope = chunk.copyOfRange(marker, fd + 1)
                    if (rawScope.size == 493) {
                        scopeRaw27Frames++
                        scopeCivFrames++
                        processSpectrumScopeFrame(
                            rawScope.copyOfRange(2, rawScope.size - 1)
                        )
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
        scopePollCount = 0L
        scopeAwaitingResponse = false
        scopeLastUdpBytes = 0
        scopeLastCivBytes = 0

        // Mark the scope active before sending the enable commands.
        // The IC-705 can begin transmitting the first 27 00 frame immediately
        // after the first enable command, so the receive path must already
        // consider the scope active.
        scopeStarted = true

        runOnUiThread {
            scopeView.visibility = View.VISIBLE
            sensitivityRow.visibility = View.VISIBLE
            scopeToggleButton.text = "SPECTRUM OFF"
        }

        updateScopeInfo("Spectrum: enabling IC-705 scope output...")

        try {
            val commands = arrayOf(
                byteArrayOf(0x27, 0x10, 0x01),
                byteArrayOf(0x27, 0x11, 0x01),
                byteArrayOf(0x27, 0x12, 0x00),
                byteArrayOf(0x27, 0x13, 0x00),
                byteArrayOf(0x27, 0x14, 0x00, 0x00),
                buildScopeSpanCommand(100_000L)
            )
            for (cmd in commands) {
                sendScopeCommand(cmd)
                Thread.sleep(80)
            }
            // No repeated 27 00 polling. The radio is already streaming raw 27 00 frames.
        } catch (e: Exception) {
            appendLog("SCOPE ENABLE SEND ERROR: ${e.message}")
            updateScopeInfo("Spectrum: enable command failed")
            return
        }

        updateScopeInfo(
            "Spectrum: ON — receiving IC-705 27 00 scope frames"
        )
    }

    private fun startSignalMeterPolling() {
        signalMeterScheduler?.shutdownNow()
        signalMeterScheduler =
            Executors.newSingleThreadScheduledExecutor()

        signalMeterScheduler?.scheduleAtFixedRate(
            {
                if (running && serialOpen) {
                    try {
                        sendCivReadSignalMeter()
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
        civ: ByteArray
    ) {
        if (!running || !serialOpen) {
            return
        }

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

        // Conventional full CI-V frame for ordinary CAT traffic.
        val startsFullCivFrame =
            chunk.size >= 6 &&
                    (chunk[0].toInt() and 0xFF) == 0xFE &&
                    (chunk[1].toInt() and 0xFF) == 0xFE

        synchronized(scopeCivReceiveBuffer) {
            if (startsFullCivFrame) {
                scopeCivReceiveBuffer.reset()
            }

            scopeCivReceiveBuffer.write(
                chunk,
                0,
                chunk.size
            )

            val bytes =
                scopeCivReceiveBuffer.toByteArray()

            val endIndex =
                bytes.indexOfLast {
                    (it.toInt() and 0xFF) == 0xFD
                }

            if (endIndex < 0) {
                return
            }

            val frame =
                bytes.copyOfRange(
                    0,
                    endIndex + 1
                )

            scopeCivReceiveBuffer.reset()

            if (
                frame.size >= 7 &&
                (frame[0].toInt() and 0xFF) == 0xFE &&
                (frame[1].toInt() and 0xFF) == 0xFE
            ) {
                processCivFrame(frame)
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
        lastScopeFrameAt = System.currentTimeMillis()
        scopeDecodedFrames++
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
        val clamped = value.coerceIn(0, 255)
        val meterText =
            if (clamped <= 120) {
                val sLevel = kotlin.math.min(9, (clamped * 9 + 60) / 120)
                "Signal: S$sLevel ($clamped)"
            } else {
                "Signal: S9 (%d)".format(clamped)
            }


        runOnUiThread {
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

        appendLog("CI-V FRAME RX: ${hex(civ)}")

        // Raw IC-705 WLAN scope form: 27 00 ... FD. The user's working
        // capture proves this exact form is what arrives inside C1.
        if (civ.size >= 493 &&
            (civ[0].toInt() and 0xFF) == 0x27 &&
            (civ[1].toInt() and 0xFF) == 0x00 &&
            (civ[civ.size - 1].toInt() and 0xFF) == 0xFD) {
            scopeCivFrames++
            scopeRaw27Frames++
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
                runOnUiThread {
                    scopeView.centerFrequencyHz = hz
                }
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
            (civ[4].toInt() and 0xFF) == 0x15 &&            (civ[5].toInt() and 0xFF) == 0x02 &&
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
                runOnUiThread {
                    scopeView.centerFrequencyHz = hz
                }
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
            socket.send(packet)
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
                lastControlPacketAt = System.currentTimeMillis()
                networkWarningLogged = false
                val data = packet.data.copyOf(packet.length)
                processIncomingPacket(data)
            } catch (e: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                if (running) {
                    appendLog("RECEIVE ERROR: ${e.message}")
                    if (!socket.isClosed) {
                        try { Thread.sleep(50) } catch (_: InterruptedException) {}
                        continue
                    }
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
                authOk = true
                appendLog("AUTH #2 SUCCESS")
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
            socket.send(packet)
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
    private fun startNetworkWatchdog() {
        stopNetworkWatchdog()
        val now = System.currentTimeMillis()
        lastControlPacketAt = now
        lastSerialPacketAt = now
        lastAudioPacketAt = now
        networkWarningLogged = false

        networkWatchdogScheduler =
            Executors.newSingleThreadScheduledExecutor()

        networkWatchdogScheduler?.scheduleAtFixedRate(
            {
                if (!running || !connected) return@scheduleAtFixedRate

                val nowMs = System.currentTimeMillis()
                val controlAge = nowMs - lastControlPacketAt
                val serialAge = nowMs - lastSerialPacketAt
                val audioPcmAge =
                    if (lastAudioPcmAt > 0L) nowMs - lastAudioPcmAt
                    else Long.MAX_VALUE
                val scopeAge =
                    if (lastScopeFrameAt > 0L) nowMs - lastScopeFrameAt
                    else Long.MAX_VALUE

                recordNetworkQualitySample()

                if (
                    audioExpected &&
                    audioPcmAge >= AUDIO_RECOVERY_AFTER_MS &&
                    nowMs - lastAudioRecoveryAt >= AUDIO_RECOVERY_COOLDOWN_MS &&
                    !audioRecoveryInProgress
                ) {
                    appendLog(
                        "AUDIO WATCHDOG: no PCM for ${audioPcmAge}ms — local audio recovery"
                    )
                    addNetworkEvent(
                        "AUDIO RECOVERY: silence ${audioPcmAge}ms"
                    )
                    lastAudioRecoveryAt = nowMs
                    recoverAudioStream()
                }

                if (
                    scopeStarted &&
                    scopeAge >= SCOPE_RECOVERY_AFTER_MS &&
                    nowMs - lastScopeRecoveryAt >= SCOPE_RECOVERY_COOLDOWN_MS &&
                    !scopeRecoveryInProgress
                ) {
                    appendLog(
                        "SCOPE WATCHDOG: no decoded 27 00 frame for ${scopeAge}ms — local spectrum recovery"
                    )
                    addNetworkEvent(
                        "SCOPE RECOVERY: silence ${scopeAge}ms"
                    )
                    lastScopeRecoveryAt = nowMs
                    recoverSpectrumStream()
                }

                if (
                    controlAge >= NETWORK_WARN_AFTER_MS ||
                    (serialOpen && serialAge >= NETWORK_WARN_AFTER_MS) ||
                    (audioExpected && audioPcmAge >= NETWORK_WARN_AFTER_MS) ||
                    (scopeStarted && scopeAge >= NETWORK_WARN_AFTER_MS)
                ) {
                    if (!networkWarningLogged) {
                        appendLog(
                            "NETWORK WARNING: control=${controlAge}ms " +
                                    "serial=${serialAge}ms " +
                                    "audioPCM=${networkAge(audioPcmAge)} " +
                                    "scope=${networkAge(scopeAge)}"
                        )
                        addNetworkEvent(
                            "WARNING: C=${controlAge} S=${serialAge} " +
                                    "AUDIO=${networkAge(audioPcmAge)} " +
                                    "SCOPE=${networkAge(scopeAge)}"
                        )
                        networkWarningLogged = true
                    }
                }

                if (
                    controlAge >= NETWORK_RESTART_AFTER_MS &&
                    receiverThread?.isAlive != true
                ) {
                    appendLog("NETWORK RECOVERY: restarting control receiver")
                    addNetworkEvent("RECOVERY: control receiver")
                    startReceiverThread()
                }

                if (
                    serialOpen &&
                    serialAge >= NETWORK_RESTART_AFTER_MS &&
                    serialReceiverThread?.isAlive != true
                ) {
                    appendLog("NETWORK RECOVERY: restarting CI-V receiver")
                    addNetworkEvent("RECOVERY: CI-V receiver")
                    startSerialReceiverThread()
                }

                if (
                    controlAge < NETWORK_WARN_AFTER_MS &&
                    (!serialOpen || serialAge < NETWORK_WARN_AFTER_MS) &&
                    (!audioExpected || audioPcmAge < NETWORK_WARN_AFTER_MS) &&
                    (!scopeStarted || scopeAge < NETWORK_WARN_AFTER_MS)
                ) {
                    networkWarningLogged = false
                }
            },
            NETWORK_WATCHDOG_INTERVAL_MS,
            NETWORK_WATCHDOG_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun recordNetworkQualitySample() {
        val now = System.currentTimeMillis()

        val controlAge =
            if (lastControlPacketAt > 0L) now - lastControlPacketAt
            else Long.MAX_VALUE

        val serialAge =
            if (lastSerialPacketAt > 0L) now - lastSerialPacketAt
            else Long.MAX_VALUE

        val audioAge =
            if (lastAudioPcmAt > 0L) now - lastAudioPcmAt
            else Long.MAX_VALUE

        val scopeAge =
            if (lastScopeFrameAt > 0L) now - lastScopeFrameAt
            else Long.MAX_VALUE

        fun score(age: Long, goodMs: Long, fairMs: Long, badMs: Long): Int {
            return when {
                age <= goodMs -> 100
                age <= fairMs -> 70
                age <= badMs -> 35
                else -> 0
            }
        }

        val controlScore =
            if (!connected) 0
            else score(controlAge, 1000L, 3000L, 8000L)

        val serialScore =
            if (!serialOpen) 100
            else score(serialAge, 1000L, 3000L, 8000L)

        val audioScore =
            if (!audioExpected) 100
            else if (audioRunning) {
                score(audioAge, 1000L, 2500L, 6000L)
            } else {
                20
            }

        val scopeScore =
            if (!scopeStarted) 100
            else score(scopeAge, 1500L, 3500L, 7000L)

        val q =
            (
                controlScore * 20 +
                        serialScore * 20 +
                        audioScore * 35 +
                        scopeScore * 25
                ) / 100

        val status = when {
            !connected -> "DISCONNECTED"
            q >= 85 -> "GOOD"
            q >= 60 -> "FAIR"
            else -> "BAD"
        }

        synchronized(networkHistoryLock) {
            if (networkHistory.size >= NETWORK_HISTORY_MAX_SAMPLES) {
                networkHistory.removeFirst()
            }
            networkHistory.addLast(
                NetworkQualitySample(
                    now,
                    controlAge,
                    serialAge,
                    audioAge,
                    scopeAge,
                    q.coerceIn(0, 100),
                    status
                )
            )
        }

        if (::networkQualitySummary.isInitialized) {
            runOnUiThread {
                networkQualitySummary.text =
                    "Quality: ${q}% $status | " +
                            "C=${networkAge(controlAge)} " +
                            "S=${networkAge(serialAge)} " +
                            "A=${networkAge(audioAge)} " +
                            "W=${networkAge(scopeAge)}"

                updateNetworkQualityIndicator(q, status)
                refreshNetworkHistoryUi()
            }
        }
    }

    private fun addNetworkEvent(message: String) {
        val line = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()) + "  " + message
        synchronized(networkHistoryLock) {
            if (networkEvents.size >= NETWORK_EVENT_MAX_ENTRIES) networkEvents.removeFirst()
            networkEvents.addLast(line)
        }
    }

    private fun refreshNetworkHistoryUi() {
        if (!::networkHistoryText.isInitialized) return
        val samples = synchronized(networkHistoryLock) { networkHistory.toList() }
        val events = synchronized(networkHistoryLock) { networkEvents.toList() }
        val out = StringBuilder()
        out.append("HISTORIC QUALITY BAR (newest at right)\n")
        out.append("100 | ")
        samples.takeLast(100).forEach { x ->
            out.append(if (x.quality >= 90) "█" else if (x.quality >= 70) "▓" else if (x.quality >= 40) "▒" else "░")
        }
        out.append("\n  0 | ")
        samples.takeLast(100).forEach { x ->
            out.append(if (x.quality >= 50) " " else "█")
        }
        out.append("\n\nSAMPLES - newest last\n")
        samples.takeLast(120).forEach { x ->
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(x.timestampMs))
            out.append(t).append(" Q=").append(x.quality).append("% ").append(x.status)
                .append(" C=").append(networkAge(x.controlAgeMs))
                .append(" S=").append(networkAge(x.serialAgeMs))
                .append(" A=").append(networkAge(x.audioAgeMs))
                .append(" W=").append(networkAge(x.scopeAgeMs)).append("\n")
        }
        if (events.isNotEmpty()) {
            out.append("\nNETWORK EVENTS\n")
            events.forEach { out.append(it).append("\n") }
        }
        networkHistoryText.text = out.toString()
    }

    private fun clearNetworkHistory() {
        synchronized(networkHistoryLock) {
            networkHistory.clear()
            networkEvents.clear()
        }
        if (::networkQualitySummary.isInitialized) {
            runOnUiThread {
                networkQualitySummary.text = "Quality: waiting for connection"
                networkHistoryText.text = "No network samples yet."
            }
        }
    }

    private fun copyNetworkDiagnosticReport() {
        val samples = synchronized(networkHistoryLock) { networkHistory.toList() }
        val events = synchronized(networkHistoryLock) { networkEvents.toList() }
        val report = StringBuilder()
        report.append("IC-705 REMOTE NETWORK DIAGNOSTIC REPORT\n")
        report.append("App version: v27.3\n")
        report.append("Generated: ")
        report.append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()))
        report.append("\nConnected=").append(connected).append(" Running=").append(running)
            .append(" CI-V=").append(serialOpen).append(" Audio=").append(audioRunning).append("\n\n")
        report.append("time,quality,status,controlAgeMs,serialAgeMs,audioAgeMs,scopeAgeMs\n")
        samples.forEach { x ->
            report.append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(x.timestampMs)))
                .append(",").append(x.quality).append(",").append(x.status)
                .append(",").append(networkAge(x.controlAgeMs))
                .append(",").append(networkAge(x.serialAgeMs))
                .append(",").append(networkAge(x.audioAgeMs))
                .append(",").append(networkAge(x.scopeAgeMs)).append("\n")
        }
        report.append("\nEVENTS\n")
        events.forEach { report.append(it).append("\n") }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("IC-705 network diagnostic", report.toString()))
        android.widget.Toast.makeText(this, "Diagnostic report copied. Paste it into ChatGPT.", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun recoverAudioStream() {
        if (!running || !audioExpected || audioRecoveryInProgress) {
            return
        }

        audioRecoveryInProgress = true

        Thread {
            try {
                appendLog("AUDIO RECOVERY: stopping only the audio stream")
                audioRunning = false

                try { audioScheduler?.shutdownNow() } catch (_: Exception) {}
                audioScheduler = null

                try { audioReceiverThread?.interrupt() } catch (_: Exception) {}
                audioReceiverThread = null

                try { audioTrack?.pause() } catch (_: Exception) {}
                try { audioTrack?.flush() } catch (_: Exception) {}
                try { audioTrack?.stop() } catch (_: Exception) {}
                try { audioTrack?.release() } catch (_: Exception) {}
                audioTrack = null

                try { audioSocket?.close() } catch (_: Exception) {}
                audioSocket = null

                Thread.sleep(250)

                if (!running) {
                    return@Thread
                }

                val radioIp = settingsIpEdit.text.toString().trim()
                appendLog(
                    "AUDIO RECOVERY: reopening UDP $AUDIO_PORT to $radioIp"
                )

                openAudioReceive(radioIp)

                lastAudioPcmAt = System.currentTimeMillis()
                appendLog("AUDIO RECOVERY: stream reopened")
                addNetworkEvent("AUDIO RECOVERY: stream reopened")
            } catch (e: Exception) {
                if (running) {
                    appendLog("AUDIO RECOVERY FAILED: ${e.message}")
                    addNetworkEvent("AUDIO RECOVERY FAILED: ${e.message}")
                }
            } finally {
                audioRecoveryInProgress = false
            }
        }.apply {
            name = "IC705-Audio-Recovery"
            isDaemon = true
            start()
        }
    }

    private fun recoverSpectrumStream() {
        if (!running || !serialOpen || !scopeStarted || scopeRecoveryInProgress) {
            return
        }

        scopeRecoveryInProgress = true

        Thread {
            try {
                appendLog("SCOPE RECOVERY: restarting only the spectrum stream")

                try {
                    sendScopeCommand(
                        byteArrayOf(
                            0x27.toByte(),
                            0x11.toByte(),
                            0x00.toByte()
                        )
                    )
                } catch (_: Exception) {
                }

                scopeStarted = false
                resetScopeAssembly()

                Thread.sleep(250)

                if (!running || !serialOpen) {
                    return@Thread
                }

                startSpectrumScope()

                appendLog("SCOPE RECOVERY: spectrum stream restarted")
                addNetworkEvent("SCOPE RECOVERY: stream restarted")
            } catch (e: Exception) {
                if (running) {
                    appendLog("SCOPE RECOVERY FAILED: ${e.message}")
                    addNetworkEvent("SCOPE RECOVERY FAILED: ${e.message}")
                }
            } finally {
                scopeRecoveryInProgress = false
            }
        }.apply {
            name = "IC705-Scope-Recovery"
            isDaemon = true
            start()
        }
    }

    private fun updateNetworkQualityIndicator(
        quality: Int,
        status: String
    ) {
        if (!::networkQualityIndicator.isInitialized) {
            return
        }

        val color =
            when {
                status == "GOOD" -> Color.rgb(0, 220, 0)
                status == "FAIR" -> Color.YELLOW
                else -> Color.RED
            }

        networkQualityIndicator.text = "●"
        networkQualityIndicator.setTextColor(color)
        networkQualityIndicator.contentDescription =
            "Network quality: $quality percent, $status"
    }

    private fun stopNetworkWatchdog() {
        try {
            networkWatchdogScheduler?.shutdownNow()
        } catch (_: Exception) {
        }
        networkWatchdogScheduler = null
        networkWarningLogged = false
    }

    private fun stopSession() {
        try {
            stopSpectrumScope()
        } catch (_: Exception) {
        }


        if (teardownStarted) {
            return
        }
        teardownStarted = true
        running = false
        stopNetworkWatchdog()
        appendLog("")
        appendLog("DISCONNECTING...")
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

                writeBeInt(                    audioDisconnect,
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
                    Thread.sleep(100)
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
                Thread.sleep(50)
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
                    Thread.sleep(500)
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
        audioExpected = false
        audioRecoveryInProgress = false
        scopeRecoveryInProgress = false
        lastAudioPcmAt = 0L
        lastScopeFrameAt = 0L
        cleanupSocketOnly()
        runOnUiThread {
            connectButton.isEnabled = true
            disconnectButton.isEnabled = false
            setFrequencyButton.isEnabled = false
            down500Button.isEnabled = false
            up500Button.isEnabled = false
            down1kButton.isEnabled = false
            up1kButton.isEnabled = false
            usbButton.isEnabled = false
            lsbButton.isEnabled = false
            enableBandButtons(false)
            updateConnectionButtonColors(false)
        }
        appendLog("Connection closed.")
        appendLog("")
    }
    private fun cleanupSocketOnly() {
        stopSignalMeterPolling()
        stopScopePolling()
        try {
            serialScheduler?.shutdownNow()
        } catch (_: Exception) {
        }
        serialScheduler = null

        audioRunning = false
        audioExpected = false

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
        runOnUiThread {
            connectButton.isEnabled = true
            disconnectButton.isEnabled = false
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
        private val spectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0,255,180); style = Paint.Style.STROKE; strokeWidth = 3f }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(55,70,75); style = Paint.Style.STROKE; strokeWidth = 1f }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 18f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL) }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(160,220,255); textSize = 24f; typeface = Typeface.DEFAULT_BOLD }
        private val tunedFrequencyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0,255,0); style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true }
        private var spectrum = IntArray(356)
        private val waterfall = java.util.ArrayDeque<IntArray>()
        @Volatile private var sensitivity = 1.0f
        @Volatile private var noiseFloor = 0
        @Volatile private var displayBandwidthKHz = 75
        @Volatile var centerFrequencyHz = 0L
        @Volatile private var totalSpanFrequencyHz = 100_000L
        init { setBackgroundColor(Color.rgb(4,8,10)) }
        fun clearScope() { synchronized(this) { spectrum=IntArray(356); waterfall.clear(); centerFrequencyHz=0L; totalSpanFrequencyHz=100_000L }; postInvalidate() }
        fun setSensitivity(gain: Float) { sensitivity=gain.coerceIn(0.25f,4f); postInvalidate() }
        fun setNoiseFloor(v:Int) { noiseFloor=v.coerceIn(0,120); postInvalidate() }
        fun setDisplayBandwidthKHz(v:Int) { displayBandwidthKHz=v.coerceIn(5,1000); postInvalidate() }
        fun updateSpectrum(centerHz:Long,totalSpanHz:Long,samples:IntArray) { centerFrequencyHz=centerHz; totalSpanFrequencyHz=if(totalSpanHz>0) totalSpanHz else 100_000L; synchronized(this){ spectrum=samples.copyOf(); if(waterfall.size>=WATERFALL_ROWS) waterfall.removeLast(); waterfall.addFirst(samples.copyOf()) }; postInvalidate() }
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas); val w=width.toFloat(); val h=height.toFloat(); if(w<=0f||h<=0f)return
            canvas.drawColor(Color.rgb(4,8,10)); val spectrumHeight=maxOf(140f,h*0.45f)
            canvas.drawText("${displayBandwidthKHz} kHz SPECTRUM / WATERFALL",12f,30f,titlePaint)
            for(i in 0..10){val x=w*i/10f;canvas.drawLine(x,40f,x,spectrumHeight,gridPaint)}
            for(i in 0..4){val y=40f+(spectrumHeight-55f)*i/4f;canvas.drawLine(0f,y,w,y,gridPaint)}
            val samples=synchronized(this){spectrum.copyOf()}; if(samples.size>=2){
                val path=Path(); val denom=maxOf(1,160-noiseFloor)
                for(i in samples.indices){val x=i.toFloat()/(samples.size-1).toFloat()*(w-1f); val adjusted=maxOf(0,samples[i]-noiseFloor); val level=((adjusted.toFloat()/denom)*sensitivity).coerceIn(0f,1f); val y=46f+(spectrumHeight-70f)*(1f-level); if(i==0)path.moveTo(x,y) else path.lineTo(x,y)}
                canvas.drawPath(path,spectrumPaint)
            }
            drawFrequencyLabels(canvas,w,spectrumHeight)
            // Bold green tuned-frequency marker.
            val tunedHz = centerFrequencyHz
            val spanHz = totalSpanFrequencyHz
            if (tunedHz > 0L && spanHz > 0L) {
                val markerX = w / 2f
                canvas.drawLine(markerX, 40f, markerX, h, tunedFrequencyPaint)
            }
            canvas.drawLine(0f,spectrumHeight,w,spectrumHeight,gridPaint)
            val rows=synchronized(this){waterfall.toList()}; val rowHeight=maxOf(1f,(h-spectrumHeight-2f)/WATERFALL_ROWS.toFloat())
            for(r in rows.indices) drawWaterfallRow(canvas,rows[r],spectrumHeight+r*rowHeight,w,rowHeight)
        }
        private fun drawWaterfallRow(canvas:Canvas,row:IntArray,y:Float,width:Float,rowHeight:Float){ if(row.isEmpty())return; val denom=maxOf(1,160-noiseFloor); for(i in row.indices){val adjusted=maxOf(0,row[i]-noiseFloor); val level=((adjusted.toFloat()/denom)*sensitivity).coerceIn(0f,1f); val paint=Paint().apply{color=Color.rgb((8+247*level).toInt().coerceIn(0,255),(12+180*level*level).toInt().coerceIn(0,255),(25+225*level).toInt().coerceIn(0,255))}; val x0=i.toFloat()/row.size*width; val x1=(i+1).toFloat()/row.size*width; canvas.drawRect(x0,y,x1+1f,y+rowHeight+1f,paint)}}
        private fun drawFrequencyLabels(canvas:Canvas,width:Float,spectrumHeight:Float){val center=centerFrequencyHz;if(center<=0L)return; val visibleHz=minOf(displayBandwidthKHz.toLong()*1000L,totalSpanFrequencyHz); val left=formatMHz(center-visibleHz/2); val mid=formatMHz(center); val right=formatMHz(center+visibleHz/2); canvas.drawText(left,6f,spectrumHeight-4f,textPaint); val mw=textPaint.measureText(mid); canvas.drawText(mid,width/2f-mw/2f,spectrumHeight-4f,textPaint); val rw=textPaint.measureText(right); canvas.drawText(right,width-rw-6f,spectrumHeight-4f,textPaint)}
        private fun formatMHz(hz:Long):String=String.format(java.util.Locale.US,"%.6f",hz/1_000_000.0)
    }


}