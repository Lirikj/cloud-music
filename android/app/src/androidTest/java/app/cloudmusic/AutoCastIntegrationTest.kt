package app.cloudmusic

import android.content.ComponentName
import androidx.media3.common.*
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cloudmusic.cast.*
import app.cloudmusic.data.Provider
import app.cloudmusic.playback.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.*
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AutoCastIntegrationTest {
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = i.targetContext.applicationContext as CloudMusicApplication
    @Test fun mediaBrowserBrowseSearchAndArtistQueue() {
        runBlocking { app.repository.load(); app.repository.demo() }
        lateinit var future: ListenableFuture<MediaBrowser>
        i.runOnMainSync { future = MediaBrowser.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
        val browser = future.get(15, TimeUnit.SECONDS)
        try {
            fun <T> request(action: () -> ListenableFuture<T>): T { lateinit var result: ListenableFuture<T>; i.runOnMainSync { result = action() }; return result.get(15, TimeUnit.SECONDS) }
            val root = request { browser.getLibraryRoot(null) }; assertEquals(0, root.resultCode)
            val groups = request { browser.getChildren(root.value!!.mediaId, 0, 50, null) }.value!!
            assertTrue(groups.any { it.mediaMetadata.title == "Исполнители" }); assertTrue(request { browser.getChildren(MusicCatalogue.FOLDERS, 0, 50, null) }.value!!.any { it.mediaMetadata.title == "На устройстве" })
            val artists = request { browser.getChildren(MusicCatalogue.ARTISTS, 0, 500, null) }.value!!
            val artist = artists.first { it.mediaMetadata.title == "Cloud Music" }
            val tracks = request { browser.getChildren(artist.mediaId, 0, 100, null) }.value!!
            assertEquals(3, tracks.size); assertTrue(tracks.all { it.mediaMetadata.isPlayable == true })
            request { browser.search("орбите", null) }
            val found = request { browser.getSearchResult("орбите", 0, 50, null) }.value!!
            assertEquals(1, found.size); assertEquals("По орбите", found.single().mediaMetadata.title)
            i.runOnMainSync { browser.setMediaItem(tracks.first()); browser.prepare(); browser.play() }
            val end = android.os.SystemClock.elapsedRealtime() + 10_000
            var playing = false
            while (!playing && android.os.SystemClock.elapsedRealtime() < end) { i.runOnMainSync { playing = browser.isPlaying }; Thread.sleep(100) }
            var diagnostic = ""
            i.runOnMainSync { diagnostic = "state=${browser.playbackState} ready=${browser.playWhenReady} suppression=${browser.playbackSuppressionReason} queue=${browser.mediaItemCount} error=${browser.playerError}" }
            assertTrue(diagnostic, playing)
            i.runOnMainSync { assertEquals(3, browser.mediaItemCount); assertEquals("Cloud Music", browser.mediaMetadata.artist); browser.pause(); browser.clearMediaItems() }
        } finally {
            i.runOnMainSync { MediaBrowser.releaseFuture(future) }
            runBlocking { app.repository.library.value.folders.firstOrNull { it.id == "demo" }?.let { app.repository.remove(it) } }
        }
    }
    @Test fun nativeCastPickerUsesDarkThemeAndUnavailableServicesLeaveMusicUsable() {
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var dialog: androidx.mediarouter.app.MediaRouteChooserDialog? = null
            scenario.onActivity {
                dialog = androidx.mediarouter.app.MediaRouteChooserDialog(it, R.style.Theme_CloudMusic_CastDialog)
                dialog!!.routeSelector = androidx.mediarouter.media.MediaRouteSelector.EMPTY
                dialog!!.show(); assertTrue(dialog!!.isShowing)
            }
            Thread.sleep(300); NativeUi.screenshot("cast-device-picker.png")
            scenario.onActivity { dialog!!.dismiss() }
            if (com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                NativeUi.click("Трансляция Chromecast", true)
                assertNotNull(app.casting.state.value.error)
                assertNull(app.casting.state.value.device)
            }
        }
    }
    @Test fun relayAuthenticatesRangesHeadAndSessionShutdownWithoutAudioCache() {
        runBlocking { app.repository.load(); app.repository.demo() }
        val track = app.repository.library.value.tracks.first { it.id == "demo-0" }
        val server = CastStreamServer(app).start(InetAddress.getByName("127.0.0.1"))
        val url = server.audioUrl(track)
        fun fetch(method: String, path: String = url.encodedPath!!, range: String? = null): Pair<String, ByteArray> {
            return Socket("127.0.0.1", url.port).use { s ->
                s.soTimeout = 5000
                s.getOutputStream().write(("$method $path HTTP/1.1\r\nHost: localhost\r\n" + (range?.let { "Range: $it\r\n" } ?: "") + "\r\n").toByteArray())
                val bytes = s.getInputStream().readBytes(); val marker = "\r\n\r\n".toByteArray()
                val split = (0..bytes.size - marker.size).first { bytes.copyOfRange(it, it + marker.size).contentEquals(marker) }
                String(bytes, 0, split, Charsets.US_ASCII) to bytes.copyOfRange(split + 4, bytes.size)
            }
        }
        try {
            val (header, body) = fetch("GET", range = "bytes=8-11")
            assertTrue(header.startsWith("HTTP/1.1 206")); assertTrue(header.contains("Content-Length: 4")); assertEquals("WAVE", String(body))
            val (head, empty) = fetch("HEAD"); assertTrue(head.startsWith("HTTP/1.1 200")); assertTrue(empty.isEmpty())
            assertTrue(fetch("GET", range = "bytes=999999999-").first.startsWith("HTTP/1.1 416"))
            assertTrue(fetch("GET", "/wrong/audio/demo-0").first.startsWith("HTTP/1.1 403"))
            assertTrue(fetch("POST").first.startsWith("HTTP/1.1 405"))
            assertTrue(fetch("OPTIONS").first.startsWith("HTTP/1.1 204"))
            app.repository.library.value.tracks.firstOrNull { it.provider == Provider.YANDEX && it.title == "Dancing in My Room" }?.let { cloud ->
                val remoteUrl = server.audioUrl(cloud)
                val (remoteHeader, remoteBytes) = fetch("GET", remoteUrl.encodedPath!!, "bytes=0-255")
                assertTrue(remoteHeader.startsWith("HTTP/1.1 206")); assertEquals(256, remoteBytes.size)
                assertEquals("ID3", String(remoteBytes, 0, 3))
                println("CastRelayCloudRangeVerified: 256 exact bytes")
            }
            app.repository.library.value.tracks.firstOrNull { it.provider == Provider.LOCAL && it.artist == "QA Artist" }?.let { local ->
                val localUrl = server.audioUrl(local)
                assertEquals("WAVE", String(fetch("GET", localUrl.encodedPath!!, "bytes=8-11").second))
                println("CastRelayLocalContentUriVerified: WAVE header")
            }
            assertFalse(java.io.File(app.cacheDir, "audio-v1").exists())
        } finally {
            server.close()
            runBlocking { app.repository.library.value.folders.firstOrNull { it.id == "demo" }?.let { app.repository.remove(it) } }
        }
        try { Socket("127.0.0.1", url.port).use { fail("Relay must stop with its session") } } catch (_: java.io.IOException) { }
    }
    @Test fun castFullQueueIsLoadedOneItemAtATimeAndAutoplaySurvivesPendingLoad() {
        class Fake : RemoteTransport {
            override var onChanged: (() -> Unit)? = null
            var value = RemoteTransport.Status()
            val loads = mutableListOf<MediaItem>(); var plays = 0; var pauses = 0
            override fun status() = value
            override fun load(item: MediaItem, position: Long, autoplay: Boolean, onFailure: (String) -> Unit) { loads += item }
            fun emit(id: String, mode: RemoteTransport.Mode, finished: Boolean = false) { value = RemoteTransport.Status(id, mode, 1000, 5000, finished); onChanged?.invoke() }
            override fun play() { plays++ }; override fun pause() { pauses++ }; override fun stop() { }
            override fun seek(position: Long) { value = value.copy(position = position) }
            override fun volume(value: Int) { }; override fun mute(muted: Boolean) { }; override fun release() { onChanged = null }
        }
        val fake = Fake()
        i.runOnMainSync {
            val player = CastPlaybackPlayer(fake)
            try {
                val items = (0..255).map { MediaItem.Builder().setMediaId("track-$it").setUri("cloudmusic://track/track-$it").build() }
                player.setMediaItems(items, 178, 0); player.prepare(); player.play()
                assertEquals(256, player.mediaItemCount); assertEquals(1, fake.loads.size)
                fake.emit("track-178", RemoteTransport.Mode.PAUSED)
                assertEquals("Play must be deferred until LOAD establishes a media session", 1, fake.plays)
                fake.emit("track-178", RemoteTransport.Mode.PLAYING); assertTrue(player.isPlaying)
                fake.emit("track-178", RemoteTransport.Mode.IDLE, finished = true)
                assertEquals(179, player.currentMediaItemIndex); assertEquals(2, fake.loads.size)
                fake.emit("track-178", RemoteTransport.Mode.IDLE, finished = true); assertEquals(2, fake.loads.size)
                fake.emit("track-179", RemoteTransport.Mode.PLAYING)
                player.repeatMode = Player.REPEAT_MODE_ONE
                fake.emit("track-179", RemoteTransport.Mode.IDLE, finished = true)
                assertEquals(179, player.currentMediaItemIndex); assertEquals(3, fake.loads.size)
                fake.emit("track-179", RemoteTransport.Mode.IDLE, finished = true); assertEquals("A stale FINISHED callback cannot skip twice", 3, fake.loads.size)
                fake.emit("track-179", RemoteTransport.Mode.PLAYING)
                player.pause(); assertEquals(1, fake.pauses)
                player.removeMediaItems(0, 10); assertEquals(246, player.mediaItemCount); assertEquals("track-179", player.currentMediaItem!!.mediaId)
                player.seekTo(5, 500); assertEquals("track-15", fake.loads.last().mediaId)
                player.shuffleModeEnabled = true; player.repeatMode = Player.REPEAT_MODE_ALL
                assertTrue(player.nextMediaItemIndex in 0 until player.mediaItemCount)
                val beforeShuffleChange = player.currentTimeline
                val firstBefore = beforeShuffleChange.getFirstWindowIndex(true)
                player.seekTo(10, 0); player.shuffleModeEnabled = false
                assertEquals("Published timeline snapshots must stay immutable", firstBefore, beforeShuffleChange.getFirstWindowIndex(true))
            } finally { player.release() }
            assertNull(fake.onChanged)
        }
    }
}
