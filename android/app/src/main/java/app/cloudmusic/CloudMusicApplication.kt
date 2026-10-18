package app.cloudmusic

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import app.cloudmusic.data.CloudClients
import app.cloudmusic.data.MusicDatabase
import app.cloudmusic.data.MusicRepository
import app.cloudmusic.data.ArtworkRepository
import app.cloudmusic.data.LocalFiles
import app.cloudmusic.data.MusicDownloads
import kotlinx.coroutines.*
import java.io.File
import app.cloudmusic.playback.AudioCache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class CloudMusicApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        // Idle sockets from the previous default network must not delay new requests.
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { http.connectionPool.evictAll() }
            override fun onLost(network: Network) { http.connectionPool.evictAll() }
        })
        applicationScope.launch {
            // Remove only the obsolete application audio cache, never the user's files.
            File(cacheDir, "audio-v1").deleteRecursively()
            deleteDatabase("exoplayer_internal.db")
            downloads.start(applicationScope)
        }
    }
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .followSslRedirects(false).cookieJar(object : CookieJar {
                private val cookies = mutableListOf<Cookie>()
                @Synchronized override fun saveFromResponse(url: HttpUrl, values: List<Cookie>) {
                    cookies.removeAll { old -> old.expiresAt < System.currentTimeMillis() || values.any { it.name == old.name && it.domain == old.domain && it.path == old.path } }
                    cookies.addAll(values)
                }
                @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.expiresAt >= System.currentTimeMillis() && it.matches(url) }
            }).addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "Mozilla/5.0 CloudMusic/0.1 Android").build()) }.build()
    }
    val casting by lazy { app.cloudmusic.cast.CastConnection(this) }
    val repository: MusicRepository by lazy { MusicRepository(MusicDatabase(this), CloudClients(http), LocalFiles(this)) }
    val downloads: MusicDownloads by lazy { MusicDownloads(this, repository) }
    val audio: AudioCache by lazy { AudioCache(this, repository, http) { downloads.uriForSource(it) } }
    val artwork: ArtworkRepository by lazy { ArtworkRepository(this, repository.database, audio) }
    suspend fun clearArtwork() = withContext(Dispatchers.IO) { artwork.clear() }
}
