package com.vector.assistant.automation

import com.vector.assistant.ai.GeminiBrainClient
import com.vector.assistant.service.VectorAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class UiStepDecision(
    val status: String, // "continue" | "done" | "failed"
    val action: String, // "click" | "type_and_send" | "scroll_down" | "scroll_up" | "back" | "wait" | "none"
    val nodeId: Int = -1,
    val text: String = "",
    val reply: String = ""
)

/**
 * Autonomous Multi-Step In-App Agent:
 * Reads the live Accessibility UI tree step-by-step and drives ANY opened app
 * (YouTube video play, Spotify/Music play, Chrome browsing, WhatsApp chat navigation,
 * System Settings toggles, etc.) until the user's goal is actually completed.
 */
class InAppTaskAgent(
    private val geminiBrain: GeminiBrainClient,
    private val onLog: (String) -> Unit
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeJob: Job? = null

    fun cancelCurrentTask() {
        activeJob?.cancel()
        activeJob = null
    }

    fun runTask(
        goal: String,
        initialWaitMs: Long = 1800L,
        youtubeAutoPlayPackage: String? = null,
        onFinished: (spokenReply: String, badge: String) -> Unit
    ) {
        cancelCurrentTask()
        activeJob = scope.launch {
            val acc = VectorAccessibilityService.instance
            if (acc == null) {
                onFinished(
                    "App ke andar kaam karne ke liye Settings mein Accessibility Service 'Vector Assistant' on karein.",
                    "Accessibility Required"
                )
                return@launch
            }

            if (initialWaitMs > 0L) {
                delay(initialWaitMs)
            }

            val stepHistory = ArrayList<String>()
            val maxSteps = if (youtubeAutoPlayPackage != null) 3 else 6

            for (step in 1..maxSteps) {
                val snapshot = acc.captureRichUiSnapshot()
                if (snapshot == null || snapshot.items.isEmpty()) {
                    if (step == 1 && youtubeAutoPlayPackage != null) {
                        delay(900L)
                        continue
                    }
                    onFinished("Screen ke controls read nahi ho sake.", "UI Task")
                    return@launch
                }

                // Fast path if Gemini API key isn't connected on YouTube playback
                if (!geminiBrain.isConnected() && youtubeAutoPlayPackage != null) {
                    val clicked = acc.clickFirstContentResult(youtubeAutoPlayPackage)
                    if (clicked) {
                        onFinished("Pehli matching video play kar raha hoon.", "YouTube Play")
                    } else {
                        onFinished("YouTube results open kar diye hain.", "YouTube")
                    }
                    return@launch
                }

                val decision = geminiBrain.decideNextUiStep(
                    goal = goal,
                    step = step,
                    uiSummary = snapshot.summaryText,
                    previousActions = stepHistory
                )

                onLog("UI Step #$step [${decision.status}/${decision.action}] id=${decision.nodeId} text=\"${decision.text}\"")

                if (decision.status == "done") {
                    if (decision.action != "none" && decision.action != "wait") {
                        performUiAction(acc, snapshot, decision)
                    }
                    val finalReply = decision.reply.ifBlank { "Task mukammal ho gaya hai boss." }
                    onFinished(finalReply, "Task Complete")
                    return@launch
                }

                if (decision.status == "failed") {
                    if (youtubeAutoPlayPackage != null && acc.clickFirstContentResult(youtubeAutoPlayPackage)) {
                        onFinished("Video play kar di gayi hai.", "YouTube Play")
                    } else {
                        onFinished(
                            decision.reply.ifBlank { "App ke andar yeh option screen par nahi mila." },
                            "UI Task"
                        )
                    }
                    return@launch
                }

                val acted = performUiAction(acc, snapshot, decision)
                stepHistory.add("Step $step: ${decision.action} node=${decision.nodeId} text='${decision.text}' -> ok=$acted")

                if (youtubeAutoPlayPackage != null && decision.action == "click" && acted) {
                    // On YouTube, clicking the chosen video starts playback immediately!
                    delay(800L)
                    onFinished(
                        decision.reply.ifBlank { "Video play kar raha hoon." },
                        "YouTube Play"
                    )
                    // Background convenience check for pre-roll Skip Ad
                    scope.launch {
                        delay(4500L)
                        VectorAccessibilityService.instance?.clickSkipAdIfPresent()
                    }
                    return@launch
                }

                delay(1250L)
            }

            if (youtubeAutoPlayPackage != null && acc.clickFirstContentResult(youtubeAutoPlayPackage)) {
                onFinished("Matching video play kar raha hoon.", "YouTube Play")
                return@launch
            }
            onFinished("App ke andar ka task yahan tak complete kar diya hai.", "UI Task")
        }
    }

    private fun performUiAction(
        acc: VectorAccessibilityService,
        snapshot: com.vector.assistant.service.UiSnapshot,
        decision: UiStepDecision
    ): Boolean {
        return when (decision.action) {
            "click" -> {
                val byId = if (decision.nodeId > 0) acc.clickNodeByIndex(snapshot, decision.nodeId) else false
                byId || (decision.text.isNotBlank() && acc.clickElementByText(decision.text))
            }
            "type_and_send" -> {
                acc.typeTextIntoNode(snapshot, decision.nodeId, decision.text, submitAfter = true)
            }
            "scroll_down" -> acc.scrollScreen(forward = true)
            "scroll_up" -> acc.scrollScreen(forward = false)
            "back" -> {
                acc.goBack()
                true
            }
            "wait" -> true
            else -> false
        }
    }
}

