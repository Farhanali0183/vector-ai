package com.vector.assistant.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.vector.assistant.R
import com.vector.assistant.model.VectorSettings
import com.vector.assistant.model.VectorState
import com.vector.assistant.ui.VectorVisorView

/**
 * Displays a sleek floating Vector Eyes HUD pill over other apps (when SYSTEM_ALERT_WINDOW is granted),
 * giving Vector an authentic native OS assistant presence when woken hands-free.
 */
class VectorOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val settings = VectorSettings(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var visorView: VectorVisorView? = null
    private var tvTitle: TextView? = null
    private var tvMessage: TextView? = null
    private var isAdded = false

    private val autoHideRunnable = Runnable { hide() }

    fun showOrUpdate(state: VectorState, title: String, message: String, autoHideMs: Long? = null) {
        if (!settings.isOverlayHudEnabled || !Settings.canDrawOverlays(context)) return

        mainHandler.post {
            mainHandler.removeCallbacks(autoHideRunnable)
            ensureViewAdded()
            visorView?.setVectorState(state)
            tvTitle?.text = title
            tvMessage?.text = message
            if (autoHideMs != null) {
                mainHandler.postDelayed(autoHideRunnable, autoHideMs)
            }
        }
    }

    fun updateAudioLevel(level: Float) {
        if (!isAdded) return
        mainHandler.post {
            visorView?.setAudioLevel(level)
        }
    }

    fun scheduleAutoHide(delayMs: Long = 3200L) {
        mainHandler.post {
            mainHandler.removeCallbacks(autoHideRunnable)
            if (isAdded) {
                mainHandler.postDelayed(autoHideRunnable, delayMs)
            }
        }
    }

    fun hide() {
        mainHandler.post {
            mainHandler.removeCallbacks(autoHideRunnable)
            if (isAdded && overlayView != null) {
                try {
                    windowManager?.removeViewImmediate(overlayView)
                } catch (_: Exception) {
                    try {
                        windowManager?.removeView(overlayView)
                    } catch (_: Exception) {}
                }
            }
            isAdded = false
            overlayView = null
            visorView = null
            tvTitle = null
            tvMessage = null
        }
    }

    private fun ensureViewAdded() {
        if (isAdded && overlayView != null) return
        val wm = windowManager ?: return
        try {
            val view = LayoutInflater.from(context).inflate(R.layout.overlay_vector_hud, null)
            visorView = view.findViewById(R.id.overlayVisor)
            tvTitle = view.findViewById(R.id.tvOverlayTitle)
            tvMessage = view.findViewById(R.id.tvOverlayMessage)
            view.findViewById<TextView>(R.id.btnOverlayClose).setOnClickListener {
                hide()
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 64
            }

            wm.addView(view, params)
            overlayView = view
            isAdded = true
        } catch (e: Exception) {
            Log.e("VectorOverlay", "Failed to add overlay HUD: ${e.message}")
            isAdded = false
        }
    }
}

