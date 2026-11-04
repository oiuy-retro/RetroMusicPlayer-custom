/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.service

/**
 * Builds the queues for "open this list and start playing". Pure logic, unit tested.
 * With shuffle on, the list is shuffled exactly once around the start song, which ends up
 * at position 0. With shuffle off, the start position is kept.
 */
object QueueOpener {
    data class Opened<T>(val original: ArrayList<T>, val playing: ArrayList<T>, val position: Int)

    fun <T> open(
        queue: List<T>,
        startPosition: Int,
        shuffle: Boolean,
        shuffler: (list: MutableList<T>, current: Int) -> Unit,
    ): Opened<T>? {
        if (queue.isEmpty() || startPosition !in queue.indices) return null
        val original = ArrayList(queue)
        val playing = ArrayList(original)
        if (!shuffle) return Opened(original, playing, startPosition)
        val start = original[startPosition]
        shuffler(playing, startPosition)
        // The shuffler keeps the current song first; make sure even if a mode didn't.
        val at = playing.indexOf(start)
        if (at > 0) {
            playing.removeAt(at)
            playing.add(0, start)
        } else if (at < 0) {
            playing.add(0, start)
        }
        return Opened(original, playing, 0)
    }
}
