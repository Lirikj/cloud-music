package app.cloudmusic.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Explicit, system-managed downloads to the user's public Downloads directory. */
class MusicDownloads(private val context: Context, private val repository: MusicRepository) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<List<MusicDownload>>(emptyList())
    val state = mutable.asStateFlow()
    @Volatile private var localUris: Map<String, Uri> = emptyMap()
    private fun publishState(records: List<MusicDownload>) {
        localUris = records.filter { it.status == DownloadStatus.COMPLETE && it.uri.isNotEmpty() }.associate { it.sourceId to Uri.parse(it.uri) }
        mutable.value = records
    }
    private var started = false
    @Volatile private var restored = false
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            try { restoreIndex(); sync(true) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Retry when opened. */ }
            while (isActive) {
                delay(1500)
                if (mutable.value.any { it.active }) try { sync() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* A provider can be temporarily unavailable. */ }
            }
        }
    }
    suspend fun restoreIndex() {
        if (restored) return
        withContext(Dispatchers.IO) { mutex.withLock {
            if (!restored) {
                val records = repository.database.downloads()
                repository.publishDownloads(records)
                publishState(records); restored = true
            }
        } }
    }
    fun uriForSource(id: String): Uri? = localUris[id]
    suspend fun enqueue(track: Track): Boolean {
        sync(true)
        return withContext(Dispatchers.IO) { mutex.withLock {
        require(track.provider == Provider.YANDEX) { "Скачивание доступно для треков Яндекс Диска." }
        val saved = repository.database.downloads().firstOrNull { it.sourceId == track.id }
        if (saved?.active == true || saved?.status == DownloadStatus.COMPLETE) return@withLock false
        val folder = repository.database.findFolder(track.folderId) ?: throw CloudException("Подключите исходную папку заново.")
        // Retry gets a fresh short-lived URL, rather than reusing an expired request.
        if (saved != null) repository.cloud.invalidate(track, folder)
        val url = repository.cloud.resolve(track, folder)
        require(Uri.parse(url).scheme == "https") { "Диск не выдал безопасную ссылку." }
        val filename = downloadableFilename(track, UUID.randomUUID().toString().replace("-", "").take(12))
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("${track.artist} — ${track.title}").setDescription("Cloud Music · ${byteLabel(track.size)}")
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Cloud Music/$filename")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true).setAllowedOverRoaming(false)
            .addRequestHeader("User-Agent", "CloudMusic/0.3 Android")
        if (saved != null) manager.remove(saved.managerId)
        val id = manager.enqueue(request)
        val record = MusicDownload(track.id, id, track.title, track.artist, track.album, filename, track.size)
        try { repository.database.saveDownload(record) }
        catch (error: Exception) { manager.remove(id); throw error }
        publishState(repository.database.downloads())
        true
    } }
    }
    suspend fun sync(includeCompleted: Boolean = false) = withContext(Dispatchers.IO) { mutex.withLock {
        val old = repository.database.downloads()
        val selected = if (includeCompleted) old else old.filter { it.active }
        if (selected.isNotEmpty()) {
            val found = mutableSetOf<Long>()
            manager.query(DownloadManager.Query().setFilterById(*selected.map { it.managerId }.toLongArray()))?.use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    val record = selected.firstOrNull { it.managerId == id } ?: continue
                    found += id
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    val read = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: record.expectedSize
                    var uri = ""
                    var state = when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETE
                        DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
                        DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                        DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                        else -> DownloadStatus.QUEUED
                    }
                    var error = when (state) {
                        DownloadStatus.FAILED -> failure(reason)
                        DownloadStatus.PAUSED -> if (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) "Ожидание Wi-Fi" else "Ожидание сети — Android продолжит загрузку"
                        else -> ""
                    }
                    if (state == DownloadStatus.COMPLETE) {
                        try {
                            val local = manager.getUriForDownloadedFile(id) ?: throw java.io.FileNotFoundException()
                            context.contentResolver.openFileDescriptor(local, "r")?.use { fd ->
                                if (record.expectedSize > 0 && fd.statSize >= 0 && fd.statSize != record.expectedSize) throw CloudException("Размер файла изменился. Обновите папку и повторите скачивание.")
                            } ?: throw java.io.FileNotFoundException()
                            uri = local.toString()
                        } catch (e: CloudException) { state = DownloadStatus.FAILED; error = e.message.orEmpty() }
                        catch (_: java.io.FileNotFoundException) { state = DownloadStatus.MISSING; error = "Файл удалён с устройства" }
                        catch (_: SecurityException) { state = DownloadStatus.MISSING; error = "Нет доступа к скачанному файлу" }
                    }
                    val next = record.copy(status = state, bytes = read, total = total, uri = uri, error = error)
                    if (record != next) repository.database.saveDownload(next)
                }
            } ?: throw CloudException("Системная служба загрузок недоступна.")
            selected.filter { it.managerId !in found }.forEach {
                repository.database.saveDownload(it.copy(status = DownloadStatus.MISSING, uri = "", error = "Загрузка удалена в Android"))
            }
        }
        val records = repository.database.downloads()
        repository.publishDownloads(records)
        publishState(records); restored = true
    } }
    suspend fun remove(sourceId: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val record = repository.database.downloads().firstOrNull { it.sourceId == sourceId } ?: return@withLock
        manager.remove(record.managerId) // Deletes only the explicitly selected application download.
        repository.database.removeDownload(sourceId)
        val records = repository.database.downloads()
        repository.publishDownloads(records)
        publishState(records); restored = true
    } }
    private fun failure(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "На устройстве недостаточно места"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Хранилище недоступно"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "Файл с таким именем уже существует"
        401, 403, 410 -> "Ссылка диска истекла. Нажмите «Повторить»"
        404 -> "Файл на диске не найден"
        429 -> "Яндекс Диск ограничил запросы. Повторите позже"
        in 500..599 -> "Сервис диска временно недоступен. Повторите позже"
        else -> "Не удалось скачать файл. Проверьте сеть и повторите"
    }
}
