package app.cloudmusic.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cloudmusic.BuildConfig
import app.cloudmusic.data.*

fun trackCount(n: Int): String {
    val word = if (n % 100 in 11..14) "треков" else when (n % 10) { 1 -> "трек"; 2, 3, 4 -> "трека"; else -> "треков" }
    return "$n $word"
}

@Composable fun ConnectScreen(link: String, onLink: (String) -> Unit, busy: Boolean, error: String?, onConnect: () -> Unit, onDemo: () -> Unit, onFiles: () -> Unit = {}, onLocalFolder: () -> Unit = {}) {
    val p = LocalCloudPalette.current
    val clipboard = LocalClipboardManager.current
    val provider = remember(link) { runCatching { FolderLinks.parse(link).provider.label }.getOrNull() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(76.dp).clip(RoundedCornerShape(23.dp)).background(p.elevated), contentAlignment = Alignment.Center) { Icon(Icons.Default.CloudQueue, null, Modifier.size(36.dp), tint = p.accent) }
                Box(Modifier.padding(top = 30.dp).size(76.dp).clip(RoundedCornerShape(23.dp)).background(p.accent), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, Modifier.size(36.dp), tint = p.onAccent) }
            }
        }
        Text("Вся музыка.\nОдна ссылка.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.headlineLarge, fontSize = 35.sp, lineHeight = 40.sp)
        Text("Подключите папку с Яндекс Диска или Google Drive и слушайте любимое.", Modifier.padding(top = 16.dp), color = p.muted, style = MaterialTheme.typography.bodyMedium)
        Text("Ссылка на папку", Modifier.padding(top = 28.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(link, onLink, Modifier.fillMaxWidth(), enabled = !busy,
            placeholder = { Text("Вставьте ссылку на папку", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Default.Link, null, Modifier.size(20.dp)) },
            trailingIcon = { if (link.isEmpty()) IconButton({ clipboard.getText()?.text?.let(onLink) }) { Icon(Icons.Default.ContentPaste, "Вставить из буфера") } else IconButton({ onLink("") }) { Icon(Icons.Default.Close, "Очистить ссылку") } },
            shape = RoundedCornerShape(15.dp), singleLine = true, isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (!busy) onConnect() }))
        if (provider != null) Row(Modifier.padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = p.accent); Text(provider, Modifier.padding(start = 6.dp), color = p.accent, style = MaterialTheme.typography.bodySmall) }
        if (error != null) Text(error, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Button(onConnect, Modifier.fillMaxWidth().padding(top = 22.dp).heightIn(min = 54.dp), enabled = !busy, shape = RoundedCornerShape(16.dp)) {
            if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
            Text(if (busy) "Читаем папку…" else "Подключить папку")
            if (!busy) { Spacer(Modifier.width(10.dp)); Icon(Icons.Default.ArrowForward, null, Modifier.size(19.dp)) }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Info, null, Modifier.padding(top = 3.dp).size(16.dp), tint = p.muted)
            Text("Разрешите просмотр и скачивание по ссылке в настройках диска.", color = p.muted, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onDemo, Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp), enabled = !busy) { Text("Открыть демо-коллекцию", color = p.muted, style = MaterialTheme.typography.bodySmall) }
        LocalButtons(busy, onFiles, onLocalFolder)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable fun LibraryScreen(tracks: List<Track>, folders: List<CloudFolder>, currentId: String?, playing: Boolean, onPlay: (Track, List<Track>) -> Unit, onMenu: (Track) -> Unit, onFolders: () -> Unit, onAlbum: (String, String) -> Unit, onArtist: (String) -> Unit = {}) {
    val p = LocalCloudPalette.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var artistQuery by rememberSaveable { mutableStateOf("") }
    val groups = remember(tracks, artistQuery) { artists(tracks).filter { it.name.contains(artistQuery.trim(), true) } }
    var artistSort by rememberSaveable { mutableStateOf(false) }
    val visible = remember(tracks, tab, artistSort) {
        val base = if (tab == 3) tracks.filter { it.favorite } else tracks
        if (artistSort) base.sortedBy { it.artist.lowercase() } else base.sortedBy { it.title.lowercase() }
    }
    val albums = remember(tracks) { tracks.groupBy { it.folderId to it.album }.entries.toList() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 22.dp)) {
        item {
            SectionTitle("Моя музыка", "${trackCount(tracks.size)} · ${albums.size} альб.", if (visible.isNotEmpty()) ({ onPlay(visible.first(), visible) }) else null)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
                listOf("Треки", "Исполнители", "Альбомы", "Избранное").forEachIndexed { i, label ->
                    FilterChip(tab == i, { tab = i }, label = { Text(label, style = MaterialTheme.typography.labelMedium) }, shape = RoundedCornerShape(22.dp), border = null,
                        colors = FilterChipDefaults.filterChipColors(containerColor = p.surface, selectedContainerColor = p.accent, selectedLabelColor = p.onAccent))
                }
            }
        }
        if (tab == 1) {
            item { OutlinedTextField(artistQuery, { artistQuery = it }, Modifier.fillMaxWidth().padding(bottom = 12.dp), placeholder = { Text("Найти исполнителя") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(15.dp)) }
            if (groups.isEmpty()) item { EmptyMessage(Icons.Default.PersonSearch, "Никого не найдено", "Попробуйте другое имя.") }
            items(groups, key = { it.key }) { artist ->
                Row(Modifier.fillMaxWidth().clickable { onArtist(artist.key) }.semantics { contentDescription = "Исполнитель ${artist.name}" }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape).background(p.soft), contentAlignment = Alignment.Center) { Text(artist.name.take(1).uppercase(), color = p.accent, style = MaterialTheme.typography.titleLarge) }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(artist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(trackCount(artist.tracks.size), color = p.muted, style = MaterialTheme.typography.bodySmall) }
                    Icon(Icons.Default.ChevronRight, null, tint = p.muted)
                }
            }
        } else if (tab == 2) {
            items(albums.chunked(2)) { row ->
                Row(Modifier.padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    row.forEach { group ->
                        val track = group.value.first()
                        Column(Modifier.weight(1f).clickable { onAlbum(group.key.first, group.key.second) }) {
                            Artwork(track, Modifier.fillMaxWidth().aspectRatio(1f), large = true)
                            Text(group.key.second, Modifier.padding(top = 9.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(trackCount(group.value.size), color = p.muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            if (tab == 0) item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(p.surface).clickable(onClick = onFolders).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudQueue, null, Modifier.size(18.dp), tint = p.muted)
                    Text(if (folders.size == 1) "${folders.first().provider.label} · ${folders.first().name}" else "Все источники · ${folders.size}", Modifier.weight(1f).padding(horizontal = 9.dp), color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (tab == 3) "Любимые треки" else "Все треки", Modifier.weight(1f), color = p.muted, style = MaterialTheme.typography.bodySmall)
                    TextButton({ artistSort = !artistSort }) {
                        Icon(Icons.Default.SwapVert, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp))
                        Text(if (artistSort) "По исполнителю" else "По названию", color = p.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (visible.isEmpty()) item {
                if (tab == 3) EmptyMessage(Icons.Default.FavoriteBorder, "Здесь будет любимое", "Добавьте трек в избранное через меню или плеер.")
                else EmptyMessage(Icons.Default.MusicOff, "В папке нет музыки", "Добавьте аудиофайлы на диск и обновите папку.")
            }
            items(visible, key = { it.id }) { track -> TrackRow(track, track.id == currentId && playing, { onPlay(track, visible) }, { onMenu(track) }) }
        }
    }
}

@Composable fun SearchScreen(tracks: List<Track>, currentId: String?, playing: Boolean, onPlay: (Track, List<Track>) -> Unit, onMenu: (Track) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val p = LocalCloudPalette.current
    val filtered = remember(tracks, query) { tracks.filter { (it.title + " " + it.artist + " " + it.album).contains(query.trim(), ignoreCase = true) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(12.dp)); SectionTitle("Поиск", "Найдите своё настроение")
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Трек, исполнитель, альбом", style = MaterialTheme.typography.bodyMedium) }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(15.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
        Text(if (query.isBlank()) "В вашей коллекции" else "Найдено: ${filtered.size}", Modifier.padding(top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (filtered.isEmpty()) item { EmptyMessage(Icons.Default.Search, if (tracks.isEmpty()) "Сначала добавьте музыку" else "Пока ничего", if (tracks.isEmpty()) "Подключите папку в разделе «Папки»." else "Попробуйте другое название.") }
            items(filtered, key = { it.id }) { track -> TrackRow(track, track.id == currentId && playing, { onPlay(track, filtered) }, { onMenu(track) }) }
        }
    }
}

@Composable fun FoldersScreen(folders: List<CloudFolder>, tracks: List<Track>, busy: Boolean, onAdd: () -> Unit, onOpen: (CloudFolder) -> Unit, onRefresh: (CloudFolder) -> Unit, onRemove: (CloudFolder) -> Unit, onFiles: () -> Unit, onLocalFolder: () -> Unit, downloads: List<MusicDownload>, onDownloads: () -> Unit) {
    val p = LocalCloudPalette.current
    val cloud = folders.filter { it.provider == Provider.YANDEX || it.provider == Provider.GOOGLE }
    val local = folders.filter { (it.provider == Provider.LOCAL || it.provider == Provider.DEMO) && it.id != DOWNLOAD_FOLDER_ID }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item { SectionTitle("Ваши папки", "Облако и музыка на устройстве") }
        item { Text("ОБЛАКО", Modifier.padding(bottom = 14.dp), color = p.muted, style = MaterialTheme.typography.labelMedium) }
        items(cloud, key = { it.id }) { folder -> FolderCard(folder, tracks, busy, onOpen, onRefresh, onRemove) }
        item { OutlinedButton(onAdd, Modifier.fillMaxWidth().padding(bottom = 26.dp).heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp), enabled = !busy) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Добавить папку") } }
        item { Text("НА УСТРОЙСТВЕ", Modifier.padding(bottom = 14.dp), color = p.muted, style = MaterialTheme.typography.labelMedium); LocalButtons(busy, onFiles, onLocalFolder); Spacer(Modifier.height(8.dp)) }
        if (downloads.isNotEmpty()) item {
            val complete = downloads.filter { it.status == DownloadStatus.COMPLETE }
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(20.dp)).background(p.surface).clickable(onClick = onDownloads).semantics { contentDescription = "Открыть скачанную музыку" }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(p.soft), contentAlignment = Alignment.Center) { Icon(Icons.Default.DownloadDone, null, tint = p.accent) }
                Column(Modifier.padding(horizontal = 13.dp).weight(1f)) {
                    Text("Скачано из Яндекс Диска", style = MaterialTheme.typography.titleMedium)
                    Text("${trackCount(complete.size)} · ${if (complete.isEmpty()) "Для прослушивания офлайн" else byteLabel(complete.sumOf { it.total.coerceAtLeast(it.bytes) })}", color = p.muted, style = MaterialTheme.typography.bodySmall)
                    if (downloads.any { it.active }) Text("Загружается: ${downloads.count { it.active }}", color = p.accent, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.ChevronRight, null, tint = p.muted)
            }
        }
        items(local, key = { it.id }) { folder -> FolderCard(folder, tracks, busy, onOpen, onRefresh, onRemove) }
        item {
            Text("Выбранные файлы открываются без копирования. Скачивание из облака запускается через меню трека.", Modifier.padding(top = 8.dp), color = p.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun FolderCard(folder: CloudFolder, tracks: List<Track>, busy: Boolean, onOpen: (CloudFolder) -> Unit, onRefresh: (CloudFolder) -> Unit, onRemove: (CloudFolder) -> Unit) {
    val p = LocalCloudPalette.current
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(20.dp)).background(p.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable { onOpen(folder) }.semantics { contentDescription = "Открыть папку ${folder.name}" }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(p.soft), contentAlignment = Alignment.Center) { Icon(if (folder.provider == Provider.LOCAL || folder.provider == Provider.DEMO) Icons.Default.Smartphone else Icons.Default.FolderOpen, null, tint = p.accent) }
                Column(Modifier.padding(start = 13.dp).weight(1f)) { Text(folder.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(folder.provider.label, color = p.muted, style = MaterialTheme.typography.bodySmall) }
            }
            CloudIcon(Icons.Default.LinkOff, "Отключить папку ${folder.name}", { onRemove(folder) }, enabled = !busy)
        }
        HorizontalDivider(Modifier.padding(top = 13.dp), color = p.border)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(trackCount(tracks.count { it.folderId == folder.id }), Modifier.weight(1f), color = p.muted, style = MaterialTheme.typography.bodySmall)
            TextButton({ onRefresh(folder) }, enabled = !busy && folder.provider != Provider.DEMO) { Icon(Icons.Default.Refresh, null, Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text("Обновить", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable fun DownloadsScreen(downloads: List<MusicDownload>, currentId: String?, playing: Boolean, onBack: () -> Unit, onPlay: (Track, List<Track>) -> Unit, onCancel: (MusicDownload) -> Unit, onRetry: (MusicDownload) -> Unit, onDelete: (MusicDownload) -> Unit) {
    val p = LocalCloudPalette.current
    val complete = remember(downloads) { downloads.filter { it.status == DownloadStatus.COMPLETE }.map { it.track() } }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        PageBack("Скачанная музыка", onBack)
        Text("Загрузки / Cloud Music · ${if (complete.isEmpty()) trackCount(0) else byteLabel(complete.sumOf { it.size })}", color = p.muted, style = MaterialTheme.typography.bodySmall)
        if (complete.isNotEmpty()) Button({ onPlay(complete.first(), complete) }, Modifier.fillMaxWidth().padding(vertical = 16.dp).heightIn(min = 52.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Слушать офлайн") }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (downloads.isEmpty()) item { EmptyMessage(Icons.Default.Download, "Пока ничего не скачано", "Откройте меню трека Яндекс Диска и выберите «Скачать на устройство».") }
            items(downloads, key = { it.sourceId }) { d ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (d.status == DownloadStatus.COMPLETE) Artwork(d.track(), Modifier.size(48.dp))
                        else Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(p.soft), contentAlignment = Alignment.Center) { Icon(if (d.active) Icons.Default.Downloading else Icons.Default.ErrorOutline, null, tint = p.accent) }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp).then(if (d.status == DownloadStatus.COMPLETE) Modifier.clickable { onPlay(d.track(), complete) }.semantics { contentDescription = "Слушать скачанный трек ${d.title}" } else Modifier)) {
                            Text(d.title, color = if (playing && currentId in listOf(d.sourceId, d.localId)) p.accent else p.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(d.artist, color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when (d.status) {
                                DownloadStatus.COMPLETE -> "На устройстве · ${byteLabel(d.total.coerceAtLeast(d.bytes))}"
                                DownloadStatus.RUNNING -> "${byteLabel(d.bytes)} / ${byteLabel(d.total)}"
                                DownloadStatus.QUEUED -> "В очереди · ${byteLabel(d.total)}"
                                else -> d.error
                            }, color = p.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        when {
                            d.active -> CloudIcon(Icons.Default.Close, "Отменить скачивание ${d.title}", { onCancel(d) })
                            d.status == DownloadStatus.COMPLETE -> CloudIcon(Icons.Default.DeleteOutline, "Удалить скачанную копию ${d.title}", { onDelete(d) })
                            else -> { CloudIcon(Icons.Default.Refresh, "Повторить скачивание ${d.title}", { onRetry(d) }); CloudIcon(Icons.Default.Close, "Убрать загрузку ${d.title}", { onDelete(d) }) }
                        }
                    }
                    if (d.active) {
                        if (d.total > 0) LinearProgressIndicator(progress = { (d.bytes.toFloat() / d.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 12.dp), color = p.accent, trackColor = p.border)
                        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp), color = p.accent, trackColor = p.border)
                    }
                }
            }
        }
    }
}

@Composable fun SettingsScreen(light: Boolean, folderCount: Int, busy: Boolean, onTheme: (Boolean) -> Unit, onBack: () -> Unit, onFolders: () -> Unit, onRefresh: () -> Unit, onClearArtwork: () -> Unit = {}) {
    val p = LocalCloudPalette.current
    var about by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp)) {
        PageBack("Настройки", onBack)
        Text("ОФОРМЛЕНИЕ", Modifier.padding(start = 6.dp, top = 8.dp, bottom = 12.dp), color = p.muted, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.padding(horizontal = 6.dp).fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(p.surface).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(false to "Тёмная", true to "Светлая").forEach { (value, name) ->
                TextButton({ onTheme(value) }, Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (light == value) p.elevated else p.surface)) {
                    Icon(if (value) Icons.Default.LightMode else Icons.Default.DarkMode, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text(name)
                }
            }
        }
        Text("МЕДИАТЕКА", Modifier.padding(start = 6.dp, top = 30.dp, bottom = 6.dp), color = p.muted, style = MaterialTheme.typography.labelMedium)
        SettingRow("Подключённые папки", "Источников: $folderCount", Icons.Default.ChevronRight, onFolders)
        SettingRow("Обновить медиатеку", "Проверить новые треки", Icons.Default.Refresh, onRefresh, enabled = !busy && folderCount > 0)
        SettingRow("Очистить кэш обложек", "До 4 МБ · скачанные треки сохраняются", Icons.Default.CleaningServices, onClearArtwork, enabled = !busy)
        Text("ПРИЛОЖЕНИЕ", Modifier.padding(start = 6.dp, top = 26.dp, bottom = 6.dp), color = p.muted, style = MaterialTheme.typography.labelMedium)
        SettingRow("Язык", "Русский", null, {})
        SettingRow("О Cloud Music", "Ваша музыка, ваш ритм", Icons.Default.ChevronRight, { about = true })
        Text("Cloud Music · ${BuildConfig.VERSION_NAME}", Modifier.padding(start = 6.dp, top = 35.dp), color = p.muted, style = MaterialTheme.typography.bodySmall)
    }
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("Cloud Music") }, text = { Text("Личный плеер для музыки с Яндекс Диска и Google Drive. Папки, избранное и настройки сохраняются на этом устройстве. Для облачных треков нужен интернет.") }, confirmButton = { TextButton({ about = false }) { Text("Понятно") } })
}
@Composable private fun SettingRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit, enabled: Boolean = true) {
    val p = LocalCloudPalette.current
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled && icon != null, onClick = onClick).padding(horizontal = 6.dp)) {
        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, color = if (enabled) p.text else p.muted); Text(subtitle, Modifier.padding(top = 4.dp), color = p.muted, style = MaterialTheme.typography.bodySmall) }
            if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = p.muted)
        }
        HorizontalDivider(color = p.border)
    }
}

