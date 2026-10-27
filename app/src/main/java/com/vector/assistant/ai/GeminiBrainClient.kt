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
 * Handles high-level reasoning, intelligent Urdu/English chatting,
 * and generating attractive customized WhatsApp marketing or greeting messages!
 */
class GeminiBrainClient(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vector_gemini_prefs", Context.MODE_PRIVATE)

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

    /**
     * Ask Gemini any general question or topic.
     * Vector gets a natural, concise spoken answer.
     */
    suspend fun askGemini(userPrompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            return@withContext "Gemini API key configure nahi hai. App settings mein ja kar apni Gemini Pro key enter karein."
        }

        val systemInstruction = "You are Vector, a smart, loyal, futuristic Android voice assistant. Answer in Roman Urdu or friendly Urdu/English concisely in 2-3 spoken sentences so it sounds natural when read aloud."
        return@withContext callGeminiApi(apiKey, "$systemInstruction\n\nUser asked: $userPrompt")
    }

    /**
     * Generates an attractive, catchy, and professional WhatsApp message
     * for contacts based on a topic or occasion.
     */
    suspend fun generateAttractiveWhatsAppMessage(topic: String): String = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            // High-quality local template fallback if API key is not yet set
            return@withContext "Salam! Umeed hai aap khairiyat se honge. Ek zaroori aur dilchasp update share karni thi: $topic. Tafseelat ke liye rabta farmayein!"
        }

        val prompt = "Write a very attractive, warm, professional, and engaging WhatsApp message (in Roman Urdu / Urdu with polite emojis) about this topic: '$topic'. Keep it appealing, concise, and ready to send directly to friends, clients, or contacts. Do not add quotes."
        return@withContext callGeminiApi(apiKey, prompt)
    }

    private fun callGeminiApi(apiKey: String, prompt: String): String {
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

            // Construct JSON payload
            val rootJson = JSONObject().apply {
                val contents = JSONArray().apply {
                    val partObj = JSONObject().apply {
                        val parts = JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        }
                        put("parts", parts)
                    }
                    put(partObj)
                }
                put("contents", contents)
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
                        return parts.getJSONObject(0).optString("text", "Koi jawab nahi mila.")
                    }
                }
                return "Gemini se jawab parse nahi ho saka."
            } else {
                return "Gemini API Error ($responseCode). Baraye meharbani apni API key check karein."
            }
        } catch (e: Exception) {
            return "Internet ya Gemini se connect karne mein masla: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }
}
