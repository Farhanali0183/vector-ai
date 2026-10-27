package com.vector.assistant.audio

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * VoiceprintMatcher handles Speaker Verification (Biometrics).
 * It extracts acoustic frequency features from audio PCM frames and
 * compares them against the enrolled owner's voiceprint using Cosine Similarity.
 */
class VoiceprintMatcher(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vector_voiceprint_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Minimum similarity score required to verify it's the owner (0.0 to 1.0)
    var verificationThreshold: Float = 0.78f

    /**
     * Checks if the user has already enrolled their voice profile.
     */
    fun isProfileEnrolled(): Boolean {
        val enrolledCount = prefs.getInt("enrolled_samples_count", 0)
        return enrolledCount >= 3
    }

    fun getEnrolledCount(): Int {
        return prefs.getInt("enrolled_samples_count", 0)
    }

    /**
     * Enrolls a new voice sample. Keeps an average centroid of acoustic features.
     */
    fun enrollSample(audioData: ShortArray): Int {
        val features = extractFeatures(audioData)
        val currentCount = prefs.getInt("enrolled_samples_count", 0)
        val profileJson = prefs.getString("voice_profile_vector", null)

        val existing = if (profileJson != null) gson.fromJson(profileJson, FloatArray::class.java) else null
        val updatedVector: FloatArray = if (existing == null || currentCount == 0) {
            features
        } else {
            FloatArray(features.size) { i ->
                // Exponential moving average to combine voice samples
                (existing[i] * currentCount + features[i]) / (currentCount + 1)
            }
        }

        prefs.edit()
            .putInt("enrolled_samples_count", currentCount + 1)
            .putString("voice_profile_vector", gson.toJson(updatedVector))
            .apply()

        return currentCount + 1
    }

    /**
     * Resets the enrolled voice profile.
     */
    fun resetProfile() {
        prefs.edit().clear().apply()
    }

    /**
     * Matches the incoming voice buffer against the saved owner profile.
     * Returns a similarity score between 0.0f and 1.0f.
     */
    fun matchScore(incomingAudio: ShortArray): Float {
        val profileJson = prefs.getString("voice_profile_vector", null) ?: return 1.0f // If not enrolled, allow open testing
        val ownerVector = gson.fromJson(profileJson, FloatArray::class.java) ?: return 1.0f
        val incomingVector = extractFeatures(incomingAudio)

        return computeCosineSimilarity(ownerVector, incomingVector)
    }

    /**
     * Returns true if incoming voice matches the owner above threshold.
     */
    fun isOwner(incomingAudio: ShortArray): Boolean {
        if (!isProfileEnrolled()) return true // Fallback to allow commands if training not done yet
        val score = matchScore(incomingAudio)
        return score >= verificationThreshold
    }

    /**
     * Extracts acoustic frequency distribution, energy profile, and spectral characteristics.
     */
    private fun extractFeatures(pcm: ShortArray): FloatArray {
        val featureDimension = 32
        val features = FloatArray(featureDimension)

        if (pcm.isEmpty()) return features

        var totalEnergy = 0.0
        var zeroCrossings = 0

        val chunkSize = pcm.size / (featureDimension / 2)
        for (i in 0 until (featureDimension / 2)) {
            val start = i * chunkSize
            val end = (start + chunkSize).coerceAtMost(pcm.size)
            var bandEnergy = 0.0

            for (j in start until end) {
                val sample = pcm[j].toDouble() / 32768.0
                bandEnergy += sample * sample

                if (j > 0 && ((pcm[j] >= 0 && pcm[j - 1] < 0) || (pcm[j] < 0 && pcm[j - 1] >= 0))) {
                    zeroCrossings++
                }
            }
            features[i] = bandEnergy.toFloat()
            totalEnergy += bandEnergy
        }

        // Spectral shape & variance representation
        for (i in (featureDimension / 2) until featureDimension) {
            val refIdx = i - (featureDimension / 2)
            features[i] = (features[refIdx] / (totalEnergy.toFloat() + 1e-6f))
        }

        features[0] = (zeroCrossings.toFloat() / pcm.size.toFloat()) * 10f

        // Normalize vector to unit length
        var norm = 0.0
        for (f in features) norm += f * f
        val mag = sqrt(norm).toFloat()
        if (mag > 0) {
            for (i in features.indices) features[i] /= mag
        }

        return features
    }

    /**
     * Standard Cosine Similarity formula: (A . B) / (||A|| * ||B||)
     */
    private fun computeCosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        var dot = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        val len = minOf(vecA.size, vecB.size)
        for (i in 0 until len) {
            dot += vecA[i] * vecB[i]
            normA += vecA[i] * vecA[i]
            normB += vecB[i] * vecB[i]
        }

        if (normA == 0f || normB == 0f) return 0.0f
        val similarity = (dot / (sqrt(normA) * sqrt(normB))).coerceIn(0.0f, 1.0f)
        return similarity
    }
}
