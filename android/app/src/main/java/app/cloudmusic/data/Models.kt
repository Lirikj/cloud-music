package app.cloudmusic.data

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class Provider(val label: String) { YANDEX("Яндекс Диск"), GOOGLE("Google Drive"), LOCAL("Устройство"), DEMO("Демо · на устройстве") }

data class FolderLink(val provider: Provider, val url: String, val remoteId: String, val resourceKey: String = "") {
    val id: String get() = stableId("${provider.name}:$remoteId")
}
data class CloudFolder(val id: String, val name: String, val url: String, val provider: Provider, val resourceKey: String = "", val updatedAt: Long = 0L)
data class Track(
    val id: String, val folderId: String, val provider: Provider, val remoteId: String,
    val title: String, val artist: String, val album: String, val path: String = "",
    val resourceKey: String = "", val size: Long = 0L, val favorite: Boolean = false,
)
data class CollectionSnapshot(val folders: List<CloudFolder> = emptyList(), val tracks: List<Track> = emptyList())
data class FolderContents(val name: String, val tracks: List<Track>, val downloadLinks: Map<String, String> = emptyMap())
data class SavedStreamLink(val trackId: String, val folderId: String, val revision: Long, val url: String, val expiresAt: Long)
data class ArtistGroup(val key: String, val name: String, val tracks: List<Track>)
fun artistKey(name: String): String = name.trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
fun artists(tracks: List<Track>): List<ArtistGroup> = tracks.groupBy { artistKey(it.artist) }.map { (key, songs) ->
    ArtistGroup(key, songs.first().artist.trim(), songs)
}.sortedBy { it.key }
class CloudException(message: String) : java.io.IOException(message)

fun stableId(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

object FolderLinks {
    fun parse(input: String): FolderLink {
        val uri = try { URI(input.trim()) } catch (_: Exception) { throw IllegalArgumentException("Вставьте полную ссылку на папку.") }
        require(uri.scheme?.lowercase() == "https" && uri.rawUserInfo == null && uri.port in listOf(-1, 443)) { "Нужна безопасная ссылка https:// на папку." }
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty().trimEnd('/')
        val params = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val pair = it.split('=', limit = 2)
            if (pair.size == 2) URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") else null
        }.toMap()
        if (host in setOf("disk.yandex.ru", "disk.yandex.com", "yadi.sk")) {
            val id = Regex("^/d/([A-Za-z0-9_-]{5,200})$").matchEntire(path)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("Нужна ссылка на папку Яндекс Диска, а не на отдельный файл.")
            return FolderLink(Provider.YANDEX, "https://disk.yandex.ru/d/$id", id)
        }
        if (host == "drive.google.com") {
            val id = Regex("^/drive/(?:u/\\d+/)?folders/([A-Za-z0-9_-]{5,200})$").matchEntire(path)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("Скопируйте ссылку на папку Google Drive: drive.google.com/drive/folders/…")
            val key = params["resourcekey"].orEmpty()
            require(key.isEmpty() || Regex("[A-Za-z0-9_-]{1,200}").matches(key)) { "Некорректный ключ доступа в ссылке." }
            val url = "https://drive.google.com/drive/folders/$id" + if (key.isNotEmpty()) "?resourcekey=$key" else ""
            return FolderLink(Provider.GOOGLE, url, id, key)
        }
        throw IllegalArgumentException("Поддерживаются папки Яндекс Диска и Google Drive.")
    }
}

object AudioNames {
    private val extensions = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "aiff", "aif", "mp4", "wma", "alac")
    fun isAudio(name: String, mime: String = ""): Boolean = mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in extensions
    fun metadata(name: String): Pair<String, String> {
        val title = name.substringBeforeLast('.', name).replace('_', ' ').trim()
        val split = title.split(" - ", limit = 2)
        return if (split.size == 2 && split.all { it.isNotBlank() }) split[1].trim() to split[0].trim()
        else title.ifBlank { name } to "Неизвестный исполнитель"
    }
}
