package com.vector.assistant.ai

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Connects Vector Assistant directly to Google Gemini Pro / Flash API.
 * Handles:
 * 1. Long-term memory-infused reasoning
 * 2. Multi-turn continuous conversation (Gemini Live Mode)
 * 3. High quality Roman Urdu / Urdu / English spoken responses
 * 4. Attractive bulk WhatsApp marketing & greeting generators
 */
class GeminiBrainClient(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vector_gemini_prefs", Context.MODE_PRIVATE)
    val memoryManager = VectorMemoryManager(context)

    // In-memory conversation history for Continuous Live Chat
    private val conversationHistory = ArrayList<JSONObject>()

    companion object {
        private const val KEY_API_KEY = "gemini_api_key"
        private const val DEFAULT_MODEL = "gemini-1.5-flash"
    }

    fun saveApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun getApiKey(): String? {
        return prefs.getString(KEY_API_KEY, null)
    }

    fun isConnected(): Boolean {
        val key = getApiKey()
        return !key.isNullOrBlank()
    }

    fun clearConversationHistory() {
        conversationHistory.clear()
    }

    /**
     * Ask Gemini any question, taking long-term memories into account.
     */
    suspend fun askGemini(userPrompt: String, isContinuousSession: Boolean = false): String = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            return@withContext "Gemini API key configure nahi hai. App settings mein ja kar apni Gemini key lagayein."
        }

        val memoriesSnippet = memoryManager.getFormattedMemoriesForPrompt()
        val systemInstruction = """
            You are Vector, an ultra-smart, loyal, futuristic Android voice assistant.
            $memoriesSnippet
            Guidelines:
            1. Answer in natural conversational Roman Urdu (or friendly Urdu/English).
            2. Keep responses brief (1-3 spoken sentences), perfect for speech audio output.
            3. Do not include markdown asterisks (*, **), bullet points, or complex symbols, speak naturally like a human.
        """.trimIndent()

        if (!isContinuousSession) {
            clearConversationHistory()
        }

        // Add user turn to conversation history
        val userTurn = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    val textWithContext = if (conversationHistory.isEmpty()) {
                        "$systemInstruction\n\nUser: $userPrompt"
                    } else {
                        userPrompt
                    }
                    put("text", textWithContext)
                })
            })
        }
        conversationHistory.add(userTurn)

        // Limit conversation history to last 10 messages to save context and speed up response
        if (conversationHistory.size > 10) {
            conversationHistory.removeAt(0)
        }

        val answer = callGeminiMultiTurnApi(apiKey, conversationHistory)

        // Add assistant response to history
        val modelTurn = JSONObject().apply {
            put("role", "model")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", answer) })
            })
        }
        conversationHistory.add(modelTurn)

        return@withContext answer
    }

    /**
     * Generates an attractive, catchy, and professional WhatsApp message
     * for contacts based on a topic or occasion.
     */
    suspend fun generateAttractiveWhatsAppMessage(topic: String): String = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            return@withContext "Salam! Umeed hai aap khairiyat se honge. Ek zaroori aur dilchasp update share karni thi: $topic. Tafseelat ke liye rabta farmayein!"
        }

        val prompt = "Write a very attractive, warm, professional, and engaging WhatsApp message (in Roman Urdu / Urdu with polite emojis) about this topic: '$topic'. Keep it appealing, concise, and ready to send directly to friends, clients, or contacts. Do not add quotes."
        return@withContext callGeminiSinglePrompt(apiKey, prompt)
    }

    private fun callGeminiSinglePrompt(apiKey: String, prompt: String): String {
        val singleTurn = ArrayList<JSONObject>().apply {
            add(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", prompt) })
                })
            })
        }
        return callGeminiMultiTurnApi(apiKey, singleTurn)
    }

    private fun callGeminiMultiTurnApi(apiKey: String, contentsList: List<JSONObject>): String {
        var conn: HttpURLConnection? = null
        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$DEFAULT_MODEL:generateContent?key=$apiKey"
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
                connectTimeout = 12000
                readTimeout = 12000
            }

            val contentsArray = JSONArray()
            for (turn in contentsList) {
                contentsArray.put(turn)
            }

            val rootJson = JSONObject().apply {
                put("contents", contentsArray)
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 250)
                })
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(rootJson.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream
            } else {
                conn.errorStream
            }

            val responseStr = BufferedReader(InputStreamReader(stream)).use { it.readText() }

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(responseStr)
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val text = parts.getJSONObject(0).optString("text", "Koi jawab nahi mila.")
                        // Clean markdown asterisks for smooth TTS
                        return text.replace("*", "").replace("#", "").trim()
                    }
                }
                return "Jawab samajh nahi aaya."
            } else {
                return "Gemini API Error ($responseCode). API key check karein."
            }
        } catch (e: Exception) {
            return "Internet ya Gemini se connect karne mein masla: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }
}
