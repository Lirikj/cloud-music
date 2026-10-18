package app.cloudmusic.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.cloudmusic.data.*
import okhttp3.OkHttpClient
import java.io.IOException

/** Streaming only: no audio is written to disk or prepared for neighbouring tracks. */
@androidx.annotation.OptIn(UnstableApi::class)
class AudioCache(context: Context, private val repository: MusicRepository, http: OkHttpClient, private val offline: (String) -> Uri? = { null }) {
    val priority = PriorityTaskManager().apply { add(0) }
    val headers = HeaderCache()
    private val network = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http))
    private fun factory(background: Boolean) = DataSource.Factory {
        val cloud = CloudDataSource(network.createDataSource()) { uri, refresh ->
            if (!refresh) uri.lastPathSegment?.let(offline)?.let { return@CloudDataSource it }
            val track = uri.lastPathSegment?.let(repository.database::findTrack) ?: throw IOException("Трек удалён из медиатеки.")
            val folder = repository.database.findFolder(track.folderId) ?: throw IOException("Папка отключена.")
            if (refresh) repository.cloud.invalidate(track, folder)
            Uri.parse(repository.cloud.resolve(track, folder))
        }
        val upstream = if (background) PriorityDataSource(cloud, priority, 0) else cloud
        object : DataSource {
            private var source: DataSource? = null
            private val listeners = mutableListOf<TransferListener>()
            override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
            override fun open(dataSpec: DataSpec): Long {
                val local = if (dataSpec.uri.scheme == "cloudmusic") dataSpec.uri.lastPathSegment?.let(offline) else null
                source = if (local != null) {
                    val native = network.createDataSource()
                    object : DataSource by native { override fun open(dataSpec: DataSpec) = native.open(dataSpec.withUri(local)) }
                } else if (dataSpec.uri.scheme == "cloudmusic") {
                    val track = dataSpec.uri.lastPathSegment?.let(repository.database::findTrack) ?: throw IOException("Трек не найден.")
                    PrefixDataSource(upstream, headers, headerKey(track), track.size)
                } else network.createDataSource()
                listeners.forEach { source!!.addTransferListener(it) }
                return source!!.open(dataSpec)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = source!!.read(buffer, offset, length)
            override fun getUri(): Uri? = source?.uri
            override fun getResponseHeaders() = source?.responseHeaders.orEmpty()
            override fun close() { try { source?.close() } finally { source = null } }
        }
    }
    val playbackFactory: DataSource.Factory = factory(false)
    val backgroundFactory: DataSource.Factory = factory(true)
    fun headerKey(track: Track): String = "${track.id}:${repository.database.findFolder(track.folderId)?.updatedAt ?: 0}"
    fun uri(track: Track): Uri = when (track.provider) {
        Provider.LOCAL, Provider.DEMO -> Uri.parse(track.remoteId)
        else -> Uri.parse("cloudmusic://track/${track.id}")
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class CloudDataSource(private val upstream: DataSource, private val resolve: (Uri, Boolean) -> Uri) : DataSource {
    private var originalUri: Uri? = null
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
    override fun open(dataSpec: DataSpec): Long {
        originalUri = dataSpec.uri
        if (dataSpec.uri.scheme != "cloudmusic") return upstream.open(dataSpec)
        try { return upstream.open(dataSpec.withUri(resolve(dataSpec.uri, false))) }
        catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (error.responseCode !in listOf(401, 403, 410, 500, 502, 503, 504)) throw error
            upstream.close()
            return upstream.open(dataSpec.withUri(resolve(dataSpec.uri, true)))
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)
    override fun getUri(): Uri? = originalUri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() { try { upstream.close() } finally { originalUri = null } }
}

/** Reuses only a bounded metadata prefix in RAM; the audio body is always streamed. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PrefixDataSource(private val upstream: DataSource, private val headers: HeaderCache,
    private val key: String, private val fileSize: Long) : DataSource {
    private lateinit var spec: DataSpec
    private var prefix = byteArrayOf()
    private var prefixOffset = 0
    private var prefixEnd = 0
    private var consumed = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    private var recorded = java.io.ByteArrayOutputStream()
    private var recordLength = -1
    private var recording = true
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec; consumed = 0; opened = false
        recorded = java.io.ByteArrayOutputStream(); recordLength = -1; recording = dataSpec.position == 0L
        prefix = headers.get(key) ?: byteArrayOf()
        prefixOffset = minOf(dataSpec.position, prefix.size.toLong()).toInt()
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            fileSize > 0 -> (fileSize - dataSpec.position).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }
        prefixEnd = if (remaining >= 0) minOf(prefix.size.toLong(), prefixOffset + remaining).toInt() else prefix.size
        if (prefixOffset >= prefixEnd) return openUpstream()
        if (remaining == C.LENGTH_UNSET.toLong()) {
            opened = true
            val length = upstream.open(spec.subrange((prefixEnd - prefixOffset).toLong()))
            if (length >= 0) remaining = length + prefixEnd - prefixOffset
        }
        return remaining
    }
    private fun openUpstream(): Long {
        opened = true
        val length = upstream.open(spec.subrange(consumed))
        if (remaining == C.LENGTH_UNSET.toLong() && length >= 0) remaining = length
        return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val amount = if (remaining >= 0) minOf(length.toLong(), remaining).toInt() else length
        val n = if (prefixOffset < prefixEnd) {
            val count = minOf(amount, prefixEnd - prefixOffset)
            prefix.copyInto(buffer, offset, prefixOffset, prefixOffset + count); prefixOffset += count; count
        } else { if (!opened) openUpstream(); upstream.read(buffer, offset, amount) }
        if (n < 0) { remaining = 0; return n }
        capture(buffer, offset, n)
        consumed += n
        if (remaining >= 0) remaining -= n
        return n
    }
    private fun capture(bytes: ByteArray, offset: Int, size: Int) {
        if (!recording) return
        val target = if (recordLength > 0) recordLength else HeaderCache.MAX_ENTRY
        val count = minOf(size, target - recorded.size())
        if (count > 0) recorded.write(bytes, offset, count)
        if (recordLength < 0 && recorded.size() >= 10) {
            val head = recorded.toByteArray()
            if (head[0] != 73.toByte() || head[1] != 68.toByte() || head[2] != 51.toByte() || (6..9).any { head[it].toInt() and 128 != 0 }) {
                recording = false; recorded.reset(); return
            }
            recordLength = minOf(HeaderCache.MAX_ENTRY, 10 + (6..9).fold(0) { value, i -> (value shl 7) or head[i].toInt() })
        }
        if (recordLength > 0 && recorded.size() >= recordLength) {
            headers.put(key, recorded.toByteArray().copyOf(recordLength)); recording = false; recorded.reset()
        }
    }
    override fun getUri(): Uri = spec.uri
    override fun getResponseHeaders() = if (opened) upstream.responseHeaders else emptyMap()
    override fun close() { try { if (opened) upstream.close() } finally { opened = false; prefix = byteArrayOf(); recorded.reset() } }
}