@Composable private fun LocalButtons(busy: Boolean, onFiles: () -> Unit, onFolder: () -> Unit) {
    OutlinedButton(onFiles, Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp), enabled = !busy, shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.AudioFile, null); Spacer(Modifier.width(8.dp)); Text("Добавить файлы устройства") }
    TextButton(onFolder, Modifier.fillMaxWidth(), enabled = !busy) { Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Выбрать папку устройства") }
}

@Composable fun ArtistScreen(key: String, tracks: List<Track>, currentId: String?, playing: Boolean, onBack: () -> Unit, onPlay: (Track, List<Track>) -> Unit, onMenu: (Track) -> Unit) {
    val selected = remember(tracks, key) { tracks.filter { artistKey(it.artist) == key }.sortedBy { it.title.lowercase() } }
    val p = LocalCloudPalette.current
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        PageBack(selected.firstOrNull()?.artist ?: "Исполнитель", onBack)
        Text(trackCount(selected.size), color = p.muted, style = MaterialTheme.typography.bodySmall)
        if (selected.isNotEmpty()) Button({ onPlay(selected.first(), selected) }, Modifier.fillMaxWidth().padding(vertical = 16.dp).heightIn(min = 52.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Слушать исполнителя") }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(selected, key = { it.id }) { t -> TrackRow(t, t.id == currentId && playing, { onPlay(t, selected) }, { onMenu(t) }) }
        }
    }
}
