package app.cloudmusic.ui

import android.app.Activity
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.cloudmusic.MusicViewModel
import app.cloudmusic.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CloudMusicApp(sharedLink: String? = null, consumeLink: () -> Unit = {}, vm: MusicViewModel = viewModel()) {
    val library by vm.library.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val visibleTracks = remember(library.tracks) { browseTracks(library.tracks) }
    val playback by vm.playback.uiState.collectAsStateWithLifecycle()
    val operation by vm.operation.collectAsStateWithLifecycle()
    val light by vm.light.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("library") }
    var link by rememberSaveable { mutableStateOf("") }
    var albumName by rememberSaveable { mutableStateOf("") }
    var artistId by rememberSaveable { mutableStateOf("") }
    var folderId by rememberSaveable { mutableStateOf("") }
    var menuId by remember { mutableStateOf<String?>(null) }
    var deleteDownload by remember { mutableStateOf<MusicDownload?>(null) }
    var permissionTrackId by rememberSaveable { mutableStateOf<String?>(null) }
    var queue by remember { mutableStateOf(false) }
    var removeFolder by remember { mutableStateOf<CloudFolder?>(null) }
    val snack = remember { SnackbarHostState() }
    val current = library.tracks.firstOrNull { it.id == playback.currentId }
    val isConnect = screen == "connect" || screen == "library" && library.folders.isEmpty()
    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.addLocal(uris) { screen = "folders" } }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> vm.localFolder(uri) { screen = "folders" } }
    val openFiles = { filesPicker.launch(arrayOf("audio/*")) }
    val openLocalFolder = { folderPicker.launch(null) }
    val context = LocalContext.current
    val downloadPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionTrackId?.let { if (granted) vm.downloadId(it) else vm.downloadPermissionDenied() }; permissionTrackId = null
    }
    val startDownload: (Track) -> Unit = { track ->
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) { permissionTrackId = track.id; downloadPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        else vm.download(track)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.syncDownloads() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(screen) { if (screen == "device") screen = "folders"; if (screen in listOf("folders", "downloads")) vm.syncDownloads() }
    LaunchedEffect(sharedLink) { if (sharedLink != null) { link = sharedLink; screen = "connect"; consumeLink() } }
    LaunchedEffect(playback.connected, playback.currentId, current?.id, operation.starting) {
        if (screen == "player" && playback.connected && !operation.starting && current == null) screen = "library"
    }
    LaunchedEffect(operation.message) { operation.message?.let { snack.showSnackbar(it); vm.clearMessage() } }
    BackHandler(enabled = screen != "library") { screen = "library" }
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        }
    }
    CloudMusicTheme(light) {
        val p = LocalCloudPalette.current
        Surface(Modifier.fillMaxSize(), color = p.background) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    if (screen != "player" || current == null) BrandHeader(light, { vm.setLight(!light) }, { screen = "settings" })
                    if (operation.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent, trackColor = p.border)
                    val page = if (operation.starting) "loading" else if (isConnect) "connect" else screen
                    val entrance = remember { Animatable(1f) }
                    LaunchedEffect(page, light) { entrance.snapTo(0f); entrance.animateTo(1f, tween(160)) }
                    Box(Modifier.weight(1f).graphicsLayer { translationY = (1f - entrance.value) * 8.dp.toPx() }) {
                        when (page) {
                            "loading" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }
                            "player" -> if (current != null) PlayerScreen(current, library.folders.find { it.id == current.folderId }, playback, vm.playback, { screen = "library" }, { vm.favorite(current) }, { queue = true }, { menuId = current.id }, { artistId = artistKey(current.artist); screen = "artist" }, library.tracks.find { it.id == playback.nextId })
                            "settings" -> SettingsScreen(light, library.folders.size, operation.busy, vm::setLight, { screen = "library" }, { screen = "folders" }, vm::refreshAll, vm::clearArtwork)
                            "folders" -> FoldersScreen(library.folders, library.tracks, operation.busy, { screen = "connect"; vm.clearMessage() }, { folderId = it.id; albumName = ""; screen = "collection" }, vm::refresh, { removeFolder = it }, openFiles, openLocalFolder, downloads, { screen = "downloads" })
                            "downloads" -> DownloadsScreen(downloads, playback.currentId, playback.playing, { screen = "folders" }, { t, list -> vm.playback.play(list, t) }, vm::removeDownload, vm::retryDownload, { deleteDownload = it })
                            "artist" -> ArtistScreen(artistId, visibleTracks, playback.currentId, playback.playing, { screen = "library" }, { t, list -> vm.playback.play(list, t) }, { menuId = it.id })
                            "search" -> SearchScreen(visibleTracks, playback.currentId, playback.playing, { t, list -> vm.playback.play(list, t) }, { menuId = it.id })
                            "collection" -> {
                                val tracks = library.tracks.filter { it.folderId == folderId && (albumName.isEmpty() || it.album == albumName) }
                                val folder = library.folders.find { it.id == folderId }
                                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                                    PageBack(albumName.ifEmpty { folder?.name ?: "Папка" }, { screen = "library" })
                                    Text(trackCount(tracks.size), color = p.muted, style = MaterialTheme.typography.bodySmall)
                                    if (tracks.isNotEmpty()) Button({ vm.playback.play(tracks, tracks.first()); screen = "player" }, Modifier.fillMaxWidth().padding(vertical = 14.dp).heightIn(min = 52.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Слушать") }
                                    LazyColumn(Modifier.weight(1f)) {
                                        if (tracks.isEmpty()) item { EmptyMessage(Icons.Default.MusicOff, "В папке нет музыки", "Добавьте аудиофайлы на диск и обновите папку.") }
                                        itemsIndexed(tracks, key = { _, t -> t.id }) { _, track -> TrackRow(track, track.id == playback.currentId && playback.playing, { vm.playback.play(tracks, track) }, { menuId = track.id }) }
                                    }
                                }
                            }
                            "connect" -> ConnectScreen(link, { link = it; if (operation.error != null) vm.clearMessage() }, operation.busy, operation.error,
                                { vm.connect(link) { screen = "library"; link = "" } }, { vm.demo { screen = "library" } }, openFiles, openLocalFolder)
                            else -> LibraryScreen(visibleTracks, library.folders, playback.currentId, playback.playing, { track, list -> vm.playback.play(list, track) }, { menuId = it.id }, { screen = "folders" }, { id, album -> folderId = id; albumName = album; screen = "collection" }, { artistId = it; screen = "artist" })
                        }
                    }
                    if (screen != "player") {
                        if (current != null && !isConnect && screen != "settings") {
                            MiniPlayer(current, playback, { screen = "player" }, { vm.favorite(current) }, vm.playback::toggle, vm.playback)
                        }
                        NavigationBar(containerColor = p.background, tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0)) {
                            listOf(Triple("library", "Музыка", Icons.Default.LibraryMusic), Triple("search", "Поиск", Icons.Default.Search), Triple("folders", "Папки", Icons.Default.FolderOpen)).forEach { (key, title, icon) ->
                                NavigationBarItem(selected = screen == key || key == "folders" && screen == "downloads" || key == "library" && screen in listOf("collection", "artist"), onClick = { screen = key; vm.clearMessage() }, icon = { Icon(icon, null, Modifier.size(22.dp)) }, label = { Text(title, style = MaterialTheme.typography.labelSmall) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = p.accent, selectedTextColor = p.text, indicatorColor = p.background, unselectedIconColor = p.muted, unselectedTextColor = p.muted))
                            }
                        }
                    }
                }
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 140.dp, start = 20.dp, end = 20.dp))
            }
        }
        val menuTrack = library.tracks.find { it.id == menuId }
        if (menuTrack != null) ModalBottomSheet(onDismissRequest = { menuId = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(menuTrack, Modifier.size(52.dp)); Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(menuTrack.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(menuTrack.artist, color = p.muted, style = MaterialTheme.typography.bodySmall) }
                }
                Spacer(Modifier.height(18.dp))
                SheetAction(if (menuTrack.favorite) "Убрать из избранного" else "Добавить в избранное", Icons.Default.FavoriteBorder) { vm.favorite(menuTrack); menuId = null }
                SheetAction("Добавить в очередь", Icons.Default.PlaylistAdd) { vm.playback.enqueue(menuTrack); menuId = null }
                SheetAction("Все треки исполнителя", Icons.Default.Person) { artistId = artistKey(menuTrack.artist); menuId = null; screen = "artist" }
                SheetAction("Открыть альбом", Icons.Default.Album) { folderId = menuTrack.folderId; albumName = menuTrack.album; menuId = null; screen = "collection" }
                val saved = downloads.firstOrNull { it.sourceId == menuTrack.id || it.localId == menuTrack.id }
                if (menuTrack.provider == Provider.YANDEX) {
                    when {
                        saved?.status == DownloadStatus.COMPLETE -> SheetAction("Скачано · ${byteLabel(saved.total)}", Icons.Default.DownloadDone) { screen = "downloads"; menuId = null }
                        saved?.active == true -> SheetAction("Открыть загрузки", Icons.Default.Downloading) { screen = "downloads"; menuId = null }
                        else -> SheetAction("Скачать на устройство · ${byteLabel(menuTrack.size)}", Icons.Default.Download) { startDownload(menuTrack); menuId = null }
                    }
                }
                if (saved?.status == DownloadStatus.COMPLETE) SheetAction("Удалить скачанную копию", Icons.Default.DeleteOutline) { deleteDownload = saved; menuId = null }
                Spacer(Modifier.height(20.dp))
            }
        }
        if (queue) ModalBottomSheet(onDismissRequest = { queue = false }, containerColor = p.surface) {
            Text("Очередь воспроизведения", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(horizontal = 24.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(playback.queue) { index, id -> library.tracks.find { it.id == id }?.let { track ->
                    TrackRow(track, index == playback.index && playback.playing, { vm.playback.jump(index); queue = false }, { queue = false; menuId = track.id })
                } }
            }
        }
        if (deleteDownload != null) AlertDialog(onDismissRequest = { deleteDownload = null }, title = { Text("Удалить скачанную копию?") }, text = { Text("Файл «${deleteDownload?.title}» будет удалён из «Загрузки / Cloud Music». Оригинал на Яндекс Диске сохранится.") }, confirmButton = { TextButton({ deleteDownload?.let(vm::removeDownload); deleteDownload = null }) { Text("Удалить") } }, dismissButton = { TextButton({ deleteDownload = null }) { Text("Отмена") } })
        if (removeFolder != null) AlertDialog(onDismissRequest = { removeFolder = null }, title = { Text("Отключить папку?") }, text = { Text("Треки этой папки будут удалены из медиатеки Cloud Music. Файлы на диске сохранятся.") }, confirmButton = { TextButton({ removeFolder?.let(vm::remove); removeFolder = null }) { Text("Отключить") } }, dismissButton = { TextButton({ removeFolder = null }) { Text("Отмена") } })
        if (operation.error != null && !isConnect) AlertDialog(onDismissRequest = vm::clearMessage, title = { Text("Не удалось загрузить музыку") }, text = { Text(operation.error.orEmpty()) }, confirmButton = { TextButton(vm::clearMessage) { Text("Понятно") } })
        if (playback.error != null) AlertDialog(onDismissRequest = vm.playback::clearError, title = { Text("Не удалось включить трек") }, text = { Text(playback.error.orEmpty()) }, confirmButton = { TextButton({ vm.playback.clearError(); vm.playback.toggle() }) { Text("Повторить") } }, dismissButton = { TextButton(vm.playback::clearError) { Text("Закрыть") } })
    }
}
@Composable private fun SheetAction(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(23.dp)); Text(title, Modifier.padding(start = 15.dp)) }
}
