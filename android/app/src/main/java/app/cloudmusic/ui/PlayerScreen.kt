package app.cloudmusic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cloudmusic.data.CloudFolder
import app.cloudmusic.data.Track
import app.cloudmusic.playback.PlaybackConnection
import app.cloudmusic.playback.PlaybackState
import androidx.media3.common.Player
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlayerScreen(track: Track, folder: CloudFolder?, state: PlaybackState, player: PlaybackConnection, onBack: () -> Unit, onFavorite: () -> Unit, onQueue: () -> Unit, onMenu: () -> Unit, onArtist: () -> Unit = {}, nextTrack: Track? = null) {
    val progress by player.state.collectAsStateWithLifecycle()
    val p = LocalCloudPalette.current
    var dragging by remember(track.id) { mutableStateOf<Float?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxHeight < 630.dp
    Column(Modifier.fillMaxSize().then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CloudIcon(Icons.Default.KeyboardArrowDown, "Свернуть плеер", onBack)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ИГРАЕТ ИЗ ПАПКИ", color = p.muted, style = MaterialTheme.typography.labelSmall)
                Text(folder?.name ?: track.album, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CloudIcon(Icons.Default.MoreHoriz, "Действия с треком ${track.title}", onMenu)
        }
        if (compact) Artwork(track, Modifier.padding(vertical = 12.dp).widthIn(max = 240.dp).fillMaxWidth().aspectRatio(1f), state.artwork, large = true)
        else BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight, 400.dp)
            Artwork(track, Modifier.size(side), state.artwork, large = true)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist, Modifier.clickable(onClick = onArtist).padding(top = 6.dp, bottom = 8.dp), color = p.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CloudIcon(if (track.favorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, if (track.favorite) "Убрать из избранного" else "Добавить в избранное", onFavorite, selected = track.favorite)
        }
        Slider(value = dragging ?: if (state.duration > 0) (progress.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { player.seek((it * state.duration).toLong()) }; dragging = null },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp), enabled = state.duration > 0,
            thumb = { Box(Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape).background(p.accent)) },
            track = { slider ->
                Box(Modifier.fillMaxWidth().height(4.dp).clip(androidx.compose.foundation.shape.CircleShape).background(p.border)) {
                    Box(Modifier.fillMaxWidth(slider.value.coerceIn(0f, 1f)).fillMaxHeight().background(p.accent))
                }
            },
            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.border, activeTickColor = p.onAccent, inactiveTickColor = p.muted))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(timeLabel(dragging?.let { (it * state.duration).toLong() } ?: progress.position), color = p.muted, style = MaterialTheme.typography.bodySmall)
            Text(if (state.duration > 0) timeLabel(state.duration) else "—:—", color = p.muted, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CloudIcon(Icons.Default.Shuffle, "Случайный порядок", player::shuffle, selected = state.shuffle)
            CloudIcon(Icons.Default.SkipPrevious, "Предыдущий трек", player::previous)
            RoundPlay(state.playing, state.buffering, player::toggle)
            CloudIcon(Icons.Default.SkipNext, "Следующий трек", player::next, enabled = state.index + 1 < state.queue.size || state.shuffle || state.repeat != Player.REPEAT_MODE_OFF)
            CloudIcon(if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Повтор", player::repeat, selected = state.repeat != Player.REPEAT_MODE_OFF)
        }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp).clip(RoundedCornerShape(18.dp)).background(p.surface).clickable(onClick = onQueue).padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (nextTrack != null) Artwork(nextTrack, Modifier.size(44.dp))
            else Icon(Icons.Default.DoneAll, null, Modifier.size(32.dp), tint = p.muted)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("ДАЛЕЕ", color = p.muted, style = MaterialTheme.typography.labelSmall)
                Text(nextTrack?.title ?: "Последний трек в очереди", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (nextTrack != null) Text(nextTrack.artist, color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CloudIcon(Icons.Default.QueueMusic, "Очередь воспроизведения", onQueue)
        }
        PlaybackDeviceRow(folder?.provider?.label.orEmpty())
    }
    }
}
