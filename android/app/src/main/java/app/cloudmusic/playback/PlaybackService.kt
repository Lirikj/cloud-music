package app.cloudmusic.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.session.DefaultMediaNotificationProvider
import app.cloudmusic.R
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import android.content.pm.PackageManager
import app.cloudmusic.cast.CastCoordinator
import app.cloudmusic.CloudMusicApplication
import app.cloudmusic.MainActivity
import kotlinx.coroutines.*

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var casting: CastCoordinator? = null
    private var localPlayer: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bufferingPriority = false
    override fun onCreate() {
        super.onCreate()
        val app = application as CloudMusicApplication
        val audio = app.audio
        val player = ExoPlayer.Builder(this)
            .setLoadControl(StreamingPolicy.loadControl())
            .setMediaSourceFactory(DefaultMediaSourceFactory(audio.playbackFactory)).build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                setHandleAudioBecomingNoisy(true); setWakeMode(C.WAKE_MODE_LOCAL)
            }
        localPlayer = player
        fun prioritizeMusic() {
            val remote = player.currentMediaItem?.localConfiguration?.uri?.scheme == "cloudmusic"
            val needed = remote && player.playWhenReady && (player.playbackState == Player.STATE_BUFFERING ||
                player.playbackState == Player.STATE_READY && player.isLoading && player.totalBufferedDuration < 20_000)
            if (needed && !bufferingPriority) { audio.priority.add(10); bufferingPriority = true }
            if (!needed && bufferingPriority) { audio.priority.remove(10); bufferingPriority = false }
        }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { prioritizeMusic() }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val id = player.currentMediaItem?.mediaId ?: return
                val bytes = mediaMetadata.artworkData ?: return
                scope.launch(Dispatchers.IO) {
                    try { app.repository.database.findTrack(id)?.let { app.artwork.store(it, bytes) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Artwork must not interrupt audio. */ }
                }
            }
        })
        scope.launch { while (isActive) { delay(500); prioritizeMusic() } }
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val guarded = object : ForwardingPlayer(player) {
            override fun play() { if (foregroundBeforeAudioFocus()) super.play() }
            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (!playWhenReady || foregroundBeforeAudioFocus()) super.setPlayWhenReady(playWhenReady)
            }
        }
        val callback = LibraryCallback(app, scope)
        session = MediaLibrarySession.Builder(this, guarded, callback).setSessionActivity(intent).build()
        casting = CastCoordinator(app, guarded, session!!, scope)
        scope.launch { app.repository.library.collect { session?.let(callback::changed) } }
    }
    private fun foregroundBeforeAudioFocus(): Boolean {
        // Android 15+ denies audio focus to a background app until its media FGS exists.
        // Media3 1.5 posts the normal notification asynchronously, after Player.play().
        if (Build.VERSION.SDK_INT < 35) return true
        val current = session ?: return false
        return try {
            val channel = DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, "Музыка", NotificationManager.IMPORTANCE_LOW))
            val notification = Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(current.player.mediaMetadata.title ?: "Cloud Music")
                .setContentText(current.player.mediaMetadata.artist).setContentIntent(current.sessionActivity)
                .setStyle(Notification.MediaStyle().setMediaSession(current.platformToken)).setOnlyAlertOnce(true).build()
            ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java))
            startForeground(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            true
        } catch (_: IllegalStateException) { false } catch (_: SecurityException) { false }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        val host = controllerInfo.packageName
        val ownSignature = packageManager.checkSignatures(packageName, host) == PackageManager.SIGNATURE_MATCH
        val googleAuto = host in setOf("com.google.android.projection.gearhead", "com.google.android.gms") &&
            packageManager.checkSignatures(host, "com.google.android.gms") == PackageManager.SIGNATURE_MATCH
        return if (host == packageName || ownSignature || controllerInfo.isTrusted || googleAuto) session else null
    }
    override fun onDestroy() {
        casting?.release(); casting = null
        scope.cancel()
        if (bufferingPriority) { (application as CloudMusicApplication).audio.priority.remove(10); bufferingPriority = false }
        session?.release(); session = null
        localPlayer?.release(); localPlayer = null
        super.onDestroy()
    }
}
