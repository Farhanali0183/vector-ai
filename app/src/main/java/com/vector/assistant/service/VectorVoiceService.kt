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
        const val CHANNEL_ID = "vector_voice_channel"
        const val NOTIFICATION_ID = 1001

        const val BROADCAST_LOG = "com.vector.assistant.LOG_UPDATE"
        const val EXTRA_LOG_MSG = "extra_log_msg"

        var isServiceRunning = false
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var voiceprintMatcher: VoiceprintMatcher
    private lateinit var audioRecorderManager: AudioRecorderManager
    private lateinit var speaker: VoiceSpeaker
    private lateinit var commandExecutor: CommandExecutor
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
        commandExecutor = CommandExecutor(this, speaker) { logMsg ->
            broadcastLog(logMsg)
        }

        // 3. Audio Stream Listener (Low-latency speaker verification)
        audioRecorderManager = AudioRecorderManager(
            matcher = voiceprintMatcher,
            onWakeWordDetected = { pcm ->
                broadcastLog("Owner Voice Verified! Listening for command...")
                mainHandler.post {
                    startSpeechRecognition()
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
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                isServiceRunning = true
                audioRecorderManager.startListening()
                broadcastLog("Vector is active! Listening with Screen-Off support.")
            }
        }
        return START_STICKY
    }

    private fun startSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            broadcastLog("Speech recognition not available on this device.")
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    speaker.speak("Yes boss?")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    Log.d("VectorVoiceService", "Speech recognition error: $error")
                    // Resume background audio listener
                    audioRecorderManager.startListening()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val recognized = matches?.firstOrNull() ?: ""
                    if (recognized.isNotEmpty()) {
                        commandExecutor.processCommand(recognized)
                    }
                    // Resume listening for the next wake word
                    audioRecorderManager.startListening()
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        // Pause raw audio recorder during high-level recognition
        audioRecorderManager.stopListening()
        speechRecognizer?.startListening(recognizerIntent)
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

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        broadcastLog("Vector deactivated.")
    }

    override fun onDestroy() {
        stopForegroundService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
