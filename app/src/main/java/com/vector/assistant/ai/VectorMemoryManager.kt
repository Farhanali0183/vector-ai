package com.vector.assistant.ai

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages Long-Term Memory for Vector Assistant.
 * Remembers user preferences, personal details, routines, and facts.
 */
class VectorMemoryManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vector_ai_memory", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val keyMemories = "saved_memories_list"

    fun addMemory(fact: String): Boolean {
        val cleanFact = fact.trim()
        if (cleanFact.length < 3) return false

        val current = getAllMemories().toMutableList()
        // Prevent exact duplicates
        if (!current.any { it.equals(cleanFact, ignoreCase = true) }) {
            current.add(cleanFact)
            saveList(current)
            return true
        }
        return false
    }

    fun getAllMemories(): List<String> {
        val json = prefs.getString(keyMemories, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearAllMemories() {
        prefs.edit().remove(keyMemories).apply()
    }

    fun getFormattedMemoriesForPrompt(): String {
        val memories = getAllMemories()
        if (memories.isEmpty()) return ""

        val sb = StringBuilder("Owner's Personal Facts & Preferences:\n")
        memories.forEachIndexed { index, m ->
            sb.append("${index + 1}. $m\n")
        }
        return sb.toString()
    }

    private fun saveList(list: List<String>) {
        val json = gson.toJson(list)
        prefs.edit().putString(keyMemories, json).apply()
    }
}
