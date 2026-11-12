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
import android.view.KeyEvent
import com.vector.assistant.ai.GeminiBrainClient
import com.vector.assistant.audio.VoiceSpeaker
import com.vector.assistant.model.VectorSessionState
import com.vector.assistant.model.VectorSettings
import com.vector.assistant.model.VoiceDecision
import com.vector.assistant.service.VectorAccessibilityService
import com.vector.assistant.service.VectorNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Executes structured VoiceDecisions from Gemini Multimodal Audio OR text/offline commands:
 * - Smart conversational AI (Roman Urdu / Urdu / English) with long-term memory
 * - Multi-contact AI WhatsApp campaigns & single WhatsApp messages
 * - Phone calls, call history, contact saving, VIP alerts
 * - YouTube, app launcher, flashlight, volume, Accessibility navigation
 */
class CommandExecutor(
    private val context: Context,
    private val speaker: VoiceSpeaker,
    private val onLog: (String) -> Unit,
    private val onContinuousChatNextTurn: (() -> Unit)? = null,
    private val onCycleComplete: (() -> Unit)? = null,
    private val onWakeScreenRequested: ((dismissLockScreen: Boolean) -> Unit)? = null
) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val settings = VectorSettings(context)
    val geminiBrain = GeminiBrainClient(context)
    private val inAppTaskAgent = InAppTaskAgent(geminiBrain, onLog)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isTorchOn = false

    @Volatile
    var isContinuousChatActive = false
        private set

    fun stopContinuousChat(speakConfirmation: Boolean = false, persistDisable: Boolean = false) {
        isContinuousChatActive = false
        if (persistDisable) {
            settings.isAutoContinuousChat = false
        }
        VectorSessionState.setContinuousChat(false)
        geminiBrain.clearConversationHistory()
        if (speakConfirmation) {
            speakAndFinish("Theek hai boss, live chat band ho gayi hai. Ab 'Hey Vector' bol kar bula sakte hain.", "Live Chat Off")
        }
    }

    fun startContinuousChat(silent: Boolean = false) {
        isContinuousChatActive = true
        settings.isAutoContinuousChat = true
        VectorSessionState.setContinuousChat(true)
        geminiBrain.clearConversationHistory()
        onLog("Live Chat: Continuous conversation ACTIVE (No wake-word needed)")
        if (silent) {
            onContinuousChatNextTurn?.invoke()
            return
        }
        val msg = "Live chat open hai boss. Ab bina mic dabaye aur bina Hey Vector bole lagatar baat karein."
        VectorSessionState.addVectorMessage(msg, badge = "Live Chat On")
        speaker.speak(msg) {
            if (isContinuousChatActive) {
                onContinuousChatNextTurn?.invoke()
            } else {
                onCycleComplete?.invoke()
            }
        }
    }

    fun processCommand(rawText: String, isVoiceInput: Boolean = true) {
        val cleaned = stripWakePrefix(rawText.trim())
        if (cleaned.isEmpty()) {
            onCycleComplete?.invoke()
            return
        }

        VectorSessionState.addUserMessage(rawText.trim(), isVoice = isVoiceInput)
        onLog("Order Received: \"$cleaned\"")

        val cmd = cleaned.lowercase(Locale.getDefault())

        // Fast instant offline triggers so critical device controls have zero latency
        when {
            cmd in setOf("torch on", "torch chalao", "torch jalao", "flashlight on", "batti on") -> {
                toggleTorch(true); return
            }
            cmd in setOf("torch off", "torch band", "torch band karo", "flashlight off", "batti off", "batti bujha do") -> {
                toggleTorch(false); return
            }
            cmd in setOf("start chat", "live chat", "long chat", "continuous chat", "guftugu shuru", "guftugu shuru karo", "live chat on") -> {
                startContinuousChat(); return
            }
            isContinuousChatActive && cmd in setOf("stop chat", "chat band", "chat band karo", "live chat band", "live chat off", "guftugu band", "guftugu khatam", "bye vector", "alvida") -> {
                stopContinuousChat(speakConfirmation = true, persistDisable = true); return
            }
        }

        if (geminiBrain.isConnected()) {
            scope.launch {
                val decision = geminiBrain.processTextWithIntent(cleaned, isContinuousChatActive)
                if (decision != null) {
                    executeDecision(decision, alreadyLoggedUser = true)
                } else {
                    processOfflineCommand(cleaned)
                }
            }
        } else {
            processOfflineCommand(cleaned)
        }
    }

    fun executeDecision(decision: VoiceDecision, alreadyLoggedUser: Boolean = false) {
        if (!alreadyLoggedUser && decision.transcript.isNotBlank()) {
            VectorSessionState.addUserMessage(decision.transcript, isVoice = true)
            onLog("Heard: \"${decision.transcript}\" [intent=${decision.intent}]")
        }

        when (decision.intent) {
            "wake_only" -> {
                // Handled by service directly
                onCycleComplete?.invoke()
            }
            "continuous_start" -> startContinuousChat()
            "continuous_stop" -> stopContinuousChat(speakConfirmation = true, persistDisable = true)
            "torch_on" -> toggleTorch(true)
            "torch_off" -> toggleTorch(false)
            "time" -> handleTime()
            "battery" -> handleBattery()
            "volume_up" -> handleVolume(raise = true, raw = decision.transcript)
            "volume_down" -> handleVolume(raise = false, raw = decision.transcript)
            "call" -> handleCall(decision.target.ifBlank { decision.transcript })
            "whatsapp_bulk" -> handleMultiContactWhatsAppStructured(decision.target, decision.message)
            "whatsapp_single" -> handleSingleWhatsAppStructured(decision.target, decision.message)
            "whatsapp_reply" -> handleWhatsAppReply(decision.target, decision.message, decision.reply)
            "read_screen" -> handleReadScreen()
            "app_click" -> handleAppClick(decision.target.ifBlank { decision.message })
            "app_type" -> handleAppType(decision.message.ifBlank { decision.target })
            "app_scroll" -> handleAppScroll(decision.target)
            "youtube_play" -> handleYouTube(decision.message.ifBlank { decision.target }, autoPlay = true)
            "youtube" -> {
                val tLower = decision.transcript.lowercase(Locale.getDefault())
                val wantsPlay = tLower.contains("play") || tLower.contains("chala") ||
                    tLower.contains("lagao") || tLower.contains("sunao") || tLower.contains("baja")
                handleYouTube(decision.message.ifBlank { decision.target }, autoPlay = wantsPlay)
            }
            "open_app" -> handleOpenApp(decision.target.ifBlank { decision.transcript })
            "app_task" -> handleAppTask(decision.target, decision.message.ifBlank { decision.transcript })
            "media_control" -> handleMediaControl(decision.target.ifBlank { decision.transcript })
            "save_contact" -> handleSaveContactStructured(decision.target, decision.message, decision.transcript)
            "call_history" -> handleCallHistory()
            "vip_add" -> handleVipContactSetup(decision.target.ifBlank { decision.transcript })
            "memory_save" -> handleSaveMemory(decision.message.ifBlank { decision.transcript })
            "memory_recall" -> handleRecallMemories()
            "memory_clear" -> {
                geminiBrain.memoryManager.clearAllMemories()
                onLog("Memory: All personal memories wiped")
                speakAndFinish("Main ne aapki saari personal memories clear kar di hain.", "Memory Cleared")
            }
            "go_home" -> handleAccessibilityAction("home")
            "go_back" -> handleAccessibilityAction("back")
            "open_notifications" -> handleAccessibilityAction("notifications")
            "lock_screen" -> handleAccessibilityAction("lock")
            "chat" -> {
                val reply = decision.reply.ifBlank { "Mujhe samajh nahi aaya boss, dobara bolein." }
                onLog("Gemini: $reply")
                speakAndFinish(reply, badge = "Gemini AI")
            }
            else -> {
                if (decision.reply.isNotBlank()) {
                    speakAndFinish(decision.reply, badge = "Gemini AI")
                } else {
                    processOfflineCommand(decision.transcript)
                }
            }
        }
    }

    private fun processOfflineCommand(rawText: String) {
        val command = rawText.lowercase(Locale.getDefault()).trim()
        when {
            command.contains("long chat") || command.contains("live chat") || command.contains("guftugu shuru") || command.contains("start chat") -> {
                startContinuousChat()
            }
            isContinuousChatActive && (command.contains("chat band") || command.contains("guftugu band") || command.contains("stop chat") || command == "bye" || command == "alvida" || command == "stop") -> {
                stopContinuousChat(speakConfirmation = true, persistDisable = true)
            }
            command.contains("kya yaad") || command.contains("meri memories") || command.contains("what do you remember") -> {
                handleRecallMemories()
            }
            command.startsWith("yaad") || command.startsWith("remember") || command.contains("yaad rakho") -> {
                handleSaveMemory(rawText)
            }
            command.contains("memories clear") || command.contains("sab bhool jao") || command.contains("clear memories") -> {
                geminiBrain.memoryManager.clearAllMemories()
                speakAndFinish("Main ne aapki saari personal memories clear kar di hain.", "Memory Cleared")
            }
            command.contains("screen par kya") || command.contains("screen read") || command.contains("read screen") -> {
                handleReadScreen()
            }
            command.contains("is ka reply") || command.contains("reply do") || command.contains("jawab do") -> {
                handleWhatsAppReply("", rawText, "")
            }
            (command.contains("in sab") || command.contains("sab ko") || command.contains("campaign")) && command.contains("whatsapp") -> {
                handleMultiContactWhatsAppRaw(rawText)
            }
            command.contains("whatsapp") && (command.contains("msg") || command.contains("message") || command.contains("bhejo") || command.contains("send")) -> {
                handleSingleWhatsAppRaw(command)
            }
            command.contains("save number") || command.contains("number save") || command.contains("contact save") || command.contains("save contact") -> {
                handleSaveContact(rawText)
            }
            command.contains("call history") || command.contains("recent call") || command.contains("last call") || command.contains("kis ki call") -> {
                handleCallHistory()
            }
            command.contains("vip") && (command.contains("add") || command.contains("lagao") || command.contains("set") || command.contains("contact")) -> {
                handleVipContactSetup(command)
            }
            command.contains("skip ad") || command.contains("ad skip") -> {
                handleMediaControl("skip_ad")
            }
            command in setOf("pause", "roko", "pause karo", "stop video", "stop music") -> {
                handleMediaControl("play_pause")
            }
            command in setOf("play", "resume", "chalao", "dubara chalao", "play karo") -> {
                val acc = VectorAccessibilityService.instance
                if (acc?.captureScreenContext(200)?.contains("com.google.android.youtube") == true) {
                    inAppTaskAgent.runTask(
                        goal = "Play the first matching video on YouTube",
                        initialWaitMs = 200L,
                        youtubeAutoPlayPackage = "com.google.android.youtube"
                    ) { reply, badge -> speakAndFinish(reply, badge) }
                } else {
                    handleMediaControl("play_pause")
                }
            }
            command in setOf("next", "next song", "next video", "agla gana", "agla") -> {
                handleMediaControl("next")
            }
            command.contains("youtube") && (command.contains("search") || command.contains("play") || command.contains("chala") || command.contains("lagao") || command.contains("sunao") || command.contains("open") || command.contains("kholo")) -> {
                val wantsPlay = command.contains("play") || command.contains("chala") || command.contains("lagao") || command.contains("sunao")
                val query = command
                    .replace("youtube", "")
                    .replace("per search karo", "")
                    .replace("par", "")
                    .replace("search", "")
                    .replace("play", "")
                    .replace("chalao", "")
                    .replace("chala do", "")
                    .replace("lagao", "")
                    .replace("sunao", "")
                    .replace("open", "")
                    .replace("kholo", "")
                    .replace("karo", "")
                    .trim()
                handleYouTube(query, autoPlay = wantsPlay)
            }
            (command.contains("torch") || command.contains("flashlight") || command.contains("batti")) &&
                (command.contains("off") || command.contains("band") || command.contains("bujha") || command.contains("stop")) -> {
                toggleTorch(false)
            }
            (command.contains("torch") || command.contains("flashlight") || command.contains("batti")) &&
                (command.contains("on") || command.contains("chala") || command.contains("chalu") || command.contains("jala") || command.contains("start") || command.contains("khol")) -> {
                toggleTorch(true)
            }
            command == "torch" || command == "flashlight" -> toggleTorch(!isTorchOn)
            Regex("\\b(time|waqt|baje)\\b").containsMatchIn(command) && command.length < 40 -> handleTime()
            Regex("\\b(battery|charging)\\b").containsMatchIn(command) && command.length < 40 -> handleBattery()
            command.contains("volume up") || command.contains("awaaz barhao") || command.contains("awaaz tez") || command.contains("volume barhao") -> {
                handleVolume(raise = true, raw = command)
            }
            command.contains("volume down") || command.contains("awaaz kam") || command.contains("volume kam") || command == "mute" -> {
                handleVolume(raise = false, raw = command)
            }
            command.startsWith("call ") || command.contains("ko call") || command.contains("call lagao") || command.contains("call milao") || command.contains("phone milao") || command.startsWith("dial ") -> {
                handleCall(command)
            }
            (command.startsWith("open ") || command.endsWith(" kholo") || command.startsWith("launch ")) && !command.contains("kya") -> {
                handleOpenApp(command)
            }
            command in setOf("go home", "home screen", "bahar aao") -> handleAccessibilityAction("home")
            command in setOf("go back", "back jao", "peeche jao") -> handleAccessibilityAction("back")
            command.contains("lock screen") || command.contains("screen lock") -> handleAccessibilityAction("lock")
            else -> {
                if (geminiBrain.isConnected()) {
                    scope.launch {
                        val reply = geminiBrain.askGemini(rawText, isContinuousSession = isContinuousChatActive)
                        onLog("Gemini Response: $reply")
                        speakAndFinish(reply, badge = "Gemini AI")
                    }
                } else {
                    speakAndFinish(
                        "Gemini API key connect nahi hai. Settings mein ja kar key enter karein taake har sawal ka jawab mil sake.",
                        badge = "Setup Needed"
                    )
                }
            }
        }
    }

    private fun handleTime() {
        val currentTime = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        onLog("Action: Time ($currentTime)")
        speakAndFinish("Abhi waqt $currentTime hua hai.", badge = "Clock • $currentTime")
    }

    private fun handleBattery() {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        onLog("Action: Battery $level%")
        speakAndFinish("Aapke phone ki battery $level percent hai.", badge = "Battery • $level%")
    }

    private fun handleVolume(raise: Boolean, raw: String) {
        val stream = if (raw.contains("ring", ignoreCase = true)) AudioManager.STREAM_RING else AudioManager.STREAM_MUSIC
        val dir = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager?.adjustStreamVolume(stream, dir, AudioManager.FLAG_SHOW_UI)
        val msg = if (raise) "Volume barha diya gaya hai." else "Volume kam kar diya gaya hai."
        onLog("Action: Volume ${if (raise) "UP" else "DOWN"}")
        speakAndFinish(msg, badge = if (raise) "Volume Up" else "Volume Down")
    }

    private fun handleAccessibilityAction(action: String) {
        val acc = VectorAccessibilityService.instance
        if (acc == null) {
            speakAndFinish(
                "Phone control ke liye Settings mein Accessibility Service 'Vector Assistant' on karein.",
                badge = "Accessibility Required"
            )
            return
        }
        when (action) {
            "home" -> {
                acc.goHome()
                speakAndFinish("Home screen open ho gayi.", badge = "Home")
            }
            "back" -> {
                acc.goBack()
                speakAndFinish("Back kar diya gaya.", badge = "Back")
            }
            "notifications" -> {
                acc.openNotifications()
                speakAndFinish("Notifications open kar diye hain.", badge = "Notifications")
            }
            "lock" -> {
                acc.lockScreen()
                onCycleComplete?.invoke()
            }
        }
    }

    private fun handleMultiContactWhatsAppRaw(rawText: String) {
        val parts = rawText.split("ke ", "that ", "about ", limit = 2)
        val topic = if (parts.size > 1) parts[1].trim() else "Zaroori itla aur rabta"
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
        handleMultiContactWhatsAppStructured(contactsPart, topic)
    }

    private fun handleMultiContactWhatsAppStructured(targetsRaw: String, topicRaw: String) {
        val contactNames = targetsRaw.split(",", " aur ", " and ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (contactNames.isEmpty()) {
            speakAndFinish("Contacts ke naam sahi samajh nahi aaye. Dobara boleiye.", badge = "WhatsApp")
            return
        }

        val topic = topicRaw.ifBlank { "Zaroori itla aur rabta" }
        scope.launch {
            onLog("AI Campaign: Drafting message for ${contactNames.joinToString(", ")}")
            val attractiveMessage = geminiBrain.generateAttractiveWhatsAppMessage(topic)
            onLog("Drafted Message: $attractiveMessage")

            VectorSessionState.addVectorMessage(
                "Campaign Message Draft:\n$attractiveMessage",
                badge = "WhatsApp • ${contactNames.size} Contacts"
            )
            speaker.speak("Attractive message tayar hai. ${contactNames.size} contacts ko WhatsApp open kiya ja raha hai.")

            var launchedCount = 0
            for ((index, name) in contactNames.withIndex()) {
                onLog("Opening WhatsApp #${index + 1}: $name")
                val phoneNumber = lookupContactNumber(name)
                if (dispatchWhatsAppMessage(phoneNumber, attractiveMessage)) {
                    launchedCount++
                }
                delay(3500)
            }

            if (launchedCount > 0) {
                speakAndFinish("Sabhi contacts ke liye WhatsApp campaign mukammal ho gaya hai!", badge = "Campaign Done")
            } else {
                speakAndFinish("WhatsApp open nahi ho saka. App install ya overlay permission check karein.", badge = "WhatsApp Error")
            }
        }
    }

    private fun handleSingleWhatsAppRaw(command: String) {
        val clean = command.replace("whatsapp", "")
            .replace("per", "")
            .replace("par", "")
            .replace("ko", "")
            .replace("msg", "")
            .replace("message", "")
            .replace("karo", "")
            .replace("send", "")
            .replace("bhejo", "")
            .trim()

        val splitWords = clean.split("ke ", "that ", limit = 2)
        val targetName = splitWords[0].trim()
        val messageText = if (splitWords.size > 1) splitWords[1].trim() else "Salam!"
        handleSingleWhatsAppStructured(targetName, messageText)
    }

    private fun handleSingleWhatsAppStructured(targetName: String, messageText: String) {
        val cleanTarget = targetName.trim()
        if (cleanTarget.isEmpty()) {
            // If no target specified, maybe user wants to reply in active WhatsApp chat
            val fallbackTarget = WhatsAppChatAgent.getBestReplyTarget()
            if (fallbackTarget.isNotEmpty()) {
                handleWhatsAppReply(fallbackTarget, messageText, "")
                return
            }
            speakAndFinish("Kis contact ko WhatsApp message bhejna hai?", badge = "WhatsApp")
            return
        }
        val finalMsg = messageText.ifBlank { "Salam!" }
        WhatsAppChatAgent.markContactMessaged(cleanTarget)
        val phoneNumber = lookupContactNumber(cleanTarget)
        onLog("WhatsApp -> $cleanTarget ($phoneNumber): \"$finalMsg\"")

        // Check if user already has an active Direct-Reply notification for this exact sender
        val directSent = WhatsAppChatAgent.sendDirectNotificationReply(context, cleanTarget, finalMsg)
        if (directSent) {
            speakAndFinish(
                "$cleanTarget ko '$finalMsg' send kar diya gaya hai. Reply aane par main aapko bata doonga.",
                badge = "WhatsApp Sent • $cleanTarget"
            )
            return
        }

        onWakeScreenRequested?.invoke(true)
        WhatsAppChatAgent.armAutoSend(cleanTarget)
        val launched = dispatchWhatsAppMessage(phoneNumber, finalMsg)
        if (launched) {
            val accOn = VectorAccessibilityService.instance != null
            val spoken = if (accOn) {
                "$cleanTarget ko '$finalMsg' WhatsApp par bhej raha hoon. Unka jawab aane par aapko bata doonga."
            } else {
                "WhatsApp par $cleanTarget ka message tayar kar diya hai. Auto-send ke liye Accessibility on karein."
            }
            speakAndFinish(spoken, badge = "WhatsApp • $cleanTarget")
        } else {
            WhatsAppChatAgent.consumeAutoSendIfArmed()
            speakAndFinish("WhatsApp open nahi ho saka. App install ya overlay permission check karein.", badge = "WhatsApp Error")
        }
    }

    private fun handleWhatsAppReply(targetRaw: String, messageRaw: String, replyHint: String) {
        val target = WhatsAppChatAgent.getBestReplyTarget(targetRaw)
        val msgToSend = messageRaw.ifBlank { "Theek hai, thori der mein baat karte hain!" }.trim()

        // 1. Best path: Instant background RemoteInput Direct Reply (works even when screen is off or locked!)
        if (WhatsAppChatAgent.sendDirectNotificationReply(context, target, msgToSend)) {
            val displayTarget = target.ifBlank { "contact" }
            onLog("WhatsApp Direct Reply -> $displayTarget: \"$msgToSend\"")
            speakAndFinish(
                "$displayTarget ko reply bhej diya hai: '$msgToSend'",
                badge = "Replied • $displayTarget"
            )
            return
        }

        // 2. If WhatsApp is already open on screen, type & send directly inside current chat via Accessibility
        val acc = VectorAccessibilityService.instance
        if (acc != null) {
            val screenText = acc.captureScreenContext(200)
            if (screenText.contains("com.whatsapp")) {
                if (acc.sendTextInCurrentApp(msgToSend)) {
                    onLog("WhatsApp UI Reply typed & sent: \"$msgToSend\"")
                    speakAndFinish("WhatsApp chat mein reply bhej diya hai: '$msgToSend'", badge = "WhatsApp Sent")
                    return
                }
            }
        }

        // 3. Fallback: Open WhatsApp chat with pre-filled message & arm auto-send
        if (target.isNotBlank()) {
            handleSingleWhatsAppStructured(target, msgToSend)
        } else {
            val info = replyHint.ifBlank {
                "Abhi koi recent WhatsApp notification ya open chat nahi mili. Pehle contact ka naam bolein."
            }
            speakAndFinish(info, badge = "WhatsApp")
        }
    }

    private fun handleReadScreen() {
        val acc = VectorAccessibilityService.instance
        if (acc == null) {
            speakAndFinish(
                "Screen parhne ke liye Settings mein Accessibility Service 'Vector Assistant' on karein.",
                badge = "Accessibility Required"
            )
            return
        }
        val screenContext = acc.captureScreenContext(1600)
        if (screenContext.isBlank()) {
            speakAndFinish("Abhi screen par kuch readable text nazar nahi aa raha.", badge = "Screen Reader")
            return
        }
        if (geminiBrain.isConnected()) {
            scope.launch {
                val prompt = "Read and summarize concisely in 1-3 spoken Roman Urdu sentences what is currently visible on the user's phone screen:\n$screenContext"
                val summary = geminiBrain.askGemini(prompt, isContinuousSession = isContinuousChatActive)
                speakAndFinish(summary, badge = "Screen Reader")
            }
        } else {
            speakAndFinish(screenContext.take(260), badge = "Screen Reader")
        }
    }

    private fun handleAppClick(targetLabel: String) {
        val acc = VectorAccessibilityService.instance
        if (acc == null) {
            speakAndFinish("App control ke liye Accessibility Service on karein.", badge = "Accessibility Required")
            return
        }
        val clean = targetLabel.trim()
        if (clean.isEmpty()) {
            speakAndFinish("Kis cheez par click karna hai?", badge = "UI Action")
            return
        }
        onWakeScreenRequested?.invoke(true)
        if (acc.clickElementByText(clean)) {
            onLog("UI Click: \"$clean\"")
            speakAndFinish("'$clean' par tap kar diya hai.", badge = "Tapped")
        } else {
            speakAndFinish("Screen par '$clean' button nahi mila.", badge = "UI Action")
        }
    }

    private fun handleAppType(textToType: String) {
        val acc = VectorAccessibilityService.instance
        if (acc == null) {
            speakAndFinish("App ke andar likhne ke liye Accessibility Service on karein.", badge = "Accessibility Required")
            return
        }
        val clean = textToType.trim()
        if (clean.isEmpty()) {
            speakAndFinish("Kya likh kar send karna hai?", badge = "UI Input")
            return
        }
        onWakeScreenRequested?.invoke(true)
        if (acc.sendTextInCurrentApp(clean)) {
            onLog("UI Typed & Submitted: \"$clean\"")
            speakAndFinish("'$clean' likh kar bhej diya hai.", badge = "Sent In App")
        } else {
            speakAndFinish("Is screen par koi text box nahi mila.", badge = "UI Input")
        }
    }

    private fun handleAppScroll(direction: String) {
        val acc = VectorAccessibilityService.instance
        if (acc == null) {
            speakAndFinish("Scroll karne ke liye Accessibility Service on karein.", badge = "Accessibility Required")
            return
        }
        val forward = !direction.contains("up", ignoreCase = true) && !direction.contains("upar", ignoreCase = true)
        if (acc.scrollScreen(forward)) {
            speakAndFinish(if (forward) "Neeche scroll kar diya hai." else "Upar scroll kar diya hai.", badge = "Scrolled")
        } else {
            speakAndFinish("Yeh screen scroll nahi ho rahi.", badge = "Scroll")
        }
    }

    private fun dispatchWhatsAppMessage(phoneNumber: String?, messageText: String): Boolean {
        return try {
            val formattedNumber = phoneNumber?.replace("+", "")?.replace(" ", "")?.replace("-", "") ?: ""
            val url = if (formattedNumber.isNotEmpty()) {
                "https://api.whatsapp.com/send?phone=$formattedNumber&text=${Uri.encode(messageText)}"
            } else {
                "https://api.whatsapp.com/send?text=${Uri.encode(messageText)}"
            }

            val pm = context.packageManager
            val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
            var chosenIntent: Intent? = null

            for (pkg in packages) {
                val candidate = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setPackage(pkg)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (candidate.resolveActivity(pm) != null) {
                    chosenIntent = candidate
                    break
                }
            }

            if (chosenIntent == null) {
                chosenIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(chosenIntent)
            true
        } catch (e: Exception) {
            onLog("WhatsApp open error: ${e.message}")
            false
        }
    }

    private fun handleSaveMemory(rawText: String) {
        val fact = rawText
            .replace("yaad rakho ke", "", ignoreCase = true)
            .replace("yaad rakho", "", ignoreCase = true)
            .replace("remember that", "", ignoreCase = true)
            .replace("remember", "", ignoreCase = true)
            .replace("yaad rakhna ke", "", ignoreCase = true)
            .replace("yaad rakhna", "", ignoreCase = true)
            .replace("yaad rakh lo", "", ignoreCase = true)
            .trim()

        if (fact.length > 2) {
            val success = geminiBrain.memoryManager.addMemory(fact)
            if (success) {
                onLog("Memory Saved: \"$fact\"")
                speakAndFinish("Theek hai boss, main ne yaad rakh liya: $fact", badge = "Memory Saved")
            } else {
                speakAndFinish("Yeh baat mujhe pehle se yaad hai boss.", badge = "Memory")
            }
        } else {
            speakAndFinish("Aap kya baat yaad rakhwana chahte hain?", badge = "Memory")
        }
    }

    private fun handleRecallMemories() {
        val memories = geminiBrain.memoryManager.getAllMemories()
        if (memories.isEmpty()) {
            speakAndFinish("Mujhe abhi aapke bare mein koi baat yaad nahi hai. Bolen: 'Yaad rakho mera office 9 baje hai'.", badge = "Memory")
        } else {
            onLog("Memories Recalled: ${memories.size} facts")
            speakAndFinish("Mujhe aapke bare mein yeh baatein yaad hain: " + memories.joinToString(". "), badge = "Memory • ${memories.size}")
        }
    }

    private fun handleSaveContactStructured(target: String, message: String, rawFallback: String) {
        val digits = (message.ifBlank { rawFallback }).filter { it.isDigit() }
        val name = target.ifBlank {
            rawFallback.replace(digits, "")
                .replace("save number", "", ignoreCase = true)
                .replace("number save karo", "", ignoreCase = true)
                .replace("number save", "", ignoreCase = true)
                .replace("contact save", "", ignoreCase = true)
                .replace("karo", "", ignoreCase = true)
                .trim()
        }.ifBlank { "New Contact" }

        if (digits.length < 5) {
            speakAndFinish("Phone number sahi samajh nahi aaya. Dobara boleiye.", badge = "Contacts")
            return
        }
        saveContactToPhonebook(name, digits)
    }

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
            speakAndFinish("Number sahi samajh nahi aaya. Dobara boleiye.", badge = "Contacts")
            return
        }
        saveContactToPhonebook(name, digits)
    }

    private fun saveContactToPhonebook(name: String, digits: String) {
        try {
            val ops = ArrayList<ContentProviderOperation>()
            val idx = ops.size
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, idx)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, idx)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, digits)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            onLog("Action: Saved contact $name ($digits)")
            speakAndFinish("$name ka number save ho gaya hai.", badge = "Contact Saved")
        } catch (e: Exception) {
            onLog("Save contact error: ${e.message}")
            speakAndFinish("Contact save karne mein masla hua. Contacts permission check karein.", badge = "Permission Error")
        }
    }

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
                        summaryList.add("$typeStr from ${name ?: number ?: "Unknown"}")
                    } while (it.moveToNext())
                    val response = "Aapki aakhri calls hain: " + summaryList.joinToString(", ")
                    onLog("Call History: $response")
                    speakAndFinish(response, badge = "Call History")
                } else {
                    speakAndFinish("Koi recent call record nahi mila.", badge = "Call History")
                }
            } ?: speakAndFinish("Call history permission allow karein.", badge = "Permission Needed")
        } catch (e: Exception) {
            onLog("Call log error: ${e.message}")
            speakAndFinish("Call history check karne ke liye permission allow karein.", badge = "Permission Needed")
        }
    }

    private fun handleVipContactSetup(command: String) {
        val vipName = command
            .replace("vip", "", ignoreCase = true)
            .replace("contact", "", ignoreCase = true)
            .replace("lagao", "", ignoreCase = true)
            .replace("set", "", ignoreCase = true)
            .replace("add", "", ignoreCase = true)
            .replace("per", "", ignoreCase = true)
            .replace("ko", "", ignoreCase = true)
            .trim()

        if (vipName.isNotEmpty()) {
            VectorNotificationListenerService.addVipContact(context, vipName)
            onLog("VIP Added: $vipName")
            speakAndFinish("$vipName ko VIP alert list mein shamil kar diya gaya hai. Ab unka message aane par alarm bajega.", badge = "VIP • $vipName")
        } else {
            speakAndFinish("Kis contact ko VIP list mein add karna hai?", badge = "VIP")
        }
    }

    private fun handleYouTube(queryRaw: String, autoPlay: Boolean = false) {
        val query = queryRaw.trim()
        if (query.isEmpty()) {
            if (autoPlay) {
                val acc = VectorAccessibilityService.instance
                if (acc?.captureScreenContext(200)?.contains("com.google.android.youtube") == true) {
                    inAppTaskAgent.runTask(
                        goal = "Play the top video currently visible on YouTube",
                        initialWaitMs = 200L,
                        youtubeAutoPlayPackage = "com.google.android.youtube"
                    ) { reply, badge -> speakAndFinish(reply, badge) }
                    return
                }
            }
            handleOpenApp("youtube")
            return
        }
        inAppTaskAgent.cancelCurrentTask()
        onWakeScreenRequested?.invoke(true)
        onLog("Action: YouTube ${if (autoPlay) "Play" else "Search"} \"$query\"")
        try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
        }

        if (autoPlay) {
            if (VectorAccessibilityService.instance != null) {
                speaker.speak("YouTube par $query play kar raha hoon.")
                inAppTaskAgent.runTask(
                    goal = "Click and play the best matching non-sponsored video for '$query' on YouTube",
                    initialWaitMs = 2200L,
                    youtubeAutoPlayPackage = "com.google.android.youtube"
                ) { reply, badge ->
                    if (badge == "YouTube Play") {
                        VectorSessionState.addVectorMessage(reply, badge = badge)
                        onLog("YouTube Auto-Play: $reply")
                        if (isContinuousChatActive) {
                            onContinuousChatNextTurn?.invoke()
                        } else {
                            onCycleComplete?.invoke()
                        }
                    } else {
                        speakAndFinish(reply, badge = badge)
                    }
                }
            } else {
                speakAndFinish(
                    "YouTube par $query search khol diya hai. Auto-play ke liye Accessibility 'Vector Assistant' on karein.",
                    badge = "YouTube"
                )
            }
        } else {
            speakAndFinish("YouTube per $query search kar raha hoon.", badge = "YouTube")
        }
    }

    private fun handleAppTask(appTargetRaw: String, goalRaw: String) {
        val cleanGoal = goalRaw.trim()
        if (cleanGoal.isEmpty()) {
            speakAndFinish("App ke andar kya kaam karna hai?", badge = "UI Task")
            return
        }
        inAppTaskAgent.cancelCurrentTask()

        if (VectorAccessibilityService.instance == null) {
            speakAndFinish(
                "Kisi bhi app ke andar khud task complete karne ke liye Settings mein Accessibility Service 'Vector Assistant' on karein.",
                badge = "Accessibility Required"
            )
            return
        }

        val appName = appTargetRaw
            .replace("open", "", ignoreCase = true)
            .replace("kholo", "", ignoreCase = true)
            .replace("khol kar", "", ignoreCase = true)
            .trim()

        var waitMs = 300L
        if (appName.isNotEmpty()) {
            val opened = launchAppByQuery(appName)
            if (opened == null) {
                speakAndFinish("App '$appName' phone mein nahi mili.", badge = "App Not Found")
                return
            }
            onLog("App Task: Launched $opened -> Goal: \"$cleanGoal\"")
            waitMs = 2100L
        } else {
            onWakeScreenRequested?.invoke(true)
            onLog("App Task (Current Screen): \"$cleanGoal\"")
        }

        inAppTaskAgent.runTask(
            goal = cleanGoal,
            initialWaitMs = waitMs,
            youtubeAutoPlayPackage = if (appName.contains("youtube", ignoreCase = true)) "com.google.android.youtube" else null
        ) { reply, badge ->
            speakAndFinish(reply, badge = badge)
        }
    }

    private fun handleMediaControl(commandRaw: String) {
        val cmd = commandRaw.lowercase(Locale.getDefault()).trim()
        if (cmd.contains("skip")) {
            val skipped = VectorAccessibilityService.instance?.clickSkipAdIfPresent() == true
            if (skipped) {
                speakAndFinish("Ad skip kar diya gaya hai.", badge = "Media")
            } else {
                speakAndFinish("Abhi screen par Skip Ad button nazar nahi aaya.", badge = "Media")
            }
            return
        }

        val keyCode = when {
            cmd.contains("next") || cmd.contains("agla") -> KeyEvent.KEYCODE_MEDIA_NEXT
            cmd.contains("prev") || cmd.contains("pichla") -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }

        try {
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            val label = when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> "Agla track play kar diya hai."
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Pichla track play kar diya hai."
                else -> "Media play pause toggle kar diya hai."
            }
            speakAndFinish(label, badge = "Media Control")
        } catch (e: Exception) {
            onLog("Media key error: ${e.message}")
            speakAndFinish("Media control send nahi ho saka.", badge = "Media")
        }
    }

    private fun handleCall(rawTarget: String) {
        val targetName = rawTarget
            .replace("ko call milao", "", ignoreCase = true)
            .replace("ko call lagao", "", ignoreCase = true)
            .replace("ko call karo", "", ignoreCase = true)
            .replace("ko call", "", ignoreCase = true)
            .replace("call milao", "", ignoreCase = true)
            .replace("call lagao", "", ignoreCase = true)
            .replace("call karo", "", ignoreCase = true)
            .replace("phone milao", "", ignoreCase = true)
            .replace("phone karo", "", ignoreCase = true)
            .replace("ko phone", "", ignoreCase = true)
            .replace("call", "", ignoreCase = true)
            .replace("dial", "", ignoreCase = true)
            .replace("phone", "", ignoreCase = true)
            .replace("ko", "", ignoreCase = true)
            .trim()

        if (targetName.isEmpty()) {
            speakAndFinish("Kisko call karni hai?", badge = "Phone")
            return
        }

        val digitsOnly = targetName.filter { it.isDigit() || it == '+' }
        val phoneNumber = if (digitsOnly.length >= 5) digitsOnly else lookupContactNumber(targetName)

        if (phoneNumber != null) {
            onWakeScreenRequested?.invoke(true)
            onLog("Action: Calling $targetName ($phoneNumber)")
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(callIntent)
                speakAndFinish("$targetName ko call mila raha hoon.", badge = "Calling • $targetName")
            } catch (e: SecurityException) {
                onLog("Call permission error: ${e.message}")
                speakAndFinish("Call karne ke liye Phone permission allow karein.", badge = "Permission Needed")
            }
        } else {
            onLog("Contact not found: $targetName")
            speakAndFinish("Contact $targetName phonebook mein nahi mila.", badge = "Not Found")
        }
    }

    private fun handleOpenApp(rawTarget: String) {
        val appQuery = rawTarget
            .replace("open", "", ignoreCase = true)
            .replace("kholo", "", ignoreCase = true)
            .replace("launch", "", ignoreCase = true)
            .replace("chalao", "", ignoreCase = true)
            .trim()

        if (appQuery.isEmpty()) {
            speakAndFinish("Kaunsi app open karni hai?", badge = "Apps")
            return
        }

        val launchedLabel = launchAppByQuery(appQuery)
        if (launchedLabel != null) {
            speakAndFinish("$launchedLabel khol raha hoon.", badge = "App • $launchedLabel")
            return
        }

        onLog("App not found: $appQuery")
        speakAndFinish("App $appQuery phone mein nahi mili.", badge = "App Not Found")
    }

    private fun launchAppByQuery(appQuery: String): String? {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val lowerQuery = appQuery.lowercase(Locale.getDefault())

        var exactMatchPkg: String? = null
        var exactLabel = appQuery
        var prefixMatchPkg: String? = null
        var prefixLabel = appQuery
        var partialMatchPkg: String? = null
        var partialLabel = appQuery

        for (app in packages) {
            if (pm.getLaunchIntentForPackage(app.packageName) == null) continue
            val label = pm.getApplicationLabel(app).toString()
            val lowerLabel = label.lowercase(Locale.getDefault())
            when {
                lowerLabel == lowerQuery -> {
                    exactMatchPkg = app.packageName
                    exactLabel = label
                    break
                }
                lowerLabel.startsWith(lowerQuery) && prefixMatchPkg == null -> {
                    prefixMatchPkg = app.packageName
                    prefixLabel = label
                }
                lowerLabel.contains(lowerQuery) && partialMatchPkg == null -> {
                    partialMatchPkg = app.packageName
                    partialLabel = label
                }
            }
        }

        val chosenPkg = exactMatchPkg ?: prefixMatchPkg ?: partialMatchPkg ?: return null
        val chosenLabel = when (chosenPkg) {
            exactMatchPkg -> exactLabel
            prefixMatchPkg -> prefixLabel
            else -> partialLabel
        }

        val launchIntent = pm.getLaunchIntentForPackage(chosenPkg) ?: return null
        onWakeScreenRequested?.invoke(true)
        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(launchIntent)
        onLog("Action: Opened $chosenLabel ($chosenPkg)")
        return chosenLabel
    }

    private fun toggleTorch(enable: Boolean) {
        try {
            val cameraId = cameraManager?.cameraIdList?.firstOrNull() ?: run {
                speakAndFinish("Is phone mein flashlight available nahi hai.", badge = "Flashlight")
                return
            }
            cameraManager.setTorchMode(cameraId, enable)
            isTorchOn = enable
            onLog("Action: Torch ${if (enable) "ON" else "OFF"}")
            speakAndFinish(
                if (enable) "Torch on kar di gayi hai." else "Torch band kar di gayi hai.",
                badge = if (enable) "Torch ON" else "Torch OFF"
            )
        } catch (e: Exception) {
            Log.e("CommandExecutor", "Torch error: ${e.message}")
            speakAndFinish("Flashlight control karne mein masla aaya.", badge = "Torch Error")
        }
    }

    private fun speakAndFinish(text: String, badge: String? = null) {
        VectorSessionState.addVectorMessage(text, badge = badge)
        speaker.speak(text) {
            if (isContinuousChatActive) {
                onContinuousChatNextTurn?.invoke()
            } else {
                onCycleComplete?.invoke()
            }
        }
    }

    private fun stripWakePrefix(text: String): String {
        val prefixes = listOf(
            "hey vector", "hi vector", "ok vector", "okay vector", "suno vector",
            "aye vector", "hello vector", "hey victor", "suno victor", "vector"
        )
        val lower = text.lowercase(Locale.getDefault())
        for (p in prefixes) {
            if (lower.startsWith(p)) {
                return text.substring(p.length).trimStart(',', ' ', ':', '-', '.')
            }
        }
        return text
    }

    private fun lookupContactNumber(name: String): String? {
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        return try {
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
