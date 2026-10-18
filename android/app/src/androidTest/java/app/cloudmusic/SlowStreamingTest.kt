package app.cloudmusic

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cloudmusic.playback.StreamingPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SlowStreamingTest {
    @Test fun boundedRamBufferSurvivesBriefNetworkPauseAt96Kbps() {
        val i = InstrumentationRegistry.getInstrumentation()
        // Valid 64 kbit/s PCM WAV, delivered at 96 kbit/s. No whole-file buffering or disk writes.
        val pcm = ByteArray(8_000 * 40) { 128.toByte() }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(pcm.size + 36); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(8000); putShort(1); putShort(8)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        val data = header + pcm
        val pause = AtomicBoolean(false)
        val paused = AtomicBoolean(false)
        val factory = DataSource.Factory {
            object : DataSource {
                var position = 0
                override fun addTransferListener(transferListener: TransferListener) {}
                override fun open(dataSpec: DataSpec): Long { position = dataSpec.position.toInt(); return (data.size - position).toLong() }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position == data.size) return C.RESULT_END_OF_INPUT
                    if (pause.compareAndSet(true, false)) { paused.set(true); Thread.sleep(1200) }
                    val n = minOf(800, length, data.size - position)
                    Thread.sleep((n * 1000L / 12000).coerceAtLeast(1))
                    data.copyInto(buffer, offset, position, position + n); position += n; return n
                }
                override fun getUri() = Uri.parse("https://qa.example/slow.wav")
                override fun close() {}
            }
        }
        lateinit var player: ExoPlayer
        i.runOnMainSync { player = ExoPlayer.Builder(i.targetContext).setLoadControl(StreamingPolicy.loadControl()).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build(); player.setMediaItem(MediaItem.fromUri("https://qa.example/slow.wav")); player.prepare(); player.play() }
        try {
            var playing = false
            var buffer = 0L
            var position = 0L
            val started = SystemClock.elapsedRealtime()
            val end = started + 20_000
            while (SystemClock.elapsedRealtime() < end) {
                i.runOnMainSync { playing = player.isPlaying; buffer = player.totalBufferedDuration; position = player.currentPosition }
                if (playing && buffer >= 3000) break
                Thread.sleep(50)
            }
            assertTrue("Slow stream must play and build a reserve; buffer=$buffer", playing && buffer >= 3000)
            pause.set(true)
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!paused.get() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            assertTrue(paused.get())
            Thread.sleep(1800)
            var after = 0L
            i.runOnMainSync { playing = player.isPlaying; after = player.currentPosition }
            assertTrue("A 1.2 s network pause must not stop buffered playback", playing && after > position + 1200)
        } finally { i.runOnMainSync { player.release() } }
    }
}
