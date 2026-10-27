package com.vector.assistant.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.vector.assistant.audio.VoiceSpeaker

/**
 * Listens for incoming WhatsApp and SMS messages.
 * If message is from a designated VIP contact, it sounds a UNIQUE ALARM
 * and speaks out loud who texted!
 */
class VectorNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val PREFS_NAME = "vector_vip_prefs"
        private const val KEY_VIP_LIST = "vip_contact_names"

        fun addVipContact(context: Context, name: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentList = prefs.getStringSet(KEY_VIP_LIST, mutableSetOf()) ?: mutableSetOf()
            val updated = HashSet(currentList)
            updated.add(name.lowercase().trim())
            prefs.edit().putStringSet(KEY_VIP_LIST, updated).apply()
        }

        fun getVipContacts(context: Context): Set<String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getStringSet(KEY_VIP_LIST, emptySet()) ?: emptySet()
        }

        fun clearVipContacts(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_VIP_LIST).apply()
        }
    }

    private var speaker: VoiceSpeaker? = null
    private var alarmRingtone: Ringtone? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        speaker = VoiceSpeaker(this)
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            alarmRingtone = RingtoneManager.getRingtone(this, alarmUri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
        } catch (e: Exception) {
            Log.e("VectorNotification", "Error initializing alarm ringtone: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: ""
        // Check if notification is from WhatsApp or SMS
        val isWhatsApp = pkg.contains("whatsapp")
        val isSms = pkg.contains("messaging") || pkg.contains("sms") || pkg.contains("mms")

        if (!isWhatsApp && !isSms) return

        val extras = sbn.notification.extras ?: return
        val senderTitle = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val messageText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (senderTitle.isEmpty()) return

        val vipContacts = getVipContacts(this)
        val isVip = vipContacts.any { vip ->
            senderTitle.lowercase().contains(vip.lowercase()) || vip.lowercase().contains(senderTitle.lowercase())
        }

        if (isVip) {
            triggerVipAlarm(senderTitle, messageText)
        }
    }

    private fun triggerVipAlarm(sender: String, message: String) {
        // 1. Play Unique Loud Alarm Sound for 4 seconds
        try {
            alarmRingtone?.play()
            mainHandler.postDelayed({
                try {
                    alarmRingtone?.stop()
                } catch (e: Exception) {}
            }, 4000)
        } catch (e: Exception) {
            Log.e("VectorNotification", "Failed to play alarm: ${e.message}")
        }

        // 2. Speak VIP alert out loud
        mainHandler.postDelayed({
            val alertMsg = "Alert! VIP Message from $sender. Message: $message"
            speaker?.speak(alertMsg)
            broadcastLog("🚨 VIP ALERT: $sender sent: \"$message\"")
        }, 4200)
    }

    private fun broadcastLog(msg: String) {
        val intent = Intent(VectorVoiceService.BROADCAST_LOG).apply {
            putExtra(VectorVoiceService.EXTRA_LOG_MSG, msg)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        alarmRingtone?.stop()
        speaker?.shutdown()
        speaker = null
        super.onDestroy()
    }
}
