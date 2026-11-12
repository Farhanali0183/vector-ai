package com.vector.assistant.audio

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * VoiceprintMatcher handles BOTH:
 * 1. Personalized Wake-Phrase Detection (MFCC + Subsequence DTW against enrolled "Hey Vector" samples)
 * 2. Speaker Verification / Biometrics (Cosine similarity of MFCC speaker timbre)
 */
class VoiceprintMatcher(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vector_voiceprint_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    var verificationThreshold: Float = 0.62f
    var wakePhraseThreshold: Float = 0.60f

    companion object {
        private const val KEY_ENROLLED_COUNT = "enrolled_samples_count"
        private const val KEY_SPEAKER_VECTOR = "voice_profile_vector_v2"
        private const val KEY_WAKE_TEMPLATES = "wake_templates_mfcc_v2"
        private const val KEY_BIOMETRICS_ENFORCED = "biometrics_enforced"

        private const val SAMPLE_RATE = 16000
        private const val FFT_SIZE = 512
        private const val HOP_SIZE = 256
        private const val NUM_MEL_FILTERS = 16
        private const val NUM_MFCC = 12
        private const val MAX_TEMPLATES = 3
    }

    fun isBiometricsEnforced(): Boolean = prefs.getBoolean(KEY_BIOMETRICS_ENFORCED, false)

    fun setBiometricsEnforced(enforced: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRICS_ENFORCED, enforced).apply()
    }

    fun isProfileEnrolled(): Boolean = getEnrolledCount() >= MAX_TEMPLATES

    fun getEnrolledCount(): Int = prefs.getInt(KEY_ENROLLED_COUNT, 0)

    fun enrollSample(audioData: ShortArray): Int {
        val trimmed = trimSilence(audioData)
        val frames = extractMfccFrames(trimmed)
        val speakerVec = computeSpeakerCentroid(frames)

        val currentCount = getEnrolledCount().coerceAtMost(MAX_TEMPLATES - 1)
        val existingSpeakerJson = prefs.getString(KEY_SPEAKER_VECTOR, null)
        val existingSpeaker = existingSpeakerJson?.let {
            try { gson.fromJson(it, FloatArray::class.java) } catch (_: Exception) { null }
        }

        val updatedSpeaker = if (existingSpeaker == null || currentCount == 0 || existingSpeaker.size != speakerVec.size) {
            speakerVec
        } else {
            FloatArray(speakerVec.size) { i ->
                (existingSpeaker[i] * currentCount + speakerVec[i]) / (currentCount + 1)
            }.also { normalizeInPlace(it) }
        }

        val existingTemplates = loadWakeTemplates().toMutableList()
        if (currentCount == 0) existingTemplates.clear()
        if (frames.isNotEmpty()) {
            // Downsample long enrollment recordings to at most 110 frames (~1.75s)
            existingTemplates.add(frames.take(110))
        }

        val newCount = (getEnrolledCount() + 1).coerceAtMost(MAX_TEMPLATES)
        prefs.edit()
            .putInt(KEY_ENROLLED_COUNT, newCount)
            .putString(KEY_SPEAKER_VECTOR, gson.toJson(updatedSpeaker))
            .putString(KEY_WAKE_TEMPLATES, gson.toJson(existingTemplates))
            .apply()

        return newCount
    }

    fun resetProfile() {
        val keepEnforced = isBiometricsEnforced()
        prefs.edit().clear().putBoolean(KEY_BIOMETRICS_ENFORCED, keepEnforced).apply()
    }

    fun matchScore(incomingAudio: ShortArray): Float {
        val profileJson = prefs.getString(KEY_SPEAKER_VECTOR, null) ?: return 1.0f
        val ownerVector = try {
            gson.fromJson(profileJson, FloatArray::class.java)
        } catch (_: Exception) {
            null
        } ?: return 1.0f

        val frames = extractMfccFrames(trimSilence(incomingAudio))
        if (frames.isEmpty()) return 0.0f
        val incomingVector = computeSpeakerCentroid(frames)
        if (ownerVector.size != incomingVector.size) return 1.0f

        return computeCosineSimilarity(ownerVector, incomingVector)
    }

    fun isOwner(incomingAudio: ShortArray): Boolean {
        if (!isBiometricsEnforced() || !isProfileEnrolled()) return true
        return matchScore(incomingAudio) >= verificationThreshold
    }

    /**
     * Evaluates how well the start/prefix of incomingAudio matches the user's enrolled wake phrase
     * ("Hey Vector" / "Suno Vector") using Subsequence Dynamic Time Warping over MFCC frames.
     */
    fun wakePhraseMatchScore(incomingAudio: ShortArray): Float {
        val templates = loadWakeTemplates()
        if (templates.isEmpty()) return 0.0f

        val trimmed = trimSilence(incomingAudio)
        val queryFrames = extractMfccFrames(trimmed).take(160) // Look within first ~2.5s
        if (queryFrames.size < 10) return 0.0f

        var bestScore = 0.0f
        for (template in templates) {
            if (template.size < 8) continue
            val avgDist = subsequenceDtwDistance(template, queryFrames)
            val sim = exp(-avgDist * 0.50f).coerceIn(0.0f, 1.0f)
            if (sim > bestScore) bestScore = sim
        }
        return bestScore
    }

    fun isWakePhraseMatch(incomingAudio: ShortArray): Boolean {
        if (!isProfileEnrolled()) return false
        return wakePhraseMatchScore(incomingAudio) >= wakePhraseThreshold
    }

    private fun loadWakeTemplates(): List<List<FloatArray>> {
        val json = prefs.getString(KEY_WAKE_TEMPLATES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<List<FloatArray>>>() {}.type
            gson.fromJson<List<List<FloatArray>>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun trimSilence(pcm: ShortArray): ShortArray {
        if (pcm.size < 800) return pcm
        val block = 256
        var firstActive = 0
        var lastActive = pcm.size

        var idx = 0
        while (idx + block <= pcm.size) {
            val m = AudioUtils.analyzeFrame(pcm.copyOfRange(idx, idx + block), block)
            if (m.speechBandRms > 100.0) {
                firstActive = (idx - block).coerceAtLeast(0)
                break
            }
            idx += block
        }

        idx = pcm.size - block
        while (idx >= firstActive) {
            val m = AudioUtils.analyzeFrame(pcm.copyOfRange(idx, idx + block), block)
            if (m.speechBandRms > 100.0) {
                lastActive = (idx + block * 2).coerceAtMost(pcm.size)
                break
            }
            idx -= block
        }

        if (lastActive - firstActive < 1600) return pcm
        return pcm.copyOfRange(firstActive, lastActive)
    }

    private fun extractMfccFrames(pcm: ShortArray): List<FloatArray> {
        if (pcm.size < FFT_SIZE) return emptyList()
        val frames = ArrayList<FloatArray>()

        // Pre-compute Hamming window
        val window = FloatArray(FFT_SIZE) { i ->
            (0.54 - 0.46 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat()
        }

        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)
        val mag = DoubleArray(FFT_SIZE / 2 + 1)

        var pos = 0
        while (pos + FFT_SIZE <= pcm.size) {
            // Pre-emphasis + Hamming window
            for (i in 0 until FFT_SIZE) {
                val curr = pcm[pos + i].toDouble() / 32768.0
                val prev = if (pos + i > 0) pcm[pos + i - 1].toDouble() / 32768.0 else 0.0
                val preEmph = curr - 0.97 * prev
                real[i] = preEmph * window[i]
                imag[i] = 0.0
            }

            fft(real, imag)

            for (k in 0..FFT_SIZE / 2) {
                mag[k] = real[k] * real[k] + imag[k] * imag[k]
            }

            val melLog = computeMelLogEnergies(mag)
            val mfcc = dct(melLog)
            frames.add(mfcc)
            pos += HOP_SIZE
        }

        if (frames.isEmpty()) return frames

        // Cepstral Mean Normalization (CMN) across utterance for channel/mic invariance
        val mean = FloatArray(NUM_MFCC)
        for (f in frames) {
            for (c in 0 until NUM_MFCC) mean[c] += f[c]
        }
        val frameCount = frames.size.toFloat()
        for (c in 0 until NUM_MFCC) mean[c] = mean[c] / frameCount

        for (f in frames) {
            for (c in 0 until NUM_MFCC) f[c] -= mean[c]
            normalizeInPlace(f)
        }
        return frames
    }

    private fun computeSpeakerCentroid(frames: List<FloatArray>): FloatArray {
        val dim = NUM_MFCC * 2
        val vec = FloatArray(dim)
        if (frames.isEmpty()) return vec

        val mean = FloatArray(NUM_MFCC)
        for (f in frames) {
            for (i in 0 until NUM_MFCC) mean[i] += f[i]
        }
        val frameCount = frames.size.toFloat()
        for (i in 0 until NUM_MFCC) mean[i] = mean[i] / frameCount

        val std = FloatArray(NUM_MFCC)
        for (f in frames) {
            for (i in 0 until NUM_MFCC) {
                val d = f[i] - mean[i]
                std[i] += d * d
            }
        }
        for (i in 0 until NUM_MFCC) {
            vec[i] = mean[i]
            vec[NUM_MFCC + i] = sqrt(std[i] / frames.size)
        }
        normalizeInPlace(vec)
        return vec
    }

    /**
     * Subsequence DTW: finds best alignment of template (len M) inside prefix of query (len N).
     */
    private fun subsequenceDtwDistance(template: List<FloatArray>, query: List<FloatArray>): Float {
        val m = template.size
        val n = query.size
        if (m == 0 || n == 0) return Float.MAX_VALUE

        var prev = FloatArray(n) { j ->
            // Allow matching to start anywhere in the first 35% of the utterance
            if (j <= n / 3) euclideanDist(template[0], query[j]) else 1e6f
        }
        var curr = FloatArray(n)

        for (i in 1 until m) {
            curr[0] = prev[0] + euclideanDist(template[i], query[0])
            for (j in 1 until n) {
                val cost = euclideanDist(template[i], query[j])
                val bestPrev = minOf(prev[j - 1], minOf(prev[j], curr[j - 1]))
                curr[j] = cost + bestPrev
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }

        var minTotal = Float.MAX_VALUE
        for (j in (m / 2).coerceAtMost(n - 1) until n) {
            if (prev[j] < minTotal) minTotal = prev[j]
        }
        return minTotal / m.toFloat()
    }

    private fun euclideanDist(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum)
    }

    private fun computeMelLogEnergies(powerSpec: DoubleArray): DoubleArray {
        val lowMel = hzToMel(100.0)
        val highMel = hzToMel(6800.0)
        val melPoints = DoubleArray(NUM_MEL_FILTERS + 2) { i ->
            lowMel + i * (highMel - lowMel) / (NUM_MEL_FILTERS + 1)
        }
        val binPoints = IntArray(NUM_MEL_FILTERS + 2) { i ->
            val hz = melToHz(melPoints[i])
            ((FFT_SIZE + 1) * hz / SAMPLE_RATE).toInt().coerceIn(0, FFT_SIZE / 2)
        }

        val energies = DoubleArray(NUM_MEL_FILTERS)
        for (m in 1..NUM_MEL_FILTERS) {
            var sum = 1e-10
            val left = binPoints[m - 1]
            val center = binPoints[m]
            val right = binPoints[m + 1]

            if (center > left) {
                for (k in left until center) {
                    val weight = (k - left).toDouble() / (center - left)
                    sum += powerSpec[k] * weight
                }
            }
            if (right > center) {
                for (k in center..right) {
                    val weight = (right - k).toDouble() / (right - center)
                    sum += powerSpec[k] * weight
                }
            }
            energies[m - 1] = ln(sum)
        }
        return energies
    }

    private fun dct(melLog: DoubleArray): FloatArray {
        val out = FloatArray(NUM_MFCC)
        for (k in 0 until NUM_MFCC) {
            val coeffIdx = k + 1 // Skip c0 energy
            var sum = 0.0
            for (n in melLog.indices) {
                sum += melLog[n] * cos(PI * coeffIdx * (n + 0.5) / melLog.size)
            }
            out[k] = sum.toFloat()
        }
        return out
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wLenR = cos(ang)
            val wLenI = sin(ang)
            var i = 0
            while (i < n) {
                var wR = 1.0
                var wI = 0.0
                for (k in 0 until len / 2) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + len / 2] * wR - imag[i + k + len / 2] * wI
                    val vI = real[i + k + len / 2] * wI + imag[i + k + len / 2] * wR
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + len / 2] = uR - vR
                    imag[i + k + len / 2] = uI - vI
                    val nextWR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun normalizeInPlace(vec: FloatArray) {
        var norm = 0.0
        for (v in vec) norm += v * v
        val mag = sqrt(norm).toFloat()
        if (mag > 1e-6f) {
            for (i in vec.indices) vec[i] /= mag
        }
    }

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
        return (dot / (sqrt(normA) * sqrt(normB))).coerceIn(0.0f, 1.0f)
    }
}
