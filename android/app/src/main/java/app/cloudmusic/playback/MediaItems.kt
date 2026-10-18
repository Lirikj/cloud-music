package app.cloudmusic.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.cloudmusic.CloudMusicApplication
import app.cloudmusic.R
import app.cloudmusic.cast.CastStreamServer
import app.cloudmusic.data.Track

object MediaItems {
    fun categoryArtwork(app: CloudMusicApplication, id: String): Uri {
        val icon = when {
            id == MusicCatalogue.ARTISTS || id.startsWith("artist:") -> R.drawable.ic_auto_artist
            id == MusicCatalogue.FAVORITES -> R.drawable.ic_auto_favorite
            id == MusicCatalogue.OFFLINE -> R.drawable.ic_auto_offline
            id == MusicCatalogue.FOLDERS || id.startsWith("folder:") -> R.drawable.ic_auto_folder
            else -> R.drawable.ic_auto_tracks
        }
        return Uri.parse("android.resource://${app.packageName}/$icon")
    }
    fun track(app: CloudMusicApplication, track: Track, artwork: Boolean = false): MediaItem = MediaItem.Builder()
        .setMediaId(track.id).setUri(app.downloads.uriForSource(track.id) ?: app.audio.uri(track))
        .setMimeType(CastStreamServer.audioMime(track))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
            .setIsPlayable(true).setIsBrowsable(false).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply { if (artwork) setArtworkUri(artworkUri(app, track)) }.build()).build()
    fun artworkUri(app: CloudMusicApplication, track: Track): Uri = if (app.artwork.cachedFile(track) != null)
        Uri.Builder().scheme("content").authority("${app.packageName}.artwork").appendPath(track.id).build()
        else Uri.parse("android.resource://${app.packageName}/mipmap/ic_launcher")
}
