package app.cloudmusic.data

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CloudDataTest {
    @Test fun yandexLinksNormalizeAndKeepIdentity() {
        val a = FolderLinks.parse(" https://yadi.sk/d/AbcDef123?utm_source=share ")
        val b = FolderLinks.parse("https://disk.yandex.ru/d/AbcDef123/")
        assertEquals(a.id, b.id)
        assertEquals("https://disk.yandex.ru/d/AbcDef123", a.url)
    }
    @Test fun googleUserPathAndResourceKeyArePreserved() {
        val link = FolderLinks.parse("https://drive.google.com/drive/u/0/folders/AbcDef123?usp=sharing&resourcekey=0-test_key")
        assertEquals(Provider.GOOGLE, link.provider)
        assertEquals("0-test_key", link.resourceKey)
        assertEquals("https://drive.google.com/drive/folders/AbcDef123?resourcekey=0-test_key", link.url)
    }
    @Test fun rejectsCredentialsLookalikeDomainsFilesAndInsecureUrls() {
        listOf("http://disk.yandex.ru/d/AbcDef123", "https://drive.google.com.evil.example/drive/folders/AbcDef123", "https://user@disk.yandex.ru/d/AbcDef123", "https://drive.google.com/file/d/AbcDef123/view", "https://disk.yandex.ru:8443/d/AbcDef123", "https://disk.yandex.ru/d/AbcDef123/extra").forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) { FolderLinks.parse(url) }
        }
    }
    @Test fun filenameFallbackPreservesSongHyphensAndCyrillic() {
        assertEquals("По орбите - live" to "Моно", AudioNames.metadata("Моно - По орбите - live.FLAC"))
        assertEquals("No artist" to "Неизвестный исполнитель", AudioNames.metadata("No_artist.mp3"))
        assertTrue(AudioNames.isAudio("Трек.FLAC"))
        assertTrue(AudioNames.isAudio("download", "audio/mpeg"))
        assertFalse(AudioNames.isAudio("cover.jpg"))
    }
    @Test fun googlePublicHtmlParsesNamesEntitiesFoldersAndResourceKeys() {
        val html = """<html><title>My music</title><div class="flip-entries">
            <a href="https://drive.google.com/file/d/track_123/view?resourcekey=0-one">A &amp; B - Свет.mp3</a>
            <a href="https://drive.google.com/drive/folders/folder_123?resourcekey=0-two">Альбом</a>
            <a href="https://evil.example/file/d/bad/view">bad.mp3</a>
            </div></html>"""
        val page = GoogleFolderPage.parse(html)
        assertEquals("My music", page.name)
        assertEquals(2, page.entries.size)
        assertEquals("A & B - Свет.mp3", page.entries[0].name)
        assertEquals("0-one", page.entries[0].resourceKey)
        assertTrue(page.entries[1].folder)
    }
    @Test fun googleRejectsLoginAndUnknownPageButAcceptsEmptyFolder() {
        assertThrows(CloudException::class.java) { GoogleFolderPage.parse("<html><title>Sign in - Google Accounts</title></html>") }
        assertThrows(CloudException::class.java) { GoogleFolderPage.parse("<html><title>Error</title></html>") }
        assertEquals(emptyList<GoogleEntry>(), GoogleFolderPage.parse("<title>Empty</title><div class='flip-entries'></div>").entries)
    }
    @Test fun yandexPaginationDoesNotDropTheLastPage() {
        val server = MockWebServer()
        server.use {
            server.enqueue(MockResponse().setBody("""{"name":"Music","type":"dir","_embedded":{"total":3,"items":[{"name":"One.mp3","path":"/One.mp3","type":"file"},{"name":"cover.jpg","path":"/cover.jpg","type":"file"}]}}"""))
            server.enqueue(MockResponse().setBody("""{"name":"Music","type":"dir","_embedded":{"total":3,"items":[{"name":"Two.FLAC","path":"/Two.FLAC","type":"file"}]}}"""))
            val client = CloudClients(OkHttpClient(), server.url("/disk").toString())
            val tracks = client.readFolder(FolderLinks.parse("https://disk.yandex.ru/d/test_123")).tracks
            assertEquals(listOf("One", "Two"), tracks.map { it.title })
            assertEquals("0", server.takeRequest().requestUrl?.queryParameter("offset"))
            assertEquals("2", server.takeRequest().requestUrl?.queryParameter("offset"))
        }
    }
    @Test fun yandexNestedFoldersKeepAlbumAndSourceIdentity() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"name":"Collection","type":"dir","_embedded":{"total":1,"items":[{"name":"Album","path":"/Album","type":"dir"}]}}"""))
            server.enqueue(MockResponse().setBody("""{"name":"Album","type":"dir","_embedded":{"total":1,"items":[{"name":"Artist - Song.mp3","path":"/Album/Artist - Song.mp3","type":"file"}]}}"""))
            val link = FolderLinks.parse("https://disk.yandex.ru/d/test_123")
            val track = CloudClients(OkHttpClient(), server.url("/disk").toString()).readFolder(link).tracks.single()
            assertEquals("Album", track.album)
            assertEquals(link.id, track.folderId)
            assertEquals("Artist", track.artist)
            assertEquals("/Album/Artist - Song.mp3", track.remoteId)
        }
    }
    @Test fun forbiddenAndFileLinksFailWithoutFakeSuccess() {
        MockWebServer().use { server ->
            val client = CloudClients(OkHttpClient(), server.url("/disk").toString())
            val link = FolderLinks.parse("https://disk.yandex.ru/d/test_123")
            server.enqueue(MockResponse().setResponseCode(403))
            assertThrows(CloudException::class.java) { client.readFolder(link) }
            server.enqueue(MockResponse().setBody("""{"name":"Only.mp3","type":"file"}"""))
            assertThrows(CloudException::class.java) { client.readFolder(link) }
        }
    }
    @Test fun streamResolverRequestsFreshDownloadLinkAndEncodesPath() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"href":"https://download.example/audio?token=fresh"}"""))
            val folder = CloudFolder("folder", "Music", "https://disk.yandex.ru/d/test_123", Provider.YANDEX)
            val track = Track("track", "folder", Provider.YANDEX, "/Artist & Song.mp3", "Song", "Artist", "Album")
            val result = CloudClients(OkHttpClient(), server.url("/disk").toString()).resolve(track, folder)
            assertEquals("https://download.example/audio?token=fresh", result)
            assertEquals("/Artist & Song.mp3", server.takeRequest().requestUrl?.queryParameter("path"))
        }
    }
    @Test fun downloadLinksAreReusedButExpireAndCanBeInvalidated() {
        MockWebServer().use { server ->
            repeat(4) { n -> server.enqueue(MockResponse().setBody("""{"href":"https://download.example/audio?generation=$n"}""")) }
            var clock = 1000L
            val client = CloudClients(OkHttpClient(), server.url("/disk").toString()) { clock }
            val folder = CloudFolder("folder", "Music", "https://disk.yandex.ru/d/test_123", Provider.YANDEX)
            val track = Track("track", "folder", Provider.YANDEX, "/song.mp3", "Song", "Artist", "Album")
            val first = client.resolve(track, folder)
            assertEquals(first, client.resolve(track, folder))
            assertEquals(1, server.requestCount)
            clock += 240_001
            assertNotEquals(first, client.resolve(track, folder))
            client.invalidate(track, folder)
            assertTrue(client.resolve(track, folder).endsWith("generation=2"))
            assertTrue(client.resolve(track, folder.copy(updatedAt = 9)).endsWith("generation=3"))
            assertEquals(4, server.requestCount)
        }
    }
    @Test fun simultaneousResolutionsShareOneRequestAndFailuresAreNotCached() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("""{"href":"https://download.example/audio"}""").setBodyDelay(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            val client = CloudClients(OkHttpClient(), server.url("/disk").toString())
            val folder = CloudFolder("folder", "Music", "https://disk.yandex.ru/d/test_123", Provider.YANDEX)
            val track = Track("track", "folder", Provider.YANDEX, "/song.mp3", "Song", "Artist", "Album")
            assertThrows(CloudException::class.java) { client.resolve(track, folder) }
            val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
            try {
                val jobs = (1..4).map { executor.submit<String> { client.resolve(track, folder) } }
                assertEquals(1, jobs.map { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }.toSet().size)
                assertEquals(2, server.requestCount)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun directLinksFromFolderMetadataSkipExtraDownloadRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"name":"Music","type":"dir","_embedded":{"total":1,"items":[{"name":"Artist - Song.mp3","path":"/song.mp3","type":"file","file":"https://cdn.example/direct"}]}}"""))
            val client = CloudClients(OkHttpClient(), server.url("/disk").toString())
            val link = FolderLinks.parse("https://disk.yandex.ru/d/test_123")
            val contents = client.readFolder(link)
            val track = contents.tracks.single()
            val folder = CloudFolder(link.id, contents.name, link.url, Provider.YANDEX, updatedAt = 5)
            contents.downloadLinks.forEach { (id, url) -> client.seed(id, folder.updatedAt, url) }
            assertEquals("https://cdn.example/direct", client.resolve(track, folder))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun headerCacheEvictsByBudgetAndRecentUse() {
        val cache = app.cloudmusic.playback.HeaderCache(100)
        cache.put("a", ByteArray(40)); cache.put("b", ByteArray(40)); cache.get("a"); cache.put("c", ByteArray(40))
        assertNotNull(cache.get("a")); assertNull(cache.get("b")); assertEquals(80, cache.sizeBytes())
        cache.put("oversize", ByteArray(101)); assertNull(cache.get("oversize"))
        cache.put("a", ByteArray(20)); assertEquals(60, cache.sizeBytes())
        cache.clear(); assertEquals(0, cache.sizeBytes())
    }
    @Test fun artistGroupingNormalizesCaseAndWhitespace() {
        val tracks = listOf(" Artist  Name ", "artist name", "Another").mapIndexed { i, name -> Track("$i", "f", Provider.LOCAL, "content://$i", "Song $i", name, "Album") }
        val groups = artists(tracks)
        assertEquals(2, groups.size)
        assertEquals(listOf("0", "1"), groups.single { it.key == "artist name" }.tracks.map { it.id })
    }

    @Test fun downloadNamesAreBoundedSafeAndUniquePerSourceAndAttempt() {
        val track = Track("a", "folder", Provider.YANDEX, "/track.mp3", "../" + "😀曲".repeat(100), "A/B:C", "Album", path = "/track.mp3")
        val first = downloadableFilename(track, "123abc")
        assertFalse(first.contains('/')); assertFalse(first.contains('\\')); assertFalse(first.contains(':'))
        assertTrue(first.toByteArray(Charsets.UTF_8).size < 255)
        assertTrue(first.endsWith(".mp3"))
        assertNotEquals(first, downloadableFilename(track.copy(id = "b"), "123abc"))
        assertNotEquals(first, downloadableFilename(track, "456abc"))
    }
    @Test fun offlineCopiesDoNotDuplicateConnectedCloudTracksInLibrary() {
        val source = Track("source", "cloud", Provider.YANDEX, "/song.mp3", "Song", "Artist", "Album")
        val downloaded = MusicDownload(source.id, 1, source.title, source.artist, source.album, "Song.mp3", 100, DownloadStatus.COMPLETE, 100, 100, "content://downloads/1").track()
        assertEquals(listOf(source), browseTracks(listOf(source, downloaded)))
        assertEquals(listOf(downloaded), browseTracks(listOf(downloaded)))
    }

}
