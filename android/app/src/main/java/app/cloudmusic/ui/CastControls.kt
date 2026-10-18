package app.cloudmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudmusic.CloudMusicApplication

@Composable fun CastButton() {
    val context = LocalContext.current
    val connection = (context.applicationContext as CloudMusicApplication).casting
    val state by connection.state.collectAsStateWithLifecycle()
    CloudIcon(if (state.device != null) Icons.Default.CastConnected else Icons.Default.Cast, "Трансляция Chromecast", { connection.open(context) }, selected = state.device != null)
}

@Composable fun PlaybackDeviceRow(provider: String) {
    val context = LocalContext.current
    val connection = (context.applicationContext as CloudMusicApplication).casting
    val state by connection.state.collectAsStateWithLifecycle()
    val p = LocalCloudPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { connection.open(context) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (state.device != null) Icons.Default.SpeakerGroup else Icons.Default.Devices, null, Modifier.size(20.dp), tint = if (state.device != null) p.accent else p.muted)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(if (state.connecting) "Подключение…" else state.device ?: "Этот телефон", color = if (state.device != null) p.accent else p.text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.error ?: if (state.device != null) "Chromecast · $provider" else provider, color = p.muted, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (state.device != null) TextButton({ connection.returnToPhone() }) { Text("На телефон") }
        else Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = p.muted)
    }
}
