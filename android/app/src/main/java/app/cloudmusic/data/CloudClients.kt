package app.cloudmusic.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CloudClients(private val http: OkHttpClient, private val yandexBase: String = "https://cloud-api.yandex.net/v1/disk",
    private val now: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }) {
    private data class DownloadLink(val url: String, val expires: Long)
    private val downloads = ConcurrentHashMap<String, DownloadLink>()
    fun seed(trackId: String, revision: Long, url: String, ttlMillis: Long = 240_000) {
        if (!url.startsWith("https://") || url.length > 4096 || ttlMillis <= 0) return
        downloads["$trackId:$revision"] = DownloadLink(url, now() + minOf(ttlMillis, 240_000))
        while (downloads.size > 512) downloads.keys.firstOrNull()?.let(downloads::remove) ?: break
    }
    fun resolve(track: Track, folder: CloudFolder): String {
        if (track.provider == Provider.DEMO || track.provider == Provider.LOCAL) return track.remoteId
        // Short-lived in-memory reuse; folder metadata may seed this cache. Concurrent requests for the same track share one API call.
        val key = "${track.id}:${folder.updatedAt}"
        val result = downloads.compute(key) { _, cached ->
            if (cached != null && cached.expires > now()) cached
            else DownloadLink(resolveFresh(track, folder), now() + TimeUnit.MINUTES.toMillis(4))
        }!!
        if (downloads.size > 512) downloads.keys.firstOrNull { it != key }?.let(downloads::remove)
        return result.url
    }
    fun invalidate(track: Track, folder: CloudFolder) { downloads.remove("${track.id}:${folder.updatedAt}") }
    fun readFolder(link: FolderLink): FolderContents = when (link.provider) {
        Provider.YANDEX -> readYandex(link)
        Provider.GOOGLE -> readGoogle(link)
        Provider.DEMO -> error("Local collection")
        Provider.LOCAL -> error("Use the device file picker")
    }
    private fun resolveFresh(track: Track, folder: CloudFolder): String = when (track.provider) {
        Provider.YANDEX -> {
            val url = "$yandexBase/public/resources/download".toHttpUrl().newBuilder()
                .addQueryParameter("public_key", folder.url).addQueryParameter("path", track.remoteId).build()
            val json = JSONObject(getText(url.toString()))
            val target = json.optString("href")
            if (!target.startsWith("https://")) throw CloudException("Диск не выдал ссылку для прослушивания. Попробуйте обновить папку.")
            target
        }
        Provider.GOOGLE -> resolveGoogle(track)
        Provider.DEMO -> track.remoteId
        Provider.LOCAL -> track.remoteId
    }
    private fun readYandex(link: FolderLink): FolderContents {
        val tracks = mutableListOf<Track>()
        val links = mutableMapOf<String, String>()
        val visited = mutableSetOf<String>()
        var rootName = "Музыка"
        fun walk(path: String?, album: String, depth: Int) {
            if (depth > 20 || visited.size >= 300 || tracks.size >= 20_000) throw CloudException("Папка слишком большая. Подключите отдельную папку с альбомами.")
            if (!visited.add(path.orEmpty())) return
            var offset = 0
            while (true) {
                val url = "$yandexBase/public/resources".toHttpUrl().newBuilder()
                    .addQueryParameter("public_key", link.url).addQueryParameter("limit", "200").addQueryParameter("offset", offset.toString())
                    .apply { if (path != null) addQueryParameter("path", path) }.build()
                val json = JSONObject(getText(url.toString()))
                if (json.optString("type") != "dir") throw CloudException("Это ссылка на файл. Подключите папку с музыкой.")
                if (path == null) rootName = json.optString("name", "Музыка")
                val embedded = json.optJSONObject("_embedded") ?: throw CloudException("Не удалось получить содержимое папки.")
                val items = embedded.optJSONArray("items") ?: throw CloudException("Диск вернул неполный список файлов. Попробуйте позже.")
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val name = item.optString("name")
                    val itemPath = item.optString("path")
                    if (itemPath.isBlank()) continue
                    if (item.optString("type") == "dir") walk(itemPath, name, depth + 1)
                    else if (AudioNames.isAudio(name, item.optString("mime_type"))) {
                        if (tracks.size >= 20_000) throw CloudException("В папке больше 20 000 треков. Подключите папку поменьше.")
                        val (title, artist) = AudioNames.metadata(name)
                        val id = stableId("${link.id}:$itemPath")
                        val direct = item.optString("file")
                        if (links.size < 512 && direct.startsWith("https://") && direct.length <= 4096) links[id] = direct
                        tracks += Track(id, link.id, link.provider, itemPath, title, artist,
                            if (path == null) rootName else album, path = itemPath, size = item.optLong("size"))
                    }
                }
                offset += items.length()
                if (offset >= embedded.optInt("total", offset) || items.length() == 0) break
            }
        }
        walk(null, "Музыка", 0)
        return FolderContents(rootName, tracks.distinctBy { it.id }, links)
    }
    private fun readGoogle(link: FolderLink): FolderContents {
        val tracks = mutableListOf<Track>()
        val visited = mutableSetOf<String>()
        var rootName = "Музыка"
        fun walk(id: String, key: String, album: String, depth: Int) {
            if (depth > 20 || visited.size >= 300) throw CloudException("Папка слишком большая. Подключите отдельную папку с альбомами.")
            if (!visited.add(id)) return
            val url = "https://drive.google.com/embeddedfolderview".toHttpUrl().newBuilder()
                .addQueryParameter("id", id).apply { if (key.isNotBlank()) addQueryParameter("resourcekey", key) }.build()
            val page = GoogleFolderPage.parse(getText(url.toString()))
            if (depth == 0) rootName = page.name
            for (entry in page.entries) {
                if (entry.folder) walk(entry.id, entry.resourceKey, entry.name, depth + 1)
                else if (AudioNames.isAudio(entry.name)) {
                    if (tracks.size >= 20_000) throw CloudException("В папке больше 20 000 треков. Подключите папку поменьше.")
                    val (title, artist) = AudioNames.metadata(entry.name)
                    tracks += Track(stableId("${link.id}:${entry.id}"), link.id, link.provider, entry.id,
                        title, artist, if (depth == 0) rootName else album, resourceKey = entry.resourceKey)
                }
            }
        }
        walk(link.remoteId, link.resourceKey, "Музыка", 0)
        return FolderContents(rootName, tracks.distinctBy { it.id })
    }
    private fun resolveGoogle(track: Track): String {
        var url = "https://drive.usercontent.google.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("id", track.remoteId).addQueryParameter("export", "download")
            .apply { if (track.resourceKey.isNotEmpty()) addQueryParameter("resourcekey", track.resourceKey) }.build()
        repeat(3) {
            http.newCall(Request.Builder().url(url).header("Range", "bytes=0-1023").build()).execute().use { response ->
                ensureSuccess(response)
                val type = response.header("Content-Type").orEmpty().lowercase()
                if (!type.contains("text/html")) {
                    if (type.contains("text/") || type.contains("json")) throw CloudException("Google Drive не выдал аудиофайл. Проверьте доступ и лимит скачиваний.")
                    return response.request.url.toString()
                }
                val doc = Jsoup.parse(response.peekBody(2_000_000).string())
                val form = doc.selectFirst("form#download-form")
                    ?: throw CloudException("Google Drive ограничил скачивание или закрыл доступ к треку. Попробуйте позже.")
                val action = form.attr("action").toHttpUrl()
                if (action.isHttps.not() || action.host !in setOf("drive.usercontent.google.com", "drive.google.com")) throw CloudException("Не удалось получить безопасную ссылку Google Drive.")
                url = action.newBuilder().apply {
                    form.select("input[name]").forEach { addQueryParameter(it.attr("name"), it.attr("value")) }
                }.build()
            }
        }
        throw CloudException("Google Drive требует дополнительного подтверждения скачивания этого файла.")
    }
    internal fun getText(url: String): String = http.newCall(Request.Builder().url(url).build()).execute().use {
        ensureSuccess(it)
        val body = it.body ?: throw CloudException("Сервис вернул пустой ответ.")
        if (body.contentLength() > 8_000_000) throw CloudException("Список файлов слишком большой.")
        it.peekBody(8_000_001).string().also { text -> if (text.length > 8_000_000) throw CloudException("Список файлов слишком большой.") }
    }
    private fun ensureSuccess(response: Response) {
        when (response.code) {
            401, 403 -> throw CloudException("Нет доступа к папке или файлу. Разрешите просмотр и скачивание по ссылке.")
            404 -> throw CloudException("Папка или файл не найдены. Проверьте ссылку и доступ.")
            429 -> throw CloudException("Диск временно ограничил запросы. Попробуйте позже.")
        }
        if (!response.isSuccessful) throw CloudException("Сервис диска временно недоступен (${response.code}). Попробуйте ещё раз.")
        if (response.request.url.host == "accounts.google.com") throw CloudException("Эта папка требует входа в Google. Откройте доступ по ссылке.")
    }
}

