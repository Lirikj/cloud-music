package app.cloudmusic.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.media3.common.PriorityTaskManager.PriorityTooLowException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.Id3Decoder
import app.cloudmusic.playback.AudioCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

@androidx.annotation.OptIn(UnstableApi::class)
class ArtworkRepository(context: Context, private val database: MusicDatabase, private val audio: AudioCache) {
    private val directory = File(context.cacheDir, "artwork-v1").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val requests = Semaphore(1)
    init { prune() }
    @Synchronized fun clear() { memory.evictAll(); directory.listFiles()?.forEach { it.delete() } }
    private fun key(track: Track) = "${track.id}-${database.findFolder(track.folderId)?.updatedAt ?: 0}" + if (track.folderId == DOWNLOAD_FOLDER_ID) "-${stableId(track.remoteId).take(12)}" else ""
    fun cachedFile(track: Track): File? = File(directory, "${key(track)}.jpg").takeIf { it.isFile }

    suspend fun load(track: Track): Bitmap? = withContext(Dispatchers.IO) {
        if (track.provider == Provider.DEMO) return@withContext null
        val key = key(track)
        memory.get(key)?.let { return@withContext it }
        locks.getOrPut(key) { Mutex() }.withLock {
            memory.get(key)?.let { return@withLock it }
            val image = File(directory, "$key.jpg")
            if (image.isFile) runCatching { decode(image.readBytes()) }.getOrNull()?.let { memory.put(key, it); return@withLock it }
            val missing = File(directory, "$key.none")
            if (missing.isFile && System.currentTimeMillis() - missing.lastModified() < 86_400_000) return@withLock null
            requests.withPermit {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val length = if (track.size > 0) minOf(track.size, EmbeddedArtwork.MAX_BYTES + 256L * 1024) else EmbeddedArtwork.MAX_BYTES + 256L * 1024
                        val spec = DataSpec.Builder().setUri(audio.uri(track)).setLength(length).build()
                        val bytes = DataSourceInputStream(audio.backgroundFactory.createDataSource(), spec).use { input ->
                            val prefix = java.io.ByteArrayOutputStream()
                            val reader = object : InputStream() {
                                override fun read(): Int { val v = input.read(); if (v >= 0 && prefix.size() < app.cloudmusic.playback.HeaderCache.MAX_ENTRY) prefix.write(v); return v }
                                override fun read(b: ByteArray, off: Int, len: Int): Int {
                                    val n = input.read(b, off, len)
                                    if (n > 0) prefix.write(b, off, minOf(n, app.cloudmusic.playback.HeaderCache.MAX_ENTRY - prefix.size()))
                                    return n
                                }
                            }
                            val artwork = EmbeddedArtwork.read(reader)
                            if (track.provider != Provider.LOCAL) audio.headers.put(audio.headerKey(track), prefix.toByteArray())
                            artwork
                        }
                        if (bytes == null) { missing.writeText(""); prune(); return@withPermit null }
                        return@withPermit save(key, bytes)
                    } catch (e: CancellationException) { throw e }
                    catch (_: PriorityTooLowException) { delay(200) }
                    catch (_: Exception) { return@withPermit null } // Network errors are retried on next appearance.
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        }
    }
    suspend fun store(track: Track, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val key = key(track)
        locks.getOrPut(key) { Mutex() }.withLock { if (memory.get(key) == null) save(key, bytes) }
    }
    private fun save(key: String, bytes: ByteArray): Bitmap? {
        val bitmap = decode(bytes) ?: return null
        memory.put(key, bitmap)
        val temporary = File(directory, "$key.tmp")
        temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        temporary.renameTo(File(directory, "$key.jpg"))
        File(directory, "$key.none").delete()
        prune()
        return bitmap
    }
    private fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.size > EmbeddedArtwork.MAX_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 384 || bounds.outHeight / inSampleSize > 384) inSampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
    @Synchronized private fun prune() {
        val files = directory.listFiles()?.filter { it.extension != "tmp" }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (total <= 4L * 1024 * 1024 && count <= 512) break
            val size = file.length()
            if (file.delete()) { total -= size; count-- }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal object EmbeddedArtwork {
    const val MAX_BYTES = 4 * 1024 * 1024
    fun read(input: InputStream): ByteArray? {
        val header = exact(input, 10) ?: return null
        if (header.copyOfRange(0, 3).contentEquals(byteArrayOf(73, 68, 51))) {
            if (header[3].toInt() !in 2..4 || (6..9).any { header[it].toInt() and 128 != 0 }) return null
            val size = (6..9).fold(0) { value, i -> (value shl 7) or header[i].toInt() }
            if (size <= 0 || size > MAX_BYTES - 10) return null
            val tag = header + (exact(input, size) ?: return null)
            val metadata = Id3Decoder { version, a, b, c, d ->
                if (version == 2) a == 80 && b == 73 && c == 67 else a == 65 && b == 80 && c == 73 && d == 67
            }.decode(tag, tag.size) ?: return null
            val frames = (0 until metadata.length()).mapNotNull { metadata[it] as? ApicFrame }
            return (frames.firstOrNull { it.pictureType == 3 } ?: frames.firstOrNull())?.pictureData
        }
        if (header.copyOfRange(0, 4).contentEquals("fLaC".toByteArray())) {
            // FLAC metadata starts immediately after the four byte signature.
            val stream = java.io.SequenceInputStream(header.copyOfRange(4, 10).inputStream(), input)
            var consumed = 4
            while (consumed < MAX_BYTES) {
                val block = exact(stream, 4) ?: return null
                val type = block[0].toInt() and 127
                val size = ((block[1].toInt() and 255) shl 16) or ((block[2].toInt() and 255) shl 8) or (block[3].toInt() and 255)
                consumed += 4 + size
                if (consumed > MAX_BYTES) return null
                val data = exact(stream, size) ?: return null
                if (type == 6) return flacPicture(data)
                if (block[0].toInt() and 128 != 0) return null
            }
        }
        return null
    }
    private fun flacPicture(data: ByteArray): ByteArray? = runCatching {
        val buffer = ByteBuffer.wrap(data)
        buffer.int // picture type
        repeat(2) { val size = buffer.int; require(size >= 0 && size <= buffer.remaining()); buffer.position(buffer.position() + size) }
        repeat(4) { buffer.int } // dimensions, depth, indexed colors
        val size = buffer.int
        require(size > 0 && size <= buffer.remaining())
        ByteArray(size).also { buffer.get(it) }
    }.getOrNull()
    private fun exact(input: InputStream, size: Int): ByteArray? {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = input.read(bytes, offset, size - offset)
            if (n < 0) return null
            if (n == 0) return null
            offset += n
        }
        return bytes
    }
}
