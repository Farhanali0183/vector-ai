package com.vector.assistant.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.vector.assistant.automation.UiStepDecision
import com.vector.assistant.automation.WhatsAppChatAgent
import com.vector.assistant.model.VoiceDecision
import com.vector.assistant.service.VectorAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class GeminiVerifyResult(
    val success: Boolean,
    val model: String? = null,
    val message: String
)

data class GeminiAudioResult(
    val isApiSuccess: Boolean,
    val decision: VoiceDecision? = null,
    val errorMessage: String = ""
)

/**
 * Connects Vector Assistant directly to Google Gemini v1beta API.
 * Features:
 * 1. Real key verification & smart model discovery (excludes TTS/image/embedding models)
 * 2. Top-level systemInstruction & thinkingBudget=0 (prevents maxOutputTokens exhaustion on Gemini 2.5+)
 * 3. Multimodal audio/wav understanding (Wake word + Roman Urdu/English STT + Intent + Reply in 1 call)
 * 4. Multi-turn continuous conversation memory without error pollution
 */
class GeminiBrainClient(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vector_gemini_prefs", Context.MODE_PRIVATE)
    val memoryManager = VectorMemoryManager(context)

    companion object {
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_WORKING_MODEL = "gemini_working_model"
        private const val TAG = "GeminiBrainClient"

        private val historyLock = Any()
        private val conversationHistory = ArrayList<JSONObject>()

        val CANDIDATE_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite"
        )
    }

    fun saveApiKey(apiKey: String) {
        prefs.edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .remove(KEY_WORKING_MODEL)
            .apply()
    }

    fun clearApiKey() {
        prefs.edit()
            .remove(KEY_API_KEY)
            .remove(KEY_WORKING_MODEL)
            .apply()
    }

    fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun getWorkingModel(): String =
        prefs.getString(KEY_WORKING_MODEL, CANDIDATE_MODELS[0]) ?: CANDIDATE_MODELS[0]

    fun setWorkingModel(model: String) {
        prefs.edit().putString(KEY_WORKING_MODEL, model).apply()
    }

    fun isConnected(): Boolean = !getApiKey().isNullOrBlank()

    fun clearConversationHistory() {
        synchronized(historyLock) {
            conversationHistory.clear()
        }
    }

    /**
     * Tests an API key against Google Gemini before saving it as active.
     */
    suspend fun verifyAndConnectKey(rawKey: String): GeminiVerifyResult = withContext(Dispatchers.IO) {
        val cleanKey = rawKey.trim()
        if (cleanKey.length < 15) {
            return@withContext GeminiVerifyResult(
                success = false,
                message = "API key bohot chhoti ya invalid lag rahi hai."
            )
        }

        val discoveredList = discoverRankedModelsFromGoogle(cleanKey)
        val modelsToTry = LinkedHashSet<String>().apply {
            addAll(CANDIDATE_MODELS)
            addAll(discoveredList)
        }.toList()

        val probeTurns = listOf(
            JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", "Reply with exactly: Ready") })
                })
            }
        )

        var lastHttpCode = -1
        var lastErrorMsg = "Internet connection issue."

        for (model in modelsToTry) {
            val resp = executeRequest(
                apiKey = cleanKey,
                model = model,
                systemInstruction = "Reply concisely.",
                contentsList = probeTurns,
                expectJson = false,
                maxTokens = 64
            )
            if (resp.isSuccess) {
                prefs.edit()
                    .putString(KEY_API_KEY, cleanKey)
                    .putString(KEY_WORKING_MODEL, model)
                    .apply()
                return@withContext GeminiVerifyResult(
                    success = true,
                    model = model,
                    message = "Gemini connected successfully ($model)!"
                )
            }
            lastHttpCode = resp.httpCode
            lastErrorMsg = resp.text

            if (resp.httpCode == 401 || resp.httpCode == 403 ||
                resp.text.contains("API key not valid", ignoreCase = true) ||
                resp.text.contains("API_KEY_INVALID", ignoreCase = true)
            ) {
                return@withContext GeminiVerifyResult(
                    success = false,
                    message = "API key invalid ya expire hai. Google AI Studio se sahi key check karein."
                )
            }
        }

        // If model listing worked (key valid) but all models were 429 quota limited
        if (discoveredList.isNotEmpty() && lastHttpCode == 429) {
            prefs.edit()
                .putString(KEY_API_KEY, cleanKey)
                .putString(KEY_WORKING_MODEL, discoveredList.first())
                .apply()
            return@withContext GeminiVerifyResult(
                success = true,
                model = discoveredList.first(),
                message = "Key valid hai (${discoveredList.first()}), lekin abhi rate/quota limit active hai. Thodi der baad try karein."
            )
        }

        val friendly = when (lastHttpCode) {
            429 -> "Gemini API quota limit poori ho gayi hai. Thodi der baad try karein."
            400, 401, 403 -> "API key invalid hai: $lastErrorMsg"
            -1 -> "Internet connect nahi ho saka. Network check karein."
            else -> "Gemini connect nahi ho saka ($lastHttpCode): $lastErrorMsg"
        }
        GeminiVerifyResult(success = false, message = friendly)
    }

    /**
     * Multimodal audio understanding: sends 16kHz mono WAV to Gemini and gets structured
     * wake verification + transcript + intent + natural reply in one low-latency round-trip.
     */
    suspend fun processVoiceAudio(
        wavBytes: ByteArray,
        requireWakeWord: Boolean,
        isContinuousSession: Boolean
    ): GeminiAudioResult = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
            ?: return@withContext GeminiAudioResult(false, errorMessage = "Gemini API key configured nahi hai.")

        val audioBase64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val systemPrompt = buildStructuredSystemPrompt(requireWakeWord = requireWakeWord)

        val instructionText = if (requireWakeWord) {
            "Listen carefully. Set wake=true if speaker addresses 'Vector' (Hey Vector, Suno Vector, Vector, Ok Vector) OR gives a direct voice command after screen wake. Return strict JSON only."
        } else {
            "Continuous live chat active: transcribe clearly and return strict JSON intent. If pure ambient noise/unrelated background TV chatter, set wake=false."
        }

        val currentTurn = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", "audio/wav")
                        put("data", audioBase64)
                    })
                })
                put(JSONObject().apply {
                    put("text", instructionText)
                })
            })
        }

        val turnsToSend = buildTurnsWithHistory(currentTurn, includeHistory = isContinuousSession)

        val apiResp = callWithModelFallback(
            apiKey = apiKey,
            systemInstruction = systemPrompt,
            contentsList = turnsToSend,
            expectJson = true,
            maxTokens = 768
        )

        if (!apiResp.isSuccess) {
            return@withContext GeminiAudioResult(false, errorMessage = apiResp.text)
        }

        val decision = parseVoiceDecision(apiResp.text, defaultWake = !requireWakeWord)
        if (decision.wake && decision.transcript.isNotBlank() && decision.intent != "wake_only") {
            val replyText = decision.reply.ifBlank { "Action executed: ${decision.intent}" }
            appendTurnToHistory(decision.transcript, replyText, keepHistory = isContinuousSession)
        }

        GeminiAudioResult(true, decision = decision)
    }

    /**
     * Understands typed text or offline SpeechRecognizer transcript with smart intent extraction.
     */
    suspend fun processTextWithIntent(
        userPrompt: String,
        isContinuousSession: Boolean
    ): VoiceDecision? = withContext(Dispatchers.IO) {
        val apiKey = getApiKey() ?: return@withContext null
        val systemPrompt = buildStructuredSystemPrompt(requireWakeWord = false)

        val userTurn = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", userPrompt) })
            })
        }

        val turnsToSend = buildTurnsWithHistory(userTurn, includeHistory = isContinuousSession)
        val apiResp = callWithModelFallback(
            apiKey = apiKey,
            systemInstruction = systemPrompt,
            contentsList = turnsToSend,
            expectJson = true,
            maxTokens = 768
        )

        if (!apiResp.isSuccess) {
            return@withContext VoiceDecision(
                wake = true,
                transcript = userPrompt,
                intent = "chat",
                reply = apiResp.text
            )
        }

        val decision = parseVoiceDecision(apiResp.text, defaultWake = true).let {
            if (it.transcript.isBlank()) it.copy(transcript = userPrompt) else it
        }
        val replyText = decision.reply.ifBlank { "Done boss." }
        appendTurnToHistory(userPrompt, replyText, keepHistory = isContinuousSession)
        decision
    }

    /**
     * Direct conversational query to Gemini (used for simple questions and legacy callers).
     */
    suspend fun askGemini(userPrompt: String, isContinuousSession: Boolean = false): String = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
            ?: return@withContext "Gemini API key configure nahi hai. Settings mein ja kar apni Gemini key enter karein."

        val memoriesSnippet = memoryManager.getFormattedMemoriesForPrompt()
        val systemInstruction = """
            You are Vector, an ultra-smart, loyal, futuristic Android voice assistant.
            $memoriesSnippet
            Guidelines:
            1. Answer in natural conversational Roman Urdu (or friendly Urdu/English matching the user's language).
            2. Keep responses concise (1-3 spoken sentences), ideal for voice playback.
            3. Do not include markdown asterisks (*, **), hashes (#), or bullet symbols.
        """.trimIndent()

        if (!isContinuousSession) {
            clearConversationHistory()
        }

        val userTurn = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", userPrompt) })
            })
        }

        val turnsToSend = buildTurnsWithHistory(userTurn, includeHistory = isContinuousSession)
        val response = callWithModelFallback(
            apiKey = apiKey,
            systemInstruction = systemInstruction,
            contentsList = turnsToSend,
            expectJson = false,
            maxTokens = 1024
        )

        if (response.isSuccess) {
            appendTurnToHistory(userPrompt, response.text, keepHistory = isContinuousSession)
        }
        return@withContext response.text
    }

    suspend fun generateAttractiveWhatsAppMessage(topic: String): String = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
            ?: return@withContext "Salam! Umeed hai aap khairiyat se honge. Ek zaroori update share karni thi: $topic. Tafseelat ke liye rabta farmayein!"

        val prompt = "Write a very attractive, warm, professional, and engaging WhatsApp message (in Roman Urdu / Urdu with polite emojis) about this topic: '$topic'. Keep it appealing, concise, and ready to send directly to friends, clients, or contacts. Do not add quotes."
        val singleTurn = listOf(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", prompt) })
            })
        })

        val res = callWithModelFallback(
            apiKey = apiKey,
            systemInstruction = "You write warm, polished WhatsApp messages ready to send.",
            contentsList = singleTurn,
            expectJson = false,
            maxTokens = 512
        )
        return@withContext if (res.isSuccess) res.text
        else "Salam! Umeed hai aap khairiyat se honge. Ek zaroori update share karni thi: $topic."
    }

    /**
     * Autonomous UI Navigator: inspects live screen elements [id] and selects the exact next action
     * to accomplish multi-step goals inside YouTube, Spotify, Chrome, WhatsApp, Settings, etc.
     */
    suspend fun decideNextUiStep(
        goal: String,
        step: Int,
        uiSummary: String,
        previousActions: List<String>
    ): UiStepDecision = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
            ?: return@withContext UiStepDecision("failed", "none", -1, "", "Gemini API key missing hai.")

        val historyBlock = if (previousActions.isEmpty()) "None yet." else previousActions.joinToString("\n")
        val sysPrompt = """
            You are an Android UI Automation Agent controlling apps via Accessibility elements.
            Analyze the user's goal and current screen elements, then output ONLY valid JSON:
            {
              "status": "continue" | "done" | "failed",
              "action": "click" | "type_and_send" | "scroll_down" | "scroll_up" | "back" | "wait" | "none",
              "node_id": integer,
              "text": string,
              "reply": string
            }

            Rules:
            - Never click "Sponsored" ads unless asked. Skip bottom nav chrome if looking for content.
            - To type in a search bar: if you see an INPUT [id], use status="continue", action="type_and_send" with that node_id and query in "text". If you only see a Search icon button [id] and NO INPUT box yet, set status="continue", action="click" on that Search button first.
            - When clicking a video/song/link/switch finishes the user's final goal (e.g. clicking the matching YouTube video or toggling Wi-Fi on), set status="done", action="click", node_id=<id>, and put a short Roman Urdu confirmation in "reply". If the goal is ALREADY visibly completed on screen (e.g. video player is open or switch already shows checked=true), set status="done", action="none".
            - Keep "reply" concise (1 spoken Roman Urdu sentence).
        """.trimIndent()

        val userMsg = """
            Goal: $goal
            Step: $step
            Previous Actions:
            $historyBlock

            Current Screen UI:
            $uiSummary
        """.trimIndent()

        val turn = listOf(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", userMsg) })
            })
        })

        val res = callWithModelFallback(
            apiKey = apiKey,
            systemInstruction = sysPrompt,
            contentsList = turn,
            expectJson = true,
            maxTokens = 320
        )

        if (!res.isSuccess) {
            return@withContext UiStepDecision("failed", "none", -1, "", res.text)
        }

        val json = extractJsonObject(res.text)
        if (json != null) {
            return@withContext UiStepDecision(
                status = json.optString("status", "failed").lowercase().trim(),
                action = json.optString("action", "none").lowercase().trim(),
                nodeId = json.optInt("node_id", -1),
                text = json.optString("text", "").trim(),
                reply = json.optString("reply", "").replace("*", "").replace("#", "").trim()
            )
        }

        UiStepDecision("failed", "none", -1, "", "Screen action samajh nahi aayi.")
    }

    private fun buildStructuredSystemPrompt(requireWakeWord: Boolean): String {
        val memoriesSnippet = memoryManager.getFormattedMemoriesForPrompt()
        val chatContext = WhatsAppChatAgent.getPromptContextSummary()
        val screenContext = try {
            VectorAccessibilityService.instance?.captureScreenContext(850).orEmpty()
        } catch (_: Exception) {
            ""
        }

        val liveContextBlock = buildString {
            if (chatContext.isNotBlank()) append(chatContext).append("\n")
            if (screenContext.isNotBlank()) append(screenContext).append("\n")
        }.trim()

        return """
            You are Vector, a next-generation hands-free Android AI Operator & Companion supporting Roman Urdu, Urdu, Hindi, and English.
            $memoriesSnippet
            ${if (liveContextBlock.isNotEmpty()) "Live Device Context:\n$liveContextBlock" else ""}
            Output ONLY a valid JSON object with exactly these keys:
            {
              "wake": boolean,
              "transcript": string,
              "intent": string,
              "target": string,
              "message": string,
              "reply": string
            }

            Rules:
            1. Wake Detection:
               ${if (requireWakeWord) {
                   "- Set wake=true ONLY if the speaker addresses Vector (e.g. 'Hey Vector', 'Suno Vector', 'Vector', 'Ok Vector', 'Aye Vector', 'Hi Vector', or close phonetics like 'Hey Victor'). If unrelated background speech or silence, set wake=false, intent='none', transcript='', target='', message='', reply=''."
               } else {
                   "- Continuous live mode: set wake=true for ANY command, reply, follow-up ('haan', 'is ka reply do', 'sweet sa msg karo'), question, or request. Set wake=false ONLY if audio is silence, noise, or background TV chatter clearly NOT meant for Vector."
               }}
            2. 'transcript': Write clearly what the user said.
            3. 'intent': Choose the SINGLE best intent from:
               - "wake_only": User ONLY said the wake phrase ("Hey Vector" / "Suno Vector") with no question/command yet. Set reply="".
               - "torch_on" / "torch_off": Flashlight/torch/batti on or off.
               - "time": Asking current clock time.
               - "battery": Asking phone battery level.
               - "volume_up" / "volume_down": Raise, lower, or mute phone volume.
               - "call": Call or dial a contact/number. Put contact name or digits in 'target'.
               - "whatsapp_single": Start a WhatsApp message to a named contact (e.g. "Ali ko hy ka message bhejo", "Ahmed ko koi pyara sa msg karo"). Put contact name in 'target' and the actual ready-to-send message text in 'message' (write a warm/sweet message yourself if user asks for a sweet/nice message!).
               - "whatsapp_reply": Reply to an incoming/active WhatsApp chat OR send a requested message to the current/latest sender (e.g. "is ka reply do", "haan jawab de do", "use sweet sa message karo", "bolo main abhi busy hoon"). Put recipient name in 'target' (use Latest Incoming Chat Sender or Last Messaged Contact from Live Device Context if user says "is ko"/"us ko"), and put the COMPLETE crafted reply text in 'message'!
               - "whatsapp_bulk": Send AI WhatsApp campaign to multiple contacts. Put comma-separated names in 'target' and topic in 'message'.
               - "youtube": Search ONLY on YouTube without auto-playing (e.g. "YouTube par news search karo"). Put query in 'message'.
               - "youtube_play": Search AND actually play/open a video/song/naat on YouTube (e.g. "YouTube par Atif Aslam ka gana play karo", "Pasoori song lagao YouTube par", "koi funny video chalao"). Put search query in 'message'.
               - "open_app": ONLY launch an app with no further in-app task (e.g. "Instagram kholo").
               - "app_task": Multi-step autonomous task INSIDE an app — EITHER opening an app and completing tasks inside it ("Spotify khol kar Atif Aslam chalao", "Chrome mein cricket score search karke pehla link kholo", "Settings khol kar Wi-Fi on karo", "WhatsApp open karke Ali ko search karke hy likho") OR acting on the currently open screen ("pehli video play karo", "is par click karo", "search box mein ... likh kar chalao"). Put app name in 'target' (or "" if user means the currently open screen!) and put the complete clear goal in 'message'.
               - "media_control": Control currently playing video/music or ads ("play", "pause", "roko", "resume", "next video", "agla gana", "previous", "skip ad"). Put 'target' as one of: "play_pause", "next", "previous", "skip_ad".
               - "read_screen": User asks what is currently on screen / read the open chat or page ("screen par kya hai", "message parho").
               - "app_click": Click/tap an item, button, tab, contact, or video currently visible on screen (put exact or partial visible label in 'target').
               - "app_type": Type and send/search text inside whatever app is currently open (put exact text in 'message').
               - "app_scroll": Scroll the open app screen ('target' = "down" or "up").
               - "save_contact": Save a phone number to contacts. Put name in 'target' and digits in 'message'.
               - "call_history": Check recent calls / missed calls.
               - "vip_add": Add a contact to VIP siren alerts. Put contact name in 'target'.
               - "memory_save": User asks Vector to remember a personal fact ("yaad rakho..."). Put the clean fact in 'message'.
               - "memory_recall": User asks what Vector remembers about them.
               - "memory_clear": Clear saved memories.
               - "go_home" / "go_back" / "open_notifications" / "lock_screen": Phone navigation control.
               - "continuous_start" / "continuous_stop": Enable or disable Continuous Live Chat mode.
               - "chat": Any question, general conversation, advice, translation, or request not matching a device/app command.
            4. 'reply': Keep 'reply' concise (1-2 spoken sentences) in Roman Urdu/English matching the user, NO markdown (*, #).
        """.trimIndent()
    }

    private fun buildTurnsWithHistory(currentTurn: JSONObject, includeHistory: Boolean): List<JSONObject> {
        val list = ArrayList<JSONObject>()
        if (includeHistory) {
            synchronized(historyLock) {
                list.addAll(conversationHistory)
            }
        }
        list.add(currentTurn)
        return list
    }

    private fun appendTurnToHistory(userText: String, modelText: String, keepHistory: Boolean) {
        if (!keepHistory) return
        synchronized(historyLock) {
            conversationHistory.add(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", userText) })
                })
            })
            conversationHistory.add(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", modelText) })
                })
            })
            while (conversationHistory.size > 12) {
                conversationHistory.removeAt(0)
                if (conversationHistory.isNotEmpty()) {
                    conversationHistory.removeAt(0)
                }
            }
        }
    }

    private fun parseVoiceDecision(rawText: String, defaultWake: Boolean): VoiceDecision {
        val json = extractJsonObject(rawText)
        if (json != null) {
            val wake = json.optBoolean("wake", defaultWake)
            val transcript = json.optString("transcript", "").trim()
            val intent = json.optString("intent", if (wake) "chat" else "none").trim().lowercase()
            val target = json.optString("target", "").trim()
            val message = json.optString("message", "").trim()
            val reply = json.optString("reply", "").replace("*", "").replace("#", "").trim()
            return VoiceDecision(wake, transcript, intent, target, message, reply)
        }
        val cleaned = rawText.replace("*", "").replace("#", "").trim()
        return VoiceDecision(
            wake = defaultWake,
            transcript = "",
            intent = "chat",
            reply = cleaned
        )
    }

    private fun extractJsonObject(raw: String): JSONObject? {
        var cleaned = raw.trim()
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            val endFence = cleaned.lastIndexOf("```")
            if (endFence >= 0) {
                cleaned = cleaned.substring(0, endFence)
            }
            cleaned = cleaned.trim()
        }
        val firstBrace = cleaned.indexOf('{')
        val lastBrace = cleaned.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            val candidate = cleaned.substring(firstBrace, lastBrace + 1)
            return try {
                JSONObject(candidate)
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    fun discoverRankedModelsFromGoogle(apiKey: String): List<String> {
        var conn: HttpURLConnection? = null
        val validModels = ArrayList<String>()
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("x-goog-api-key", apiKey)
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode == 200) {
                val responseStr = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(responseStr)
                val models = json.optJSONArray("models")
                if (models != null) {
                    for (i in 0 until models.length()) {
                        val m = models.getJSONObject(i)
                        val name = m.optString("name", "").removePrefix("models/")
                        val methods = m.optJSONArray("supportedGenerationMethods")
                        val supportsGen = methods != null &&
                            (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                        if (!supportsGen || name.isBlank()) continue

                        val lower = name.lowercase()
                        val isExcluded = lower.contains("tts") ||
                            lower.contains("image") ||
                            lower.contains("imagen") ||
                            lower.contains("veo") ||
                            lower.contains("embedding") ||
                            lower.contains("aqa") ||
                            lower.contains("robotics") ||
                            lower.contains("computer-use") ||
                            lower.contains("native-audio") ||
                            lower.contains("live")

                        if (!isExcluded && lower.contains("gemini")) {
                            validModels.add(name)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dynamic model discovery failed: ${e.message}")
        } finally {
            conn?.disconnect()
        }

        // Rank models: 2.5-flash > 2.5-flash-lite > 2.0-flash > other flash > non-exp > rest
        return validModels.sortedBy { name ->
            val lower = name.lowercase()
            when {
                lower == "gemini-2.5-flash" -> 0
                lower == "gemini-2.5-flash-lite" -> 1
                lower == "gemini-2.0-flash" -> 2
                lower == "gemini-2.0-flash-lite" -> 3
                lower.contains("flash") && !lower.contains("exp") && !lower.contains("preview") -> 4
                lower.contains("flash") -> 5
                !lower.contains("exp") -> 6
                else -> 7
            }
        }
    }

    private fun callWithModelFallback(
        apiKey: String,
        systemInstruction: String,
        contentsList: List<JSONObject>,
        expectJson: Boolean,
        maxTokens: Int
    ): ApiResponse {
        var workingModel = prefs.getString(KEY_WORKING_MODEL, null)
        if (workingModel.isNullOrBlank()) {
            val discovered = discoverRankedModelsFromGoogle(apiKey).firstOrNull()
            if (!discovered.isNullOrBlank()) {
                workingModel = discovered
                setWorkingModel(discovered)
            }
        }

        val modelsToTry = LinkedHashSet<String>()
        if (!workingModel.isNullOrBlank()) modelsToTry.add(workingModel)
        modelsToTry.addAll(CANDIDATE_MODELS)

        var lastResponse = ApiResponse(false, -1, "Gemini connect nahi ho saka.")

        for (model in modelsToTry) {
            val response = executeRequest(apiKey, model, systemInstruction, contentsList, expectJson, maxTokens)
            if (response.isSuccess) {
                setWorkingModel(model)
                Log.i(TAG, "Using working Gemini model: $model")
                return response
            }
            lastResponse = response
            if (response.httpCode == 401 || response.httpCode == 403) {
                return ApiResponse(false, response.httpCode, "Gemini API key invalid ya blocked hai. Settings mein key check karein.")
            }
            Log.w(TAG, "Model $model failed [${response.httpCode}]: ${response.text}")
        }

        // Last resort: query live catalog in case user key only exposes newer/regional model names
        val liveModels = discoverRankedModelsFromGoogle(apiKey)
        for (liveModel in liveModels) {
            if (modelsToTry.contains(liveModel)) continue
            val resp = executeRequest(apiKey, liveModel, systemInstruction, contentsList, expectJson, maxTokens)
            if (resp.isSuccess) {
                setWorkingModel(liveModel)
                return resp
            }
            lastResponse = resp
        }

        return lastResponse
    }

    private data class ApiResponse(val isSuccess: Boolean, val httpCode: Int, val text: String)

    private fun executeRequest(
        apiKey: String,
        model: String,
        systemInstruction: String,
        contentsList: List<JSONObject>,
        expectJson: Boolean,
        maxTokens: Int,
        allowThinkingConfig: Boolean = true,
        allowJsonMime: Boolean = expectJson
    ): ApiResponse {
        var conn: HttpURLConnection? = null
        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", apiKey)
                doOutput = true
                connectTimeout = 15000
                readTimeout = 20000
            }

            val contentsArray = JSONArray()
            for (turn in contentsList) {
                contentsArray.put(turn)
            }

            val genConfig = JSONObject().apply {
                put("temperature", if (expectJson) 0.35 else 0.7)
                put("maxOutputTokens", maxTokens)
                if (allowJsonMime) {
                    put("responseMimeType", "application/json")
                }
                // Prevent Gemini 2.5+ thinking tokens from consuming output tokens & adding latency
                if (allowThinkingConfig && (model.contains("2.5") || model.contains("3."))) {
                    put("thinkingConfig", JSONObject().apply {
                        put("thinkingBudget", 0)
                    })
                }
            }

            val rootJson = JSONObject().apply {
                if (systemInstruction.isNotBlank()) {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemInstruction) })
                        })
                    })
                }
                put("contents", contentsArray)
                put("generationConfig", genConfig)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(rootJson.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode == HttpURLConnection.HTTP_OK) conn.inputStream else conn.errorStream
            val responseStr = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(responseStr)
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val sb = StringBuilder()
                        for (i in 0 until parts.length()) {
                            val p = parts.getJSONObject(i)
                            if (p.optBoolean("thought", false)) continue
                            val partText = p.optString("text", "")
                            if (partText.isNotEmpty()) sb.append(partText)
                        }
                        val finalText = sb.toString().trim()
                        if (finalText.isNotEmpty()) {
                            val cleanOut = if (expectJson) finalText else finalText.replace("*", "").replace("#", "").trim()
                            return ApiResponse(true, responseCode, cleanOut)
                        }
                    }
                    val finishReason = firstCandidate.optString("finishReason", "")
                    if (finishReason == "SAFETY") {
                        return ApiResponse(false, responseCode, "Safety policy ki wajah se yeh jawab nahi diya ja sakta.")
                    }
                }
                return ApiResponse(false, responseCode, "Gemini se khali jawab mila.")
            } else {
                val parsedError = try {
                    JSONObject(responseStr).optJSONObject("error")?.optString("message", "") ?: ""
                } catch (_: Exception) {
                    ""
                }

                // Auto-retry if model rejects thinkingConfig or responseMimeType with HTTP 400
                if (responseCode == 400 && (allowThinkingConfig || allowJsonMime) &&
                    (parsedError.contains("thinking", ignoreCase = true) ||
                        parsedError.contains("responseMimeType", ignoreCase = true) ||
                        parsedError.contains("JSON", ignoreCase = true))
                ) {
                    conn.disconnect()
                    conn = null
                    return executeRequest(
                        apiKey = apiKey,
                        model = model,
                        systemInstruction = systemInstruction,
                        contentsList = contentsList,
                        expectJson = expectJson,
                        maxTokens = maxTokens,
                        allowThinkingConfig = false,
                        allowJsonMime = false
                    )
                }

                val errorMsg = if (parsedError.isNotEmpty()) parsedError else "Gemini Error $responseCode"
                Log.e(TAG, "Gemini failed on $model [$responseCode]: $errorMsg")
                return ApiResponse(false, responseCode, errorMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini network exception on $model: ${e.message}")
            return ApiResponse(false, -1, "Internet connection issue: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
