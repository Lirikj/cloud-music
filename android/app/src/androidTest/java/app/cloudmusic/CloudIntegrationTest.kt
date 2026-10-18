package app.cloudmusic

import android.content.ComponentName
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.cloudmusic.data.*
import app.cloudmusic.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CloudIntegrationTest {
    @Test fun liveYandexFolderPaginationAndBackgroundPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val url = InstrumentationRegistry.getArguments().getString("yandex_folder").orEmpty()
        assumeTrue("Pass -e yandex_folder to run the real-cloud test", url.isNotBlank())
        val app = instrumentation.targetContext.applicationContext as CloudMusicApplication
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val folder = runBlocking(Dispatchers.IO) { app.repository.connect(FolderLinks.parse(url)) }
            val tracks = app.repository.library.value.tracks.filter { it.folderId == folder.id }
            val expected = InstrumentationRegistry.getArguments().getString("expected_tracks")?.toIntOrNull()
            if (expected != null) assertEquals("All pages must be loaded", expected, tracks.size)
            assertTrue(tracks.isNotEmpty())
            val selected = tracks.first()
            val stream = app.repository.cloud.resolve(selected, folder)
            app.http.newCall(Request.Builder().url(stream).header("Range", "bytes=0-1023").build()).execute().use { response ->
                assertTrue(response.isSuccessful)
                assertFalse(response.header("Content-Type").orEmpty().contains("text/html"))
                val bytes = ByteArray(256)
                assertTrue((response.body?.byteStream()?.read(bytes) ?: 0) > 0)
            }
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            instrumentation.runOnMainSync { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            val controller = future.get(15, TimeUnit.SECONDS)
            try {
                instrumentation.runOnMainSync {
                    controller.setMediaItem(MediaItem.Builder().setMediaId(selected.id).setUri("cloudmusic://track/${selected.id}").setMediaMetadata(MediaMetadata.Builder().setTitle(selected.title).setArtist(selected.artist).build()).build())
                    controller.prepare(); controller.play()
                }
                var isPlaying = false
                val deadline = System.currentTimeMillis() + 45_000
                while (!isPlaying && System.currentTimeMillis() < deadline) {
                    instrumentation.runOnMainSync { isPlaying = controller.isPlaying }
                    Thread.sleep(200)
                }
                assertTrue("Real cloud audio must enter playback", isPlaying)
                var before = 0L
                instrumentation.runOnMainSync { before = controller.currentPosition }
                instrumentation.uiAutomation.executeShellCommand("input keyevent 3").close()
                Thread.sleep(2500)
                var after = 0L
                instrumentation.runOnMainSync { after = controller.currentPosition; isPlaying = controller.isPlaying }
                assertTrue("Playback must continue after Home", isPlaying && after > before + 1000)
                instrumentation.runOnMainSync { controller.seekTo(30_000L) }
                Thread.sleep(1800)
                instrumentation.runOnMainSync { after = controller.currentPosition }
                assertTrue("Seeking must work with ranged cloud requests", after >= 29_000L)
                val sessionDump = instrumentation.uiAutomation.executeShellCommand("dumpsys media_session").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
                assertTrue("System media session must be published", sessionDump.contains("app.cloudmusic"))
            } finally {
                instrumentation.runOnMainSync { controller.pause(); controller.clearMediaItems(); MediaController.releaseFuture(future) }
            }
        }
    }

    @Test fun databaseRefreshPreservesFavoritesAndRemovalIsScoped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = MusicDatabase(context)
        val folder = CloudFolder("qa-folder", "QA", "demo://qa", Provider.DEMO)
        val a = Track("qa-track", folder.id, Provider.DEMO, "asset:///demo/track-0.wav", "Test", "QA", "QA")
        try {
            database.replaceFolder(folder, listOf(a))
            database.setFavorite(a.id, true)
            database.replaceFolder(folder.copy(name = "QA updated"), listOf(a, a.copy(id = "qa-track-2")))
            assertTrue(database.findTrack(a.id)!!.favorite)
            assertEquals(2, database.snapshot().tracks.count { it.folderId == folder.id })
            val otherIds = database.snapshot().folders.filter { it.id != folder.id }.map { it.id }.toSet()
            database.removeFolder(folder.id)
            assertNull(database.findTrack(a.id))
            assertTrue(database.snapshot().folders.map { it.id }.containsAll(otherIds))
        } finally { database.removeFolder(folder.id); database.close() }
    }
    @Test fun schemaUpgradeAndShortLivedLinksPreserveLibrary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-migration.db"
        context.deleteDatabase(name)
        val folder = CloudFolder("migration", "Saved", "https://disk.yandex.ru/d/test_123", Provider.YANDEX, updatedAt = 123)
        val track = Track("saved-track", folder.id, Provider.YANDEX, "/song.mp3", "Song", "Artist", "Album", favorite = true)
        try {
            MusicDatabase(context, name).use { db -> db.replaceFolder(folder, listOf(track)); db.writableDatabase.execSQL("DROP TABLE stream_links"); db.writableDatabase.version = 1 }
            MusicDatabase(context, name).use { db ->
                assertTrue(db.findTrack(track.id)!!.favorite)
                assertEquals("Saved", db.findFolder(folder.id)!!.name)
                db.saveStreamLinks(folder, mapOf(track.id to "https://cdn.example/direct"))
            }
            MusicDatabase(context, name).use { db ->
                val client = CloudClients(okhttp3.OkHttpClient())
                MusicRepository(db, client).load()
                assertEquals("https://cdn.example/direct", client.resolve(track, folder))
                db.writableDatabase.execSQL("UPDATE stream_links SET expires_at=0")
                assertTrue(db.activeStreamLinks().isEmpty())
                db.removeFolder(folder.id)
                assertNull(db.findTrack(track.id))
            }
        } finally { context.deleteDatabase(name) }
    }

}
