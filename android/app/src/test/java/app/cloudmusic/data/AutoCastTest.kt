package app.cloudmusic.data

import app.cloudmusic.playback.MusicCatalogue
import app.cloudmusic.cast.HttpRange
import app.cloudmusic.cast.CastQueueOrder
import org.junit.Assert.*
import org.junit.Test

class AutoCastTest {
    private val folder = CloudFolder("f", "Music", "https://disk.yandex.ru/d/test123", Provider.YANDEX)
    private val a = Track("a", "f", Provider.YANDEX, "/a.mp3", "Alpha", "Artist", "Album", favorite = true)
    private val b = a.copy(id = "b", title = "Beta", favorite = false)
    private val local = a.copy(id = "local", folderId = "device", provider = Provider.LOCAL, title = "Local", artist = "Other", remoteId = "content://files/1")
    @Test fun catalogueCarriesArtistFolderAndSearchQueueContext() {
        val c = MusicCatalogue(CollectionSnapshot(listOf(folder), listOf(a, b, local)))
        assertEquals(4, c.children(MusicCatalogue.ROOT).size)
        assertTrue(c.children(MusicCatalogue.FOLDERS).any { it.id == MusicCatalogue.OFFLINE })
        val artist = c.children(MusicCatalogue.ARTISTS).first { it.title == "Artist" }
        val songs = c.children(artist.id)
        val choice = c.selection(songs.last().id)!!
        assertEquals(listOf("a", "b"), choice.tracks.map { it.id }); assertEquals(1, choice.index)
        assertEquals(listOf("local"), c.tracksFor(MusicCatalogue.OFFLINE).map { it.id })
        assertEquals(listOf("a", "b"), c.tracksFor("folder:f").map { it.id })
        assertEquals(listOf("a", "local"), c.tracksFor(MusicCatalogue.FAVORITES).map { it.id })
        assertEquals(2, c.selection(c.search("ARTIST").first().id)!!.tracks.size)
        assertNull(c.selection("play/bad/$")); assertNull(c.selection(MusicCatalogue.playId("artist:wrong", a.id)))
    }
    @Test fun browsePagingCannotOverflowOrSkipRequestedItems() {
        val values = (0..1499).toList()
        assertEquals(1000, MusicCatalogue.page(values, 0, 1000).size)
        assertEquals((1000..1499).toList(), MusicCatalogue.page(values, 1, 1000))
        assertTrue(MusicCatalogue.page(values, Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        assertTrue(MusicCatalogue.page(values, -1, 20).isEmpty())
    }
    @Test fun singleRangesClampEndsAndHandleSuffixes() {
        assertNull(HttpRange.parse(null, 100))
        assertEquals(HttpRange.Slice(2, 9), HttpRange.parse("bytes=2-9", 100))
        assertEquals(HttpRange.Slice(90, 99), HttpRange.parse("bytes=90-", 100))
        assertEquals(HttpRange.Slice(95, 99), HttpRange.parse("bytes=-5", 100))
        assertEquals(HttpRange.Slice(0, 99), HttpRange.parse("bytes=-999", 100))
        assertEquals(HttpRange.Slice(90, 99), HttpRange.parse("bytes=90-999", 100))
    }
    @Test fun invalidRangesAreRejectedInsteadOfReadingWrongOffsets() {
        listOf("bytes=100-", "bytes=9-2", "bytes=-0", "bytes=-", "bytes=0-3,8-9", "bytes=999999999999999999999999-", "items=0-4").forEach {
            try { HttpRange.parse(it, 100); fail(it) } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun shuffleVisitsFullQueueAndRepeatUsesFullPermutation() {
        val q = CastQueueOrder(listOf(2, 0, 3, 1))
        var at = q.first(true); val seen = mutableListOf<Int>()
        while (at >= 0) { seen += at; at = q.next(at, 0, true) }
        assertEquals(listOf(2, 0, 3, 1), seen)
        assertEquals(2, q.next(1, 2, true)); assertEquals(1, q.previous(2, 2, true))
        assertEquals(3, q.next(3, 1, true)); assertEquals(1, q.next(0, 0, false))
        assertEquals(-1, CastQueueOrder(emptyList()).next(0, 2, true))
    }
}
