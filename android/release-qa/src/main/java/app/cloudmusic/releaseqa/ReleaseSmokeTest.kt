package app.cloudmusic.releaseqa

import android.content.pm.ApplicationInfo
import android.graphics.drawable.AdaptiveIconDrawable
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReleaseSmokeTest {
    private val i get() = NativeUi.instrumentation
    @Before fun permissions() { i.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL") }
    @After fun cleanup() { NativeUi.restoreNetwork(); i.uiAutomation.dropShellPermissionIdentity() }
    private fun waitFor(timeout: Long = 20_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out. Visible UI:\n${NativeUi.dump()}")
    }
    private fun launch() {
        NativeUi.shell("am force-stop app.cloudmusic")
        NativeUi.shell("am start -W -n app.cloudmusic/.MainActivity")
        waitFor { NativeUi.find("Музыка") != null }
    }
    private fun media(): MediaController? = i.context.getSystemService(MediaSessionManager::class.java)
        .getActiveSessions(null).firstOrNull { it.packageName == "app.cloudmusic" }
    private fun playing() = media()?.playbackState?.state == PlaybackState.STATE_PLAYING
    private fun position(): Long {
        val s = media()?.playbackState ?: return 0
        return s.position + if (s.state == PlaybackState.STATE_PLAYING) ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong() else 0
    }
    private fun search(query: String) { NativeUi.click("Поиск"); NativeUi.setText(query) }
    private fun visiblePrefix(prefix: String): String? {
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?): String? {
            if (n == null) return null
            n.text?.toString()?.takeIf { it.startsWith(prefix) }?.let { return it }
            for (j in 0 until n.childCount) walk(n.getChild(j))?.let { return it }
            return null
        }
        return walk(i.uiAutomation.rootInActiveWindow)
    }
    @Test fun aCloudStreamingDownloadAndOfflineRestart() {
        val cloudFolder = i.arguments.getString("cloudMusicTestFolder")?.takeIf { it.startsWith("https://") } ?: return
        launch()
        NativeUi.setText(cloudFolder)
        NativeUi.click("Подключить папку")
        waitFor(60_000) { NativeUi.find("Моя музыка") != null }
        NativeUi.screenshot("release-library-dark.png")
        search("Dancing in My Room")
        NativeUi.click("Включить Dancing in My Room — 347aidan", true)
        waitFor(45_000) { playing() && position() > 500 }
        assertEquals("Dancing in My Room", media()!!.metadata!!.getString(android.media.MediaMetadata.METADATA_KEY_TITLE))
        val before = position(); NativeUi.shell("input keyevent 3"); Thread.sleep(2000)
        assertTrue("Cloud audio continues in background", playing() && position() > before + 1000)
        media()!!.transportControls.seekTo(30_000)
        waitFor { playing() && position() >= 30_000 }
        NativeUi.shell("am start -W -n app.cloudmusic/.MainActivity")
        NativeUi.click("Пауза", true)
        NativeUi.click("Действия с треком Dancing in My Room", true)
        var action: String? = null
        waitFor { action = visiblePrefix("Скачать на устройство ·"); action != null }
        NativeUi.screenshot("release-download-menu.png"); NativeUi.click(action!!)
        NativeUi.click("Папки")
        repeat(4) { if (NativeUi.find("Открыть скачанную музыку", true) == null) NativeUi.scrollDown() }
        NativeUi.click("Открыть скачанную музыку", true)
        NativeUi.screenshot("release-download-progress.png")
        waitFor(90_000) { NativeUi.find("Слушать офлайн") != null }
        NativeUi.screenshot("release-download-complete.png")
        NativeUi.shell("svc wifi disable"); NativeUi.shell("svc data disable")
        try {
            // A process restart discards all buffered cloud audio before offline verification.
            launch(); search("Dancing in My Room")
            NativeUi.click("Включить Dancing in My Room — 347aidan", true)
            waitFor { playing() && position() > 500 }
            media()!!.transportControls.seekTo(35_000)
            waitFor { playing() && position() >= 35_000 }
            NativeUi.click("Открыть плеер", true); NativeUi.screenshot("release-offline-after-restart.png")
            NativeUi.click("Свернуть плеер", true); NativeUi.click("Пауза", true)
        } finally { NativeUi.restoreNetwork() }
        NativeUi.click("Папки")
        repeat(4) { if (NativeUi.find("Открыть скачанную музыку", true) == null) NativeUi.scrollDown() }
        NativeUi.click("Открыть скачанную музыку", true)
        NativeUi.click("Удалить скачанную копию Dancing in My Room", true); NativeUi.click("Удалить")
        waitFor { NativeUi.find("Пока ничего не скачано") != null }
    }
    @Test fun bDeviceFolderArtistsAndOfflinePlayback() {
        launch(); NativeUi.click("Папки"); NativeUi.click("Выбрать папку устройства"); Thread.sleep(800)
        if (NativeUi.find("Show roots", true) != null) NativeUi.click("Show roots", true)
        if (NativeUi.find("Downloads") != null) NativeUi.click("Downloads")
        if (NativeUi.find("Files in CloudMusicQA") == null) {
            if (NativeUi.find("CloudMusicQA") == null && NativeUi.find("Download") != null) NativeUi.click("Download")
            NativeUi.click("CloudMusicQA")
        }
        NativeUi.click("USE THIS FOLDER"); Thread.sleep(300)
        if (NativeUi.find("ALLOW") != null) NativeUi.click("ALLOW") else NativeUi.click("Allow")
        waitFor { NativeUi.find("CloudMusicQA") != null }
        // SAF indexing completes asynchronously and returns to Folders on success.
        waitFor {
            if (NativeUi.find("Исполнители") != null) true
            else { NativeUi.click("Музыка"); false }
        }
        NativeUi.click("Исполнители"); NativeUi.setText("QA Artist")
        NativeUi.click("Исполнитель QA Artist", true); NativeUi.screenshot("release-artist-tracks.png")
        NativeUi.shell("svc wifi disable"); NativeUi.shell("svc data disable")
        try {
            NativeUi.click("Слушать исполнителя")
            waitFor { playing() && position() > 500 }
            assertEquals("QA Artist", media()!!.metadata!!.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST))
            assertEquals("Artist queue", 2, media()!!.queue!!.size)
            media()!!.transportControls.seekTo(5000); waitFor { position() >= 5000 }
            NativeUi.click("Открыть плеер", true); NativeUi.screenshot("release-local-offline.png")
            NativeUi.click("Свернуть плеер", true); NativeUi.click("Пауза", true)
        } finally { NativeUi.restoreNetwork() }
    }
    @Test fun cThemesPlayerTransitionsAndReleaseFlags() {
        launch(); search("First"); NativeUi.click("Включить First — QA Artist", true)
        waitFor { playing() }
        repeat(8) { NativeUi.click("Открыть плеер", true); NativeUi.click("Свернуть плеер", true) }
        NativeUi.click("Включить светлую тему", true); NativeUi.click("Открыть плеер", true)
        NativeUi.screenshot("release-player-light.png"); NativeUi.click("Очередь воспроизведения", true)
        waitFor { NativeUi.find("Очередь воспроизведения") != null }
        NativeUi.shell("input keyevent 4"); NativeUi.click("Свернуть плеер", true)
        NativeUi.click("Включить тёмную тему", true); NativeUi.click("Пауза", true)
        val info = i.context.packageManager.getApplicationInfo("app.cloudmusic", 0)
        assertEquals("APK must not be debuggable", 0, info.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertEquals("APK must not be testOnly", 0, info.flags and ApplicationInfo.FLAG_TEST_ONLY)
        assertTrue(i.context.packageManager.getApplicationIcon("app.cloudmusic") is AdaptiveIconDrawable)
    }
    @Test fun dAndroidAutoLibrarySearchAndArtistPlayback() {
        val context = i.context
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaBrowser>
        i.runOnMainSync {
            future = androidx.media3.session.MediaBrowser.Builder(context, androidx.media3.session.SessionToken(context,
                android.content.ComponentName("app.cloudmusic", "app.cloudmusic.playback.PlaybackService"))).buildAsync()
        }
        val browser = future.get(15, java.util.concurrent.TimeUnit.SECONDS)
        fun <T> request(action: () -> com.google.common.util.concurrent.ListenableFuture<T>): T {
            lateinit var response: com.google.common.util.concurrent.ListenableFuture<T>
            i.runOnMainSync { response = action() }; return response.get(15, java.util.concurrent.TimeUnit.SECONDS)
        }
        try {
            val root = request { browser.getLibraryRoot(null) }; assertEquals(0, root.resultCode)
            val groups = request { browser.getChildren(root.value!!.mediaId, 0, 50, null) }.value!!
            assertEquals(setOf("Все треки", "Исполнители", "Папки", "Избранное"), groups.map { it.mediaMetadata.title.toString() }.toSet())
            val artists = request { browser.getChildren("artists", 0, 500, null) }.value!!
            val artist = artists.first { it.mediaMetadata.title == "QA Artist" }
            val tracks = request { browser.getChildren(artist.mediaId, 0, 50, null) }.value!!
            assertEquals(2, tracks.size)
            request { browser.search("Dancing in My Room", null) }
            val found = request { browser.getSearchResult("Dancing in My Room", 0, 50, null) }.value!!
            assertEquals(1, found.size)
            found.single().mediaMetadata.artworkUri?.takeIf { it.scheme == "content" }?.let { uri ->
                context.contentResolver.openInputStream(uri).use { assertTrue("The browser must have a read-only cover grant", it!!.read() >= 0) }
            }
            i.runOnMainSync { browser.setMediaItem(tracks.first()); browser.prepare(); browser.play() }
            waitFor { playing() && position() > 500 }
            i.runOnMainSync { assertEquals(2, browser.mediaItemCount); assertEquals("QA Artist", browser.mediaMetadata.artist); browser.pause(); browser.clearMediaItems() }
        } finally { i.runOnMainSync { androidx.media3.session.MediaBrowser.releaseFuture(future) } }
    }

}
