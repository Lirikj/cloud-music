package app.cloudmusic.data

import java.util.Locale

const val DOWNLOAD_FOLDER_ID = "device-downloads"
const val OFFLINE_PREFIX = "offline:"
val DownloadFolder = CloudFolder(DOWNLOAD_FOLDER_ID, "Скачано из Яндекс Диска", "downloads://yandex", Provider.LOCAL)
enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETE, FAILED, MISSING }
data class MusicDownload(val sourceId: String, val managerId: Long, val title: String, val artist: String,
    val album: String, val filename: String, val expectedSize: Long, val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytes: Long = 0, val total: Long = expectedSize, val uri: String = "", val error: String = "") {
    val active get() = status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.PAUSED)
    val localId get() = OFFLINE_PREFIX + sourceId
    fun track() = Track(localId, DOWNLOAD_FOLDER_ID, Provider.LOCAL, uri, title, artist, album, path = filename, size = if (total > 0) total else bytes)
}
fun downloadableFilename(track: Track, attempt: String): String {
    val extension = track.path.substringAfterLast('.', "mp3").lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "mp3"
    var name = "${track.artist} - ${track.title}".replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").trim(' ', '.').take(100).ifBlank { "Трек" }
    while (name.toByteArray(Charsets.UTF_8).size > 150) name = name.dropLast(Character.charCount(name.codePointBefore(name.length)))
    return "$name-${stableId(track.id).take(12)}-${attempt.filter { it.isLetterOrDigit() }.take(16)}.$extension"
}
fun browseTracks(tracks: List<Track>): List<Track> {
    val ids = tracks.asSequence().filter { it.folderId != DOWNLOAD_FOLDER_ID }.map { it.id }.toHashSet()
    return tracks.filter { it.folderId != DOWNLOAD_FOLDER_ID || it.id.removePrefix(OFFLINE_PREFIX) !in ids }
}
fun byteLabel(bytes: Long): String = when {
    bytes <= 0 -> "Размер неизвестен"
    bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} КБ"
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.forLanguageTag("ru"), "%.1f МБ", bytes / (1024.0 * 1024))
    else -> String.format(Locale.forLanguageTag("ru"), "%.1f ГБ", bytes / (1024.0 * 1024 * 1024))
}
