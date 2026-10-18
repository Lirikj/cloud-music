package app.cloudmusic.playback

import app.cloudmusic.data.*
import java.util.Base64

/** Stable browse IDs carry the source list, so playing an artist preserves that artist's queue. */
class MusicCatalogue(private val snapshot: CollectionSnapshot) {
    data class Entry(val id: String, val title: String, val subtitle: String = "", val track: Track? = null)
    data class Selection(val tracks: List<Track>, val index: Int)
    private val tracks = browseTracks(snapshot.tracks).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    fun children(parent: String): List<Entry> = when (parent) {
        ROOT -> listOf(Entry(ALL, "Все треки"), Entry(ARTISTS, "Исполнители"), Entry(FOLDERS, "Папки"), Entry(FAVORITES, "Избранное"))
        ARTISTS -> artists(tracks).map { Entry("artist:" + stableId(it.key), it.name, "Треков: ${it.tracks.size}") }
        FOLDERS -> listOf(Entry(OFFLINE, "На устройстве")) + snapshot.folders.map { Entry("folder:" + it.id, it.name, it.provider.label) }
        else -> tracksFor(parent).map { Entry(playId(parent, it.id), it.title, it.artist, it) }
    }
    fun tracksFor(parent: String): List<Track> = when {
        parent == ALL -> tracks
        parent == FAVORITES -> tracks.filter { it.favorite }
        parent == OFFLINE -> snapshot.tracks.filter { it.provider == Provider.LOCAL }.sortedBy { it.title }
        parent.startsWith("folder:") -> snapshot.tracks.filter { it.folderId == parent.removePrefix("folder:") }.sortedBy { it.title }
        parent.startsWith("artist:") -> tracks.filter { stableId(artistKey(it.artist)) == parent.removePrefix("artist:") }
        else -> emptyList()
    }
    fun search(query: String): List<Entry> {
        val q = query.trim().take(256)
        if (q.isEmpty()) return emptyList()
        return tracks.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
            .map { Entry(playId("search:$q", it.id), it.title, it.artist, it) }
    }
    fun selection(mediaId: String): Selection? = runCatching {
        val parts = mediaId.split('/'); if (parts.size != 3 || parts[0] != "play" || mediaId.length > 4096) return null
        val parent = decode(parts[1]); val id = decode(parts[2])
        val list = if (parent.startsWith("search:")) search(parent.removePrefix("search:")).mapNotNull { it.track } else tracksFor(parent)
        val index = list.indexOfFirst { it.id == id }; if (index < 0) null else Selection(list, index)
    }.getOrNull()
    fun item(id: String): Entry? = if (id == ROOT) Entry(ROOT, "Cloud Music") else
        children(ROOT).firstOrNull { it.id == id } ?: children(ARTISTS).firstOrNull { it.id == id } ?: children(FOLDERS).firstOrNull { it.id == id }
        ?: selection(id)?.let { Entry(id, it.tracks[it.index].title, it.tracks[it.index].artist, it.tracks[it.index]) }
    companion object {
        const val ROOT = "cloud-music-root"
        const val ALL = "all"
        const val ARTISTS = "artists"
        const val FOLDERS = "folders"
        const val FAVORITES = "favorites"
        const val OFFLINE = "offline"
        private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        private fun decode(value: String) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
        fun playId(parent: String, id: String) = "play/${encode(parent)}/${encode(id)}"
        fun <T> page(items: List<T>, page: Int, size: Int): List<T> {
            if (page < 0 || size <= 0) return emptyList()
            val start = page.toLong() * size
            if (start >= items.size) return emptyList()
            return items.subList(start.toInt(), minOf(items.size.toLong(), start + size).toInt())
        }
    }
}
