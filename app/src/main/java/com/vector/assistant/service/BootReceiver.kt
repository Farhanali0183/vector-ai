package com.vector.assistant.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vector.assistant.model.VectorSettings
import com.vector.assistant.ui.MainActivity

/**
 * Handles BOOT_COMPLETED / MY_PACKAGE_REPLACED safely across Android 13/14:
 * Attempts service resume if permitted, and posts a quick tap notification if Android blocks
 * background microphone FGS startup after reboot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val settings = VectorSettings(context)
        if (!settings.serviceWasRunning || settings.userExplicitlyPaused) return

        val hasMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) return

        // On Android 14+ (API 34), starting a microphone foreground service directly from BOOT_COMPLETED
        // can throw ForegroundServiceStartNotAllowedException unless exempt; try safely and show resume notification.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val serviceIntent = Intent(context, VectorVoiceService::class.java).apply {
                    this.action = VectorVoiceService.ACTION_START
                }
                ContextCompat.startForegroundService(context, serviceIntent)
                return
            } catch (_: Exception) {
                // Fall back to notification
            }
        }

        showResumeNotification(context)
    }

    private fun showResumeNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channelId = "vector_boot_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Vector Startup Reminder",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            202,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Vector Assistant Ready")
            .setContentText("Tap to resume hands-free 'Hey Vector' listening.")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        try {
            manager.notify(2002, notif)
        } catch (_: Exception) {
        }
    }
}

