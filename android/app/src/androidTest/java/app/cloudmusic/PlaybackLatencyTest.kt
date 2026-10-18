package app.cloudmusic

import android.content.ComponentName
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cloudmusic.data.Provider
import app.cloudmusic.playback.PlaybackService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackLatencyTest {
    @Test fun coldAndRepeatedCloudPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as CloudMusicApplication
        val tracks = app.repository.database.snapshot().tracks.filter { it.provider == Provider.YANDEX }.sortedBy { it.title }.take(3)
        assumeTrue("Connect a real folder before benchmarking", tracks.size == 3)
        val rows = JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use {
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            instrumentation.runOnMainSync { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
            val controller = future.get(15, TimeUnit.SECONDS)
            try {
                repeat(2) { pass ->
                    for (track in tracks) {
                        app.audio.headers.clear()
                        val start = SystemClock.elapsedRealtime()
                        instrumentation.runOnMainSync {
                            controller.setMediaItem(MediaItem.Builder().setMediaId(track.id).setUri("cloudmusic://track/${track.id}")
                                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).build()).build())
                            controller.prepare(); controller.play()
                        }
                        var playing = false
                        var error: String? = null
                        var details = ""
                        while (!playing && error == null && SystemClock.elapsedRealtime() - start < 45_000) {
                            Thread.sleep(25)
                            instrumentation.runOnMainSync { playing = controller.isPlaying; error = controller.playerError?.errorCodeName; details = "state=${controller.playbackState}, ready=${controller.playWhenReady}, suppression=${controller.playbackSuppressionReason}, buffered=${controller.bufferedPosition}" }
                        }
                        val elapsed = SystemClock.elapsedRealtime() - start
                        assertNull(error)
                        assertTrue("Playback must start: $details", playing)
                        var cover = false
                        instrumentation.runOnMainSync { cover = controller.mediaMetadata.artworkData != null }
                        val row = JSONObject().put("track", track.title).put("pass", pass).put("startup_ms", elapsed).put("embedded_artwork", cover)
                        rows.put(row)
                        println("CloudMusicLatency $row")
                        val directory = File(app.getExternalFilesDir(null), "qa").apply { mkdirs() }
                        val label = InstrumentationRegistry.getArguments().getString("benchmark_label", "latest")!!
                        File(directory, "latency-$label.json").writeText(rows.toString(2))
                        Thread.sleep(1000)
                        instrumentation.runOnMainSync { controller.pause(); controller.clearMediaItems() }
                    }
                }
            } finally { instrumentation.runOnMainSync { controller.pause(); controller.clearMediaItems(); MediaController.releaseFuture(future) } }
        }
        val label = InstrumentationRegistry.getArguments().getString("benchmark_label", "latest")!!
        val directory = File(app.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "latency-$label.json").writeText(rows.toString(2))
    }
}
