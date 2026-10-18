package app.cloudmusic

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.cloudmusic.ui.CloudMusicApp

class MainActivity : ComponentActivity() {
    private val sharedLink = mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readLink(intent)
        setContent { CloudMusicApp(sharedLink = sharedLink.value, consumeLink = { sharedLink.value = null }) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); readLink(intent) }
    private fun readLink(intent: Intent?) {
        if (intent?.action == android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            androidx.lifecycle.ViewModelProvider(this)[MusicViewModel::class.java].playFromSearch(
                intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty(),
                intent.getStringExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE).orEmpty(),
                intent.getStringExtra(android.provider.MediaStore.EXTRA_MEDIA_ARTIST).orEmpty(),
                intent.getStringExtra(android.provider.MediaStore.EXTRA_MEDIA_ALBUM).orEmpty())
            return
        }
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            sharedLink.value = Regex("https://[^\\s<>]+").find(text)?.value?.trimEnd(')', ']') ?: text
        }
    }
}
