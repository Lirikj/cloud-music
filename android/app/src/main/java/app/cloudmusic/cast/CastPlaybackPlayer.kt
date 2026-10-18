package app.cloudmusic.cast

import android.os.Looper
import androidx.media3.common.*
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Keeps the complete queue on the phone; Cast loads only the current item, avoiding 64 KiB message limits. */
@androidx.annotation.OptIn(UnstableApi::class)
class CastPlaybackPlayer(private val remote: RemoteTransport) : SimpleBasePlayer(Looper.getMainLooper()) {
    private data class Entry(val item: MediaItem, val uid: Any = Any())
    private var entries = mutableListOf<Entry>()
    private var index = 0
    private var position = 0L
    private var prepared = false
    private var ready = false
    private var buffering = false
    private var ended = false
    private var shuffle = false
    private var repeat = Player.REPEAT_MODE_OFF
    private var error: PlaybackException? = null
    private var status = RemoteTransport.Status()
    private var order = CastQueueOrder(emptyList())
    private var generation = 0
    private var startedGeneration = -1
    private var released = false
    private var readinessPending = false
    private var readinessSent = false
    init { remote.onChanged = ::changed }
    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()
    private fun rebuildOrder() { order = CastQueueOrder(if (entries.isEmpty()) emptyList() else listOf(index) + entries.indices.filter { it != index }.shuffled()) }
    private fun fail(message: String) { error = PlaybackException(message, null, PlaybackException.ERROR_CODE_REMOTE_ERROR); buffering = false; ready = false; invalidateState() }
    private fun load() {
        if (entries.isEmpty()) return
        generation++; val request = generation; startedGeneration = -1
        buffering = true; ended = false; error = null; readinessPending = true; readinessSent = true
        try { remote.load(entries[index].item, position, ready) { if (!released && request == generation) fail(it) } }
        catch (_: Exception) { fail("Не удалось передать трек на Chromecast.") }
    }
    private fun changed() {
        if (released) return
        status = remote.status()
        if (status.id == entries.getOrNull(index)?.item?.mediaId) {
            if (status.mode != RemoteTransport.Mode.IDLE) {
                startedGeneration = generation; position = status.position.coerceAtLeast(0)
                buffering = status.mode == RemoteTransport.Mode.BUFFERING
                if (!buffering) {
                    val actual = status.mode == RemoteTransport.Mode.PLAYING
                    if (readinessPending && actual == ready) readinessPending = false
                    else if (readinessPending && !readinessSent) { if (ready) remote.play() else remote.pause(); readinessSent = true }
                    else if (!readinessPending) ready = actual
                }
            } else if (status.failed) fail("Chromecast не смог воспроизвести файл. Проверьте сеть и поддерживаемые форматы.")
            else if (status.finished && startedGeneration == generation) {
                startedGeneration = -1
                val next = order.next(index, repeat, shuffle)
                if (next < 0) { ended = true; ready = false; buffering = false; position = status.duration }
                else { index = next; position = 0; load() }
            }
        }
        invalidateState()
    }
    override fun getState(): State {
        val data = entries.mapIndexed { i, e -> MediaItemData.Builder(e.uid).setMediaItem(e.item).setIsSeekable(true)
            .setDurationUs(if (i == index && status.id == e.item.mediaId && status.duration > 0) status.duration * 1000 else C.TIME_UNSET).build() }
        val base = State.Builder().setPlaylist(data).build().timeline
        val snapshotOrder = order
        val timeline = object : ForwardingTimeline(base) {
            override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean) = snapshotOrder.next(windowIndex, repeatMode, shuffleModeEnabled)
            override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean) = snapshotOrder.previous(windowIndex, repeatMode, shuffleModeEnabled)
            override fun getFirstWindowIndex(shuffleModeEnabled: Boolean) = snapshotOrder.first(shuffleModeEnabled)
            override fun getLastWindowIndex(shuffleModeEnabled: Boolean) = snapshotOrder.last(shuffleModeEnabled)
        }
        return State.Builder().setAvailableCommands(Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS).build())
            .setPlaylist(timeline, Tracks.EMPTY, entries.getOrNull(index)?.item?.mediaMetadata)
            .setCurrentMediaItemIndex(if (entries.isEmpty()) C.INDEX_UNSET else index)
            .setPlayWhenReady(ready, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (!prepared || entries.isEmpty() || error != null) Player.STATE_IDLE else if (ended) Player.STATE_ENDED else if (buffering) Player.STATE_BUFFERING else Player.STATE_READY)
            .setIsLoading(buffering).setPlayerError(error).setRepeatMode(repeat).setShuffleModeEnabled(shuffle)
            .setContentPositionMs(PositionSupplier { if (!buffering && !ended && status.id == entries.getOrNull(index)?.item?.mediaId) remote.status().position else position })
            .setContentBufferedPositionMs(PositionSupplier { if (status.duration > 0) minOf(status.duration, position + 1000) else position })
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100).build())
            .setDeviceVolume(status.volume).setIsDeviceMuted(status.muted).build()
    }
    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        entries = mediaItems.map { Entry(it) }.toMutableList(); index = if (entries.isEmpty()) 0 else startIndex.takeIf { it in entries.indices } ?: 0
        position = startPositionMs.takeIf { it >= 0 } ?: 0; error = null; ended = false; rebuildOrder()
        if (prepared && entries.isNotEmpty()) load() else if (entries.isEmpty()) { remote.stop(); prepared = false; buffering = false }
        return done()
    }
    override fun handlePrepare(): ListenableFuture<*> { prepared = true; if (entries.isNotEmpty() && (!buffering || error != null)) load(); return done() }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        ready = playWhenReady
        readinessPending = true; readinessSent = false
        if (prepared && startedGeneration == generation && !buffering) {
            if (playWhenReady) remote.play() else remote.pause(); readinessSent = true
        }
        return done()
    }
    override fun handleStop(): ListenableFuture<*> { remote.stop(); generation++; prepared = false; buffering = false; ready = false; return done() }
    override fun handleRelease(): ListenableFuture<*> { released = true; remote.release(); return done() }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex !in entries.indices) return done()
        val newTrack = mediaItemIndex != index; index = mediaItemIndex; position = positionMs.takeIf { it >= 0 } ?: 0; ended = false
        if (prepared) { if (newTrack || error != null) load() else remote.seek(position) }
        return done()
    }
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> { repeat = repeatMode; return done() }
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> { shuffle = shuffleModeEnabled; rebuildOrder(); return done() }
    private fun mutate(action: () -> Unit): ListenableFuture<*> {
        val old = entries.getOrNull(index)?.uid; val oldIndex = index; action()
        val newIndex = entries.indexOfFirst { it.uid === old }
        index = if (entries.isEmpty()) 0 else if (newIndex >= 0) newIndex else minOf(oldIndex, entries.lastIndex)
        rebuildOrder()
        if (entries.isEmpty()) { remote.stop(); prepared = false; ready = false; buffering = false }
        else if (newIndex < 0 && prepared) { position = 0; load() }
        return done()
    }
    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>) = mutate { entries.addAll(index, mediaItems.map { Entry(it) }) }
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int) = mutate { entries.subList(fromIndex, toIndex).clear() }
    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) = mutate { val moved = entries.subList(fromIndex, toIndex).toList(); entries.subList(fromIndex, toIndex).clear(); entries.addAll(newIndex, moved) }
    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>) = mutate { entries.subList(fromIndex, toIndex).clear(); entries.addAll(fromIndex, mediaItems.map { Entry(it) }) }
    override fun handleSetDeviceVolume(volume: Int, flags: Int): ListenableFuture<*> { remote.volume(volume); return done() }
    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> { remote.volume((remote.status().volume + 5).coerceAtMost(100)); return done() }
    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> { remote.volume((remote.status().volume - 5).coerceAtLeast(0)); return done() }
    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> { remote.mute(muted); return done() }
}
