package app.cloudmusic.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cloudmusic.data.Track
import app.cloudmusic.CloudMusicApplication
import app.cloudmusic.playback.PlaybackState
import app.cloudmusic.playback.PlaybackConnection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun CloudIcon(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true) {
    val p = LocalCloudPalette.current
    val scale = animateFloatAsState(if (selected) 1.04f else 1f, tween(120), label = "icon-selection")
    IconButton(onClick, modifier.size(48.dp), enabled = enabled) { Icon(icon, label, Modifier.size(22.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }, tint = if (selected) p.accent else p.text) }
}
@Composable fun Logo() {
    val p = LocalCloudPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(15, 26, 20).forEach { height -> Box(Modifier.width(5.dp).height(height.dp).clip(RoundedCornerShape(4.dp)).background(p.accent)) }
    }
}
@Composable fun BrandHeader(light: Boolean, onTheme: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo(); Spacer(Modifier.width(9.dp))
        Text("cloud music", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, maxLines = 1, letterSpacing = (-1).sp)
        CastButton()
        CloudIcon(if (light) Icons.Default.DarkMode else Icons.Default.LightMode, if (light) "Включить тёмную тему" else "Включить светлую тему", onTheme)
        CloudIcon(Icons.Default.Tune, "Настройки", onSettings)
    }
}
@Composable fun SectionTitle(title: String, subtitle: String, onPlay: (() -> Unit)? = null) {
    val p = LocalCloudPalette.current
    Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            if (subtitle.isNotBlank()) Text(subtitle, Modifier.padding(top = 6.dp), color = p.muted, style = MaterialTheme.typography.bodySmall)
        }
        if (onPlay != null) RoundPlay(false, false, onPlay, 52)
    }
}
@Composable fun RoundPlay(playing: Boolean, buffering: Boolean, onClick: () -> Unit, size: Int = 70) {
    val p = LocalCloudPalette.current
    FilledIconButton(onClick, Modifier.size(size.dp), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = p.accent, contentColor = p.onAccent)) {
        if (buffering) CircularProgressIndicator(Modifier.size(26.dp), color = p.onAccent, strokeWidth = 2.dp)
        else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Пауза" else "Воспроизвести", Modifier.size(if (size > 60) 32.dp else 26.dp))
    }
}
@Composable fun Artwork(track: Track?, modifier: Modifier = Modifier, bytes: ByteArray? = null, large: Boolean = false) {
    val app = LocalContext.current.applicationContext as CloudMusicApplication
    var bitmap by remember(track?.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(track?.id, track?.size, bytes) {
        if (bytes == null && track != null) {
            bitmap = withContext(Dispatchers.IO) { app.artwork.load(track)?.asImageBitmap() }
        } else if (bytes != null) bitmap = withContext(Dispatchers.Default) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (bounds.outWidth / inSampleSize > 384 || bounds.outHeight / inSampleSize > 384) inSampleSize *= 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val imageAlpha = animateFloatAsState(if (bitmap != null) 1f else 0f, tween(180), label = "artwork-appear")
    val palettes = listOf(Color(0xFFDD7044) to Color(0xFF23382C), Color(0xFFB7CEE2) to Color(0xFF173557), Color(0xFFA397CA) to Color(0xFF392E56), Color(0xFFCEDC95) to Color(0xFF25422C))
    val (base, ink) = palettes[(track?.album?.hashCode().orZero() and Int.MAX_VALUE) % palettes.size]
    Box(modifier.clip(RoundedCornerShape(if (large) 22.dp else 10.dp)).background(base)) {
        if (bitmap == null) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension * .44f
                val center = Offset(size.width * .74f, size.height * .77f)
                drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = .25f), ink), center, radius), radius, center)
                for (i in 0..16) drawCircle(base.copy(alpha = .77f), radius * (1f - i / 18f), center, style = Stroke(size.minDimension * .004f))
            }
            if (large) Text(track?.album.orEmpty().uppercase(), Modifier.padding(25.dp).align(Alignment.TopStart), color = ink, fontWeight = FontWeight.ExtraBold, fontSize = 25.sp, lineHeight = 29.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (bitmap != null) Image(bitmap!!, "Обложка ${track?.album.orEmpty()}", Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha.value }, contentScale = ContentScale.Crop)
    }
}
private fun Int?.orZero() = this ?: 0

@Composable fun TrackRow(track: Track, playing: Boolean, onPlay: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalCloudPalette.current
    Row(modifier.fillMaxWidth().heightIn(min = 74.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onPlay).semantics { contentDescription = "Включить ${track.title} — ${track.artist}" }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, Modifier.size(48.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(track.title, color = if (playing) p.accent else p.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (playing) PlayingBars()
            else if (track.favorite) Icon(Icons.Default.Favorite, "В избранном", Modifier.size(14.dp), tint = p.accent)
        }
        CloudIcon(Icons.Default.MoreHoriz, "Действия с треком ${track.title}", onMenu)
    }
}
@Composable private fun PlayingBars() {
    val p = LocalCloudPalette.current
    val transition = rememberInfiniteTransition(label = "equalizer")
    val a = transition.animateFloat(.25f, 1f, infiniteRepeatable(tween(430, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar-a")
    val b = transition.animateFloat(1f, .35f, infiniteRepeatable(tween(610, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar-b")
    val c = transition.animateFloat(.5f, .9f, infiniteRepeatable(tween(370, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar-c")
    Canvas(Modifier.size(18.dp).semantics { contentDescription = "Сейчас играет" }) {
        listOf(a.value, b.value, c.value).forEachIndexed { i, fraction ->
            val height = size.height * fraction
            drawRoundRect(p.accent, Offset(i * size.width / 3, size.height - height), androidx.compose.ui.geometry.Size(size.width / 5, height), androidx.compose.ui.geometry.CornerRadius(size.width / 10))
        }
    }
}
@Composable fun MiniPlayer(track: Track, playback: PlaybackState, onOpen: () -> Unit, onLike: () -> Unit, onToggle: () -> Unit, player: PlaybackConnection) {
    val progress by player.state.collectAsStateWithLifecycle()
    val p = LocalCloudPalette.current
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(p.elevated)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClick = onOpen).semantics { contentDescription = "Открыть плеер" }, verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, Modifier.size(43.dp), playback.artwork)
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = p.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            CloudIcon(if (track.favorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, if (track.favorite) "Убрать из избранного" else "Добавить в избранное", onLike, selected = track.favorite)
            if (playback.buffering) IconButton(onToggle, Modifier.size(48.dp)) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = p.accent) }
            else CloudIcon(if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playback.playing) "Пауза" else "Воспроизвести", onToggle)
        }
        LinearProgressIndicator(progress = { if (playback.duration > 0) (progress.position.toFloat() / playback.duration).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.border)
    }
}
@Composable fun PageBack(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        CloudIcon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onBack)
        Text(title, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.headlineMedium)
    }
}
@Composable fun EmptyMessage(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    val p = LocalCloudPalette.current
    Column(modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(36.dp), tint = p.accent)
        Text(title, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        Text(text, Modifier.padding(top = 8.dp), color = p.muted, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
fun timeLabel(milliseconds: Long): String = if (milliseconds < 0) "—:—" else "%d:%02d".format(milliseconds / 60_000, milliseconds / 1000 % 60)
