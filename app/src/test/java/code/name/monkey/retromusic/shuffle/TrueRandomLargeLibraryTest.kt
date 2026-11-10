/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.shuffle

import code.name.monkey.retromusic.helper.ShuffleHelper
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.service.QueueOpener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * True random on a 100,000-song library sorted by album (10 songs per album) must give a fully
 * mixed order: every song exactly once, and about as many neighbours from the same album as pure
 * chance gives (about 9 for this library), not long album runs.
 */
class TrueRandomLargeLibraryTest {

    private val songsPerAlbum = 10
    private val size = 100_000

    private fun library(): List<Song> = (0 until size).map { i ->
        val album = i / songsPerAlbum
        Song(i.toLong() + 1, "t$i", i % songsPerAlbum + 1, 2020, 200_000, "/m/a$album/${i % songsPerAlbum}.mp3", 0,
            album.toLong() + 1, "Album $album", album.toLong() + 1, "Artist $album", null, null)
    }

    private fun sameAlbumNeighbours(q: List<Long>, albumOf: (Long) -> Long): Int =
        (1 until q.size).count { albumOf(q[it]) == albumOf(q[it - 1]) }

    private fun assertFullyMixed(order: List<Long>) {
        assertEquals(size, order.size)
        assertEquals("every song exactly once", size, order.toHashSet().size)
        val albumOf = { id: Long -> (id - 1) / songsPerAlbum }
        val pairs = sameAlbumNeighbours(order, albumOf)
        // Expected by chance: (n - 1) * (k - 1) / (n - 1) = k - 1 = 9. Album order would give 90,000.
        assertTrue("same-album neighbours: $pairs", pairs <= 40)
        // The first and last part of the queue are both mixed (no "random, then in order" tail).
        val tail = order.takeLast(1_000)
        assertTrue("tail same-album neighbours", sameAlbumNeighbours(tail, albumOf) <= 10)
    }

    /** The path the Songs, Albums and Artists shuffle buttons take with default settings. */
    @Test
    fun plainShuffleThroughQueueOpener() {
        val lib = library()
        val start = Random(1).nextInt(lib.size)
        val opened = QueueOpener.open(lib, start, true) { list, cur -> ShuffleHelper.makeShuffleList(list, cur) }!!
        assertEquals(lib[start], opened.playing[0])
        assertFullyMixed(opened.playing.map { it.id })
    }

    /** True random through the shuffle engine (used when other shuffle options are set). */
    @Test
    fun engineTrueRandom() {
        val lib = library()
        val tracks = lib.map { ShuffleTrack(it.id, it.duration, it.albumId.toString(), "", it.data, it.trackNumber) }
        val order = ShuffleEngine.order(tracks, 1234, ShuffleConfig(mode = ShuffleMode.TRUE_RANDOM), Random(7))
        assertEquals(1234, order[0])
        assertFullyMixed(order.map { tracks[it].id })
        val noRepeat = ShuffleEngine.orderNoRepeat(
            tracks, 0, (1L..50_000L).toSet(), ShuffleConfig(mode = ShuffleMode.TRUE_RANDOM), Random(8)
        )
        assertEquals(size, noRepeat.toHashSet().size)
    }

    /** The old Albums tab button (albums shuffled, songs in album order) fails the same check. */
    @Test
    fun oldAlbumTabOrderIsDetected() {
        val byAlbum = library().groupBy { it.albumId }.values.shuffled(Random(3)).flatten().map { it.id }
        val pairs = sameAlbumNeighbours(byAlbum) { (it - 1) / songsPerAlbum }
        assertTrue(pairs > 80_000)
    }
}
