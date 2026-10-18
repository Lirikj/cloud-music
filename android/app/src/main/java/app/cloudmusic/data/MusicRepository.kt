package app.cloudmusic.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MusicRepository(val database: MusicDatabase, val cloud: CloudClients, private val localFiles: LocalFiles? = null) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(CollectionSnapshot())
    val library = mutable.asStateFlow()
    suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock {
        mutable.value = database.snapshot()
        database.activeStreamLinks().forEach { cloud.seed(it.trackId, it.revision, it.url, it.expiresAt - System.currentTimeMillis()) }
    } }
    suspend fun connect(link: FolderLink): CloudFolder = withContext(Dispatchers.IO) {
        mutex.withLock {
            val contents = cloud.readFolder(link)
            val folder = CloudFolder(link.id, contents.name, link.url, link.provider, link.resourceKey, System.currentTimeMillis())
            database.replaceFolder(folder, contents.tracks)
            database.saveStreamLinks(folder, contents.downloadLinks)
            contents.downloadLinks.forEach { (id, url) -> cloud.seed(id, folder.updatedAt, url) }
            mutable.value = database.snapshot()
            folder
        }
    }
    suspend fun refresh(folder: CloudFolder) {
        if (folder.provider == Provider.DEMO || folder.id == DOWNLOAD_FOLDER_ID) return
        if (folder.provider == Provider.LOCAL) {
            if (folder.url.startsWith("content:")) connectLocalTree(android.net.Uri.parse(folder.url))
            else withContext(Dispatchers.IO) { mutex.withLock {
                val reader = localFiles ?: error("Local files unavailable")
                val old = database.snapshot().tracks.filter { it.folderId == folder.id }
                val tracks = old.map { reader.read(android.net.Uri.parse(it.remoteId), folder.id, folder.name) }
                database.replaceFolder(folder.copy(updatedAt = System.currentTimeMillis()), tracks)
                mutable.value = database.snapshot()
            } }
            return
        }
        connect(FolderLinks.parse(folder.url))
    }
    suspend fun addLocalFiles(uris: List<android.net.Uri>) = withContext(Dispatchers.IO) { mutex.withLock {
        val reader = localFiles ?: error("Local files unavailable")
        val folder = reader.selectedFolder.copy(updatedAt = System.currentTimeMillis())
        val old = database.snapshot().tracks.filter { it.folderId == folder.id }
        val added = reader.selected(uris)
        database.replaceFolder(folder, (old + added).distinctBy { it.id })
        mutable.value = database.snapshot()
        added.size
    } }
    suspend fun connectLocalTree(uri: android.net.Uri) = withContext(Dispatchers.IO) { mutex.withLock {
        val (folder, tracks) = (localFiles ?: error("Local files unavailable")).tree(uri)
        database.replaceFolder(folder, tracks); mutable.value = database.snapshot()
    } }
    suspend fun publishDownloads(downloads: List<MusicDownload>) = withContext(Dispatchers.IO) { mutex.withLock {
        val complete = downloads.filter { it.status == DownloadStatus.COMPLETE && it.uri.isNotEmpty() }.map { it.track().copy(favorite = database.findTrack(it.sourceId)?.favorite ?: false) }
        val existing = database.snapshot().tracks.filter { it.folderId == DOWNLOAD_FOLDER_ID }
        if (existing.map { it.copy(favorite = false) }.toSet() != complete.map { it.copy(favorite = false) }.toSet()) {
            if (complete.isEmpty()) database.removeFolder(DOWNLOAD_FOLDER_ID) else database.replaceFolder(DownloadFolder, complete)
            mutable.value = database.snapshot()
        }
    } }
    suspend fun favorite(track: Track) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sourceId = if (track.folderId == DOWNLOAD_FOLDER_ID) track.id.removePrefix(OFFLINE_PREFIX) else track.id
            database.setFavorite(sourceId, !track.favorite)
            database.setFavorite(OFFLINE_PREFIX + sourceId, !track.favorite)
            mutable.value = database.snapshot()
        }
    }
    suspend fun remove(folder: CloudFolder) = withContext(Dispatchers.IO) {
        mutex.withLock { database.removeFolder(folder.id); mutable.value = database.snapshot() }
    }
    suspend fun demo() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val folder = CloudFolder("demo", "Демо-коллекция", "demo://collection", Provider.DEMO, updatedAt = System.currentTimeMillis())
            val tracks = listOf("Тихое утро", "По орбите", "Медленный свет").mapIndexed { index, title ->
                Track("demo-$index", folder.id, Provider.DEMO, "asset:///demo/track-$index.wav", title, "Cloud Music", "Первые звуки")
            }
            database.replaceFolder(folder, tracks); mutable.value = database.snapshot()
        }
    }
}
