package app.cloudmusic.cast

import androidx.media3.common.Player
import android.os.PowerManager
import android.annotation.SuppressLint
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import app.cloudmusic.CloudMusicApplication
import app.cloudmusic.playback.MediaItems
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** One MediaSession remains the control surface for the phone, Auto and the notification. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CastCoordinator(private val app: CloudMusicApplication, private val local: Player, private val session: MediaSession, private val scope: CoroutineScope) {
    private var remote: CastPlaybackPlayer? = null
    private var relay: CastStreamServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val job = scope.launch {
        app.casting.sessions.collectLatest { cast ->
            if (cast == null) disconnect() else if (remote == null) {
                val localWasPlaying = local.playWhenReady
                var server: CastStreamServer? = null
                try {
                    withContext(Dispatchers.IO) { server = CastStreamServer(app).start() }
                    val transport = GoogleCastTransport(app, cast, requireNotNull(server))
                    val player = CastPlaybackPlayer(transport)
                    val old = (0 until local.mediaItemCount).map { local.getMediaItemAt(it) }.ifEmpty {
                        cast.remoteMediaClient?.mediaStatus?.mediaInfo?.contentId?.let(app.repository.database::findTrack)?.let { listOf(MediaItems.track(app, it)) }.orEmpty()
                    }
                    val start = local.currentMediaItemIndex.coerceAtLeast(0)
                    val position = (if (local.mediaItemCount == 0) cast.remoteMediaClient?.approximateStreamPosition ?: 0 else local.currentPosition).coerceAtLeast(0)
                    val play = if (local.mediaItemCount == 0) cast.remoteMediaClient?.isPlaying == true else local.playWhenReady
                    local.pause(); local.stop()
                    remote = player; relay = server
                    player.addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            if (player.playWhenReady && player.playbackState in listOf(Player.STATE_BUFFERING, Player.STATE_READY)) holdAwake() else releaseWakeLock()
                        }
                    })
                    session.setPlayer(player)
                    player.setMediaItems(old, start, position)
                    player.repeatMode = local.repeatMode; player.shuffleModeEnabled = local.shuffleModeEnabled
                    if (old.isNotEmpty()) { player.prepare(); player.playWhenReady = play }
                } catch (e: CancellationException) { server?.let { withContext(NonCancellable + Dispatchers.IO) { it.close() } }; throw e }
                catch (_: Exception) { session.setPlayer(local); remote?.release(); remote = null; relay = null; releaseWakeLock(); if (local.mediaItemCount > 0) local.prepare(); local.playWhenReady = localWasPlaying; server?.let { withContext(Dispatchers.IO) { it.close() } }; app.casting.playbackFailed("Для трансляции подключите телефон и Chromecast к одной Wi-Fi-сети.") }

            }
        }
    }
    private suspend fun disconnect() {
        val player = remote ?: return
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val position = player.currentPosition.coerceAtLeast(0)
        val resume = app.casting.consumeResumeOnPhone() && player.playWhenReady
        val repeat = player.repeatMode; val shuffle = player.shuffleModeEnabled
        session.setPlayer(local)
        local.setMediaItems(items, index, position); local.repeatMode = repeat; local.shuffleModeEnabled = shuffle
        if (items.isNotEmpty()) { local.prepare(); local.playWhenReady = resume }
        player.release(); remote = null; releaseWakeLock()
        val server = relay; relay = null; withContext(NonCancellable + Dispatchers.IO) { server?.close() }
    }
    @SuppressLint("WakelockTimeout")
    private fun holdAwake() {
        if (wakeLock?.isHeld == true) return
        // The LAN relay must keep streaming with the screen off. Every disconnect/destroy releases it.
        wakeLock = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CloudMusic:castRelay").apply {
            setReferenceCounted(false); acquire()
        }
    }
    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    fun release() {
        job.cancel(); remote?.release(); remote = null; releaseWakeLock()
        val server = relay; relay = null
        // Network cleanup must not block the service's main thread.
        if (server != null) Thread({ server.close() }, "cloud-cast-close").start()
    }
}
