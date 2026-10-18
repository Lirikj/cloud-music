package app.cloudmusic.releaseqa
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
internal object NativeUi {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    fun find(value: String, description: Boolean = false): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val text = if (description) node.contentDescription else node.text
            if (text?.toString() == value) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    fun click(value: String, description: Boolean = false, timeout: Long = 10_000) {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < end) {
            var node = find(value, description)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { Thread.sleep(150); return }
            Thread.sleep(100)
        }
        throw AssertionError("UI element unavailable: $value")
    }
    fun restoreNetwork() {
        shell("svc wifi enable"); shell("svc data enable")
        val context = instrumentation.targetContext
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val end = android.os.SystemClock.elapsedRealtime() + 10_000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            if (capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) { Thread.sleep(300); return }
            Thread.sleep(100)
        }
    }
    fun setText(value: String) {
        fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return node
            for (index in 0 until node.childCount) editable(node.getChild(index))?.let { return it }
            return null
        }
        val end = android.os.SystemClock.elapsedRealtime() + 5000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            val node = editable(instrumentation.uiAutomation.rootInActiveWindow)
            if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })) { Thread.sleep(300); return }
            Thread.sleep(100)
        }
        throw AssertionError("No editable field")
    }
    fun scrollDown() {
        fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.actionList.any { it.id in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id) }) return node
            for (index in 0 until node.childCount) scrollable(node.getChild(index))?.let { return it }
            return null
        }
        val end = android.os.SystemClock.elapsedRealtime() + 5000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val node = scrollable(root)
            if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { Thread.sleep(500); return }
            if (root?.packageName?.toString() == "app.cloudmusic") {
                val bounds = android.graphics.Rect(); root.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    // Swipe coordinates come from the live accessibility tree, never a screenshot.
                    val x = bounds.centerX(); val y1 = bounds.top + bounds.height() * 3 / 4; val y2 = bounds.top + bounds.height() / 3
                    shell("input swipe $x $y1 $x $y2 400"); Thread.sleep(500); return
                }
            }
            Thread.sleep(100)
        }
        throw AssertionError("No scrollable UI")
    }
    fun screenshot(name: String) {
        Thread.sleep(250)
        val context = instrumentation.targetContext
        val dir = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        val image = instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("No native screenshot")
        File(dir, name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    fun dump(): String {
        fun walk(node: AccessibilityNodeInfo?, depth: Int): String = if (node == null) "" else " ".repeat(depth) + "${node.text} | ${node.contentDescription}\n" + (0 until node.childCount).joinToString("") { walk(node.getChild(it), depth + 1) }
        return walk(instrumentation.uiAutomation.rootInActiveWindow, 0)
    }
}

