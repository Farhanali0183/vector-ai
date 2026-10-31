package com.vector.assistant.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Handles Vector's spoken audio responses to the user.
 */
class VoiceSpeaker(context: Context, private val onInitCompleted: (() -> Unit)? = null) {

    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Try Urdu/English locale, fallback to US
                val urduResult = tts?.setLanguage(Locale("ur", "PK"))
                if (urduResult == TextToSpeech.LANG_MISSING_DATA || urduResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.US
                }
                tts?.setSpeechRate(1.05f)
                tts?.setPitch(1.0f)
                isReady = true
                onInitCompleted?.invoke()
            } else {
                Log.e("VoiceSpeaker", "TextToSpeech init failed with status: $status")
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isReady || tts == null) {
            onDone?.invoke()
            return
        }

        val utteranceId = "vector_speech_${System.currentTimeMillis()}"
        if (onDone != null) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) {
                        onDone()
                    }
                }
                override fun onError(id: String?) {
                    if (id == utteranceId) {
                        onDone()
                    }
                }
            })
        }

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
