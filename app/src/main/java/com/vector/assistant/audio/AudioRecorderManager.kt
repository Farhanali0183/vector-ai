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
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

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

                while (isActive && isRunning) {
                    val readCount = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                    if (readCount > 0) {
                        val frame = shortBuffer.copyOf(readCount)
                        val energy = calculateRMS(frame)

                        // Simple energy threshold for speech activity detection (VAD)
                        if (energy > 500) {
                            isSpeechActive = true
                            silenceFrames = 0
                            for (s in frame) voiceBuffer.add(s)
                        } else if (isSpeechActive) {
                            silenceFrames++
                            for (s in frame) voiceBuffer.add(s)

                            // 15 frames of silence (~1 second) = end of utterance
                            if (silenceFrames > 15 || voiceBuffer.size > 48000) {
                                val completeUtterance = voiceBuffer.toShortArray()
                                voiceBuffer.clear()
                                isSpeechActive = false

                                // 1. Check Voice Biometrics (Speaker Recognition)
                                val isOwner = matcher.isOwner(completeUtterance)
                                val score = matcher.matchScore(completeUtterance)

                                if (isOwner) {
                                    // Verified Owner! Trigger wake word & command
                                    onWakeWordDetected(completeUtterance)
                                } else {
                                    // Ignored! Voice does not match owner
                                    onVoiceRejected(score)
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
