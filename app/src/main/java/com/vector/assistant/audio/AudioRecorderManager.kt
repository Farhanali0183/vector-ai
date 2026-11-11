package com.vector.assistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.vector.assistant.model.VectorSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque

/**
 * Manages continuous low-latency 16kHz PCM audio streaming with:
 * - Circular 1.0s pre-roll buffer so initial wake words ("Hey", "Suno") are never clipped
 * - Speech-band (300-3500Hz) adaptive VAD + ZCR filtering to ignore room hum/taps
 * - Hardware AcousticEchoCanceler & NoiseSuppressor when available
 * - Seamless switching between WAKE_LISTEN and DIRECT_COMMAND without tearing down AudioRecord
 * - Automatic recovery from phone calls or OS mic preemption
 */
class AudioRecorderManager(
    private val context: Context,
    private val onUtteranceCaptured: (pcm: ShortArray, isDirectCommand: Boolean) -> Unit,
    private val onCommandTimeout: () -> Unit,
    private val onAudioLevel: (Float) -> Unit,
    private val onRecorderStatus: (String) -> Unit
) {

    enum class CaptureMode {
        WAKE_LISTEN,
        DIRECT_COMMAND
    }

    private val settings = VectorSettings(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val frameSize = 1024 // 64ms per frame
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        .coerceAtLeast(sampleRate * 2) // 1 second buffer

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null

    @Volatile
    var isRunning = false
        private set

    @Volatile
    private var isInputMuted = false

    @Volatile
    private var muteUntilMs = 0L

    @Volatile
    private var captureMode = CaptureMode.WAKE_LISTEN

    @Volatile
    private var directCommandDeadlineMs = 0L

    @Volatile
    private var resetBufferFlag = false

    fun setCaptureMode(mode: CaptureMode, timeoutMs: Long = 7500L) {
        captureMode = mode
        resetBufferFlag = true
        if (mode == CaptureMode.DIRECT_COMMAND) {
            directCommandDeadlineMs = System.currentTimeMillis() + timeoutMs
        }
    }

    fun setInputMuted(muted: Boolean, tailGuardMs: Long = 350L) {
        if (muted) {
            isInputMuted = true
            resetBufferFlag = true
        } else {
            muteUntilMs = System.currentTimeMillis() + tailGuardMs
            isInputMuted = false
            resetBufferFlag = true
            if (captureMode == CaptureMode.DIRECT_COMMAND) {
                directCommandDeadlineMs = System.currentTimeMillis() + 7500L
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening(): Boolean {
        if (isRunning && recordingJob?.isActive == true) return true

        isRunning = true
        recordingJob = scope.launch {
            var retryDelayMs = 600L

            while (isActive && isRunning) {
                // Respect active phone / VoIP calls
                val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
                if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                    onAudioLevel(0f)
                    delay(1000L)
                    continue
                }

                val record = createAudioRecord()
                if (record == null) {
                    onRecorderStatus("Mic busy or permission missing; retrying...")
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(4000L)
                    continue
                }

                retryDelayMs = 600L
                var aec: AcousticEchoCanceler? = null
                var ns: NoiseSuppressor? = null

                try {
                    try {
                        if (AcousticEchoCanceler.isAvailable()) {
                            aec = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                        }
                        if (NoiseSuppressor.isAvailable()) {
                            ns = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
                        }
                    } catch (_: Exception) {
                    }

                    record.startRecording()
                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        throw IllegalStateException("AudioRecord failed to enter RECORDSTATE_RECORDING")
                    }

                    runCaptureLoop(record)
                } catch (e: Exception) {
                    Log.e(TAG, "AudioRecord loop error: ${e.message}")
                    onRecorderStatus("Mic re-initializing (${e.message ?: "recovering"})...")
                } finally {
                    onAudioLevel(0f)
                    try { aec?.release() } catch (_: Exception) {}
                    try { ns?.release() } catch (_: Exception) {}
                    try {
                        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            record.stop()
                        }
                    } catch (_: Exception) {}
                    try { record.release() } catch (_: Exception) {}
                }

                if (isActive && isRunning) {
                    delay(450L)
                }
            }
        }
        return true
    }

    private suspend fun runCaptureLoop(record: AudioRecord) {
        val shortBuffer = ShortArray(frameSize)
        val preRollQueue = ArrayDeque<ShortArray>(18)
        val preRollFramesLimit = 14 // ~900ms pre-roll
        val voiceBuffer = ArrayList<Short>(sampleRate * 4)

        var isSpeechActive = false
        var activeSpeechFrames = 0
        var consecutiveOnsetFrames = 0
        var silenceFrames = 0
        var zeroPeakFrames = 0
        var noiseFloor = 75.0
        var lastLevelEmitMs = 0L

        while (scope.isActive && isRunning) {
            // Yield during active calls
            val audioMode = audioManager?.mode ?: AudioManager.MODE_NORMAL
            if (audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION) {
                break
            }

            if (resetBufferFlag) {
                resetBufferFlag = false
                isSpeechActive = false
                activeSpeechFrames = 0
                consecutiveOnsetFrames = 0
                silenceFrames = 0
                voiceBuffer.clear()
                preRollQueue.clear()
            }

            val readCount = record.read(shortBuffer, 0, shortBuffer.size)
            if (readCount < 0) {
                throw IllegalStateException("AudioRecord read error code: $readCount")
            }
            if (readCount == 0) {
                delay(20L)
                continue
            }

            val now = System.currentTimeMillis()
            val frame = shortBuffer.copyOf(readCount)
            val metrics = AudioUtils.analyzeFrame(frame, readCount)

            // Detect OS-level mic silence/preemption (e.g. >8s of pure digital zeros)
            if (metrics.peak == 0) {
                zeroPeakFrames++
                if (zeroPeakFrames > 140) {
                    zeroPeakFrames = 0
                    throw IllegalStateException("Microphone stream muted by system; reconnecting")
                }
            } else {
                zeroPeakFrames = 0
            }

            // While TTS is speaking or post-TTS room-echo tail is active, discard frames safely
            if (isInputMuted || now < muteUntilMs) {
                isSpeechActive = false
                activeSpeechFrames = 0
                consecutiveOnsetFrames = 0
                silenceFrames = 0
                voiceBuffer.clear()
                preRollQueue.clear()
                if (now - lastLevelEmitMs > 60L) {
                    lastLevelEmitMs = now
                    onAudioLevel(0f)
                }
                continue
            }

            // Check timeout if waiting for a direct follow-up command
            if (captureMode == CaptureMode.DIRECT_COMMAND && !isSpeechActive && now > directCommandDeadlineMs) {
                captureMode = CaptureMode.WAKE_LISTEN
                onAudioLevel(0f)
                onCommandTimeout()
                continue
            }

            val (minThresh, snrMult) = when (settings.micSensitivityLevel) {
                0 -> Pair(85.0, 1.85)
                2 -> Pair(185.0, 2.55)
                else -> Pair(125.0, 2.20)
            }

            // Asymmetric noise floor adaptation: drops fast in quiet, rises slowly only on steady background
            if (!isSpeechActive) {
                if (metrics.speechBandRms < noiseFloor) {
                    noiseFloor = noiseFloor * 0.85 + metrics.speechBandRms * 0.15
                } else if (metrics.speechBandRms < noiseFloor * 1.6) {
                    noiseFloor = noiseFloor * 0.98 + metrics.speechBandRms * 0.02
                }
                noiseFloor = noiseFloor.coerceIn(30.0, 650.0)
            }

            val speechThreshold = maxOf(minThresh, noiseFloor * snrMult)
            val isSpeechLikeFrame = metrics.speechBandRms > speechThreshold && metrics.zcr in 0.012..0.58

            if (now - lastLevelEmitMs > 45L) {
                lastLevelEmitMs = now
                val normLevel = ((metrics.speechBandRms - noiseFloor * 0.8) / (speechThreshold * 2.5))
                    .toFloat()
                    .coerceIn(0f, 1f)
                onAudioLevel(normLevel)
            }

            if (!isSpeechActive) {
                if (preRollQueue.size >= preRollFramesLimit) {
                    preRollQueue.removeFirst()
                }
                preRollQueue.addLast(frame)

                if (isSpeechLikeFrame) {
                    consecutiveOnsetFrames++
                    // Require 2 consecutive speech frames (128ms) or 1 strong frame in DIRECT_COMMAND
                    val needed = if (captureMode == CaptureMode.DIRECT_COMMAND) 1 else 2
                    if (consecutiveOnsetFrames >= needed) {
                        isSpeechActive = true
                        activeSpeechFrames = consecutiveOnsetFrames
                        consecutiveOnsetFrames = 0
                        silenceFrames = 0
                        voiceBuffer.clear()
                        for (pre in preRollQueue) {
                            for (s in pre) voiceBuffer.add(s)
                        }
                        preRollQueue.clear()
                    }
                } else {
                    consecutiveOnsetFrames = 0
                }
            } else {
                for (s in frame) voiceBuffer.add(s)

                if (metrics.speechBandRms > speechThreshold * 0.72 && metrics.zcr in 0.01..0.60) {
                    silenceFrames = 0
                    activeSpeechFrames++
                } else {
                    silenceFrames++
                }

                val isDirect = (captureMode == CaptureMode.DIRECT_COMMAND)
                val maxSilenceFrames = if (isDirect) 13 else 11 // ~830ms vs ~700ms
                val maxSamples = if (isDirect) sampleRate * 10 else sampleRate * 7

                if (silenceFrames >= maxSilenceFrames || voiceBuffer.size >= maxSamples) {
                    val pcm = voiceBuffer.toShortArray()
                    val speechFramesCount = activeSpeechFrames
                    voiceBuffer.clear()
                    isSpeechActive = false
                    activeSpeechFrames = 0
                    silenceFrames = 0

                    val minActiveFrames = if (isDirect) 3 else 4
                    if (speechFramesCount >= minActiveFrames && AudioUtils.hasValidSpeechContent(pcm)) {
                        onAudioLevel(0f)
                        onUtteranceCaptured(pcm, isDirect)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (src in sources) {
            try {
                val rec = AudioRecord(src, sampleRate, channelConfig, audioFormat, bufferSize)
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    return rec
                }
                rec.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioSource $src failed: ${e.message}")
            }
        }
        return null
    }

    fun stopListening() {
        isRunning = false
        recordingJob?.cancel()
        recordingJob = null
        onAudioLevel(0f)
    }

    suspend fun stopListeningAndWait() {
        isRunning = false
        val job = recordingJob
        recordingJob = null
        job?.cancel()
        if (job != null) {
            withTimeoutOrNull(700L) {
                job.join()
            }
        }
        onAudioLevel(0f)
    }

    companion object {
        private const val TAG = "AudioRecorderManager"
    }
}
