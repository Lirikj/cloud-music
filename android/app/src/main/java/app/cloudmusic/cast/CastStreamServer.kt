package app.cloudmusic.cast

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import app.cloudmusic.CloudMusicApplication
import app.cloudmusic.data.Track
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Session-scoped LAN relay: at most four 64 KiB transfers, no audio files or full-track buffering. */
@androidx.annotation.OptIn(UnstableApi::class)
class CastStreamServer(private val app: CloudMusicApplication) : Closeable {
    private val running = AtomicBoolean(false)
    private val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    private val allowed = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Track>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?) = size > 512
    })
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, SynchronousQueue(), { r -> Thread(r, "cloud-cast-stream").apply { isDaemon = true } })
    private var socket: ServerSocket? = null
    private var base: String? = null
    fun start(bindAddress: InetAddress = wifiAddress()): CastStreamServer {
        check(!running.get())
        val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bindAddress, 0), 8) }
        socket = server; base = "http://${if (bindAddress is Inet6Address) "[${bindAddress.hostAddress}]" else bindAddress.hostAddress}:${server.localPort}"
        running.set(true)
        Thread({
            while (running.get()) try {
                val client = server.accept().apply { soTimeout = 10_000 }; clients += client
                try { workers.execute { try { client.use { serve(it) } } finally { clients.remove(client) } } }
                catch (_: RejectedExecutionException) { clients.remove(client); client.close() }
            } catch (_: IOException) { if (running.get()) close() }
        }, "cloud-cast-listen").apply { isDaemon = true; start() }
        return this
    }
    private fun wifiAddress(): InetAddress {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.asSequence().filter { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?: throw IOException("Подключите телефон и Chromecast к одной Wi-Fi-сети.")
    }
    fun audioUrl(track: Track): Uri {
        check(running.get()); allowed[track.id] = track
        return Uri.parse("$base/$token/audio/${Uri.encode(track.id)}")
    }
    fun coverUrl(track: Track): Uri? = app.artwork.cachedFile(track)?.let {
        allowed[track.id] = track; Uri.parse("$base/$token/cover/${Uri.encode(track.id)}")
    }
    private fun serve(client: Socket) {
        val out = client.getOutputStream().buffered(64 * 1024)
        var committed = false
        fun respond(code: Int, text: String, headers: Map<String, String> = emptyMap()) {
            committed = true
            val h = linkedMapOf("Connection" to "close", "Content-Length" to "0", "Access-Control-Allow-Origin" to "*", "Access-Control-Allow-Headers" to "Range", "Access-Control-Allow-Private-Network" to "true", "Access-Control-Expose-Headers" to "Content-Range, Content-Length, Accept-Ranges") + headers
            out.write(("HTTP/1.1 $code $text\r\n" + h.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n").toByteArray(Charsets.US_ASCII))
            out.flush()
        }
        try {
            val input = client.getInputStream()
            var budget = 8192
            fun line(): String {
                val b = ByteArrayOutputStream()
                while (true) { val v = input.read(); if (v < 0 || --budget < 0) throw IOException("Invalid HTTP header"); if (v == 10) break; if (v != 13) b.write(v) }
                return b.toString("US-ASCII")
            }
            val request = line().split(' '); if (request.size != 3) { respond(400, "Bad Request"); return }
            val parts = URI(request[1]).rawPath?.split('/') ?: emptyList()
            val headers = mutableMapOf<String, String>()
            while (true) { val h = line(); if (h.isEmpty()) break; val i = h.indexOf(':'); if (i <= 0) throw IOException("Invalid header"); headers[h.substring(0, i).lowercase()] = h.substring(i + 1).trim() }
            if (parts.size != 4 || !MessageDigest.isEqual(parts[1].toByteArray(), token.toByteArray())) { respond(403, "Forbidden"); return }
            val track = allowed[Uri.decode(parts[3])] ?: run { respond(404, "Not Found"); return }
            // A queue entry is never permission to serve an arbitrary URI or a removed file.
            if (app.repository.database.findTrack(track.id) == null) { respond(404, "Not Found"); return }
            if (request[0] == "OPTIONS") { respond(204, "No Content", mapOf("Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS")); return }
            if (request[0] !in listOf("GET", "HEAD")) { respond(405, "Method Not Allowed"); return }
            if (parts[2] == "cover") {
                val image = app.artwork.cachedFile(track) ?: run { respond(404, "Not Found"); return }
                respond(200, "OK", mapOf("Content-Type" to "image/jpeg", "Content-Length" to image.length().toString()))
                if (request[0] == "GET") image.inputStream().use { it.copyTo(out, 16 * 1024) }
            } else if (parts[2] == "audio") {
                val data = app.audio.playbackFactory.createDataSource()
                app.audio.priority.add(10)
                try {
                    val size = if (track.size > 0) track.size else data.open(DataSpec.Builder().setUri(app.audio.uri(track)).build()).also { data.close() }
                    if (size <= 0 || size == C.LENGTH_UNSET.toLong()) { respond(502, "Unknown Length"); return }
                    val range = try { HttpRange.parse(headers["range"], size) } catch (_: Exception) { respond(416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$size")); return }
                    val slice = range ?: HttpRange.Slice(0, size - 1)
                    if (request[0] == "GET") data.open(DataSpec.Builder().setUri(app.audio.uri(track)).setPosition(slice.start).setLength(slice.length).build())
                    val response = linkedMapOf("Content-Type" to audioMime(track), "Content-Length" to slice.length.toString(), "Accept-Ranges" to "bytes", "Cache-Control" to "no-store")
                    if (range != null) response["Content-Range"] = "bytes ${slice.start}-${slice.end}/$size"
                    respond(if (range == null) 200 else 206, if (range == null) "OK" else "Partial Content", response)
                    if (request[0] == "GET") {
                        val bytes = ByteArray(64 * 1024); var remaining = slice.length
                        while (remaining > 0 && running.get()) {
                            val n = data.read(bytes, 0, minOf(bytes.size.toLong(), remaining).toInt()); if (n < 0) break
                            out.write(bytes, 0, n); out.flush(); remaining -= n
                        }
                    }
                } finally { try { data.close() } finally { app.audio.priority.remove(10) } }
            } else respond(404, "Not Found")
            out.flush()
        } catch (_: Exception) { if (!committed) runCatching { respond(502, "Bad Gateway") } }
    }
    override fun close() {
        running.set(false); runCatching { socket?.close() }; clients.forEach { runCatching { it.close() } }; clients.clear()
        workers.shutdownNow(); allowed.clear(); base = null
    }
    companion object {
        fun audioMime(track: Track): String = when (track.path.ifEmpty { track.remoteId }.substringAfterLast('.').lowercase()) {
            "flac" -> "audio/flac"; "m4a", "mp4", "alac" -> "audio/mp4"; "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; "aif", "aiff" -> "audio/aiff"; "wma" -> "audio/x-ms-wma"
            else -> "audio/mpeg"
        }
    }
}
