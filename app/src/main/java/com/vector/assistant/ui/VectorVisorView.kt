package com.vector.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.vector.assistant.model.VectorState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Custom hardware-accelerated Vector Visor & Expressive Eyes view inspired by the
 * iconic Vector companion robot:
 * - Animated blinking, subtle autonomous gaze, reactive audio waveform & orbital aura
 * - Adapts seamlessly to large hero mode and compact floating overlay mode
 */
class VectorVisorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var state: VectorState = VectorState.IDLE
    private var targetLevel: Float = 0f
    private var smoothedLevel: Float = 0f

    private var phase: Float = 0f
    private var nextBlinkAtMs: Long = System.currentTimeMillis() + 2500L
    private var blinkProgress: Float = 0f // 0 = open, 1 = closed
    private var gazeTargetX: Float = 0f
    private var gazeCurrentX: Float = 0f
    private var nextGazeAtMs: Long = System.currentTimeMillis() + 1800L

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val visorBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val visorBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val eyeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val visorRect = RectF()
    private val leftEyeRect = RectF()
    private val rightEyeRect = RectF()
    private val barRect = RectF()

    private var animator: ValueAnimator? = null

    private val cyanPrimary = Color.parseColor("#00E5FF")
    private val cyanDeep = Color.parseColor("#0091EA")
    private val purpleThinking = Color.parseColor("#8B5CF6")
    private val emeraldSpeaking = Color.parseColor("#10B981")
    private val mutedSlate = Color.parseColor("#475569")

    fun setVectorState(newState: VectorState) {
        if (state != newState) {
            state = newState
            invalidate()
        }
    }

    fun setAudioLevel(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimator()
    }

    override fun onDetachedFromWindow() {
        stopAnimator()
        super.onDetachedFromWindow()
    }

    private fun startAnimator() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase += 0.045f
                if (phase > (2 * PI * 100).toFloat()) phase = 0f

                smoothedLevel += (targetLevel - smoothedLevel) * 0.25f

                val now = System.currentTimeMillis()
                // Blink timing
                if (state != VectorState.THINKING && now >= nextBlinkAtMs) {
                    val elapsed = now - nextBlinkAtMs
                    if (elapsed < 180L) {
                        val t = elapsed / 180f
                        blinkProgress = if (t < 0.5f) t * 2f else (1f - t) * 2f
                    } else {
                        blinkProgress = 0f
                        nextBlinkAtMs = now + 2400L + ((now % 2600L))
                    }
                } else {
                    blinkProgress = 0f
                }

                // Natural eye look drift when listening or idle
                if (now >= nextGazeAtMs) {
                    nextGazeAtMs = now + 1700L + (now % 1600L)
                    gazeTargetX = when (state) {
                        VectorState.LISTENING_WAKE -> (((now % 200) - 100) / 100f) * 0.9f
                        VectorState.LISTENING_COMMAND -> 0f
                        VectorState.IDLE -> (((now % 120) - 60) / 100f) * 0.4f
                        else -> 0f
                    }
                }
                gazeCurrentX += (gazeTargetX - gazeCurrentX) * 0.08f
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val minDim = minOf(w, h)

        val accentColor = when (state) {
            VectorState.IDLE -> mutedSlate
            VectorState.LISTENING_WAKE -> cyanPrimary
            VectorState.LISTENING_COMMAND -> cyanPrimary
            VectorState.THINKING -> purpleThinking
            VectorState.SPEAKING -> emeraldSpeaking
        }

        val pulse = (sin(phase.toDouble()).toFloat() + 1f) * 0.5f // 0..1
        val voiceBoost: Float = if (state == VectorState.SPEAKING) {
            0.35f + 0.45f * abs(sin((phase * 2.7f).toDouble())).toFloat()
        } else {
            smoothedLevel
        }

        // 1. Ambient backdrop glow
        val glowRadius = minDim * (0.48f + 0.06f * pulse + 0.10f * voiceBoost)
        val glowAlpha = when (state) {
            VectorState.IDLE -> 38
            VectorState.LISTENING_WAKE -> (75 + 35 * pulse + 65 * voiceBoost).toInt()
            VectorState.LISTENING_COMMAND -> (120 + 85 * voiceBoost).toInt()
            VectorState.THINKING -> (110 + 55 * pulse).toInt()
            VectorState.SPEAKING -> (115 + 75 * voiceBoost).toInt()
        }.coerceIn(0, 220)

        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius.coerceAtLeast(1f),
            intArrayOf(
                Color.argb(glowAlpha, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Color.TRANSPARENT
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        // 2. Visor dimensions (sleek wide rounded hardware screen)
        val visorW = (w * 0.78f).coerceAtMost(h * 1.48f)
        val visorH = visorW * 0.60f
        val visorLeft = cx - visorW / 2f
        val visorTop = cy - visorH / 2f
        val visorRight = cx + visorW / 2f
        val visorBottom = cy + visorH / 2f
        val visorCorner = visorH * 0.28f
        visorRect.set(visorLeft, visorTop, visorRight, visorBottom)

        // Outer reactive audio ring
        if (state != VectorState.IDLE) {
            val expand = visorH * (0.04f + 0.09f * voiceBoost + 0.025f * pulse)
            ringPaint.strokeWidth = (minDim * 0.012f).coerceAtLeast(1.5f)
            ringPaint.color = Color.argb(
                (55 + 115 * voiceBoost).toInt().coerceIn(30, 190),
                Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)
            )
            val outerRect = RectF(
                visorLeft - expand, visorTop - expand,
                visorRight + expand, visorBottom + expand
            )
            canvas.drawRoundRect(outerRect, visorCorner + expand, visorCorner + expand, ringPaint)
        }

        // Visor obsidian body
        visorBgPaint.shader = LinearGradient(
            visorLeft, visorTop, visorLeft, visorBottom,
            Color.parseColor("#0C121D"),
            Color.parseColor("#05080F"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(visorRect, visorCorner, visorCorner, visorBgPaint)

        // Visor metallic-cyan border
        visorBorderPaint.strokeWidth = (minDim * 0.016f).coerceAtLeast(2f)
        visorBorderPaint.color = Color.argb(
            if (state == VectorState.IDLE) 110 else 210,
            Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)
        )
        canvas.drawRoundRect(visorRect, visorCorner, visorCorner, visorBorderPaint)

        // 3. Expressive Vector Eyes
        val eyeW = visorW * 0.19f
        val baseEyeH = visorH * when (state) {
            VectorState.IDLE -> 0.14f
            VectorState.LISTENING_WAKE -> 0.36f
            VectorState.LISTENING_COMMAND -> 0.42f + 0.06f * voiceBoost
            VectorState.THINKING -> 0.26f
            VectorState.SPEAKING -> 0.34f
        }
        val eyeH = (baseEyeH * (1f - 0.88f * blinkProgress)).coerceAtLeast(visorH * 0.03f)
        val eyeGap = visorW * 0.15f
        val gazeShiftPx = gazeCurrentX * visorW * 0.055f
        val eyeYOffset = when (state) {
            VectorState.LISTENING_COMMAND -> -visorH * 0.06f
            VectorState.SPEAKING -> -visorH * 0.07f
            else -> 0f
        }

        val leftEyeCx = cx - eyeGap / 2f - eyeW / 2f + gazeShiftPx
        val rightEyeCx = cx + eyeGap / 2f + eyeW / 2f + gazeShiftPx
        val eyeCy = cy + eyeYOffset

        leftEyeRect.set(leftEyeCx - eyeW / 2f, eyeCy - eyeH / 2f, leftEyeCx + eyeW / 2f, eyeCy + eyeH / 2f)
        rightEyeRect.set(rightEyeCx - eyeW / 2f, eyeCy - eyeH / 2f, rightEyeCx + eyeW / 2f, eyeCy + eyeH / 2f)

        val eyeRadius = eyeW * 0.36f
        val eyeAlpha = if (state == VectorState.IDLE) 140 else 255

        // Soft eye halo
        eyeGlowPaint.color = Color.argb(
            if (state == VectorState.IDLE) 28 else 72,
            Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)
        )
        val haloPad = eyeW * 0.18f
        canvas.drawRoundRect(
            RectF(leftEyeRect.left - haloPad, leftEyeRect.top - haloPad, leftEyeRect.right + haloPad, leftEyeRect.bottom + haloPad),
            eyeRadius + haloPad, eyeRadius + haloPad, eyeGlowPaint
        )
        canvas.drawRoundRect(
            RectF(rightEyeRect.left - haloPad, rightEyeRect.top - haloPad, rightEyeRect.right + haloPad, rightEyeRect.bottom + haloPad),
            eyeRadius + haloPad, eyeRadius + haloPad, eyeGlowPaint
        )

        // Core glowing eye shapes
        val eyeTopColor = if (state == VectorState.THINKING) {
            Color.argb(eyeAlpha, 167, 139, 250)
        } else if (state == VectorState.SPEAKING) {
            Color.argb(eyeAlpha, 52, 245, 197)
        } else if (state == VectorState.IDLE) {
            Color.argb(eyeAlpha, 100, 116, 139)
        } else {
            Color.argb(eyeAlpha, 0, 240, 255)
        }
        val eyeBotColor = if (state == VectorState.IDLE) mutedSlate else cyanDeep

        eyePaint.shader = LinearGradient(
            0f, leftEyeRect.top, 0f, leftEyeRect.bottom,
            eyeTopColor, eyeBotColor, Shader.TileMode.CLAMP
        )

        if (state == VectorState.THINKING) {
            // Alternating animated heights during thinking
            val lScale = 0.7f + 0.3f * abs(sin((phase * 2.2f).toDouble())).toFloat()
            val rScale = 0.7f + 0.3f * abs(sin((phase * 2.2f + 1.4f).toDouble())).toFloat()
            val lRect = RectF(leftEyeRect.left, eyeCy - (eyeH * lScale) / 2f, leftEyeRect.right, eyeCy + (eyeH * lScale) / 2f)
            val rRect = RectF(rightEyeRect.left, eyeCy - (eyeH * rScale) / 2f, rightEyeRect.right, eyeCy + (eyeH * rScale) / 2f)
            canvas.drawRoundRect(lRect, eyeRadius, eyeRadius, eyePaint)
            canvas.drawRoundRect(rRect, eyeRadius, eyeRadius, eyePaint)
        } else {
            canvas.drawRoundRect(leftEyeRect, eyeRadius, eyeRadius, eyePaint)
            canvas.drawRoundRect(rightEyeRect, eyeRadius, eyeRadius, eyePaint)
        }

        // 4. Reactive bottom audio equalizer bars inside visor during Listening / Speaking
        if (state == VectorState.LISTENING_COMMAND || state == VectorState.SPEAKING || (state == VectorState.LISTENING_WAKE && voiceBoost > 0.08f)) {
            val barCount = 9
            val totalBarsW = visorW * 0.56f
            val step = totalBarsW / barCount
            val barW = step * 0.52f
            val barBaseY = visorBottom - visorH * 0.14f
            val maxBarH = visorH * 0.16f

            wavePaint.color = Color.argb(
                220, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)
            )
            val startX = cx - totalBarsW / 2f + (step - barW) / 2f

            for (i in 0 until barCount) {
                val distFromCenter = 1f - abs(i - barCount / 2f) / (barCount / 2f + 0.5f)
                val wave = abs(sin(phase * 3.0 + i * 0.7)).toFloat()
                val amp = (0.18f + voiceBoost * 0.82f) * distFromCenter * (0.45f + 0.55f * wave)
                val bh = (maxBarH * amp).coerceAtLeast(visorH * 0.025f)
                val bx = startX + i * step
                barRect.set(bx, barBaseY - bh, bx + barW, barBaseY)
                canvas.drawRoundRect(barRect, barW / 2f, barW / 2f, wavePaint)
            }
        }
    }
}
