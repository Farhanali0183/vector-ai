package com.vector.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Manages low-latency background audio streaming and Voice Activity Detection (VAD).
 */
class AudioRecorderManager(
    private val matcher: VoiceprintMatcher,
    private val onWakeWordDetected: (ShortArray) -> Unit,
    private val onVoiceRejected: (score: Float) -> Unit
) {

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        .coerceAtLeast(sampleRate)

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    var isRunning = false
        private set

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (isRunning) return

        try {
            val audioSources = intArrayOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            )
            for (src in audioSources) {
                try {
                    val record = AudioRecord(src, sampleRate, channelConfig, audioFormat, bufferSize)
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        audioRecord = record
                        break
                    } else {
                        record.release()
                    }
                } catch (e: Exception) {
                    // Try next source
                }
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioRecorderManager", "AudioRecord initialization failed!")
                return
            }

            audioRecord?.startRecording()
            isRunning = true

            recordingJob = scope.launch {
                val shortBuffer = ShortArray(1024)
                val voiceBuffer = ArrayList<Short>()
                var isSpeechActive = false
                var silenceFrames = 0
                var noiseFloor = 180.0

                while (isActive && isRunning) {
                    val readCount = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                    if (readCount > 0) {
                        val frame = shortBuffer.copyOf(readCount)
                        val energy = calculateRMS(frame)

                        // Dynamically adapt to ambient room noise
                        if (!isSpeechActive) {
                            noiseFloor = (noiseFloor * 0.96) + (energy * 0.04)
                        }

                        // Adaptive speech threshold: at least 250, or 1.6x ambient noise
                        val speechThreshold = maxOf(250.0, noiseFloor * 1.6)

                        if (energy > speechThreshold) {
                            isSpeechActive = true
                            silenceFrames = 0
                            for (s in frame) voiceBuffer.add(s)
                        } else if (isSpeechActive) {
                            silenceFrames++
                            for (s in frame) voiceBuffer.add(s)

                            // 8 frames of silence (~500ms) = quick wake up without long delay
                            if (silenceFrames >= 8 || voiceBuffer.size > 40000) {
                                val completeUtterance = voiceBuffer.toShortArray()
                                voiceBuffer.clear()
                                isSpeechActive = false
                                silenceFrames = 0

                                // Minimum 0.35s of voice to avoid accidental clicks
                                if (completeUtterance.size >= 5600) {
                                    val isOwner = matcher.isOwner(completeUtterance)
                                    val score = matcher.matchScore(completeUtterance)

                                    if (isOwner) {
                                        onWakeWordDetected(completeUtterance)
                                    } else {
                                        onVoiceRejected(score)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Error starting audio listener: ${e.message}")
            stopListening()
        }
    }

    fun stopListening() {
        isRunning = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore teardown errors
        }
        audioRecord = null
    }

    private fun calculateRMS(buffer: ShortArray): Double {
        var sum = 0.0
        for (s in buffer) {
            sum += abs(s.toInt())
        }
        return sum / buffer.size
    }
}
