package com.vector.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vector.assistant.R
import com.vector.assistant.audio.AudioRecorderManager
import com.vector.assistant.audio.VoiceSpeaker
import com.vector.assistant.audio.VoiceprintMatcher
import com.vector.assistant.automation.CommandExecutor
import com.vector.assistant.ui.MainActivity
import java.util.Locale

/**
 * 24/7 Foreground Service running with a Partial WakeLock.
 * Keeps listening and executing voice commands even when screen is locked/turned off.
 */
class VectorVoiceService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START_VECTOR"
        const val ACTION_STOP = "ACTION_STOP_VECTOR"
        const val ACTION_TRIGGER_LISTEN = "ACTION_TRIGGER_LISTEN"
        const val ACTION_TOGGLE_CONTINUOUS = "ACTION_TOGGLE_CONTINUOUS"
        const val CHANNEL_ID = "vector_voice_channel"
        const val NOTIFICATION_ID = 1001

        const val BROADCAST_LOG = "com.vector.assistant.LOG_UPDATE"
        const val EXTRA_LOG_MSG = "extra_log_msg"

        var isServiceRunning = false
            private set

        var isContinuousChatActive = false
            internal set
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var voiceprintMatcher: VoiceprintMatcher
    private lateinit var audioRecorderManager: AudioRecorderManager
    private lateinit var speaker: VoiceSpeaker
    private lateinit var commandExecutor: CommandExecutor
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRecognizingCommand = false

    override fun onCreate() {
        super.onCreate()

        // 1. Acquire WakeLock so CPU never sleeps when screen turns off
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VectorAssistant::ScreenOffWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        // 2. Initialize Voice & Automation Components
        voiceprintMatcher = VoiceprintMatcher(this)
        speaker = VoiceSpeaker(this)
        commandExecutor = CommandExecutor(
            context = this,
            speaker = speaker,
            onLog = { logMsg -> broadcastLog(logMsg) },
            onContinuousChatNextTurn = {
                mainHandler.postDelayed({
                    if (isServiceRunning && commandExecutor.isContinuousChatActive) {
                        broadcastLog("🎙️ Continuous Live Chat: Aapki baat sun raha hoon... (Bolen!)")
                        startSpeechRecognition()
                    }
                }, 400)
            }
        )

        // 3. Audio Stream Listener (Low-latency speaker verification)
        audioRecorderManager = AudioRecorderManager(
            matcher = voiceprintMatcher,
            onWakeWordDetected = { pcm ->
                broadcastLog("Owner Voice Verified! Waking up...")
                mainHandler.post {
                    triggerCommandListening()
                }
            },
            onVoiceRejected = { score ->
                broadcastLog("Voice rejected (Unknown speaker, score: ${"%.2f".format(score)})")
            }
        )

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundService()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER_LISTEN -> {
                if (!isServiceRunning) {
                    startForeground(NOTIFICATION_ID, buildNotification())
                    isServiceRunning = true
                }
                triggerCommandListening()
            }
            ACTION_TOGGLE_CONTINUOUS -> {
                if (!isServiceRunning) {
                    startForeground(NOTIFICATION_ID, buildNotification())
                    isServiceRunning = true
                }
                if (commandExecutor.isContinuousChatActive) {
                    commandExecutor.stopContinuousChat()
                    isContinuousChatActive = false
                    speaker.speak("Continuous chat band ho gayi hai.")
                    broadcastLog("Live Chat: Switched OFF")
                    resumeWakeListener()
                } else {
                    isContinuousChatActive = true
                    commandExecutor.startContinuousChat()
                }
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                isServiceRunning = true
                audioRecorderManager.startListening()
                broadcastLog("Vector is active! Listening with Screen-Off support.")
            }
        }
        return START_STICKY
    }

    /**
     * Correct sequence:
     * 1. Pause raw audio recorder.
     * 2. Speak "Yes boss?".
     * 3. Once TTS completes speaking, start SpeechRecognizer to capture the command!
     */
    private fun triggerCommandListening() {
        if (isRecognizingCommand) return
        isRecognizingCommand = true

        // 1. Wake screen if off so user sees Vector is awake and recognizer gets priority
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "VectorAssistant::ScreenWakeLock"
            )
            screenWakeLock.acquire(8000L)
        } catch (e: Exception) {
            // Ignore
        }

        // 2. Pause raw audio recorder so it doesn't conflict with microphone
        audioRecorderManager.stopListening()

        var recognitionStarted = false
        val launchRecognizer = {
            if (!recognitionStarted) {
                recognitionStarted = true
                mainHandler.postDelayed({
                    startSpeechRecognition()
                }, 300) // Small delay to clear speaker audio from the room
            }
        }

        // Safety fallback: if TTS onDone doesn't fire within 2.5 seconds, start anyway
        val fallbackRunnable = Runnable { launchRecognizer() }
        mainHandler.postDelayed(fallbackRunnable, 2500)

        speaker.speak("Yes boss?") {
            mainHandler.removeCallbacks(fallbackRunnable)
            launchRecognizer()
        }
    }

    private fun startSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            broadcastLog("Speech recognition not available on this device.")
            isRecognizingCommand = false
            resumeWakeListener()
            return
        }

        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            // Ignore
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    broadcastLog("🎤 Listening for your command now... (e.g. 'Torch on', 'Time', 'Gemini...')")
                }

                override fun onBeginningOfSpeech() {
                    broadcastLog("🗣️ Hearing voice...")
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    broadcastLog("Processing voice command...")
                }

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording issue"
                        SpeechRecognizer.ERROR_CLIENT -> "Client app issue"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing microphone permission"
                        SpeechRecognizer.ERROR_NETWORK -> "Network issue"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard (timeout)"
                        else -> "Code $error"
                    }
                    Log.d("VectorVoiceService", "Speech recognition ended: $errorMsg ($error)")
                    broadcastLog("Recognition: $errorMsg.")

                    if (commandExecutor.isContinuousChatActive) {
                        // In continuous chat, if silence timeout occurred, exit continuous chat
                        commandExecutor.stopContinuousChat()
                        broadcastLog("Live Chat: Paused due to silence. Normal wake mode active.")
                    }

                    isRecognizingCommand = false
                    resumeWakeListener()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val recognized = matches?.firstOrNull()?.trim() ?: ""
                    if (recognized.isNotEmpty()) {
                        broadcastLog("✅ Command heard: \"$recognized\"")
                        commandExecutor.processCommand(recognized)
                    } else {
                        broadcastLog("⚠️ Voice not clear. Please try again.")
                    }
                    isRecognizingCommand = false

                    // If NOT in continuous chat, resume listening for wake word
                    if (!commandExecutor.isContinuousChatActive) {
                        mainHandler.postDelayed({
                            resumeWakeListener()
                        }, 1200)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            val lang = Locale.getDefault().toLanguageTag()
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        try {
            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            broadcastLog("Failed to start speech listener: ${e.message}")
            isRecognizingCommand = false
            resumeWakeListener()
        }
    }

    private fun resumeWakeListener() {
        if (isServiceRunning && !commandExecutor.isContinuousChatActive && !audioRecorderManager.isRunning) {
            audioRecorderManager.startListening()
        }
    }

    private fun broadcastLog(msg: String) {
        val intent = Intent(BROADCAST_LOG).apply {
            putExtra(EXTRA_LOG_MSG, msg)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vector Assistant is Active")
            .setContentText("Listening for 'Hey Vector' / 'Suno Vector' (Screen-Off ready)")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Vector Voice Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Vector listening in background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundService() {
        isServiceRunning = false
        audioRecorderManager.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        speaker.shutdown()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // Ignore release exceptions
        }

        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
        broadcastLog("Vector deactivated.")
    }

    override fun onDestroy() {
        stopForegroundService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
