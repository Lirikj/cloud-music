package app.cloudmusic.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class LocalFiles(private val context: Context) {
    val selectedFolder = CloudFolder("local-files", "Файлы устройства", "local://selected", Provider.LOCAL)
    suspend fun selected(uris: List<Uri>): List<Track> = uris.distinct().map { uri ->
        currentCoroutineContext().ensureActive()
        require(uri.scheme == "content") { "Выберите аудиофайлы через системный выбор файлов." }
        read(uri, selectedFolder.id, "Файлы устройства")
    }
    suspend fun tree(uri: Uri): Pair<CloudFolder, List<Track>> {
        require(DocumentsContract.isTreeUri(uri)) { "Выберите папку с музыкой." }
        val rootId = DocumentsContract.getTreeDocumentId(uri)
        val root = DocumentsContract.buildDocumentUriUsingTree(uri, rootId)
        val name = context.contentResolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Музыка устройства"
        val folder = CloudFolder(stableId("LOCAL:$uri"), name, uri.toString(), Provider.LOCAL, updatedAt = System.currentTimeMillis())
        val tracks = mutableListOf<Track>()
        val visited = mutableSetOf<String>()
        suspend fun walk(id: String, album: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (depth > 20 || visited.size >= 300) throw CloudException("Выберите папку поменьше: до 300 подпапок.")
            if (!visited.add(id)) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, id)
            val entries: List<Triple<String, String, String>> = context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                buildList<Triple<String, String, String>> { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) }
            } ?: throw CloudException("Не удалось прочитать папку. Выберите её снова.")
            for ((childId, childName, mime) in entries) {
                currentCoroutineContext().ensureActive()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(childId, childName, depth + 1)
                else if (AudioNames.isAudio(childName, mime)) {
                    if (tracks.size >= 20_000) throw CloudException("Выберите папку с количеством треков до 20 000.")
                    tracks += read(DocumentsContract.buildDocumentUriUsingTree(uri, childId), folder.id, album)
                }
            }
        }
        walk(rootId, name, 0)
        return folder to tracks
    }
    fun read(uri: Uri, folderId: String, albumFallback: String): Track {
        var name = "Аудиофайл"
        var size = 0L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0); if (!c.isNull(1)) size = c.getLong(1) }
        }
        if (!AudioNames.isAudio(name, context.contentResolver.getType(uri).orEmpty())) throw CloudException("Выберите аудиофайл: $name")
        val (fallbackTitle, fallbackArtist) = AudioNames.metadata(name)
        var title = fallbackTitle
        var artist = fallbackArtist
        var album = albumFallback
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(context, uri)
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it }
        } catch (_: RuntimeException) {
            // Some valid formats have no platform metadata reader. Media3 may still play them.
            context.contentResolver.openFileDescriptor(uri, "r")?.close() ?: throw CloudException("Файл недоступен. Выберите его снова.")
        } finally { reader.release() }
        return Track(stableId("$folderId:$uri"), folderId, Provider.LOCAL, uri.toString(), title, artist, album, path = name, size = size)
    }
}
