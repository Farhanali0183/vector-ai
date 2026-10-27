package com.vector.assistant.automation

import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import com.vector.assistant.ai.GeminiBrainClient
import com.vector.assistant.audio.VoiceSpeaker
import com.vector.assistant.service.VectorAccessibilityService
import com.vector.assistant.service.VectorNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CommandExecutor executes advanced voice orders:
 * 1. Gemini Pro AI Intelligence & Topic Discussions
 * 2. Multi-Contact Bulk WhatsApp Attractive Messaging (AI Drafted)
 * 3. Direct Phone Calls & Contact Saving
 * 4. Recent Call History analysis
 * 5. VIP Contact Alarms
 * 6. YouTube Video Search & Play
 * 7. Flashlight, Volume, Apps, System Navigation
 */
class CommandExecutor(
    private val context: Context,
    private val speaker: VoiceSpeaker,
    private val onLog: (String) -> Unit
) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val geminiBrain = GeminiBrainClient(context)
    private val scope = CoroutineScope(Dispatchers.Main)
    private var isTorchOn = false

    fun processCommand(rawText: String) {
        val command = rawText.lowercase().trim()
        onLog("Order Received: \"$rawText\"")

        when {
            // 1. Bulk / Multi-Contact WhatsApp Campaign ("In sab ko WhatsApp per attractive message bhejo: Ali, Hamza, Bilal ke Sale shuru ho gayi hai")
            (command.contains("in sab") || command.contains("sab ko") || command.contains("multi")) && command.contains("whatsapp") -> {
                handleMultiContactWhatsApp(rawText)
            }

            // 2. Single WhatsApp Message ("WhatsApp per Ali ko msg karo ke main aa raha hoon")
            command.contains("whatsapp") && (command.contains("msg") || command.contains("message")) -> {
                handleSingleWhatsAppMessage(command)
            }

            // 3. Save Contact ("Save number Ali 03001234567" / "Number save karo Ali 03001234567")
            command.contains("save number") || command.contains("number save") || command.contains("contact save") -> {
                handleSaveContact(rawText)
            }

            // 4. Call History ("Recent calls", "Kal kis kis ko call ki thi", "Call history")
            command.contains("call history") || command.contains("recent call") || command.contains("kis ko call ki") -> {
                handleCallHistory()
            }

            // 5. VIP Contact Alert Setup ("VIP contact lagao Ali", "Set VIP contact Boss")
            command.contains("vip") -> {
                handleVipContactSetup(command)
            }

            // 6. YouTube Search & Play ("YouTube per search karo Imran Khan", "Play on YouTube")
            command.contains("youtube") -> {
                handleYouTube(command)
            }

            // 7. Direct Phone Call ("Call Ali", "Ali ko call lagao")
            command.startsWith("call ") || command.contains("ko call") || command.contains("call lagao") -> {
                handleCall(command)
            }

            // 8. Flashlight ("Torch chalao", "Torch band karo", "Flashlight on")
            command.contains("torch on") || command.contains("flashlight on") || command.contains("torch chalao") -> {
                toggleTorch(true)
            }
            command.contains("torch off") || command.contains("flashlight off") || command.contains("torch band") -> {
                toggleTorch(false)
            }

            // 9. Volume Control
            command.contains("volume up") || command.contains("awaaz barhao") -> {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                speaker.speak("Volume barha diya gaya hai")
                onLog("Action: Volume increased")
            }
            command.contains("volume down") || command.contains("awaaz kam") -> {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                speaker.speak("Volume kam kar diya gaya hai")
                onLog("Action: Volume decreased")
            }

            // 10. Open Apps ("Open Instagram", "Camera kholo")
            command.startsWith("open ") || command.contains("kholo") -> {
                handleOpenApp(command)
            }

            // 11. Time & Battery Status
            command.contains("time") -> {
                val currentTime = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                speaker.speak("Abhi waqt $currentTime hua hai")
                onLog("Action: Spoke time ($currentTime)")
            }
            command.contains("battery") -> {
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                speaker.speak("Aapke phone ki battery $level percent hai")
                onLog("Action: Battery level $level%")
            }

            // 12. Navigation Gestures
            command.contains("home") -> {
                VectorAccessibilityService.instance?.goHome()
                speaker.speak("Home screen open ho gayi")
                onLog("Action: Accessibility HOME")
            }

            // 13. Conversational AI Brain powered by Google Gemini Pro
            else -> {
                handleGeminiAIConversation(rawText)
            }
        }
    }

    // ==================== ADVANCED AI & WHATSAPP CAMPAIGN ====================

    /**
     * Handles Multi-Contact WhatsApp Campaign with Gemini Pro Generated Attractive Content!
     */
    private fun handleMultiContactWhatsApp(rawText: String) {
        scope.launch {
            speaker.speak("Main Gemini AI se attractive message compose karwa raha hoon...")
            onLog("AI Campaign: Processing multi-contact WhatsApp request...")

            // Extract topic / purpose: e.g. "ke Sale shuru ho gayi hai"
            val parts = rawText.split("ke ", "that ", "about ", limit = 2)
            val topic = if (parts.size > 1) parts[1].trim() else "Zaroori itla aur rabta"

            // Extract contacts: e.g. "Ali, Hamza, Bilal"
            val contactsPart = parts[0]
                .replace("in sab ko", "", ignoreCase = true)
                .replace("sab ko", "", ignoreCase = true)
                .replace("whatsapp per", "", ignoreCase = true)
                .replace("whatsapp", "", ignoreCase = true)
                .replace("msg karo", "", ignoreCase = true)
                .replace("message bhejo", "", ignoreCase = true)
                .replace("attractive message", "", ignoreCase = true)
                .replace("likh kar", "", ignoreCase = true)
                .replace(":", "")
                .trim()

            val contactNames = contactsPart.split(",", " aur ", " and ")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (contactNames.isEmpty()) {
                speaker.speak("Contacts ke naam sahi samajh nahi aaye. Dobara boleiye.")
                return@launch
            }

            // Step 1: Generate high quality attractive message via Gemini
            val attractiveMessage = geminiBrain.generateAttractiveWhatsAppMessage(topic)
            onLog("Gemini Drafted Message:\n$attractiveMessage")

            speaker.speak("Attractive message tayar hai. Ab ${contactNames.size} contacts ko WhatsApp send kiya ja raha hai.")

            // Step 2: Loop through each contact safely with 3-second delay
            for ((index, name) in contactNames.withIndex()) {
                onLog("Sending to #${index + 1}: $name...")
                val phoneNumber = lookupContactNumber(name)
                dispatchWhatsAppMessage(phoneNumber, attractiveMessage)
                delay(3500) // Delay to prevent spam blocks
            }

            speaker.speak("Sabhi contacts ko attractive WhatsApp message kamyabi se bhej diya gaya hai!")
            onLog("Campaign Completed: Sent to ${contactNames.joinToString(", ")}")
        }
    }

    /**
     * Conversational Topic / Deep Intelligence powered by Gemini Pro
     */
    private fun handleGeminiAIConversation(query: String) {
        scope.launch {
            onLog("Asking Gemini Pro: \"$query\"...")
            speaker.speak("Soch raha hoon...")

            val geminiResponse = geminiBrain.askGemini(query)
            onLog("Gemini Response: $geminiResponse")

            // Vector speaks the intelligent answer!
            speaker.speak(geminiResponse)
        }
    }

    private fun handleSingleWhatsAppMessage(command: String) {
        var clean = command.replace("whatsapp", "")
            .replace("per", "")
            .replace("par", "")
            .replace("ko", "")
            .replace("msg", "")
            .replace("message", "")
            .replace("karo", "")
            .replace("send", "")
            .trim()

        val splitWords = clean.split("ke ", "that ", limit = 2)
        val targetName = splitWords[0].trim()
        val messageText = if (splitWords.size > 1) splitWords[1].trim() else "Salam, Vector assistant automated message"

        val phoneNumber = lookupContactNumber(targetName)
        speaker.speak("WhatsApp per $targetName ko message send kar raha hoon")
        onLog("Action: Sending WhatsApp to $targetName: \"$messageText\"")

        dispatchWhatsAppMessage(phoneNumber, messageText)
    }

    private fun dispatchWhatsAppMessage(phoneNumber: String?, messageText: String) {
        try {
            val formattedNumber = phoneNumber?.replace("+", "")?.replace(" ", "")?.replace("-", "") ?: ""
            val url = if (formattedNumber.isNotEmpty()) {
                "https://api.whatsapp.com/send?phone=$formattedNumber&text=${Uri.encode(messageText)}"
            } else {
                "https://api.whatsapp.com/send?text=${Uri.encode(messageText)}"
            }

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.whatsapp")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            onLog("WhatsApp open error: ${e.message}")
            speaker.speak("WhatsApp open nahi ho saka")
        }
    }

    /**
     * Saves a new contact directly into the Android Phonebook
     */
    private fun handleSaveContact(rawText: String) {
        val digits = rawText.filter { it.isDigit() }
        var name = rawText
            .replace(digits, "")
            .replace("save number", "", ignoreCase = true)
            .replace("number save karo", "", ignoreCase = true)
            .replace("number save", "", ignoreCase = true)
            .replace("contact save", "", ignoreCase = true)
            .replace("karo", "", ignoreCase = true)
            .replace("ka", "", ignoreCase = true)
            .trim()

        if (name.isEmpty()) name = "New Contact"
        if (digits.length < 5) {
            speaker.speak("Number sahi samajh nahi aaya. Dobara boleiye.")
            return
        }

        try {
            val ops = ArrayList<ContentProviderOperation>()
            val rawContactInsertIndex = ops.size

            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build())

            // Add Name
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build())

            // Add Phone Number
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, digits)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build())

            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            speaker.speak("$name ka number save ho gaya hai")
            onLog("Action: Saved contact $name ($digits)")
        } catch (e: Exception) {
            onLog("Save contact error: ${e.message}")
            speaker.speak("Contact save karne mein masla hua. Permission check karein.")
        }
    }

    /**
     * Reads recent call history from Android CallLog
     */
    private fun handleCallHistory() {
        try {
            val projection = arrayOf(
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE
            )
            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 3"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val summaryList = mutableListOf<String>()
                    do {
                        val name = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))
                        val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                        val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))

                        val typeStr = when (type) {
                            CallLog.Calls.INCOMING_TYPE -> "Incoming call"
                            CallLog.Calls.OUTGOING_TYPE -> "Outgoing call"
                            CallLog.Calls.MISSED_TYPE -> "Missed call"
                            else -> "Call"
                        }
                        val contactName = name ?: number ?: "Unknown"
                        summaryList.add("$typeStr from $contactName")
                    } while (it.moveToNext())

                    val response = "Aapki aakhri calls hain: " + summaryList.joinToString(", ")
                    speaker.speak(response)
                    onLog("Call History: $response")
                } else {
                    speaker.speak("Koi recent call record nahi mila")
                }
            }
        } catch (e: Exception) {
            onLog("Call log error: ${e.message}")
            speaker.speak("Call history check karne ke liye permission allow karein")
        }
    }

    /**
     * Configures a VIP Contact for unique siren / alarm alerts on WhatsApp / SMS
     */
    private fun handleVipContactSetup(command: String) {
        val vipName = command
            .replace("vip", "")
            .replace("contact", "")
            .replace("lagao", "")
            .replace("set", "")
            .replace("per", "")
            .replace("ko", "")
            .trim()

        if (vipName.isNotEmpty()) {
            VectorNotificationListenerService.addVipContact(context, vipName)
            speaker.speak("$vipName ko VIP alert list mein shamil kar diya gaya hai. Ab unka message aane par unique alarm bajega.")
            onLog("VIP Added: $vipName (Will sound alarm on WhatsApp/SMS)")
        } else {
            speaker.speak("Kis contact ko VIP list mein add karna hai?")
        }
    }

    /**
     * Searches and plays videos directly on YouTube app
     */
    private fun handleYouTube(command: String) {
        val query = command
            .replace("youtube", "")
            .replace("per search karo", "")
            .replace("search", "")
            .replace("play", "")
            .replace("chalao", "")
            .trim()

        speaker.speak("YouTube per $query search kar raha hoon")
        onLog("Action: Searching YouTube: \"$query\"")

        try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
        }
    }

    private fun handleCall(command: String) {
        val targetName = command
            .replace("call", "")
            .replace("ko call milao", "")
            .replace("ko call", "")
            .replace("call lagao", "")
            .trim()

        if (targetName.isEmpty()) {
            speaker.speak("Kisko call karni hai?")
            return
        }

        speaker.speak("$targetName ko call mila raha hoon...")
        onLog("Action: Calling $targetName")

        val phoneNumber = lookupContactNumber(targetName)
        if (phoneNumber != null) {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(callIntent)
            } catch (e: SecurityException) {
                onLog("Call permission error: ${e.message}")
            }
        } else {
            val dialIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("tel:$targetName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
        }
    }

    private fun toggleTorch(enable: Boolean) {
        try {
            val cameraId = cameraManager?.cameraIdList?.firstOrNull() ?: return
            cameraManager.setTorchMode(cameraId, enable)
            isTorchOn = enable
            speaker.speak(if (enable) "Torch on kar di gayi hai" else "Torch band kar di gayi hai")
            onLog("Action: Torch ${if (enable) "ON" else "OFF"}")
        } catch (e: Exception) {
            Log.e("CommandExecutor", "Torch error: ${e.message}")
        }
    }

    private fun lookupContactNumber(name: String): String? {
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        return try {
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(0)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
