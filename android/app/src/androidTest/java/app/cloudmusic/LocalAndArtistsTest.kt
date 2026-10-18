package app.cloudmusic

import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cloudmusic.data.Provider
import app.cloudmusic.data.artistKey
import app.cloudmusic.playback.PlaybackService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LocalAndArtistsTest {
    @Test fun nativeFolderPickerArtistFilterAndOfflinePlayback() {
        val i = NativeUi.instrumentation
        val app = i.targetContext.applicationContext as CloudMusicApplication
        runBlocking { app.repository.load() }
        ActivityScenario.launch(MainActivity::class.java).use {
            NativeUi.click("Папки")
            NativeUi.click("Выбрать папку устройства")
            // Use only actual system accessibility nodes, with observed labels on the QA image.
            Thread.sleep(1000)
            println("DocumentPickerBefore\n${NativeUi.dump()}")
            if (NativeUi.find("Show roots", true) != null) NativeUi.click("Show roots", true)
            if (NativeUi.find("Downloads") != null) NativeUi.click("Downloads")
            if (NativeUi.find("Files in CloudMusicQA") == null) {
                if (NativeUi.find("CloudMusicQA") == null && NativeUi.find("Download") != null) NativeUi.click("Download")
                NativeUi.click("CloudMusicQA")
            }
            Thread.sleep(300)
            println("DocumentPickerFolder\n${NativeUi.dump()}")
            NativeUi.click("USE THIS FOLDER")
            Thread.sleep(300)
            if (NativeUi.find("ALLOW") != null) NativeUi.click("ALLOW") else if (NativeUi.find("Allow") != null) NativeUi.click("Allow")
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (app.repository.library.value.tracks.count { it.provider == Provider.LOCAL && it.artist == "QA Artist" } < 2 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            val local = app.repository.library.value.tracks.filter { it.provider == Provider.LOCAL && it.artist == "QA Artist" }
            assertEquals("Both original files must be indexed", 2, local.size)
            NativeUi.screenshot("device-local.png")
            assertTrue(local.all { it.remoteId.startsWith("content:") })
            assertTrue(app.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString().contains("CloudMusicQA") })
            NativeUi.click("Музыка")
            NativeUi.click("Исполнители")
            // The artist list is sorted; use search to reach a name without guessed scrolling.
            fun editable(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT }) return node
                for (index in 0 until node.childCount) editable(node.getChild(index))?.let { return it }
                return null
            }
            var search: android.view.accessibility.AccessibilityNodeInfo? = null
            val searchDeadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (search == null && android.os.SystemClock.elapsedRealtime() < searchDeadline) { search = editable(i.uiAutomation.rootInActiveWindow); if (search == null) Thread.sleep(100) }
            val bundle = android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "QA Artist") }
            assertTrue("Artist search field must be editable", search?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, bundle) == true)
            Thread.sleep(400)
            NativeUi.screenshot("artists-filtered.png")
            NativeUi.click("Исполнитель QA Artist", true)
            NativeUi.screenshot("artist-tracks.png")
            NativeUi.click("Слушать исполнителя")
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            i.runOnMainSync { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            val player = future.get(15, TimeUnit.SECONDS)
            try {
                var queue = emptyList<String>()
                i.runOnMainSync { queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }; player.pause(); player.clearMediaItems() }
                assertEquals(local.map { it.id }.toSet(), queue.toSet())
                NativeUi.shell("svc wifi disable"); NativeUi.shell("svc data disable")
                runBlocking { app.repository.load() }
                i.runOnMainSync { player.setMediaItems(local.map { MediaItem.Builder().setMediaId(it.id).setUri(it.remoteId).build() }); player.prepare(); player.play() }
                Thread.sleep(2000)
                var playing = false
                var position = 0L
                i.runOnMainSync { playing = player.isPlaying; position = player.currentPosition; player.seekTo(5000) }
                assertTrue("Local audio must play with both networks disabled", playing && position > 500)
                app.audio.priority.proceedOrThrow(0) // Device playback must never block cloud artwork.
                NativeUi.click("Открыть плеер", true)
                NativeUi.screenshot("player-local-offline.png")
                Thread.sleep(700)
                i.runOnMainSync { position = player.currentPosition }
                assertTrue(position >= 5000)
                assertFalse(java.io.File(app.cacheDir, "audio-v1").exists())
            } finally {
                NativeUi.restoreNetwork()
                i.runOnMainSync { player.pause(); player.clearMediaItems(); MediaController.releaseFuture(future) }
            }
        }
    }
}
