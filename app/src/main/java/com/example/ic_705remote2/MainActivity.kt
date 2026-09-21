package com.example.ic_705remote2
import android.app.Activity
import android.content.Context
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
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var frequencyEdit: EditText
    private lateinit var setFrequencyButton: Button
    private lateinit var down500Button: Button
    private lateinit var up500Button: Button
    private lateinit var usbButton: Button
    private lateinit var lsbButton: Button
    private lateinit var sensitivitySeekBar: SeekBar
    private lateinit var sensitivityLabel: TextView
    private lateinit var sensitivityRow: LinearLayout
    private lateinit var scopeInfoText: TextView
    private lateinit var scopeToggleButton: Button
    private lateinit var scopeView: SpectrumView
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
    private var scopeDecodedFrames = 0L
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
    @Volatile
    private var activeModeCode: Int = -1

    @Volatile
    private var modeReceived = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "IC-705 Remote Control"
        buildUserInterface()
    }
    private fun buildUserInterface() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(12, 12, 12, 12)

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
        settingsPane.visibility = View.GONE

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
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val saveSettingsButton = Button(this)
        saveSettingsButton.text = "SAVE SETTINGS"
        settingsPane.addView(
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
        scopeInfoText = TextView(this)
        scopeInfoText.text =
            "Spectrum: waiting for IC-705 spectrum data"
        scopeInfoText.textSize = 13f
        scopeInfoText.setPadding(0, 2, 0, 4)
        mainPane.addView(
            scopeInfoText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val scopeControlRow = LinearLayout(this)
        scopeControlRow.orientation = LinearLayout.HORIZONTAL
        scopeControlRow.gravity = Gravity.CENTER_VERTICAL

        scopeToggleButton = Button(this)
        scopeToggleButton.text = "SPECTRUM ON"
        scopeToggleButton.isEnabled = false

        scopeControlRow.addView(
            scopeToggleButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        mainPane.addView(
            scopeControlRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        scopeView = SpectrumView(this)
        mainPane.addView(
            scopeView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
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
        signalMeterRow.addView(
            signalMeterBar,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
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

        val frequencyLabel = TextView(this)
        frequencyLabel.text = "Frequency (read from IC-705 / Hz)"
        frequencyLabel.textSize = 15f
        mainPane.addView(
            frequencyLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
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
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                2f
            )
        )

        setFrequencyButton = Button(this)
        setFrequencyButton.text = "SET FREQUENCY"
        setFrequencyButton.isEnabled = false
        frequencyRow.addView(
            setFrequencyButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.25f
            )
        )
        mainPane.addView(
            frequencyRow,
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
                "17m\n18.100" to 18_100_000L,
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
        mainPane.addView(
            connectionRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            mainPane,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(
            settingsPane,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        mainTabButton.setOnClickListener {
            mainPane.visibility = View.VISIBLE
            settingsPane.visibility = View.GONE
        }

        settingsTabButton.setOnClickListener {
            mainPane.visibility = View.GONE
            settingsPane.visibility = View.VISIBLE
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
            shiftFrequencyBy500(-500L)
        }
        up500Button.setOnClickListener {
            shiftFrequencyBy500(500L)
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
                setFrequencyFromUi()
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