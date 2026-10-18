package app.cloudmusic.cast

import androidx.media3.common.MediaItem
import app.cloudmusic.CloudMusicApplication
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage

interface RemoteTransport {
    enum class Mode { IDLE, BUFFERING, PLAYING, PAUSED }
    data class Status(val id: String? = null, val mode: Mode = Mode.IDLE, val position: Long = 0, val duration: Long = 0, val finished: Boolean = false, val failed: Boolean = false, val volume: Int = 100, val muted: Boolean = false)
    var onChanged: (() -> Unit)?
    fun status(): Status
    fun load(item: MediaItem, position: Long, autoplay: Boolean, onFailure: (String) -> Unit)
    fun play(); fun pause(); fun stop(); fun seek(position: Long); fun volume(value: Int); fun mute(muted: Boolean); fun release()
}

class GoogleCastTransport(private val app: CloudMusicApplication, private val session: CastSession, private val server: CastStreamServer) : RemoteTransport {
    private val client = requireNotNull(session.remoteMediaClient)
    override var onChanged: (() -> Unit)? = null
    private val callback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() { onChanged?.invoke() }
        override fun onMetadataUpdated() { onChanged?.invoke() }
    }
    init { client.registerCallback(callback) }
    override fun status(): RemoteTransport.Status {
        val s = client.mediaStatus
        return RemoteTransport.Status(s?.mediaInfo?.contentId, when (s?.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> RemoteTransport.Mode.PLAYING
            MediaStatus.PLAYER_STATE_PAUSED -> RemoteTransport.Mode.PAUSED
            MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> RemoteTransport.Mode.BUFFERING
            else -> RemoteTransport.Mode.IDLE
        }, client.approximateStreamPosition, client.streamDuration, s?.idleReason == MediaStatus.IDLE_REASON_FINISHED, s?.idleReason == MediaStatus.IDLE_REASON_ERROR,
            (runCatching { session.volume }.getOrDefault(1.0) * 100).toInt().coerceIn(0, 100), runCatching { session.isMute }.getOrDefault(false))
    }
    override fun load(item: MediaItem, position: Long, autoplay: Boolean, onFailure: (String) -> Unit) {
        val track = app.repository.database.findTrack(item.mediaId) ?: return onFailure("Трек удалён из медиатеки.")
        val meta = com.google.android.gms.cast.MediaMetadata(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, track.title)
            putString(com.google.android.gms.cast.MediaMetadata.KEY_ARTIST, track.artist)
            putString(com.google.android.gms.cast.MediaMetadata.KEY_ALBUM_TITLE, track.album)
            server.coverUrl(track)?.let { addImage(WebImage(it)) }
        }
        val info = MediaInfo.Builder(track.id).setContentUrl(server.audioUrl(track).toString()).setContentType(CastStreamServer.audioMime(track))
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setMetadata(meta).build()
        client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setCurrentTime(position).setAutoplay(autoplay).build())
            .setResultCallback { if (!it.status.isSuccess) onFailure("Chromecast не принял трек. Проверьте Wi-Fi и формат файла.") }
    }
    override fun play() { runCatching { client.play() } }
    override fun pause() { runCatching { client.pause() } }
    override fun stop() { runCatching { client.stop() } }
    override fun seek(position: Long) { client.seek(MediaSeekOptions.Builder().setPosition(position).build()) }
    override fun volume(value: Int) { runCatching { session.volume = value.coerceIn(0, 100) / 100.0 } }
    override fun mute(muted: Boolean) { runCatching { session.isMute = muted } }
    override fun release() { onChanged = null; client.unregisterCallback(callback) }
}
