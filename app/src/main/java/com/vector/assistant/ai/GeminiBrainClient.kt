package com.vector.assistant.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
 * Connects Vector Assistant directly to Google Gemini API.
 * Features:
 * 1. Automatic Model Fallback (Handles 404 model retirement/region differences gracefully)
 * 2. Long-term memory-infused reasoning
 * 3. Multi-turn continuous conversation (Gemini Live Mode)
 * 4. High quality Roman Urdu / Urdu / English spoken responses
 */
class GeminiBrainClient(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vector_gemini_prefs", Context.MODE_PRIVATE)
    val memoryManager = VectorMemoryManager(context)

    // In-memory conversation history for Continuous Live Chat
    private val conversationHistory = ArrayList<JSONObject>()

    companion object {
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_WORKING_MODEL = "gemini_working_model"
        private const val TAG = "GeminiBrainClient"

        // Candidate models in priority order to prevent 404 Not Found
        val CANDIDATE_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash-latest",
            "gemini-1.5-flash",
            "gemini-pro"
        )
    }

    fun saveApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun getApiKey(): String? {
        return prefs.getString(KEY_API_KEY, null)
    }

    fun getWorkingModel(): String {
        return prefs.getString(KEY_WORKING_MODEL, CANDIDATE_MODELS[0]) ?: CANDIDATE_MODELS[0]
    }

    fun setWorkingModel(model: String) {
        prefs.edit().putString(KEY_WORKING_MODEL, model).apply()
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
            return@withContext "Gemini API key configure nahi hai. App settings mein ja kar apni Gemini key enter karein."
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

        // Limit conversation history to last 10 messages
        if (conversationHistory.size > 10) {
            conversationHistory.removeAt(0)
        }

        val answer = callWithModelFallback(apiKey, conversationHistory)

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
            return@withContext "Salam! Umeed hai aap khairiyat se honge. Ek zaroori update share karni thi: $topic. Tafseelat ke liye rabta farmayein!"
        }

        val prompt = "Write a very attractive, warm, professional, and engaging WhatsApp message (in Roman Urdu / Urdu with polite emojis) about this topic: '$topic'. Keep it appealing, concise, and ready to send directly to friends, clients, or contacts. Do not add quotes."
        val singleTurn = listOf(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", prompt) })
            })
        })
        return@withContext callWithModelFallback(apiKey, singleTurn)
    }

    private fun callWithModelFallback(apiKey: String, contentsList: List<JSONObject>): String {
        val workingModel = getWorkingModel()
        val modelsToTry = mutableListOf(workingModel)
        for (m in CANDIDATE_MODELS) {
            if (!modelsToTry.contains(m)) modelsToTry.add(m)
        }

        var lastError = "Gemini connect nahi ho saka."

        for (model in modelsToTry) {
            val response = executeRequest(apiKey, model, contentsList)
            if (response.isSuccess) {
                if (workingModel != model) {
                    setWorkingModel(model)
                    Log.i(TAG, "Switched working Gemini model to: $model")
                }
                return response.text
            } else {
                lastError = response.text
                // If model was not found (404), try next candidate model!
                if (response.httpCode == 404) {
                    Log.w(TAG, "Model $model returned 404. Trying next fallback model...")
                    continue
                } else {
                    // 400 (Bad Key), 403 (Quota), etc. - do not loop further
                    break
                }
            }
        }

        return lastError
    }

    private data class ApiResponse(val isSuccess: Boolean, val httpCode: Int, val text: String)

    private fun executeRequest(apiKey: String, model: String, contentsList: List<JSONObject>): ApiResponse {
        var conn: HttpURLConnection? = null
        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
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

            val responseStr = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(responseStr)
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val text = parts.getJSONObject(0).optString("text", "Koi jawab nahi mila.")
                        return ApiResponse(true, responseCode, text.replace("*", "").replace("#", "").trim())
                    }
                }
                return ApiResponse(false, responseCode, "Jawab samajh nahi aaya.")
            } else {
                val parsedError = try {
                    JSONObject(responseStr).optJSONObject("error")?.optString("message", "") ?: ""
                } catch (e: Exception) {
                    ""
                }
                val errorMsg = if (parsedError.isNotEmpty()) parsedError else "Gemini Error $responseCode"
                Log.e(TAG, "Gemini Request failed on $model [$responseCode]: $errorMsg")
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
