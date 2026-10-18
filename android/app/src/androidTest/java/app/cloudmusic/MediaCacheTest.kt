package app.cloudmusic

import android.graphics.Bitmap
import android.net.Uri
import android.content.ComponentName
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cloudmusic.data.*
import app.cloudmusic.playback.CloudDataSource
import app.cloudmusic.playback.PlaybackService
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MediaCacheTest {
    @Test fun embeddedCoversAreBoundedAndHandleId3VersionsAndFlac() {
        val picture = ByteArrayOutputStream().also { stream ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, stream)
        }.toByteArray()
        fun sync(size: Int) = byteArrayOf((size shr 21 and 127).toByte(), (size shr 14 and 127).toByte(), (size shr 7 and 127).toByte(), (size and 127).toByte())
        fun integer(size: Int) = java.nio.ByteBuffer.allocate(4).putInt(size).array()
        val payload = byteArrayOf(0) + "image/png".toByteArray() + byteArrayOf(0, 3, 0) + picture
        val frame3 = "APIC".toByteArray() + integer(payload.size) + byteArrayOf(0, 0) + payload
        val frame4 = "APIC".toByteArray() + sync(payload.size) + byteArrayOf(0, 0) + payload
        for ((version, frame) in listOf(3 to frame3, 4 to frame4)) {
            val tag = "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) + sync(frame.size) + frame
            assertArrayEquals(picture, EmbeddedArtwork.read(tag.inputStream()))
            assertNull(EmbeddedArtwork.read(tag.copyOf(15).inputStream()))
        }
        val picPayload = byteArrayOf(0) + "PNG".toByteArray() + byteArrayOf(3, 0) + picture
        val picFrame = "PIC".toByteArray() + byteArrayOf((picPayload.size shr 16).toByte(), (picPayload.size shr 8).toByte(), picPayload.size.toByte()) + picPayload
        assertArrayEquals(picture, EmbeddedArtwork.read(("ID3".toByteArray() + byteArrayOf(2, 0, 0) + sync(picFrame.size) + picFrame).inputStream()))
        val flacPayload = integer(3) + integer(9) + "image/png".toByteArray() + integer(0) + integer(2) + integer(2) + integer(32) + integer(0) + integer(picture.size) + picture
        val flac = "fLaC".toByteArray() + byteArrayOf(0x86.toByte(), (flacPayload.size shr 16).toByte(), (flacPayload.size shr 8).toByte(), flacPayload.size.toByte()) + flacPayload
        assertArrayEquals(picture, EmbeddedArtwork.read(flac.inputStream()))
        assertNull(EmbeddedArtwork.read(("ID3".toByteArray() + byteArrayOf(3, 0, 0) + sync(EmbeddedArtwork.MAX_BYTES + 1)).inputStream()))
        assertNull(EmbeddedArtwork.read(ByteArray(10).inputStream()))
    }

    @Test fun expiredCdnLinkIsRefreshedOnceWithoutChangingCacheIdentity() {
        for (status in listOf(403, 500)) {
        var opened = 0
        var closed = 0
        val upstream = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) {}
            override fun open(dataSpec: DataSpec): Long {
                opened++
                if (opened == 1) throw HttpDataSource.InvalidResponseCodeException(status, "Expired", null, emptyMap(), dataSpec, byteArrayOf())
                assertEquals("https://cdn.example/fresh", dataSpec.uri.toString())
                assertEquals(100L, dataSpec.position)
                return 200L
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = C.RESULT_END_OF_INPUT
            override fun getUri() = Uri.parse("https://cdn.example/fresh")
            override fun close() { closed++ }
        }
        val flags = mutableListOf<Boolean>()
        val source = CloudDataSource(upstream) { _, refresh -> flags += refresh; Uri.parse("https://cdn.example/${if (refresh) "fresh" else "old"}") }
        val uri = Uri.parse("cloudmusic://track/test")
        assertEquals(200L, source.open(DataSpec.Builder().setUri(uri).setPosition(100).build()))
        assertEquals(uri, source.uri)
        assertEquals(listOf(false, true), flags)
        source.close()
        assertEquals(2, closed)
        }
    }

    @Test fun realCoverPersistsWithoutSavingAudio() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CloudMusicApplication
        val sample = app.repository.database.snapshot().tracks.firstOrNull { it.provider == Provider.YANDEX && it.remoteId.contains("Dancing in My Room") }
        assumeTrue("Connect the supplied music folder to run this test", sample != null)
        val track = sample ?: return@runBlocking
        val first = withTimeout(30_000) { app.artwork.load(track) }
        assertNotNull("The user's MP3 contains an embedded cover", first)
        assertTrue(first!!.width <= 384 && first.height <= 384)
        app.audio.priority.add(10)
        try { assertNotNull(withTimeout(3000) { ArtworkRepository(app, app.repository.database, app.audio).load(track) }) }
        finally { app.audio.priority.remove(10) }
        assertTrue(app.audio.headers.sizeBytes() <= 1024 * 1024)
        assertFalse("Audio cache from old versions must be removed", java.io.File(app.cacheDir, "audio-v1").exists())
        assertTrue(java.io.File(app.cacheDir, "artwork-v1").listFiles().orEmpty().sumOf { it.length() } <= 4L * 1024 * 1024)
    }

    @Test fun metadataPrefixPreservesAllBytesRangesAndUnknownLengths() {
        val data = ByteArray(1024) { (it % 251).toByte() }
        val cache = app.cloudmusic.playback.HeaderCache().apply { put("test", data.copyOf(150)) }
        for (size in listOf(data.size.toLong(), 0L)) for (start in listOf(0, 40, 149, 150, 400)) for (length in listOf(-1, 35, 200)) {
            val opens = mutableListOf<Long>()
            val upstream = object : DataSource {
                var cursor = 0
                var end = 0
                override fun addTransferListener(transferListener: TransferListener) {}
                override fun open(dataSpec: DataSpec): Long { cursor = dataSpec.position.toInt(); end = if (dataSpec.length < 0) data.size else minOf(data.size, cursor + dataSpec.length.toInt()); opens += dataSpec.position; return (end - cursor).toLong() }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int { if (cursor == end) return C.RESULT_END_OF_INPUT; val n = minOf(length, end - cursor); data.copyInto(buffer, offset, cursor, cursor + n); cursor += n; return n }
                override fun getUri() = Uri.parse("cloudmusic://track/test")
                override fun close() {}
            }
            val source = app.cloudmusic.playback.PrefixDataSource(upstream, cache, "test", size)
            val spec = DataSpec.Builder().setUri("cloudmusic://track/test").setPosition(start.toLong()).apply { if (length > 0) setLength(length.toLong()) }.build()
            val expectedEnd = if (length < 0) data.size else minOf(data.size, start + length)
            assertEquals((expectedEnd - start).toLong(), source.open(spec))
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(37)
            while (true) { val n = source.read(buffer, 0, buffer.size); if (n < 0) break; result.write(buffer, 0, n) }
            assertArrayEquals("size=$size, start=$start, length=$length", data.copyOfRange(start, expectedEnd), result.toByteArray())
            if (expectedEnd <= 150) assertTrue("Contained range needs no upstream", opens.isEmpty())
            else assertEquals(maxOf(start, 150).toLong(), opens.single())
            source.close()
        }
    }
}
