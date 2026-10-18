package app.cloudmusic.playback

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.media3.session.MediaLibraryService.*
import app.cloudmusic.CloudMusicApplication
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.*
import kotlinx.coroutines.*

@androidx.annotation.OptIn(UnstableApi::class)
class LibraryCallback(private val app: CloudMusicApplication, private val scope: CoroutineScope) : MediaLibrarySession.Callback {
    private val subscriptions = linkedSetOf<String>()
    private fun <T> async(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = scope.launch { try { result.set(block()) } catch (e: CancellationException) { result.cancel(false); throw e } catch (e: Exception) { result.setException(e) } }
        result.addListener({ if (result.isCancelled) job.cancel() }, MoreExecutors.directExecutor()); return result
    }
    private suspend fun catalogue(): MusicCatalogue = withContext(Dispatchers.IO) { app.repository.load(); MusicCatalogue(app.repository.library.value) }
    private fun item(entry: MusicCatalogue.Entry, browser: MediaSession.ControllerInfo): MediaItem {
        val track = entry.track
        if (track != null) {
            val result = MediaItems.track(app, track, artwork = true).buildUpon().setMediaId(entry.id).build()
            result.mediaMetadata.artworkUri?.takeIf { it.scheme == "content" }?.let {
                runCatching { app.grantUriPermission(browser.packageName, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            return result
        }
        return MediaItem.Builder().setMediaId(entry.id).setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).setSubtitle(entry.subtitle)
            .setArtworkUri(MediaItems.categoryArtwork(app, entry.id)).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()
    }
    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> = async {
        catalogue()
        val hints = LibraryParams.Builder().setExtras(Bundle().apply {
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
        }).build()
        LibraryResult.ofItem(item(MusicCatalogue.Entry(MusicCatalogue.ROOT, "Cloud Music"), browser), hints)
    }
    override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = async {
        val c = catalogue()
        withContext(Dispatchers.IO) { LibraryResult.ofItemList(MusicCatalogue.page(c.children(parentId), page, pageSize).map { item(it, browser) }, params) }
    }
    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = async {
        val entry = catalogue().item(mediaId)
        if (entry == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) else withContext(Dispatchers.IO) { LibraryResult.ofItem(item(entry, browser), null) }
    }
    override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = async {
        if (subscriptions.size < 256) subscriptions += parentId
        session.notifyChildrenChanged(browser, parentId, catalogue().children(parentId).size, params)
        LibraryResult.ofVoid(params)
    }
    override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = async {
        session.notifySearchResultChanged(browser, query, catalogue().search(query).size, params); LibraryResult.ofVoid(params)
    }
    override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = async {
        val c = catalogue()
        withContext(Dispatchers.IO) { LibraryResult.ofItemList(MusicCatalogue.page(c.search(query), page, pageSize).map { item(it, browser) }, params) }
    }
    override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<List<MediaItem>> = async {
        if (controller.packageName == app.packageName && mediaItems.all { it.localConfiguration != null && !it.mediaId.startsWith("play/") }) mediaItems else {
            val c = catalogue()
            withContext(Dispatchers.IO) { mediaItems.map { input ->
                val selected = c.selection(input.mediaId)?.let { it.tracks[it.index] } ?: app.repository.database.findTrack(input.mediaId)
                requireNotNull(selected) { "Unknown track" }; MediaItems.track(app, selected)
            } }
        }
    }
    override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = async {
        if (mediaItems.size == 1 && (mediaItems.single().mediaId.startsWith("play/") || mediaItems.single().requestMetadata.searchQuery != null)) {
            val c = catalogue(); val input = mediaItems.single()
            val selection = c.selection(input.mediaId) ?: input.requestMetadata.searchQuery?.let { q ->
                (if (q.isBlank()) c.tracksFor(MusicCatalogue.ALL) else c.search(q).mapNotNull { it.track }).takeIf { it.isNotEmpty() }?.let { MusicCatalogue.Selection(it, 0) }
            }
            if (selection != null) return@async withContext(Dispatchers.IO) {
                MediaSession.MediaItemsWithStartPosition(selection.tracks.map { MediaItems.track(app, it) }, selection.index, startPositionMs)
            }
        }
        val resolved = onAddMediaItems(session, controller, mediaItems)
        // This future is normally already queued on Main; never block Main waiting for it.
        suspendCancellableCoroutine { continuation -> resolved.addListener({
            runCatching { resolved.get() }.onSuccess { continuation.resumeWith(Result.success(MediaSession.MediaItemsWithStartPosition(it, startIndex, startPositionMs))) }
                .onFailure { continuation.resumeWith(Result.failure(it)) }
        }, MoreExecutors.directExecutor()); continuation.invokeOnCancellation { resolved.cancel(false) } }
    }
    override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = async {
        val c = catalogue()
        withContext(Dispatchers.IO) { MediaSession.MediaItemsWithStartPosition(c.tracksFor(MusicCatalogue.ALL).map { MediaItems.track(app, it) }, 0, 0) }
    }
    fun changed(session: MediaLibrarySession) {
        val c = MusicCatalogue(app.repository.library.value)
        subscriptions.toList().forEach { session.notifyChildrenChanged(it, c.children(it).size, null) }
    }
}
