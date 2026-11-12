package com.vector.assistant.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class VectorState {
    IDLE,
    LISTENING_WAKE,
    LISTENING_COMMAND,
    THINKING,
    SPEAKING
}

data class ChatMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val badge: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isVoice: Boolean = true
)

data class VoiceDecision(
    val wake: Boolean,
    val transcript: String,
    val intent: String,
    val target: String = "",
    val message: String = "",
    val reply: String = ""
)

class VectorSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("vector_settings_prefs", Context.MODE_PRIVATE)

    var isSmartHybridWakeEnabled: Boolean
        get() = prefs.getBoolean("smart_hybrid_wake", true)
        set(value) = prefs.edit().putBoolean("smart_hybrid_wake", value).apply()

    var isWakeScreenOnCommand: Boolean
        get() = prefs.getBoolean("wake_screen_on_command", true)
        set(value) = prefs.edit().putBoolean("wake_screen_on_command", value).apply()

    var isOverlayHudEnabled: Boolean
        get() = prefs.getBoolean("overlay_hud_enabled", true)
        set(value) = prefs.edit().putBoolean("overlay_hud_enabled", value).apply()

    /**
     * Keeps Continuous Live Chat active automatically so the user does not need to tap mic
     * or repeat "Hey Vector" before every single command.
     */
    var isAutoContinuousChat: Boolean
        get() = prefs.getBoolean("auto_continuous_chat", true)
        set(value) = prefs.edit().putBoolean("auto_continuous_chat", value).apply()

    /**
     * Announces incoming WhatsApp replies aloud and lets the user reply/chat hands-free.
     */
    var isWhatsAppAssistantEnabled: Boolean
        get() = prefs.getBoolean("whatsapp_assistant_enabled", true)
        set(value) = prefs.edit().putBoolean("whatsapp_assistant_enabled", value).apply()

    /**
     * 0 = High sensitivity (far mic), 1 = Balanced (default), 2 = Strict (noisy rooms)
     */
    var micSensitivityLevel: Int
        get() = prefs.getInt("mic_sensitivity_level", 1).coerceIn(0, 2)
        set(value) = prefs.edit().putInt("mic_sensitivity_level", value.coerceIn(0, 2)).apply()

    /**
     * 0 = Auto (Roman Urdu/English -> en, Urdu script -> ur-PK), 1 = English/Roman, 2 = Urdu
     */
    var ttsVoiceMode: Int
        get() = prefs.getInt("tts_voice_mode", 0).coerceIn(0, 2)
        set(value) = prefs.edit().putInt("tts_voice_mode", value.coerceIn(0, 2)).apply()

    var userExplicitlyPaused: Boolean
        get() = prefs.getBoolean("user_explicitly_paused", false)
        set(value) = prefs.edit().putBoolean("user_explicitly_paused", value).apply()

    var serviceWasRunning: Boolean
        get() = prefs.getBoolean("service_was_running", false)
        set(value) = prefs.edit().putBoolean("service_was_running", value).apply()
}

object VectorSessionState {
    private val idGen = AtomicLong(1L)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _state = MutableStateFlow(VectorState.IDLE)
    val state: StateFlow<VectorState> = _state.asStateFlow()

    private val _statusSubtitle = MutableStateFlow("Tap power or say 'Hey Vector' once activated")
    val statusSubtitle: StateFlow<String> = _statusSubtitle.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _isContinuousChat = MutableStateFlow(false)
    val isContinuousChat: StateFlow<Boolean> = _isContinuousChat.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(listOf("[Ready] Vector AI initialized."))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    private val _lastReply = MutableStateFlow("")
    val lastReply: StateFlow<String> = _lastReply.asStateFlow()

    fun setState(newState: VectorState, subtitle: String? = null) {
        _state.value = newState
        if (subtitle != null) {
            _statusSubtitle.value = subtitle
        }
    }

    fun setAudioLevel(level: Float) {
        _audioLevel.value = level.coerceIn(0f, 1f)
    }

    fun setContinuousChat(active: Boolean) {
        _isContinuousChat.value = active
    }

    fun addUserMessage(text: String, isVoice: Boolean = true) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        _lastTranscript.value = clean
        appendMessage(
            ChatMessage(
                id = idGen.getAndIncrement(),
                isUser = true,
                text = clean,
                isVoice = isVoice
            )
        )
    }

    fun addVectorMessage(text: String, badge: String? = null) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        _lastReply.value = clean
        appendMessage(
            ChatMessage(
                id = idGen.getAndIncrement(),
                isUser = false,
                text = clean,
                badge = badge,
                isVoice = true
            )
        )
    }

    private fun appendMessage(msg: ChatMessage) {
        synchronized(this) {
            val updated = (_messages.value + msg).takeLast(60)
            _messages.value = updated
        }
    }

    fun clearMessages() {
        synchronized(this) {
            _messages.value = emptyList()
        }
    }

    fun addLog(line: String) {
        val stamp = synchronized(timeFmt) { timeFmt.format(Date()) }
        synchronized(this) {
            val updated = (_logs.value + "[$stamp] $line").takeLast(80)
            _logs.value = updated
        }
    }
}

