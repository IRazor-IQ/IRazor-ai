package com.irazor.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ScreenContextService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenContext"
        private const val MAX_UI_ELEMENTS = 80
        private const val MAX_DEPTH = 6
        private const val TEXT_MAX = 120

        private val knownCodeEditors = setOf(
            "com.android.tools.idea", "com.jetbrains", "com.aide.ui",
            "com.foxdebug.acode", "com.duy.compiler.javascript", "com.akshatsakhadeo.quickedit",
            "com.enlightedinc.quicksshd", "com.kvannli.monkey", "com.nerdherd.ardoxd",
            "com.radinc.cxxdroid", "com.spartacusrex.spartacuside", "com.termux",
            "com.teamdev.jedit", "io.armycommander.phoneide", "ru.sash0k.androidide"
        )
        private val knownBrowsers = setOf(
            "com.android.chrome", "com.android.browser", "com.microsoft.emmx",
            "org.mozilla.firefox", "com.opera.browser", "com.brave.browser",
            "com.kiwibrowser.browser", "com.vivaldi.browser"
        )
        private val knownShoppers = setOf(
            "com.amazon.mShop.android", "com.alibaba.aliexpress", "com.shopify",
            "com.ebay.mobile", "com.walmart.android", "com.target.ui",
            "com.jumia.android", "com.noon.app"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 600
        }
        serviceInfo = info
        ScreenContextRepository.setServiceEnabled(true)
        Log.i(TAG, "ScreenContextService connected and ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        // Skip our own app to avoid infinite loops
        if (pkg == "com.irazor.ai") return

        val root = rootInActiveWindow ?: return
        try {
            val className = event.className?.toString() ?: "unknown"
            val windowTitle = extractWindowTitle(root)
            val uiElements = mutableListOf<String>()
            collectUIElements(root, uiElements, 0)
            val openFile = detectOpenFile(root, pkg)

            val context = buildScreenContext(pkg, className, windowTitle, openFile, uiElements)
            ScreenContextRepository.setContext(context, pkg)
            Log.d(TAG, "Context captured: $pkg | elements=${uiElements.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing context", e)
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "ScreenContextService interrupted")
    }

    override fun onDestroy() {
        ScreenContextRepository.setServiceEnabled(false)
        super.onDestroy()
    }

    // ── Context builders ────────────────────────────────────────────

    private fun buildScreenContext(
        pkg: String, className: String, windowTitle: String,
        openFile: String?, elements: List<String>
    ): String {
        val appLabel = guessAppLabel(pkg)
        val category = guessCategory(pkg)

        return buildString {
            appendLine("foregroundApp: $pkg")
            appendLine("appLabel: $appLabel")
            appendLine("category: $category")
            appendLine("activityName: $className")
            if (windowTitle.isNotBlank()) appendLine("windowTitle: $windowTitle")
            if (openFile != null) appendLine("openFile: $openFile")

            if (elements.isNotEmpty()) {
                appendLine("uiHierarchy:")
                elements.forEach { appendLine("  $it") }
            }
        }
    }

    // ── UI hierarchy walker ─────────────────────────────────────────

    private fun collectUIElements(
        node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_UI_ELEMENTS) return
        if (node == null) return

        val text = node.text?.toString()?.take(TEXT_MAX)?.replace("\n", "\\n")
        val desc = node.contentDescription?.toString()?.take(TEXT_MAX)?.replace("\n", "\\n")
        val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
        val viewId = try {
            node.viewIdResourceName?.takeIf { it.isNotBlank() && it != "android:id/statusBarBackground" }
        } catch (_: Exception) { null }

        val isEditable = node.isEditable
        val isClickable = node.isClickable
        val isChecked = node.isChecked
        val isScrollable = node.isScrollable
        val isPassword = node.isPassword
        val inputType = try { node.inputType } catch (_: Exception) { 0 }

        val label = when {
            text != null && text.isNotBlank() -> text
            desc != null && desc.isNotBlank() -> "[${desc}]"
            else -> null
        }

        if (label != null || isEditable || isClickable || isScrollable || isChecked) {
            val parts = mutableListOf(className)
            if (viewId != null && viewId != className) parts.add("(#${viewId.substringAfterLast("/")})")
            if (label != null) parts.add("=\"${label.take(80)}\"")
            if (isEditable && isPassword) parts.add("[password]")
            else if (isEditable) parts.add("[edit]")
            if (isClickable) parts.add("[tap]")
            if (isChecked) parts.add("[on]")
            if (!isChecked && node.isCheckable) parts.add("[off]")
            if (isScrollable) parts.add("[scroll]")
            if (inputType and 0x2000 != 0) parts.add("[multiline]")

            out.add("  ".repeat(depth) + parts.joinToString(" "))
        }

        for (i in 0 until node.childCount) {
            try {
                node.getChild(i)?.let { collectUIElements(it, out, depth + 1) }
            } catch (_: Exception) { break }
        }
    }

    // ── Window title ────────────────────────────────────────────────

    private fun extractWindowTitle(root: AccessibilityNodeInfo): String {
        val candidates = mutableListOf<String>()
        try {
            root.findAccessibilityNodeInfosByViewId("android:id/title")?.forEach {
                it.text?.toString()?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
            }
            root.findAccessibilityNodeInfosByViewId("android:id/action_bar_title")?.forEach {
                it.text?.toString()?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
            }
        } catch (_: Exception) {}
        return candidates.firstOrNull { it.length < 200 } ?: ""
    }

    // ── openFile detection ─────────────────────────────────────────

    private fun detectOpenFile(root: AccessibilityNodeInfo, pkg: String): String? {
        if (pkg in knownCodeEditors || pkg.contains("editor", true) || pkg.contains("studio", true)) {
            return scanForEditorTab(root)
        }
        return null
    }

    private fun scanForEditorTab(root: AccessibilityNodeInfo): String? {
        val candidates = mutableListOf<String>()
        fun scan(node: AccessibilityNodeInfo) {
            if (candidates.size > 10) return
            val text = node.text?.toString()?.trim()
            if (text != null && text.isNotBlank() && text.length < 150 && node.isClickable && !text.contains(" ")) {
                // File paths and names with extensions
                if (text.contains(".") || text.contains("/")) {
                    candidates.add(text)
                }
            }
            for (i in 0 until node.childCount) {
                try { node.getChild(i)?.let { scan(it) } } catch (_: Exception) { break }
            }
        }
        try { scan(root) } catch (_: Exception) {}
        // Return the most code-like filename (has extension, common code extensions)
        val codeExts = setOf(".kt", ".java", ".xml", ".py", ".js", ".ts", ".html", ".css", ".cpp", ".c", ".h", ".gradle", ".json", ".md", ".php", ".swift", ".go", ".rs", ".rb", ".kt", ".kts", ".dart", ".ex", ".vue", ".svelte")
        return candidates.firstOrNull { codeExts.any { ext -> it.contains(ext, true) } }
            ?: candidates.firstOrNull { it.contains(".") }
    }

    // ── App labeling ────────────────────────────────────────────────

    private fun guessAppLabel(pkg: String): String {
        return when {
            pkg in knownCodeEditors || pkg.contains("editor", true) -> "Code Editor"
            pkg in knownBrowsers -> "Browser"
            pkg in knownShoppers -> "Shopping"
            pkg.contains("launcher", true) || pkg == "com.android.systemui" -> "Home Screen"
            pkg.contains("settings", true) || pkg.contains("com.android.settings") -> "Settings"
            pkg.contains("game", true) || pkg.contains("unity") || pkg.contains("cocos") -> "Game"
            pkg.contains("mchat", true) || pkg.contains("whatsapp") || pkg.contains("telegram") -> "Messaging"
            pkg.contains("players", true) || pkg.contains("youtube") || pkg.contains("mxplayer") -> "Media"
            else -> pkg.substringAfterLast(".")
        }
    }

    private fun guessCategory(pkg: String): String {
        return when {
            pkg in knownCodeEditors || pkg.contains("editor", true) || pkg.contains("studio", true) -> "coding"
            pkg in knownBrowsers -> "browsing"
            pkg in knownShoppers -> "shopping"
            pkg.contains("launcher", true) || pkg == "com.android.systemui" -> "home"
            pkg.contains("settings", true) || pkg == "com.android.settings" -> "settings"
            pkg.contains("game", true) || pkg.contains("unity") || pkg.contains("cocos") || pkg.contains("com.termux") -> "gaming"
            pkg.contains("mchat", true) || pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("messages") -> "messaging"
            pkg.contains("players", true) || pkg.contains("youtube") || pkg.contains("mxplayer") || pkg.contains("galaxy") -> "media"
            else -> "other"
        }
    }
}