data class GoogleEntry(val id: String, val name: String, val folder: Boolean, val resourceKey: String = "")
data class GoogleFolderPage(val name: String, val entries: List<GoogleEntry>) {
    companion object {
        fun parse(html: String): GoogleFolderPage {
            val document = Jsoup.parse(html)
            if (document.selectFirst("form[action*=accounts.google.com]") != null || document.title().contains("Sign in", true))
                throw CloudException("Эта папка требует входа в Google. Откройте доступ по ссылке.")
            val entries = document.select("a[href]").mapNotNull { node ->
                val url = runCatching { node.attr("href").toHttpUrl() }.getOrNull() ?: return@mapNotNull null
                if (url.host != "drive.google.com") return@mapNotNull null
                val path = url.encodedPath
                val file = Regex("^/file/d/([A-Za-z0-9_-]+)/view/?$").matchEntire(path)
                val folder = Regex("^/drive/(?:u/\\d+/)?folders/([A-Za-z0-9_-]+)/?$").matchEntire(path)
                val match = file ?: folder ?: return@mapNotNull null
                GoogleEntry(match.groupValues[1], node.text().trim(), folder != null, url.queryParameter("resourcekey").orEmpty())
            }.distinctBy { it.id }
            val title = document.title().removeSuffix(" - Google Drive").trim()
            if (title.isBlank() || (entries.isEmpty() && document.select(".folder-empty, .flip-entries, .flip-empty").isEmpty()))
                throw CloudException("Google Drive не открыл список файлов. Проверьте публичный доступ к папке.")
            return GoogleFolderPage(title, entries)
        }
    }
}
