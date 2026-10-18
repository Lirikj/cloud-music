package app.cloudmusic.playback

/** At most 1 MiB of already read file headers in RAM. No audio files are saved. */
class HeaderCache(private val maxBytes: Int = 1024 * 1024) {
    private val entries = LinkedHashMap<String, ByteArray>(16, .75f, true)
    private var bytes = 0
    @Synchronized fun get(key: String): ByteArray? = entries[key]
    @Synchronized fun put(key: String, data: ByteArray) {
        if (data.isEmpty() || data.size > MAX_ENTRY || data.size > maxBytes) return
        entries.remove(key)?.let { bytes -= it.size }
        entries[key] = data; bytes += data.size
        while (bytes > maxBytes || entries.size > 64) {
            val first = entries.entries.iterator().next(); bytes -= first.value.size; entries.remove(first.key)
        }
    }
    @Synchronized fun clear() { entries.clear(); bytes = 0 }
    @Synchronized fun sizeBytes() = bytes
    companion object { const val MAX_ENTRY = 256 * 1024 }
}
