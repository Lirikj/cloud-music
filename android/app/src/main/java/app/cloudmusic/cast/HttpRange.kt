package app.cloudmusic.cast

/** Only a single byte range is accepted. Multiparts are unnecessary for Cast audio. */
object HttpRange {
    data class Slice(val start: Long, val end: Long) { val length get() = end - start + 1 }
    fun parse(header: String?, size: Long): Slice? {
        require(size > 0)
        if (header == null) return null
        val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header.trim()) ?: throw IllegalArgumentException("Invalid range")
        val (a, b) = match.destructured
        require(a.isNotEmpty() || b.isNotEmpty())
        if (a.isEmpty()) { val suffix = b.toLong(); require(suffix > 0); return Slice((size - suffix).coerceAtLeast(0), size - 1) }
        val start = a.toLong(); val end = if (b.isEmpty()) size - 1 else minOf(b.toLong(), size - 1)
        require(start in 0 until size && end >= start)
        return Slice(start, end)
    }
}
