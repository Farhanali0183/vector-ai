package com.vector.assistant.audio

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

data class FrameMetrics(
    val rawRms: Double,
    val speechBandRms: Double,
    val zcr: Double,
    val peak: Int
)

object AudioUtils {

    /**
     * Computes DC-removed RMS, 300Hz-3500Hz speech-band RMS, zero-crossing rate, and peak.
     * Filtering out sub-250Hz rumble (fan/pocket/wind) prevents false VAD wake triggers.
     */
    fun analyzeFrame(frame: ShortArray, count: Int = frame.size): FrameMetrics {
        if (count <= 1) return FrameMetrics(0.0, 0.0, 0.0, 0)

        var mean = 0.0
        var peak = 0
        for (i in 0 until count) {
            val s = frame[i].toDouble()
            mean += s
            val a = abs(frame[i].toInt())
            if (a > peak) peak = a
        }
        mean /= count

        var rawSumSq = 0.0
        var speechSumSq = 0.0
        var zc = 0

        // 1-pole HPF (~260 Hz @ 16kHz) + 1-pole LPF (~3600 Hz @ 16kHz)
        var prevX = 0.0
        var hpOut = 0.0
        var lpOut = 0.0
        var prevLp = 0.0
        val hpAlpha = 0.90
        val lpAlpha = 0.62

        for (i in 0 until count) {
            val x = frame[i].toDouble() - mean
            rawSumSq += x * x

            hpOut = hpAlpha * (hpOut + x - prevX)
            prevX = x
            lpOut += lpAlpha * (hpOut - lpOut)
            speechSumSq += lpOut * lpOut

            if (i > 0 && ((lpOut >= 0.0 && prevLp < 0.0) || (lpOut < 0.0 && prevLp >= 0.0))) {
                zc++
            }
            prevLp = lpOut
        }

        val rawRms = sqrt(rawSumSq / count)
        val speechBandRms = sqrt(speechSumSq / count)
        val zcr = zc.toDouble() / count.toDouble()
        return FrameMetrics(rawRms, speechBandRms, zcr, peak)
    }

    /**
     * Checks if captured audio utterance contains real human speech rather than a brief click or pure silence.
     */
    fun hasValidSpeechContent(pcm: ShortArray): Boolean {
        if (pcm.size < 4800) return false // < 300ms
        val frameSize = 512
        var activeFrames = 0
        var totalFrames = 0
        var maxFrameRms = 0.0

        var idx = 0
        while (idx + frameSize <= pcm.size) {
            val slice = pcm.copyOfRange(idx, idx + frameSize)
            val m = analyzeFrame(slice, slice.size)
            totalFrames++
            if (m.speechBandRms > maxFrameRms) maxFrameRms = m.speechBandRms
            if (m.speechBandRms > 90.0 && m.zcr in 0.015..0.55) {
                activeFrames++
            }
            idx += frameSize
        }

        if (totalFrames == 0) return false
        return activeFrames >= 4 && maxFrameRms >= 130.0
    }

    /**
     * Encodes 16-bit mono PCM samples into standard WAV bytes ready for Gemini multimodal inlineData.
     */
    fun pcm16ToWav(samples: ShortArray, sampleRate: Int = 16000): ByteArray {
        val dataBytes = samples.size * 2
        val totalDataLen = dataBytes + 36
        val byteRate = sampleRate * 2 // 16-bit mono

        val out = ByteArrayOutputStream(44 + dataBytes)
        // RIFF header
        out.write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
        writeIntLE(out, totalDataLen)
        out.write(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))
        // fmt chunk
        out.write(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
        writeIntLE(out, 16) // Subchunk1Size for PCM
        writeShortLE(out, 1) // PCM format
        writeShortLE(out, 1) // Mono
        writeIntLE(out, sampleRate)
        writeIntLE(out, byteRate)
        writeShortLE(out, 2) // BlockAlign
        writeShortLE(out, 16) // BitsPerSample
        // data chunk
        out.write(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
        writeIntLE(out, dataBytes)

        val buf = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = samples[i].toInt()
            buf[i * 2] = (s and 0xFF).toByte()
            buf[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
        }
        out.write(buf)
        return out.toByteArray()
    }

    private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeShortLE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }
}

