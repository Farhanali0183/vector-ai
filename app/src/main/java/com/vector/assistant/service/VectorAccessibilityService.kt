package com.vector.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.vector.assistant.automation.WhatsAppChatAgent
import java.util.Locale

data class IndexedUiNode(
    val id: Int,
    val label: String,
    val classNameShort: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val bounds: Rect,
    val node: AccessibilityNodeInfo
)

data class UiSnapshot(
    val packageName: String,
    val summaryText: String,
    val items: List<IndexedUiNode>
)

/**
 * VectorAccessibilityService gives Vector real hands-free eyes and physical tap/type control inside ANY app:
 * - Global navigation: Home, Back, Notifications, Recents, Lock Screen
 * - Auto-completing WhatsApp sends (finding input box & Send button)
 * - Rich interactive UI tree capture + indexed node tap/type + GestureDescription tap fallback
 * - Smart YouTube / Search-Result auto-play & Skip-Ad support
 */
class VectorAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VectorAccessibility"
        var instance: VectorAccessibilityService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastAutoSendAttemptMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if ((pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") && WhatsAppChatAgent.isAutoSendArmed()) {
            val now = System.currentTimeMillis()
            if (now - lastAutoSendAttemptMs > 250L) {
                lastAutoSendAttemptMs = now
                tryCompleteWhatsAppSend(pkg)
                mainHandler.postDelayed({ tryCompleteWhatsAppSend(pkg) }, 480L)
                mainHandler.postDelayed({ tryCompleteWhatsAppSend(pkg) }, 1050L)
            }
        }
    }

    private fun tryCompleteWhatsAppSend(pkg: String) {
        if (!WhatsAppChatAgent.isAutoSendArmed()) return
        val root = rootInActiveWindow ?: return

        val sendIds = listOf("$pkg:id/send", "com.whatsapp:id/send", "com.whatsapp.w4b:id/send")
        var sendNode: AccessibilityNodeInfo? = null
        for (id in sendIds) {
            val found = root.findAccessibilityNodeInfosByViewId(id)
            if (!found.isNullOrEmpty()) {
                sendNode = found.firstOrNull { it.isClickable && it.isEnabled } ?: found.firstOrNull()
                if (sendNode != null) break
            }
        }

        if (sendNode == null) {
            sendNode = findClickableByTextOrDesc(root, listOf("send", "bhejo"))
        }

        if (sendNode != null && sendNode.isEnabled) {
            val clickable = ascendToClickable(sendNode)
            if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                WhatsAppChatAgent.consumeAutoSendIfArmed()
                Log.i(TAG, "Auto-clicked WhatsApp Send button")
            }
        }
    }

    override fun onInterrupt() {
        // Service interrupted
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun openNotifications() {
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    fun openRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun lockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    fun captureScreenContext(maxChars: Int = 1400): String {
        val root = rootInActiveWindow ?: return ""
        val pkg = root.packageName?.toString().orEmpty()
        val lines = LinkedHashSet<String>()
        collectVisibleTexts(root, lines, depth = 0)
        if (lines.isEmpty()) return if (pkg.isNotEmpty()) "Active App: $pkg" else ""

        val sb = StringBuilder()
        if (pkg.isNotEmpty()) {
            sb.append("Active App Package: ").append(pkg).append("\n")
        }
        sb.append("Visible Screen Text:\n")
        for (line in lines) {
            if (sb.length + line.length + 3 > maxChars) break
            sb.append("- ").append(line).append("\n")
        }
        return sb.toString().trim()
    }

    /**
     * Builds an indexed, LLM-friendly map of interactive UI controls & content items on screen
     * so Vector can autonomously navigate, search, click videos/links, or fill forms in ANY app.
     */
    fun captureRichUiSnapshot(): UiSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        val items = ArrayList<IndexedUiNode>()
        val seenKeys = HashSet<String>()

        collectInteractiveNodes(root, items, seenKeys, depth = 0)

        val sb = StringBuilder()
        sb.append("Current App Package: ").append(pkg.ifBlank { "unknown" }).append("\n")
        sb.append("Interactive Elements (use id number to act):\n")
        for (item in items) {
            val kind = when {
                item.isEditable -> "INPUT"
                item.isClickable -> "CLICK"
                else -> "TEXT"
            }
            sb.append("[").append(item.id).append("] ")
                .append(kind)
                .append(" (").append(item.classNameShort).append(") ")
                .append("\"").append(item.label).append("\" ")
                .append("y=").append(item.bounds.centerY())
                .append("\n")
        }

        return UiSnapshot(pkg, sb.toString().trim(), items)
    }

    fun clickNodeByIndex(snapshot: UiSnapshot, nodeId: Int): Boolean {
        val item = snapshot.items.firstOrNull { it.id == nodeId } ?: return false
        return clickOrTapNode(item.node, item.bounds)
    }

    fun typeTextIntoNode(
        snapshot: UiSnapshot,
        nodeId: Int,
        text: String,
        submitAfter: Boolean = true
    ): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false

        val target = snapshot.items.firstOrNull { it.id == nodeId }?.node
            ?: snapshot.items.firstOrNull { it.isEditable }?.node
            ?: rootInActiveWindow?.let { findFirstEditableNode(it) }

        if (target == null) {
            // Maybe user needs to tap Search icon first if no EditText is open yet
            val searchCandidate = snapshot.items.firstOrNull {
                it.isClickable && (
                    it.label.contains("search", ignoreCase = true) ||
                        it.label.contains("find", ignoreCase = true)
                    )
            }
            if (searchCandidate != null) {
                clickOrTapNode(searchCandidate.node, searchCandidate.bounds)
                return false
            }
            return false
        }

        target.refresh()
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, clean)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) return false

        if (submitAfter) {
            mainHandler.postDelayed({
                val freshRoot = rootInActiveWindow
                var submitted = false
                if (freshRoot != null) {
                    val pkg = freshRoot.packageName?.toString().orEmpty()
                    val btn = findNodeByIds(
                        freshRoot,
                        listOf("$pkg:id/send", "com.whatsapp:id/send", "com.whatsapp.w4b:id/send", "$pkg:id/search_go_btn")
                    ) ?: findClickableByTextOrDesc(freshRoot, listOf("send", "search", "submit", "go"))
                    if (btn != null) {
                        submitted = ascendToClickable(btn)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    }
                }
                if (!submitted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    target.refresh()
                    target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
            }, 340L)
        }
        return true
    }

    /**
     * Smart content clicker: clicks the first real playable video/song/result card on screen
     * (skipping ads, filters, search bar, and bottom navigation tabs). Ideal for YouTube/Spotify/search lists.
     */
    fun clickFirstContentResult(packageHint: String = ""): Boolean {
        val root = rootInActiveWindow ?: return false
        val screenH = resources.displayMetrics.heightPixels
        val screenW = resources.displayMetrics.widthPixels

        val skipLabels = setOf(
            "home", "shorts", "subscriptions", "you", "library", "explore",
            "all", "filters", "search", "navigate up", "more options",
            "cast", "notifications", "voice search", "clear", "back"
        )

        val candidates = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        collectClickableContentCards(root, candidates, depth = 0)

        val filtered = candidates.filter { (n, r) ->
            val desc = collectOwnAndChildText(n).lowercase(Locale.getDefault())
            val inContentVerticalBand = r.top >= (screenH * 0.16f).toInt() && r.bottom <= (screenH * 0.88f).toInt()
            val substantialWidth = r.width() >= (screenW * 0.38f).toInt()
            val substantialHeight = r.height() in 90..(screenH * 0.65f).toInt()
            val notAd = !desc.contains("sponsored") && !desc.contains("ad ·") && !desc.startsWith("ad ")
            val notChrome = desc.isNotEmpty() && desc !in skipLabels &&
                !desc.equals("more options", ignoreCase = true) &&
                !desc.contains("account")
            val matchPkg = packageHint.isBlank() || root.packageName?.toString()?.contains(packageHint) == true

            matchPkg && inContentVerticalBand && substantialWidth && substantialHeight && notAd && notChrome
        }.sortedBy { it.second.top }

        val best = filtered.firstOrNull() ?: return false
        Log.i(TAG, "Clicking first content result at bounds=${best.second}: ${collectOwnAndChildText(best.first).take(60)}")
        return clickOrTapNode(best.first, best.second)
    }

    fun clickSkipAdIfPresent(): Boolean {
        val root = rootInActiveWindow ?: return false
        val skipNode = findClickableByTextOrDesc(root, listOf("skip ad", "skip ads", "skip"))
            ?: return false
        val rect = Rect()
        skipNode.getBoundsInScreen(rect)
        return clickOrTapNode(skipNode, rect)
    }

    fun sendTextInCurrentApp(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString().orEmpty()

        val targetInput: AccessibilityNodeInfo? =
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: findNodeByIds(root, listOf("$pkg:id/entry", "com.whatsapp:id/entry", "com.whatsapp.w4b:id/entry"))
                ?: findFirstEditableNode(root)

        if (targetInput == null) return false

        targetInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, clean)
        }
        val textSet = targetInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!textSet) return false

        mainHandler.postDelayed({
            val freshRoot = rootInActiveWindow ?: return@postDelayed
            val sendBtn = findNodeByIds(
                freshRoot,
                listOf("$pkg:id/send", "com.whatsapp:id/send", "com.whatsapp.w4b:id/send")
            ) ?: findClickableByTextOrDesc(freshRoot, listOf("send", "search", "submit", "go", "post"))

            if (sendBtn != null) {
                ascendToClickable(sendBtn)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                targetInput.refresh()
                targetInput.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }, 320L)

        return true
    }

    fun clickElementByText(query: String): Boolean {
        val cleanQuery = query.trim().lowercase(Locale.getDefault())
        if (cleanQuery.isEmpty()) return false
        val root = rootInActiveWindow ?: return false

        val matches = root.findAccessibilityNodeInfosByText(query.trim())
        if (!matches.isNullOrEmpty()) {
            for (m in matches) {
                val rect = Rect()
                m.getBoundsInScreen(rect)
                if (clickOrTapNode(m, rect)) return true
            }
        }

        val fallback = findClickableByTextOrDesc(root, listOf(cleanQuery))
        if (fallback != null) {
            val rect = Rect()
            fallback.getBoundsInScreen(rect)
            return clickOrTapNode(fallback, rect)
        }
        return false
    }

    fun scrollScreen(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findFirstScrollableNode(root) ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return scrollable.performAction(action)
    }

    private fun clickOrTapNode(node: AccessibilityNodeInfo, knownBounds: Rect): Boolean {
        node.refresh()
        val clickable = ascendToClickable(node)
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val targetRect = if (!rect.isEmpty) rect else knownBounds
        if (!targetRect.isEmpty && targetRect.centerX() > 0 && targetRect.centerY() > 0) {
            return performTapAt(targetRect.centerX(), targetRect.centerY())
        }
        return false
    }

    fun performTapAt(x: Int, y: Int): Boolean {
        if (x <= 0 || y <= 0) return false
        return try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Tap gesture failed at ($x,$y): ${e.message}")
            false
        }
    }

    private fun collectInteractiveNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<IndexedUiNode>,
        seenKeys: MutableSet<String>,
        depth: Int
    ) {
        if (node == null || depth > 22 || out.size >= 40) return
        if (node.isVisibleToUser) {
            val isEditable = node.isEditable
            val isClickable = node.isClickable
            val rawLabel = collectOwnAndChildText(node)
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val shouldInclude = (isEditable || isClickable) &&
                !rect.isEmpty &&
                (rawLabel.isNotBlank() || isEditable)

            if (shouldInclude) {
                val label = rawLabel.ifBlank { "Text input field" }.take(90)
                val checkSuffix = if (node.isCheckable) " [checked=${node.isChecked}]" else ""
                val key = "$isEditable|$label|${rect.centerY() / 30}"
                if (seenKeys.add(key)) {
                    val shortClass = node.className?.toString()?.substringAfterLast('.') ?: "View"
                    out.add(
                        IndexedUiNode(
                            id = out.size + 1,
                            label = "$label$checkSuffix",
                            classNameShort = shortClass,
                            isClickable = isClickable,
                            isEditable = isEditable,
                            bounds = rect,
                            node = node
                        )
                    )
                }
                // Avoid duplicating nested child labels inside an already-captured leaf button
                if (!isEditable && rawLabel.length < 40 && node.childCount <= 2) return
            }
        }
        for (i in 0 until node.childCount) {
            collectInteractiveNodes(node.getChild(i), out, seenKeys, depth + 1)
        }
    }

    private fun collectClickableContentCards(
        node: AccessibilityNodeInfo?,
        out: MutableList<Pair<AccessibilityNodeInfo, Rect>>,
        depth: Int
    ) {
        if (node == null || depth > 20 || out.size >= 30) return
        if (node.isVisibleToUser && node.isClickable && node.isEnabled) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty && rect.width() > 150 && rect.height() > 80) {
                out.add(Pair(node, rect))
            }
        }
        for (i in 0 until node.childCount) {
            collectClickableContentCards(node.getChild(i), out, depth + 1)
        }
    }

    private fun collectOwnAndChildText(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null || depth > 4) return ""
        val parts = LinkedHashSet<String>()
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        if (parts.isEmpty() || depth < 3) {
            for (i in 0 until node.childCount.coerceAtMost(8)) {
                val childTxt = collectOwnAndChildText(node.getChild(i), depth + 1)
                if (childTxt.isNotEmpty()) parts.add(childTxt)
            }
        }
        return parts.joinToString(" • ").take(120)
    }

    private fun findNodeByIds(root: AccessibilityNodeInfo, ids: List<String>): AccessibilityNodeInfo? {
        for (id in ids) {
            val found = root.findAccessibilityNodeInfosByViewId(id)
            if (!found.isNullOrEmpty()) {
                return found.first()
            }
        }
        return null
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val hit = findFirstEditableNode(node.getChild(i))
            if (hit != null) return hit
        }
        return null
    }

    private fun findFirstScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val hit = findFirstScrollableNode(node.getChild(i))
            if (hit != null) return hit
        }
        return null
    }

    private fun findClickableByTextOrDesc(
        node: AccessibilityNodeInfo?,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser) {
            val txt = node.text?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase(Locale.getDefault()).orEmpty()
            if (keywords.any { kw -> (txt.isNotEmpty() && txt.contains(kw)) || (desc.isNotEmpty() && desc.contains(kw)) }) {
                if (ascendToClickable(node) != null) return node
            }
        }
        for (i in 0 until node.childCount) {
            val hit = findClickableByTextOrDesc(node.getChild(i), keywords)
            if (hit != null) return hit
        }
        return null
    }

    private fun ascendToClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var curr = node
        var hops = 0
        while (curr != null && hops < 5) {
            if (curr.isClickable && curr.isEnabled) return curr
            curr = curr.parent
            hops++
        }
        return null
    }

    private fun collectVisibleTexts(
        node: AccessibilityNodeInfo?,
        out: MutableSet<String>,
        depth: Int
    ) {
        if (node == null || depth > 22 || out.size >= 45) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank() && text.length >= 2) {
                out.add(text)
            } else {
                val desc = node.contentDescription?.toString()?.trim()
                if (!desc.isNullOrBlank() && desc.length >= 2 &&
                    !desc.equals("Navigate up", ignoreCase = true)
                ) {
                    out.add(desc)
                }
            }
        }
        for (i in 0 until node.childCount) {
            collectVisibleTexts(node.getChild(i), out, depth + 1)
        }
    }
}
