package com.vector.assistant.service

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vector.assistant.audio.AudioRecorderManager
import com.vector.assistant.audio.AudioUtils
import com.vector.assistant.audio.VoiceSpeaker
import com.vector.assistant.audio.VoiceprintMatcher
import com.vector.assistant.automation.CommandExecutor
import com.vector.assistant.model.VectorSessionState
import com.vector.assistant.model.VectorSettings
import com.vector.assistant.model.VectorState
import com.vector.assistant.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 24/7 Next-Gen Screen-Off & Always-Live Voice Service:
 * - Partial WakeLock + WifiLock + Bright Screen Wake on Voice
 * - Persistent Always-On Live Chat mode (no repeated mic taps, no repeated wake word needed)
 * - Smart Fallback to "Hey Vector" / "Suno Vector" wake mode when Live Chat is toggled OFF
 * - Live Incoming WhatsApp Reply announcer + hands-free Direct Reply pipeline
 */
class VectorVoiceService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START_VECTOR"
        const val ACTION_STOP = "ACTION_STOP_VECTOR"
        const val ACTION_TRIGGER_LISTEN = "ACTION_TRIGGER_LISTEN"
        const val ACTION_TOGGLE_CONTINUOUS = "ACTION_TOGGLE_CONTINUOUS"
        const val ACTION_TEXT_COMMAND = "ACTION_TEXT_COMMAND"
        const val EXTRA_TEXT_COMMAND = "extra_text_command"
        const val ACTION_INCOMING_CHAT_ALERT = "ACTION_INCOMING_CHAT_ALERT"
        const val EXTRA_ALERT_SENDER = "extra_alert_sender"
        const val EXTRA_ALERT_MESSAGE = "extra_alert_message"
        const val EXTRA_ALERT_IS_VIP = "extra_alert_is_vip"
        const val ACTION_REFRESH_SETTINGS = "ACTION_REFRESH_SETTINGS"

        const val CHANNEL_ID = "vector_voice_channel"
        const val NOTIFICATION_ID = 1001

        const val BROADCAST_LOG = "com.vector.assistant.LOG_UPDATE"
        const val EXTRA_LOG_MSG = "extra_log_msg"

        @Volatile
        var isServiceRunning = false
            private set

        @Volatile
        var isContinuousChatActive = false
            internal set

        @Volatile
        var isMainActivityVisible = false
    }

    private lateinit var settings: VectorSettings
    private lateinit var voiceprintMatcher: VoiceprintMatcher
    private lateinit var audioRecorderManager: AudioRecorderManager
    private lateinit var speaker: VoiceSpeaker
    private lateinit var commandExecutor: CommandExecutor
    private lateinit var overlayManager: VectorOverlayManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var toneGenerator: ToneGenerator? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Volatile
    private var isProcessingTurn = false

    @Volatile
    private var isUsingOfflineSpeechRecognizer = false

    private var lastNonWakeCheckMs = 0L
    private var consecutiveNonWakeCount = 0
    private var cooldownUntilMs = 0L

    override fun onCreate() {
        super.onCreate()
        settings = VectorSettings(this)
        voiceprintMatcher = VoiceprintMatcher(this)
        overlayManager = VectorOverlayManager(this)

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
        } catch (_: Exception) {
        }

        speaker = VoiceSpeaker(
            context = this,
            onSpeakingStateChanged = { speaking ->
                if (speaking) {
                    audioRecorderManager.setInputMuted(true)
                    VectorSessionState.setState(VectorState.SPEAKING, "Speaking...")
                    val lastReply = VectorSessionState.lastReply.value
                    if (!isMainActivityVisible && lastReply.isNotBlank()) {
                        overlayManager.showOrUpdate(VectorState.SPEAKING, "VECTOR", lastReply)
                    }
                } else {
                    audioRecorderManager.setInputMuted(false, tailGuardMs = 420L)
                    if (!commandExecutor.isContinuousChatActive &&
                        !isProcessingTurn &&
                        !isUsingOfflineSpeechRecognizer &&
                        isServiceRunning
                    ) {
                        overlayManager.scheduleAutoHide(2600L)
                    }
                }
            }
        )

        commandExecutor = CommandExecutor(
            context = this,
            speaker = speaker,
            onLog = { logMsg -> broadcastLog(logMsg) },
            onContinuousChatNextTurn = {
                mainHandler.post {
                    if (isServiceRunning && commandExecutor.isContinuousChatActive) {
                        isContinuousChatActive = true
                        startDirectCommandCapture(speakPrompt = false)
                    }
                }
            },
            onCycleComplete = {
                mainHandler.post {
                    if (commandExecutor.isContinuousChatActive) {
                        startDirectCommandCapture(speakPrompt = false)
                    } else {
                        returnToWakeListen()
                    }
                }
            },
            onWakeScreenRequested = { dismissLock ->
                mainHandler.post {
                    wakeScreenIfEnabled(dismissLockScreen = dismissLock)
                }
            }
        )

        audioRecorderManager = AudioRecorderManager(
            context = this,
            onUtteranceCaptured = { pcm, isDirectCommand ->
                handleCapturedUtterance(pcm, isDirectCommand)
            },
            onCommandTimeout = {
                mainHandler.post {
                    if (commandExecutor.isContinuousChatActive) {
                        // In Always-On Live Chat we stay in continuous mode unless explicitly paused
                        startDirectCommandCapture(speakPrompt = false)
                    } else {
                        returnToWakeListen()
                    }
                }
            },
            onAudioLevel = { level ->
                VectorSessionState.setAudioLevel(level)
                overlayManager.updateAudioLevel(level)
            },
            onRecorderStatus = { statusMsg ->
                broadcastLog(statusMsg)
            }
        )

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.userExplicitlyPaused = true
                settings.serviceWasRunning = false
                stopForegroundService()
                return START_NOT_STICKY
            }

            ACTION_TEXT_COMMAND -> {
                val text = intent.getStringExtra(EXTRA_TEXT_COMMAND)?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    VectorSessionState.setState(VectorState.THINKING, "Thinking...")
                    commandExecutor.processCommand(text, isVoiceInput = false)
                }
                return if (isServiceRunning) START_STICKY else START_NOT_STICKY
            }

            ACTION_INCOMING_CHAT_ALERT -> {
                val sender = intent.getStringExtra(EXTRA_ALERT_SENDER)?.trim().orEmpty()
                val msg = intent.getStringExtra(EXTRA_ALERT_MESSAGE)?.trim().orEmpty()
                val isVip = intent.getBooleanExtra(EXTRA_ALERT_IS_VIP, false)
                if (isServiceRunning && sender.isNotEmpty() && msg.isNotEmpty()) {
                    handleIncomingChatAlert(sender, msg, isVip)
                }
                return if (isServiceRunning) START_STICKY else START_NOT_STICKY
            }

            ACTION_REFRESH_SETTINGS -> {
                if (isServiceRunning) {
                    applyPreferredListeningMode(announce = false)
                }
                return if (isServiceRunning) START_STICKY else START_NOT_STICKY
            }
        }

        if (intent == null && settings.userExplicitlyPaused) {
            stopSelf()
            return START_NOT_STICKY
        }

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            broadcastLog("⚠️ Microphone permission required to start Vector.")
            VectorSessionState.setState(VectorState.IDLE, "Microphone permission required")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!ensureForegroundAndLocks()) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_TRIGGER_LISTEN -> {
                settings.userExplicitlyPaused = false
                settings.serviceWasRunning = true
                audioRecorderManager.startListening()
                startDirectCommandCapture(speakPrompt = true)
            }

            ACTION_TOGGLE_CONTINUOUS -> {
                settings.userExplicitlyPaused = false
                settings.serviceWasRunning = true
                audioRecorderManager.startListening()
                if (commandExecutor.isContinuousChatActive) {
                    commandExecutor.stopContinuousChat(speakConfirmation = true, persistDisable = true)
                    isContinuousChatActive = false
                    broadcastLog("Live Chat: Switched OFF")
                } else {
                    isContinuousChatActive = true
                    commandExecutor.startContinuousChat(silent = false)
                }
            }

            else -> {
                settings.userExplicitlyPaused = false
                settings.serviceWasRunning = true
                audioRecorderManager.startListening()
                applyPreferredListeningMode(announce = false)
            }
        }

        return START_STICKY
    }

    private fun applyPreferredListeningMode(announce: Boolean) {
        if (settings.isAutoContinuousChat && commandExecutor.geminiBrain.isConnected()) {
            isContinuousChatActive = true
            commandExecutor.startContinuousChat(silent = !announce)
            broadcastLog("Vector Active! Always-On Live Chat Ready (No mic click, no wake word needed).")
        } else {
            if (commandExecutor.isContinuousChatActive && !settings.isAutoContinuousChat) {
                commandExecutor.stopContinuousChat(speakConfirmation = false)
            }
            isContinuousChatActive = false
            returnToWakeListen()
            broadcastLog("Vector Active! Hands-free 'Hey Vector' / 'Suno Vector' & Screen-Off ready.")
        }
    }

    private fun handleIncomingChatAlert(sender: String, msg: String, isVip: Boolean) {
        if (isProcessingTurn || speaker.isSpeakingNow) {
            mainHandler.postDelayed({
                if (isServiceRunning) handleIncomingChatAlert(sender, msg, isVip)
            }, 2200L)
            return
        }

        wakeScreenIfEnabled(dismissLockScreen = false)
        audioRecorderManager.setInputMuted(true)
        val badge = if (isVip) "VIP • $sender" else "WhatsApp • $sender"
        val displayLine = "WhatsApp par $sender ka message: \"$msg\""
        VectorSessionState.addVectorMessage(displayLine, badge = badge)
        broadcastLog("📩 Incoming WhatsApp from $sender: $msg")

        if (!isMainActivityVisible) {
            overlayManager.showOrUpdate(VectorState.SPEAKING, "WHATSAPP • $sender", msg)
        }

        val spoken = "Boss, WhatsApp par $sender ka message aaya hai: $msg. Kya main reply doon?"
        speaker.speak(spoken) {
            if (!isServiceRunning) return@speak
            if (commandExecutor.geminiBrain.isConnected()) {
                // Immediately stay ready to hear user's reply ("haan reply do" / "sweet sa msg karo")
                startDirectCommandCapture(speakPrompt = false)
            } else {
                returnToWakeListen()
            }
        }
    }

    private fun ensureForegroundAndLocks(): Boolean {
        if (!isServiceRunning) {
            val notification = buildNotification()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                isServiceRunning = true
            } catch (e: Exception) {
                Log.e("VectorVoiceService", "startForeground failed: ${e.message}")
                broadcastLog("⚠️ Open Vector app once to resume background mic.")
                return false
            }
        }

        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "VectorAssistant::ScreenOffWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (wakeLock?.isHeld == false) {
            wakeLock?.acquire()
        }

        if (wifiLock == null) {
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager?.createWifiLock(mode, "VectorAssistant::WifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {
            }
        } else if (wifiLock?.isHeld == false) {
            try { wifiLock?.acquire() } catch (_: Exception) {}
        }
        return true
    }

    private fun handleCapturedUtterance(pcm: ShortArray, isDirectCommand: Boolean) {
        if (!isServiceRunning || isProcessingTurn) return

        // 1. Optional Biometrics Speaker Lock
        if (voiceprintMatcher.isBiometricsEnforced() && voiceprintMatcher.isProfileEnrolled()) {
            val score = voiceprintMatcher.matchScore(pcm)
            if (score < voiceprintMatcher.verificationThreshold) {
                broadcastLog("Voice ignored (Biometrics mismatch: ${"%.2f".format(score)})")
                if (!isDirectCommand) returnToWakeListen()
                return
            }
        }

        val geminiConnected = commandExecutor.geminiBrain.isConnected()

        if (isDirectCommand) {
            val continuous = commandExecutor.isContinuousChatActive
            val now = System.currentTimeMillis()
            if (continuous && now < cooldownUntilMs) return

            isProcessingTurn = true
            audioRecorderManager.setInputMuted(true)
            VectorSessionState.setState(VectorState.THINKING, "Thinking...")
            if (!isMainActivityVisible) {
                overlayManager.showOrUpdate(VectorState.THINKING, "VECTOR", "Understanding your voice...")
            }

            if (geminiConnected) {
                val wav = AudioUtils.pcm16ToWav(pcm)
                serviceScope.launch {
                    try {
                        val res = commandExecutor.geminiBrain.processVoiceAudio(
                            wavBytes = wav,
                            requireWakeWord = false,
                            isContinuousSession = continuous
                        )
                        isProcessingTurn = false
                        audioRecorderManager.setInputMuted(false, tailGuardMs = 260L)

                        if (res.isApiSuccess && res.decision != null) {
                            val d = res.decision
                            if (!d.wake || (d.transcript.isBlank() && d.intent != "wake_only")) {
                                if (continuous) {
                                    consecutiveNonWakeCount++
                                    if (consecutiveNonWakeCount >= 5) {
                                        consecutiveNonWakeCount = 0
                                        cooldownUntilMs = System.currentTimeMillis() + 5000L
                                    }
                                    startDirectCommandCapture(speakPrompt = false)
                                } else {
                                    broadcastLog("No clear speech detected.")
                                    returnToWakeListen()
                                }
                            } else {
                                consecutiveNonWakeCount = 0
                                cooldownUntilMs = 0L
                                wakeScreenIfEnabled(dismissLockScreen = false)
                                if (d.intent == "wake_only") {
                                    startDirectCommandCapture(speakPrompt = true)
                                } else {
                                    commandExecutor.executeDecision(d)
                                }
                            }
                        } else {
                            broadcastLog("Gemini Error: ${res.errorMessage}")
                            val errReply = if (res.errorMessage.isNotBlank()) res.errorMessage
                            else "Internet ya Gemini connection mein masla aaya."
                            VectorSessionState.addVectorMessage(errReply, badge = "Connection")
                            speaker.speak(errReply) {
                                if (commandExecutor.isContinuousChatActive) {
                                    startDirectCommandCapture(speakPrompt = false)
                                } else {
                                    returnToWakeListen()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        isProcessingTurn = false
                        audioRecorderManager.setInputMuted(false)
                        broadcastLog("Audio processing error: ${e.message}")
                        if (commandExecutor.isContinuousChatActive) {
                            startDirectCommandCapture(speakPrompt = false)
                        } else {
                            returnToWakeListen()
                        }
                    }
                }
            } else {
                isProcessingTurn = false
                audioRecorderManager.setInputMuted(false)
                val msg = "Gemini API key connect nahi hai. Settings mein apni Gemini key paste karein."
                VectorSessionState.addVectorMessage(msg, badge = "Setup Required")
                speaker.speak(msg) {
                    returnToWakeListen()
                }
            }
            return
        }

        // BACKGROUND WAKE MODE (when Live Chat is OFF)
        val isLocalWake = voiceprintMatcher.isProfileEnrolled() && voiceprintMatcher.isWakePhraseMatch(pcm)

        if (!geminiConnected) {
            if (isLocalWake) {
                wakeUpAndListenForOfflineCommand()
            }
            return
        }

        if (!settings.isSmartHybridWakeEnabled && !isLocalWake) {
            return
        }

        val now = System.currentTimeMillis()
        if (!isLocalWake) {
            if (now < cooldownUntilMs) return
            if (now - lastNonWakeCheckMs < 2200L) return
        }

        isProcessingTurn = true
        audioRecorderManager.setInputMuted(true)
        VectorSessionState.setState(VectorState.THINKING, "Checking wake word...")

        val wav = AudioUtils.pcm16ToWav(pcm)
        serviceScope.launch {
            try {
                val res = commandExecutor.geminiBrain.processVoiceAudio(
                    wavBytes = wav,
                    requireWakeWord = !isLocalWake,
                    isContinuousSession = false
                )
                isProcessingTurn = false
                audioRecorderManager.setInputMuted(false, tailGuardMs = 220L)

                if (!res.isApiSuccess || res.decision == null) {
                    lastNonWakeCheckMs = System.currentTimeMillis()
                    if (res.errorMessage.isNotBlank()) {
                        broadcastLog("Wake check skipped: ${res.errorMessage}")
                    }
                    returnToWakeListen()
                    return@launch
                }

                val decision = res.decision
                if (!decision.wake) {
                    lastNonWakeCheckMs = System.currentTimeMillis()
                    consecutiveNonWakeCount++
                    if (consecutiveNonWakeCount >= 5) {
                        consecutiveNonWakeCount = 0
                        cooldownUntilMs = System.currentTimeMillis() + 10000L
                        broadcastLog("Background speech ignored (10s quiet cooldown).")
                    }
                    returnToWakeListen()
                    return@launch
                }

                consecutiveNonWakeCount = 0
                cooldownUntilMs = 0L
                wakeScreenIfEnabled(dismissLockScreen = false)

                if (decision.intent == "wake_only" || (decision.transcript.isBlank() && decision.reply.isBlank())) {
                    broadcastLog("Wake word detected! Listening for your command...")
                    startDirectCommandCapture(speakPrompt = true)
                } else {
                    broadcastLog("Wake + Command heard: \"${decision.transcript}\"")
                    if (!isMainActivityVisible) {
                        overlayManager.showOrUpdate(
                            VectorState.THINKING,
                            "VECTOR",
                            "\"${decision.transcript}\""
                        )
                    }
                    commandExecutor.executeDecision(decision)
                }
            } catch (e: Exception) {
                isProcessingTurn = false
                audioRecorderManager.setInputMuted(false)
                broadcastLog("Wake check exception: ${e.message}")
                returnToWakeListen()
            }
        }
    }

    private fun startDirectCommandCapture(speakPrompt: Boolean) {
        if (!isServiceRunning) return
        isProcessingTurn = false

        val continuous = commandExecutor.isContinuousChatActive
        val captureTimeoutMs = if (continuous) 0L else 9000L

        if (!commandExecutor.geminiBrain.isConnected()) {
            wakeScreenIfEnabled(dismissLockScreen = false)
            launchOfflineSpeechRecognizer(speakPrompt)
            return
        }

        if (speakPrompt) {
            wakeScreenIfEnabled(dismissLockScreen = false)
            playWakeChime()
            audioRecorderManager.setInputMuted(true)
            VectorSessionState.setState(VectorState.SPEAKING, "Yes boss?")
            speaker.speak("Yes boss?") {
                if (isServiceRunning) {
                    audioRecorderManager.setCaptureMode(AudioRecorderManager.CaptureMode.DIRECT_COMMAND, captureTimeoutMs)
                    audioRecorderManager.setInputMuted(false, tailGuardMs = 260L)
                    val subtitle = if (continuous) "Live Chat Active — Bolen (no wake word needed)" else "Listening for your command..."
                    VectorSessionState.setState(VectorState.LISTENING_COMMAND, subtitle)
                    broadcastLog("🎤 $subtitle")
                }
            }
        } else {
            audioRecorderManager.setCaptureMode(AudioRecorderManager.CaptureMode.DIRECT_COMMAND, captureTimeoutMs)
            audioRecorderManager.setInputMuted(false, tailGuardMs = 240L)
            val subtitle = if (continuous) "Live Chat Open — Bolen..." else "Listening... Speak now"
            VectorSessionState.setState(VectorState.LISTENING_COMMAND, subtitle)
            if (!isMainActivityVisible && continuous) {
                overlayManager.scheduleAutoHide(2400L)
            }
        }
    }

    private fun wakeUpAndListenForOfflineCommand() {
        mainHandler.post {
            wakeScreenIfEnabled(dismissLockScreen = false)
            broadcastLog("Offline Wake Word verified!")
            launchOfflineSpeechRecognizer(speakPrompt = true)
        }
    }

    private fun launchOfflineSpeechRecognizer(speakPrompt: Boolean) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isInteractive = powerManager?.isInteractive ?: true
        if (!isInteractive || !SpeechRecognizer.isRecognitionAvailable(this)) {
            val setupMsg = "Gemini API key connect karein taake screen-off aur hands-free voice AI kaam kare."
            VectorSessionState.addVectorMessage(setupMsg, badge = "Setup Needed")
            speaker.speak(setupMsg) {
                returnToWakeListen()
            }
            return
        }

        isUsingOfflineSpeechRecognizer = true
        serviceScope.launch {
            audioRecorderManager.stopListeningAndWait()
            if (speakPrompt) {
                playWakeChime()
                speaker.speak("Yes boss?") {
                    mainHandler.postDelayed({ beginAndroidSpeechRecognizer() }, 250L)
                }
            } else {
                beginAndroidSpeechRecognizer()
            }
        }
    }

    private fun beginAndroidSpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        VectorSessionState.setState(VectorState.LISTENING_COMMAND, "Listening (Offline STT)...")

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    broadcastLog("🎤 Offline STT ready — speak your command...")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    val norm = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    VectorSessionState.setAudioLevel(norm)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    VectorSessionState.setState(VectorState.THINKING, "Processing...")
                }

                override fun onError(error: Int) {
                    isUsingOfflineSpeechRecognizer = false
                    broadcastLog("Offline STT ended (code $error)")
                    if (commandExecutor.isContinuousChatActive) {
                        commandExecutor.stopContinuousChat(false)
                        isContinuousChatActive = false
                    }
                    audioRecorderManager.startListening()
                    returnToWakeListen()
                }

                override fun onResults(results: Bundle?) {
                    isUsingOfflineSpeechRecognizer = false
                    val recognized = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    audioRecorderManager.startListening()
                    if (recognized.isNotEmpty()) {
                        commandExecutor.processCommand(recognized, isVoiceInput = true)
                    } else {
                        returnToWakeListen()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        try {
            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            isUsingOfflineSpeechRecognizer = false
            broadcastLog("SpeechRecognizer error: ${e.message}")
            audioRecorderManager.startListening()
            returnToWakeListen()
        }
    }

    private fun returnToWakeListen() {
        if (!isServiceRunning) {
            VectorSessionState.setState(VectorState.IDLE, "Vector is paused")
            return
        }
        isProcessingTurn = false
        isContinuousChatActive = commandExecutor.isContinuousChatActive
        if (isContinuousChatActive && commandExecutor.geminiBrain.isConnected()) {
            startDirectCommandCapture(speakPrompt = false)
            return
        }
        audioRecorderManager.setCaptureMode(AudioRecorderManager.CaptureMode.WAKE_LISTEN)
        audioRecorderManager.setInputMuted(false, tailGuardMs = 300L)
        val subtitle = if (commandExecutor.geminiBrain.isConnected()) {
            "Listening for 'Hey Vector' / 'Suno Vector'..."
        } else if (voiceprintMatcher.isProfileEnrolled()) {
            "Offline Wake Ready — Say 'Hey Vector'"
        } else {
            "Tap Visor to talk • Connect Gemini for hands-free"
        }
        VectorSessionState.setState(VectorState.LISTENING_WAKE, subtitle)
        overlayManager.scheduleAutoHide(2400L)
    }

    private fun wakeScreenIfEnabled(dismissLockScreen: Boolean = false) {
        if (!settings.isWakeScreenOnCommand && !dismissLockScreen) return
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val wasOff = !powerManager.isInteractive
            if (wasOff) {
                @Suppress("DEPRECATION")
                val screenWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "VectorAssistant::ScreenWakeLock"
                )
                screenWakeLock.acquire(12000L)
            }

            // If a visual/in-app task is requested while device is off/locked, bring Vector Activity up to request keyguard dismiss cleanly
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (dismissLockScreen && (wasOff || km?.isKeyguardLocked == true) && Settings.canDrawOverlays(this)) {
                val wakeActivity = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_DISMISS_KEYGUARD, true)
                }
                startActivity(wakeActivity)
            }
        } catch (_: Exception) {
        }
    }

    private fun playWakeChime() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 110)
        } catch (_: Exception) {
        }
    }

    private fun broadcastLog(msg: String) {
        VectorSessionState.addLog(msg)
        val intent = Intent(BROADCAST_LOG).apply {
            putExtra(EXTRA_LOG_MSG, msg)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val listenIntent = Intent(this, VectorVoiceService::class.java).apply {
            action = ACTION_TRIGGER_LISTEN
        }
        val listenPi = PendingIntent.getService(
            this, 1, listenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, VectorVoiceService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vector AI is Active")
            .setContentText("Always-On Live Chat & Screen-Off Voice Ready")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_btn_speak_now, "Listen Now", listenPi)
            .addAction(android.R.drawable.ic_media_pause, "Pause", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Vector Voice Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Vector listening hands-free in background and screen-off mode"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundService() {
        isServiceRunning = false
        isContinuousChatActive = false
        commandExecutor.stopContinuousChat(false)
        audioRecorderManager.stopListening()
        overlayManager.hide()

        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null
        speaker.stopSpeaking()

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}

        VectorSessionState.setState(VectorState.IDLE, "Vector is paused")
        VectorSessionState.setAudioLevel(0f)

        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
        broadcastLog("Vector paused.")
    }

    override fun onDestroy() {
        isServiceRunning = false
        audioRecorderManager.stopListening()
        overlayManager.hide()
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        try { toneGenerator?.release() } catch (_: Exception) {}
        speaker.shutdown()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
