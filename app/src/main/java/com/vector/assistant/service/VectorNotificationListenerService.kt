package com.vector.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
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
import androidx.core.app.NotificationCompat
import com.vector.assistant.audio.VoiceSpeaker
import com.vector.assistant.automation.WhatsAppChatAgent
import com.vector.assistant.model.VectorSettings

/**
 * Listens for incoming WhatsApp & SMS notifications in real time:
 * 1. Captures RemoteInput Direct-Reply action so Vector can reply to messages completely hands-free!
 * 2. Announces incoming WhatsApp replies to VectorVoiceService when user is waiting/chatting.
 * 3. Sounds a VIP Siren Alarm + speaks alert when a VIP contact texts.
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

        fun removeVipContact(context: Context, name: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentList = prefs.getStringSet(KEY_VIP_LIST, mutableSetOf()) ?: mutableSetOf()
            val updated = HashSet(currentList)
            updated.remove(name.lowercase().trim())
            prefs.edit().putStringSet(KEY_VIP_LIST, updated).apply()
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

        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val pkg = sbn.packageName ?: ""
        val isWhatsApp = pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b"
        val isSms = pkg.contains("messaging") || pkg.contains("sms") || pkg.contains("mms")
        if (!isWhatsApp && !isSms) return

        val extras = notification.extras ?: return
        var senderTitle = extras.getString(Notification.EXTRA_CONVERSATION_TITLE)?.trim().orEmpty()
        if (senderTitle.isEmpty()) {
            senderTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        }

        var messageText = extractLatestMessageText(notification)
        if (messageText.isEmpty()) {
            messageText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        }

        if (senderTitle.isEmpty() || messageText.isEmpty()) return

        // Ignore WhatsApp background sync / self notifications
        val lowerTitle = senderTitle.lowercase()
        val lowerMsg = messageText.lowercase()
        if (lowerTitle == "whatsapp" ||
            lowerMsg.contains("checking for new messages") ||
            lowerMsg.contains("messages from") ||
            lowerMsg.contains("new messages") ||
            lowerMsg.startsWith("you:")
        ) {
            return
        }

        val (replyIntent, remoteInput) = findDirectReplyAction(notification)
        val isFresh = WhatsAppChatAgent.recordIncomingMessage(
            sender = senderTitle,
            packageName = pkg,
            messageText = messageText,
            replyAction = replyIntent,
            remoteInput = remoteInput
        )
        if (!isFresh) return

        val vipContacts = getVipContacts(this)
        val isVip = vipContacts.any { vip ->
            senderTitle.lowercase().contains(vip.lowercase()) || vip.lowercase().contains(senderTitle.lowercase())
        }

        if (isVip) {
            triggerVipAlarm(senderTitle, messageText)
        }

        val settings = VectorSettings(this)
        if (VectorVoiceService.isServiceRunning && (settings.isWhatsAppAssistantEnabled || WhatsAppChatAgent.isWatchedContact(senderTitle))) {
            val alertIntent = Intent(this, VectorVoiceService::class.java).apply {
                action = VectorVoiceService.ACTION_INCOMING_CHAT_ALERT
                putExtra(VectorVoiceService.EXTRA_ALERT_SENDER, senderTitle)
                putExtra(VectorVoiceService.EXTRA_ALERT_MESSAGE, messageText)
                putExtra(VectorVoiceService.EXTRA_ALERT_IS_VIP, isVip)
            }
            try {
                startService(alertIntent)
            } catch (e: Exception) {
                Log.w("VectorNotification", "Could not forward chat alert to VectorVoiceService: ${e.message}")
            }
        }
    }

    private fun extractLatestMessageText(notification: Notification): String {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val lastMsg = style?.messages?.lastOrNull()
        val text = lastMsg?.text?.toString()?.trim()
        return text ?: ""
    }

    private fun findDirectReplyAction(notification: Notification): Pair<PendingIntent?, RemoteInput?> {
        val actions = notification.actions ?: return Pair(null, null)
        for (action in actions) {
            val inputs = action.remoteInputs ?: continue
            val textInput = inputs.firstOrNull { it.allowFreeFormInput } ?: inputs.firstOrNull()
            if (textInput != null && action.actionIntent != null) {
                return Pair(action.actionIntent, textInput)
            }
        }
        return Pair(null, null)
    }

    private fun triggerVipAlarm(sender: String, message: String) {
        try {
            alarmRingtone?.play()
            mainHandler.postDelayed({
                try {
                    alarmRingtone?.stop()
                } catch (_: Exception) {}
            }, 4000)
        } catch (e: Exception) {
            Log.e("VectorNotification", "Failed to play alarm: ${e.message}")
        }

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
