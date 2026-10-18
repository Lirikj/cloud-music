package app.cloudmusic.cast

/** Shuffle is a permutation, so every track is visited before repeating. */
class CastQueueOrder(val order: List<Int>) {
    fun next(index: Int, repeat: Int, shuffle: Boolean): Int = step(index, repeat, shuffle, 1)
    fun previous(index: Int, repeat: Int, shuffle: Boolean): Int = step(index, repeat, shuffle, -1)
    private fun step(index: Int, repeat: Int, shuffle: Boolean, delta: Int): Int {
        if (order.isEmpty() || index !in order.indices) return -1
        if (repeat == 1) return index
        val sequence = if (shuffle) order else order.indices.toList()
        val candidate = sequence.indexOf(index) + delta
        return if (candidate in sequence.indices) sequence[candidate] else if (repeat == 2) sequence[(candidate + sequence.size) % sequence.size] else -1
    }
    fun first(shuffle: Boolean) = if (order.isEmpty()) -1 else if (shuffle) order.first() else 0
    fun last(shuffle: Boolean) = if (order.isEmpty()) -1 else if (shuffle) order.last() else order.lastIndex
}
