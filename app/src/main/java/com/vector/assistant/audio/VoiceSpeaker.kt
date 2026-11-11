package com.vector.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.vector.assistant.model.VectorSettings
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles Vector's spoken audio responses with:
 * - Queued speech if called before TTS finishes initializing
 * - Smart language matching (Urdu script -> ur-PK, Roman Urdu / English -> en-IN/GB/US)
 * - Global speaking state callbacks to prevent self-echo loops
 */
class VoiceSpeaker(
    context: Context,
    private val onSpeakingStateChanged: ((Boolean) -> Unit)? = null,
    private val onInitCompleted: (() -> Unit)? = null
) {

    private val appContext = context.applicationContext
    private val settings = VectorSettings(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    @Volatile
    private var isReady = false
    @Volatile
    var isSpeakingNow = false
        private set

    private var pendingSpeech: Pair<String, (() -> Unit)?>? = null
    private val completionCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private var safetyTimeoutRunnable: Runnable? = null

    private var englishLocale: Locale = Locale.US
    private var urduSupported = false

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureVoices()
                isReady = true
                onInitCompleted?.invoke()
                val pending = pendingSpeech
                pendingSpeech = null
                if (pending != null) {
                    speak(pending.first, pending.second)
                }
            } else {
                Log.e("VoiceSpeaker", "TextToSpeech init failed with status: $status")
                val pending = pendingSpeech
                pendingSpeech = null
                pending?.second?.invoke()
            }
        }
    }

    private fun configureVoices() {
        val engine = tts ?: return
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setSpeechRate(1.03f)
        engine.setPitch(1.0f)

        // Pick natural English locale that pronounces Roman Urdu & English cleanly
        val candidateEnglish = listOf(Locale("en", "IN"), Locale("en", "PK"), Locale.UK, Locale.US)
        for (loc in candidateEnglish) {
            val res = engine.isLanguageAvailable(loc)
            if (res == TextToSpeech.LANG_AVAILABLE ||
                res == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                res == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            ) {
                englishLocale = loc
                break
            }
        }

        val urduRes = engine.isLanguageAvailable(Locale("ur", "PK"))
        urduSupported = urduRes >= TextToSpeech.LANG_AVAILABLE

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeakingNow = true
                mainHandler.post { onSpeakingStateChanged?.invoke(true) }
            }

            override fun onDone(utteranceId: String?) {
                finishUtterance(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finishUtterance(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finishUtterance(utteranceId)
            }
        })
    }

    private fun finishUtterance(utteranceId: String?) {
        safetyTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        safetyTimeoutRunnable = null
        isSpeakingNow = false
        val cb = if (utteranceId != null) completionCallbacks.remove(utteranceId) else null
        mainHandler.post {
            onSpeakingStateChanged?.invoke(false)
            cb?.invoke()
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val cleanText = text.replace("*", "").replace("#", "").trim()
        if (cleanText.isEmpty()) {
            onDone?.invoke()
            return
        }

        if (!isReady || tts == null) {
            pendingSpeech = Pair(cleanText, onDone)
            return
        }

        val engine = tts ?: run {
            onDone?.invoke()
            return
        }

        selectLocaleForText(engine, cleanText)

        val utteranceId = "vector_${System.currentTimeMillis()}_${cleanText.hashCode()}"
        completionCallbacks.clear()
        if (onDone != null) {
            completionCallbacks[utteranceId] = onDone
        }

        isSpeakingNow = true
        mainHandler.post { onSpeakingStateChanged?.invoke(true) }

        // Safety watchdog in case TTS engine stalls without calling onDone
        safetyTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val estimatedMs = (cleanText.length * 95L).coerceIn(3500L, 25000L)
        val timeout = Runnable {
            if (isSpeakingNow) {
                Log.w("VoiceSpeaker", "TTS safety watchdog triggered")
                finishUtterance(utteranceId)
            }
        }
        safetyTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, estimatedMs)

        val result = engine.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            finishUtterance(utteranceId)
        }
    }

    fun stopSpeaking() {
        safetyTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        safetyTimeoutRunnable = null
        completionCallbacks.clear()
        isSpeakingNow = false
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        mainHandler.post { onSpeakingStateChanged?.invoke(false) }
    }

    private fun selectLocaleForText(engine: TextToSpeech, text: String) {
        val hasUrduArabicScript = text.any { it in '\u0600'..'\u06FF' }
        val mode = settings.ttsVoiceMode
        val targetLocale = when (mode) {
            2 -> if (urduSupported) Locale("ur", "PK") else englishLocale
            1 -> englishLocale
            else -> if (hasUrduArabicScript && urduSupported) Locale("ur", "PK") else englishLocale
        }
        try {
            engine.language = targetLocale
        } catch (_: Exception) {
            engine.language = Locale.US
        }
    }

    fun shutdown() {
        stopSpeaking()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        isReady = false
    }
}
