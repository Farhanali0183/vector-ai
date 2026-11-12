package com.vector.assistant.automation

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.util.Locale

data class PendingWhatsAppReply(
    val sender: String,
    val packageName: String,
    val lastIncomingMessage: String,
    val actionIntent: PendingIntent,
    val remoteInput: RemoteInput,
    val timestampMs: Long
)

/**
 * Tracks live WhatsApp / messaging sessions across notifications and UI screens so Vector can:
 * 1. Know whom you recently messaged ("Ali ko hy bhejo") and alert you immediately when they reply.
 * 2. Reply directly in the background using Android RemoteInput ("is ka reply do", "sweet sa msg karo").
 * 3. Arm Accessibility auto-send when launching a WhatsApp intent.
 */
object WhatsAppChatAgent {

    private const val TAG = "WhatsAppChatAgent"
    private const val REPLY_VALIDITY_MS = 1000L * 60L * 30L // 30 minutes
    private const val AUTO_SEND_WINDOW_MS = 12000L

    private val lock = Any()
    private val watchedContacts = LinkedHashSet<String>()
    private val replyMap = LinkedHashMap<String, PendingWhatsAppReply>()

    @Volatile
    var lastMessagedContact: String? = null
        private set

    @Volatile
    var lastIncomingSender: String? = null
        private set

    @Volatile
    var lastIncomingMessage: String? = null
        private set

    @Volatile
    private var lastIncomingAtMs: Long = 0L

    @Volatile
    private var armedAutoSendTarget: String? = null

    @Volatile
    private var armedAutoSendUntilMs: Long = 0L

    @Volatile
    private var lastAnnouncedDigest: String = ""

    @Volatile
    private var lastAnnouncedAtMs: Long = 0L

    fun markContactMessaged(contactName: String) {
        val clean = contactName.trim()
        if (clean.isEmpty()) return
        synchronized(lock) {
            watchedContacts.add(clean.lowercase(Locale.getDefault()))
            lastMessagedContact = clean
        }
    }

    fun isWatchedContact(senderName: String): Boolean {
        val sLower = senderName.trim().lowercase(Locale.getDefault())
        if (sLower.isEmpty()) return false
        synchronized(lock) {
            return watchedContacts.any { w -> sLower.contains(w) || w.contains(sLower) }
        }
    }

    fun armAutoSend(target: String) {
        armedAutoSendTarget = target.trim()
        armedAutoSendUntilMs = System.currentTimeMillis() + AUTO_SEND_WINDOW_MS
    }

    fun consumeAutoSendIfArmed(): Boolean {
        val now = System.currentTimeMillis()
        if (now <= armedAutoSendUntilMs && armedAutoSendTarget != null) {
            armedAutoSendUntilMs = 0L
            armedAutoSendTarget = null
            return true
        }
        return false
    }

    fun isAutoSendArmed(): Boolean {
        return System.currentTimeMillis() <= armedAutoSendUntilMs && armedAutoSendTarget != null
    }

    /**
     * Records an incoming chat notification + optional Direct-Reply RemoteInput.
     * Returns true if this is a fresh, non-duplicate message worth announcing to the user.
     */
    fun recordIncomingMessage(
        sender: String,
        packageName: String,
        messageText: String,
        replyAction: PendingIntent?,
        remoteInput: RemoteInput?
    ): Boolean {
        val cleanSender = sender.trim()
        val cleanMsg = messageText.trim()
        if (cleanSender.isEmpty() || cleanMsg.isEmpty()) return false

        val now = System.currentTimeMillis()
        val digest = "${cleanSender.lowercase(Locale.getDefault())}|$cleanMsg"
        if (digest == lastAnnouncedDigest && (now - lastAnnouncedAtMs) < 8000L) {
            return false
        }
        lastAnnouncedDigest = digest
        lastAnnouncedAtMs = now

        synchronized(lock) {
            lastIncomingSender = cleanSender
            lastIncomingMessage = cleanMsg
            lastIncomingAtMs = now

            if (replyAction != null && remoteInput != null) {
                replyMap[cleanSender.lowercase(Locale.getDefault())] = PendingWhatsAppReply(
                    sender = cleanSender,
                    packageName = packageName,
                    lastIncomingMessage = cleanMsg,
                    actionIntent = replyAction,
                    remoteInput = remoteInput,
                    timestampMs = now
                )
                // Cap map size
                while (replyMap.size > 15) {
                    val firstKey = replyMap.keys.firstOrNull() ?: break
                    replyMap.remove(firstKey)
                }
            }
        }
        return true
    }

    fun getBestReplyTarget(explicitTarget: String = ""): String {
        val clean = explicitTarget.trim()
        if (clean.isNotEmpty()) return clean
        return lastIncomingSender ?: lastMessagedContact ?: ""
    }

    fun sendDirectNotificationReply(
        context: Context,
        targetName: String,
        replyText: String
    ): Boolean {
        val cleanText = replyText.trim()
        if (cleanText.isEmpty()) return false

        val now = System.currentTimeMillis()
        val session: PendingWhatsAppReply? = synchronized(lock) {
            val targetLower = targetName.trim().lowercase(Locale.getDefault())
            val candidate = if (targetLower.isNotEmpty()) {
                replyMap.entries.firstOrNull { (k, _) ->
                    k.contains(targetLower) || targetLower.contains(k)
                }?.value
            } else {
                lastIncomingSender?.lowercase(Locale.getDefault())?.let { replyMap[it] }
                    ?: replyMap.values.lastOrNull()
            }
            if (candidate != null && (now - candidate.timestampMs) <= REPLY_VALIDITY_MS) {
                candidate
            } else {
                null
            }
        }

        if (session == null) return false

        return try {
            val bundle = Bundle().apply {
                putCharSequence(session.remoteInput.resultKey, cleanText)
            }
            val fillInIntent = Intent()
            RemoteInput.addResultsToIntent(arrayOf(session.remoteInput), fillInIntent, bundle)
            session.actionIntent.send(context, 0, fillInIntent)
            markContactMessaged(session.sender)
            Log.i(TAG, "Direct RemoteInput reply sent to ${session.sender}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "RemoteInput reply failed: ${e.message}")
            false
        }
    }

    fun getPromptContextSummary(): String {
        val sb = StringBuilder()
        val now = System.currentTimeMillis()
        if (!lastIncomingSender.isNullOrBlank() && !lastIncomingMessage.isNullOrBlank() && (now - lastIncomingAtMs) < REPLY_VALIDITY_MS) {
            sb.append("Latest Incoming Chat Notification: Sender='${lastIncomingSender}', Message='${lastIncomingMessage}'.\n")
        }
        if (!lastMessagedContact.isNullOrBlank()) {
            sb.append("Last WhatsApp Contact You Messaged: '${lastMessagedContact}'.\n")
        }
        return sb.toString().trim()
    }
}

