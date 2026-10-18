package app.cloudmusic.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

@androidx.annotation.OptIn(UnstableApi::class)
object StreamingPolicy {
    /** A quick initial start, larger recovery reserve, and bounded RAM. No persistent audio cache. */
    fun loadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(30_000, 180_000, 250, 8000)
        .setTargetBufferBytes(4 * 1024 * 1024).build()
}
