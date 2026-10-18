package app.cloudmusic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cloudmusic.data.*
import app.cloudmusic.playback.PlaybackConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.UnknownHostException
import android.net.Uri
import android.content.Intent

data class OperationState(val starting: Boolean = true, val busy: Boolean = false, val message: String? = null, val error: String? = null)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CloudMusicApplication
    val repository = app.repository
    val library = repository.library
    val downloads = app.downloads.state
    val playback = PlaybackConnection(application, viewModelScope)
    private val mutableOperation = MutableStateFlow(OperationState())
    val operation = mutableOperation.asStateFlow()
    private val preferences = application.getSharedPreferences("appearance", 0)
    private val mutableLight = MutableStateFlow(preferences.getBoolean("light", false))
    val light = mutableLight.asStateFlow()
    init { viewModelScope.launch { try { repository.load(); app.downloads.restoreIndex() } finally { mutableOperation.value = OperationState(starting = false) } } }
    fun setLight(value: Boolean) { mutableLight.value = value; preferences.edit().putBoolean("light", value).apply() }
    fun connect(url: String, onSuccess: () -> Unit) {
        if (mutableOperation.value.busy) return
        val link = try { FolderLinks.parse(url) } catch (e: IllegalArgumentException) { mutableOperation.value = OperationState(starting = false, error = e.message); return }
        runOperation {
            val folder = repository.connect(link)
            mutableOperation.value = OperationState(starting = false, message = "Папка «${folder.name}» подключена")
            onSuccess()
        }
    }
    fun playFromSearch(query: String, title: String = "", artist: String = "", album: String = "") = runOperation(showBusy = false) {
        repository.load()
        val focused = title.isNotBlank() || artist.isNotBlank() || album.isNotBlank()
        val q = query.trim().take(256)
        val tracks = browseTracks(repository.library.value.tracks).filter { t ->
            if (focused) (title.isBlank() || t.title.contains(title.take(256), true)) &&
                (artist.isBlank() || t.artist.contains(artist.take(256), true)) && (album.isBlank() || t.album.contains(album.take(256), true))
            else q.isBlank() || t.title.contains(q, true) || t.artist.contains(q, true) || t.album.contains(q, true)
        }.sortedBy { it.title }
        if (tracks.isEmpty()) mutableOperation.value = OperationState(starting = false, error = "По запросу ничего не найдено. Подключите папку с музыкой в приложении.")
        else playback.play(tracks, tracks.first())
    }
    fun refresh(folder: CloudFolder) = runOperation {
        if (folder.id == DOWNLOAD_FOLDER_ID) app.downloads.sync(true) else repository.refresh(folder)
        mutableOperation.value = OperationState(starting = false, message = "Медиатека обновлена")
    }
    fun refreshAll() = runOperation {
        app.downloads.sync(true)
        library.value.folders.forEach { repository.refresh(it) }
        mutableOperation.value = OperationState(starting = false, message = "Медиатека обновлена")
    }
    fun demo(onSuccess: () -> Unit) = runOperation { repository.demo(); onSuccess() }
    fun addLocal(uris: List<Uri>, onSuccess: () -> Unit) {
        if (uris.isEmpty()) return
        runOperation {
            uris.forEach { uri -> app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val count = repository.addLocalFiles(uris)
            mutableOperation.value = OperationState(starting = false, message = "Добавлено: $count")
            onSuccess()
        }
    }
    fun localFolder(uri: Uri?, onSuccess: () -> Unit) {
        if (uri == null) return
        runOperation {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            repository.connectLocalTree(uri); onSuccess()
        }
    }
    fun download(track: Track) = downloadId(track.id)
    fun downloadId(id: String) = runOperation {
        val track = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.database.findTrack(id) } ?: throw CloudException("Трек удалён из медиатеки. Обновите папку.")
        val added = app.downloads.enqueue(track)
        mutableOperation.value = OperationState(starting = false, message = if (added) "Скачивание началось · ${byteLabel(track.size)}" else "Трек уже скачан или загружается")
    }
    fun downloadPermissionDenied() { mutableOperation.value = OperationState(starting = false, error = "Для сохранения музыки на Android 8–9 разрешите запись в хранилище.") }
    fun retryDownload(download: MusicDownload) {
        val track = library.value.tracks.find { it.id == download.sourceId }
        if (track == null) mutableOperation.value = OperationState(starting = false, error = "Подключите исходную папку Яндекс Диска заново.") else download(track)
    }
    fun removeDownload(download: MusicDownload) = runOperation {
        if (download.status == DownloadStatus.COMPLETE) playback.removeFolder(setOf(download.localId, download.sourceId))
        app.downloads.remove(download.sourceId)
        mutableOperation.value = OperationState(starting = false, message = "Скачанная копия удалена")
    }
    fun syncDownloads() = runOperation(showBusy = false) { app.downloads.sync(true) }
    fun clearArtwork() = runOperation { app.clearArtwork(); mutableOperation.value = OperationState(starting = false, message = "Кэш обложек очищен") }
    fun favorite(track: Track) = runOperation(showBusy = false) { repository.favorite(track) }
    fun remove(folder: CloudFolder) = runOperation {
        val ids = library.value.tracks.filter { it.folderId == folder.id }.map { it.id }.toSet()
        val uris = if (folder.provider == Provider.LOCAL) {
            if (folder.url.startsWith("content:")) listOf(Uri.parse(folder.url))
            else library.value.tracks.filter { it.folderId == folder.id }.map { Uri.parse(it.remoteId) }
        } else emptyList()
        playback.removeFolder(ids); repository.remove(folder)
        uris.forEach { uri -> runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    }
    fun clearMessage() { mutableOperation.value = mutableOperation.value.copy(message = null, error = null) }
    private fun runOperation(showBusy: Boolean = true, action: suspend () -> Unit) {
        if (mutableOperation.value.busy) return
        if (showBusy) mutableOperation.value = OperationState(starting = false, busy = true)
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = when (e) {
                    is CloudException, is IllegalArgumentException -> e.message ?: "Не удалось выполнить действие."
                    is UnknownHostException, is java.net.SocketTimeoutException -> "Нет соединения с диском. Проверьте интернет и попробуйте снова."
                    is SecurityException -> "Нет доступа к файлу. Выберите его или папку заново через системный выбор файлов."
                    else -> "Не удалось загрузить музыку. Проверьте ссылку и повторите попытку."
                }
                mutableOperation.value = mutableOperation.value.copy(starting = false, error = message)
            } finally { if (showBusy) mutableOperation.value = mutableOperation.value.copy(busy = false) }
        }
    }
    override fun onCleared() { playback.release(); super.onCleared() }
}
