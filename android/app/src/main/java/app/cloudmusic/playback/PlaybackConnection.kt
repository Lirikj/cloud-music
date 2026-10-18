package app.cloudmusic.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.cloudmusic.data.Track
import app.cloudmusic.data.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackState(
    val connected: Boolean = false, val currentId: String? = null, val playing: Boolean = false,
    val buffering: Boolean = false, val position: Long = 0, val duration: Long = 0,
    val queue: List<String> = emptyList(), val index: Int = 0, val shuffle: Boolean = false,
    val nextId: String? = null, val repeat: Int = Player.REPEAT_MODE_OFF, val error: String? = null, val artwork: ByteArray? = null,
)

class PlaybackConnection(context: Context, scope: CoroutineScope) {
    private val downloads = (context.applicationContext as app.cloudmusic.CloudMusicApplication).downloads
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    val uiState = state.map { it.copy(position = 0) }.stateIn(scope, SharingStarted.Eagerly, PlaybackState())
    private var controller: MediaController? = null
    private var queuedUris: List<Uri> = emptyList()
    private var queueIds: List<String> = emptyList()
    private var queueChanged = true
    private var pending: (() -> Unit)? = null
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) queueChanged = true
            update()
        }
        override fun onPlayerError(error: PlaybackException) {
            val message = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<app.cloudmusic.data.CloudException>().firstOrNull()?.message
                ?: when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Нет соединения с диском. Проверьте интернет и повторите попытку."
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Диск не выдал аудиофайл. Проверьте доступ или попробуйте позже."
                    PlaybackException.ERROR_CODE_REMOTE_ERROR -> error.message ?: "Не удалось воспроизвести трек на Chromecast."
                    PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Этот аудиофайл не поддерживается устройством. Выберите другой трек."
                    else -> "Не удалось воспроизвести трек. Проверьте доступ к файлу и интернет."
                }
            mutable.value = mutable.value.copy(error = message, buffering = false)
        }
    }
    init {
        future.addListener({
            try {
                controller = future.get().also { it.addListener(listener) }
                update(); pending?.invoke(); pending = null
            } catch (_: Exception) { mutable.value = mutable.value.copy(error = "Не удалось подключить плеер. Перезапустите приложение.") }
        }, ContextCompat.getMainExecutor(context))
        scope.launch { while (isActive) { delay(500); update() } }
    }
    private fun update() {
        val c = controller ?: return
        if (queueChanged || queueIds.size != c.mediaItemCount) { queueIds = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }; queueChanged = false }
        mutable.value = mutable.value.copy(
            connected = true, currentId = c.currentMediaItem?.mediaId, playing = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            position = c.currentPosition.coerceAtLeast(0), duration = c.duration.coerceAtLeast(0),
            queue = queueIds, nextId = c.nextMediaItemIndex.takeIf { it >= 0 }?.let { c.getMediaItemAt(it).mediaId }, index = c.currentMediaItemIndex.coerceAtLeast(0),
            shuffle = c.shuffleModeEnabled, repeat = c.repeatMode, artwork = c.mediaMetadata.artworkData,
        )
    }
    private fun command(action: () -> Unit) { if (controller != null) action() else pending = action }
    fun play(tracks: List<Track>, selected: Track) {
        if (tracks.isEmpty()) return
        command {
            val index = tracks.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
            controller?.run {
                val uris = tracks.map(::trackUri)
                val sameQueue = queuedUris == uris && mediaItemCount == tracks.size && tracks.indices.all { getMediaItemAt(it).mediaId == tracks[it].id }
                if (sameQueue) { if (currentMediaItemIndex != index) seekToDefaultPosition(index) }
                else { setMediaItems(tracks.map(::mediaItem), index, 0L); queuedUris = uris }
                if (!sameQueue || playbackState == Player.STATE_IDLE || playerError != null) prepare()
                play()
            }
            mutable.value = mutable.value.copy(error = null)
            update()
        }
    }
    fun toggle() = command {
        controller?.run {
            if (isPlaying || playWhenReady && playbackState == Player.STATE_BUFFERING) pause()
            else { if (playerError != null || playbackState == Player.STATE_IDLE) prepare(); play() }
        }
        mutable.value = mutable.value.copy(error = null); update()
    }
    fun next() { controller?.seekToNextMediaItem(); update() }
    fun previous() { controller?.run { if (currentPosition > 3000) seekTo(0L) else seekToPreviousMediaItem() }; update() }
    fun seek(position: Long) { controller?.seekTo(position.coerceAtLeast(0)); update() }
    fun shuffle() { controller?.run { shuffleModeEnabled = !shuffleModeEnabled }; update() }
    fun repeat() { controller?.run { repeatMode = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }; update() }
    fun enqueue(track: Track) = command {
        controller?.run { if (mediaItemCount == 0) { setMediaItem(mediaItem(track)); prepare() } else addMediaItem((currentMediaItemIndex + 1).coerceAtLeast(0), mediaItem(track)) }
        update()
    }
    fun jump(index: Int) { controller?.run { seekToDefaultPosition(index); prepare(); play() }; update() }
    fun removeFolder(trackIds: Set<String>) {
        controller?.run {
            if (currentMediaItem?.mediaId in trackIds) pause()
            for (i in mediaItemCount - 1 downTo 0) if (getMediaItemAt(i).mediaId in trackIds) removeMediaItem(i)
        }
        update()
    }
    fun clearError() { mutable.value = mutable.value.copy(error = null) }
    fun release() { controller?.removeListener(listener); MediaController.releaseFuture(future) }
    private fun trackUri(track: Track): Uri = downloads.uriForSource(track.id) ?: if (track.provider == Provider.LOCAL || track.provider == Provider.DEMO) Uri.parse(track.remoteId) else Uri.Builder().scheme("cloudmusic").authority("track").appendPath(track.id).build()
    private fun mediaItem(track: Track) = MediaItem.Builder().setMediaId(track.id)
        .setUri(trackUri(track))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).build()).build()
}
