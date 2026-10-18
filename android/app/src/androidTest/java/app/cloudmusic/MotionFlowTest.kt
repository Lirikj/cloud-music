package app.cloudmusic

import android.content.ComponentName
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cloudmusic.playback.PlaybackService
import app.cloudmusic.data.Provider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

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

@RunWith(AndroidJUnit4::class)
class MotionFlowTest {
    @Test fun openAndCollapsePlayerWithRealAnimationClock() {
        val i = NativeUi.instrumentation
        val app = i.targetContext.applicationContext as CloudMusicApplication
        runBlocking { app.repository.load(); app.repository.demo() }
        val demo = app.repository.library.value.tracks.filter { it.provider == Provider.DEMO }
        ActivityScenario.launch(MainActivity::class.java).use {
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            i.runOnMainSync { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            val player = future.get(15, TimeUnit.SECONDS)
            try {
                i.runOnMainSync { player.setMediaItems(demo.map { MediaItem.Builder().setMediaId(it.id).setUri(it.remoteId).build() }); player.repeatMode = Player.REPEAT_MODE_ALL; player.prepare(); player.play() }
                NativeUi.click("Открыть плеер", true)
                NativeUi.click("Свернуть плеер", true)
                Thread.sleep(500)
                NativeUi.shell("dumpsys gfxinfo app.cloudmusic reset")
                repeat(8) {
                    NativeUi.click("Открыть плеер", true); Thread.sleep(250)
                    NativeUi.click("Свернуть плеер", true); Thread.sleep(250)
                }
                val label = InstrumentationRegistry.getArguments().getString("motion_label", "latest")!!
                val dir = File(app.getExternalFilesDir(null), "qa").apply { mkdirs() }
                File(dir, "motion-$label.txt").writeText(NativeUi.shell("dumpsys gfxinfo app.cloudmusic"))
                File(dir, "motion-$label-frames.txt").writeText(NativeUi.shell("dumpsys gfxinfo app.cloudmusic framestats"))
                NativeUi.click("Открыть плеер", true)
                NativeUi.click("Свернуть плеер", true)
            } finally {
                i.runOnMainSync { player.pause(); player.clearMediaItems(); MediaController.releaseFuture(future) }
                runBlocking { app.repository.library.value.folders.filter { it.provider == Provider.DEMO }.forEach { app.repository.remove(it) } }
            }
        }
    }
}
