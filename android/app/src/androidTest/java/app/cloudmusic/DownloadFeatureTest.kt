package app.cloudmusic

import android.app.DownloadManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSpec
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cloudmusic.data.*
import app.cloudmusic.playback.PlaybackService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DownloadFeatureTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = i.targetContext.applicationContext as CloudMusicApplication
    private fun controller(): Pair<com.google.common.util.concurrent.ListenableFuture<MediaController>, MediaController> {
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        i.runOnMainSync { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
        return future to future.get(15, TimeUnit.SECONDS)
    }
    private fun waitFor(timeout: Long = 15_000, diagnostic: () -> String = { "" }, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (!condition() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(100)
        assertTrue("Condition did not become true within $timeout ms: ${diagnostic()}", condition())
    }
    @Test fun liveDownloadMenuOfflinePlaybackPersistenceAndDeletion() {
        runBlocking { app.repository.load(); app.downloads.sync(true) }
        val real = app.repository.library.value.tracks.firstOrNull { it.provider == Provider.YANDEX && it.remoteId.contains("Dancing in My Room") }
        assumeTrue("Connect the real Yandex test folder first", real != null)
        val source = real!!.copy(id = "qa-download-source", folderId = "qa-download-folder", title = "QA Download", artist = "Cloud Music QA", favorite = true)
        val folder = app.repository.database.findFolder(real.folderId)!!.copy(id = source.folderId)
        app.repository.database.replaceFolder(folder, listOf(source))
        runBlocking { app.repository.load() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: MusicViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[MusicViewModel::class.java] }
                NativeUi.click("Поиск")
                NativeUi.setText("QA Download")
                NativeUi.click("Действия с треком QA Download", true)
                Thread.sleep(800)
                NativeUi.screenshot("download-menu.png")
                NativeUi.click("Скачать на устройство · ${byteLabel(source.size)}")
                waitFor { app.downloads.state.value.any { it.sourceId == source.id } }
                val firstId = app.downloads.state.value.first { it.sourceId == source.id }.managerId
                assertFalse("Duplicate clicks must not create a second file", runBlocking { app.downloads.enqueue(source) })
                assertEquals(firstId, app.downloads.state.value.first { it.sourceId == source.id }.managerId)
                NativeUi.click("Папки")
                Thread.sleep(400)
                repeat(3) { if (NativeUi.find("Открыть скачанную музыку", true) == null) NativeUi.scrollDown() }
                NativeUi.screenshot("folders-with-downloads.png")
                NativeUi.click("Открыть скачанную музыку", true)
                NativeUi.screenshot("downloads-progress.png")
                waitFor(90_000) { app.downloads.state.value.firstOrNull { it.sourceId == source.id }?.let { !it.active } == true }
                val saved = app.downloads.state.value.first { it.sourceId == source.id }
                assertEquals("Download failed: ${saved.error}", DownloadStatus.COMPLETE, saved.status)
                assertEquals(source.size, saved.bytes)
                assertTrue(saved.uri.startsWith("content:"))
                assertTrue(app.repository.database.findTrack(saved.localId)!!.favorite)
                runBlocking { app.repository.favorite(app.repository.database.findTrack(saved.localId)!!) }
                assertFalse(app.repository.database.findTrack(source.id)!!.favorite)
                assertFalse(app.repository.database.findTrack(saved.localId)!!.favorite)
                assertEquals(listOf(source.id), browseTracks(app.repository.library.value.tracks).filter { it.id in listOf(source.id, saved.localId) }.map { it.id })
                NativeUi.screenshot("downloads-complete.png")
                val restored = MusicDownloads(app, app.repository)
                runBlocking { restored.sync(true) }
                assertEquals(saved.managerId, restored.state.value.first { it.sourceId == source.id }.managerId)
                assertEquals(Uri.parse(saved.uri), restored.uriForSource(source.id))
                NativeUi.shell("svc wifi disable"); NativeUi.shell("svc data disable")
                val (future, player) = controller()
                try {
                    // Selecting the original cloud track must use the copy, with both networks off.
                    i.runOnMainSync { vm.playback.play(listOf(source), source) }
                    waitFor(diagnostic = { var info = ""; i.runOnMainSync { info = "state=${player.playbackState}, ready=${player.playWhenReady}, suppression=${player.playbackSuppressionReason}, position=${player.currentPosition}, id=${player.currentMediaItem?.mediaId}, error=${player.playerError?.errorCodeName}" }; info }) { var yes = false; i.runOnMainSync { yes = player.isPlaying && player.currentPosition > 500 }; yes }
                    i.runOnMainSync { vm.playback.removeFolder(setOf(source.id, saved.localId)) }
                    waitFor { var empty = false; i.runOnMainSync { empty = player.mediaItemCount == 0 && !player.playWhenReady }; empty }
                    // A downloaded file remains independent of the disconnected cloud source.
                    runBlocking { app.repository.remove(folder) }
                    assertNotNull(app.repository.database.findTrack(saved.localId))
                    app.audio.playbackFactory.createDataSource().useSource { data ->
                        data.open(DataSpec.Builder().setUri("cloudmusic://track/${source.id}").setLength(32).build())
                        val bytes = ByteArray(32); assertEquals(32, data.read(bytes, 0, 32)); assertEquals("ID3", String(bytes, 0, 3))
                    }
                    i.runOnMainSync { vm.playback.play(listOf(saved.track()), saved.track()) }
                    waitFor(diagnostic = { var info = ""; i.runOnMainSync { info = "state=${player.playbackState}, ready=${player.playWhenReady}, suppression=${player.playbackSuppressionReason}, position=${player.currentPosition}, id=${player.currentMediaItem?.mediaId}, error=${player.playerError?.errorCodeName}" }; info }) { var yes = false; i.runOnMainSync { yes = player.isPlaying && player.currentPosition > 500 }; yes }
                    i.runOnMainSync { vm.playback.seek(30_000) }
                    waitFor { var yes = false; i.runOnMainSync { yes = player.isPlaying && player.currentPosition >= 30_500 }; yes }
                    NativeUi.screenshot("downloads-offline.png")
                    i.runOnMainSync { vm.playback.removeFolder(setOf(source.id, saved.localId)) }
                    waitFor { var empty = false; i.runOnMainSync { empty = player.mediaItemCount == 0 && !player.playWhenReady }; empty }
                } finally { NativeUi.restoreNetwork(); i.runOnMainSync { MediaController.releaseFuture(future) } }
                NativeUi.click("Удалить скачанную копию QA Download", true)
                NativeUi.click("Удалить")
                waitFor { app.downloads.state.value.none { it.sourceId == source.id } }
                assertNull(app.repository.database.findTrack(saved.localId))
                assertNull(app.getSystemService(DownloadManager::class.java).getUriForDownloadedFile(saved.managerId))
            }
        } finally { NativeUi.restoreNetwork(); runBlocking { app.downloads.remove(source.id); app.repository.remove(folder) } }
    }
    @Test fun cancellingQueuedDownloadDoesNotInterruptTheSameTrack() {
        runBlocking { app.repository.load() }
        val real = app.repository.library.value.tracks.firstOrNull { it.provider == Provider.YANDEX && it.remoteId.contains("Dancing in My Room") }
        assumeTrue(real != null)
        val source = real!!.copy(id = "qa-cancel-source", folderId = "qa-cancel-folder", title = "QA Cancel")
        val folder = app.repository.database.findFolder(real.folderId)!!.copy(id = source.folderId)
        app.repository.database.replaceFolder(folder, listOf(source))
        val link = app.repository.cloud.resolve(real, app.repository.database.findFolder(real.folderId)!!)
        app.repository.cloud.seed(source.id, folder.updatedAt, link)
        runBlocking { app.repository.load() }
        try {
            NativeUi.shell("svc wifi disable"); NativeUi.shell("svc data disable")
            assertTrue(runBlocking { app.downloads.enqueue(source) })
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: MusicViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[MusicViewModel::class.java] }
                val (future, player) = controller()
                try {
                    i.runOnMainSync { player.setMediaItem(MediaItem.Builder().setMediaId(source.id).setUri("asset:///demo/track-0.wav").build()); player.prepare(); player.play() }
                    waitFor { var yes = false; i.runOnMainSync { yes = player.isPlaying }; yes }
                    val record = app.downloads.state.value.first { it.sourceId == source.id }
                    assertTrue(record.active)
                    i.runOnMainSync { vm.removeDownload(record) }
                    waitFor { app.downloads.state.value.none { it.sourceId == source.id } }
                    var playing = false
                    i.runOnMainSync { playing = player.isPlaying && player.currentMediaItem?.mediaId == source.id }
                    assertTrue("Cancelling a download must preserve playback", playing)
                } finally { i.runOnMainSync { player.pause(); player.clearMediaItems(); MediaController.releaseFuture(future) } }
            }
        } finally { NativeUi.restoreNetwork(); runBlocking { app.downloads.remove(source.id); app.repository.remove(folder) } }
    }
    @Test fun migrationFrom02PreservesCloudLocalFavoritesAndLinks() {
        val name = "qa-download-migration.db"
        app.deleteDatabase(name)
        try {
            val folder = CloudFolder("migration", "Local", "local://selected", Provider.LOCAL)
            val track = Track("migration-track", folder.id, Provider.LOCAL, "content://qa/file", "Song", "Artist", "Album", favorite = true)
            MusicDatabase(app, name).use { db -> db.replaceFolder(folder, listOf(track)); db.writableDatabase.execSQL("DROP TABLE downloads"); db.writableDatabase.version = 2 }
            MusicDatabase(app, name).use { db ->
                assertEquals(3, db.readableDatabase.version); assertTrue(db.findTrack(track.id)!!.favorite)
                val download = MusicDownload("source", 99, "Saved", "Artist", "Album", "saved.mp3", 100)
                db.saveDownload(download); db.removeFolder(folder.id)
                assertEquals(download, db.downloads().single())
            }
        } finally { app.deleteDatabase(name) }
    }
    @Test fun launcherIconIsAdaptiveAndHasNoWhiteInset() {
        val icon = app.packageManager.getApplicationIcon(app.packageName)
        assertTrue("Launcher must receive an adaptive icon", icon is AdaptiveIconDrawable)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 256, 256); icon.draw(Canvas(bitmap))
        var white = 0
        var accent = 0
        for (y in 0 until 256) for (x in 0 until 256) {
            val c = bitmap.getPixel(x, y)
            if (android.graphics.Color.alpha(c) > 0 && android.graphics.Color.red(c) > 245 && android.graphics.Color.green(c) > 245 && android.graphics.Color.blue(c) > 245) white++
            if (android.graphics.Color.green(c) > 200 && android.graphics.Color.blue(c) < 160) accent++
        }
        assertEquals(0, white); assertTrue(accent > 1000)
        val directory = java.io.File(app.getExternalFilesDir(null), "qa").apply { mkdirs() }
        java.io.File(directory, "launcher-icon.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
private inline fun <T> androidx.media3.datasource.DataSource.useSource(action: (androidx.media3.datasource.DataSource) -> T): T = try { action(this) } finally { close() }
