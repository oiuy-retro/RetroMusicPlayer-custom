package code.name.monkey.retromusic.shuffle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShufflePercentLimitTest {

    /** Albums of the given sizes, 3-minute songs. */
    private fun library(vararg sizes: Int): List<ShuffleTrack> {
        val out = ArrayList<ShuffleTrack>()
        sizes.forEachIndexed { a, n ->
            repeat(n) { t ->
                out += ShuffleTrack(id = out.size.toLong(), durationMs = 180_000, albumKey = "a$a",
                    folderKey = "/f$a", path = "/f$a/$t.mp3", trackNumber = t + 1, artistKeys = listOf("x$a"))
            }
        }
        return out
    }

    private fun config(l: BlockLimits) = ShuffleConfig(mode = ShuffleMode.GROUP_BLOCKS, groupLimits = l, groupInOrder = true)

    /** First block size when starting from [start]. */
    private fun firstBlock(lib: List<ShuffleTrack>, start: Int, l: BlockLimits, seed: Int = 1): Int {
        val order = ShuffleEngine.order(lib, start, config(l), Random(seed))
        assertEquals(lib.size, order.toSet().size)
        val album = lib[start].albumKey
        return order.takeWhile { lib[it].albumKey == album }.size
    }

    @Test fun roundsUpToAtLeastOneSong() {
        assertEquals(1, ShuffleEngine.percentToSongs(1, 1))
        assertEquals(1, ShuffleEngine.percentToSongs(3, 25))
        assertEquals(2, ShuffleEngine.percentToSongs(3, 34))
        assertEquals(3, ShuffleEngine.percentToSongs(10, 25))
        assertEquals(10, ShuffleEngine.percentToSongs(10, 100))
    }

    @Test fun percentOnly() {
        // 20-song album, exactly 25 %: blocks of 5. Song and time limits off.
        val lib = library(20, 20, 20)
        val l = BlockLimits(useSongs = false, useTime = false, usePercent = true, minPercent = 25, maxPercent = 25)
        for (seed in 0 until 10) assertEquals(5, firstBlock(lib, 0, l, seed))
    }

    @Test fun percentPlusSongsWhicheverFirst() {
        val lib = library(20, 20, 20)
        // 50 % = 10 songs, song limit 3: songs win.
        assertEquals(3, firstBlock(lib, 0, BlockLimits(useSongs = true, minSongs = 3, maxSongs = 3,
            usePercent = true, minPercent = 50, maxPercent = 50)))
        // 10 % = 2 songs, song limit 8: percent wins.
        assertEquals(2, firstBlock(lib, 0, BlockLimits(useSongs = true, minSongs = 8, maxSongs = 8,
            usePercent = true, minPercent = 10, maxPercent = 10)))
        // Time also on: 7 minutes = 3 songs of 3 minutes, before 50 % (10) and 8 songs.
        assertEquals(3, firstBlock(lib, 0, BlockLimits(useSongs = true, minSongs = 8, maxSongs = 8,
            useTime = true, minMs = 7 * 60_000L, maxMs = 7 * 60_000L, usePercent = true, minPercent = 50, maxPercent = 50)))
    }

    @Test fun smallAlbumsRoundUp() {
        // 3-song albums at 10 %: still 1 song per block, never 0.
        val lib = library(3, 3, 3, 3)
        val l = BlockLimits(useSongs = false, usePercent = true, minPercent = 10, maxPercent = 10)
        val order = ShuffleEngine.order(lib, -1, config(l), Random(4))
        assertEquals(12, order.toSet().size)
        var runs = 0; var last = ""
        for (i in order) { if (lib[i].albumKey != last) runs++; last = lib[i].albumKey }
        assertTrue("runs $runs", runs >= 9)
    }

    @Test fun hundredPercentPlaysWholeAlbum() {
        val lib = library(7, 12, 5)
        val l = BlockLimits(useSongs = false, usePercent = true, minPercent = 100, maxPercent = 100)
        assertEquals(7, firstBlock(lib, 0, l))
        assertEquals(12, firstBlock(lib, 9, l))
        assertEquals(5, firstBlock(lib, 20, l))
    }

    @Test fun percentRangeIsPickedPerBlock() {
        val lib = library(20, 20, 20, 20, 20, 20)
        val l = BlockLimits(useSongs = false, usePercent = true, minPercent = 10, maxPercent = 30)
        val sizes = mutableSetOf<Int>()
        for (seed in 0 until 20) {
            val order = ShuffleEngine.order(lib, -1, config(l), Random(seed))
            var n = 0; var last = ""
            val runs = mutableListOf<Int>()
            for (i in order) { if (lib[i].albumKey == last) n++ else { if (n > 0) runs += n; n = 1 }; last = lib[i].albumKey }
            runs.dropLast(1).forEach { assertTrue("$it", it <= 6); sizes += it }
        }
        assertTrue(sizes.containsAll(listOf(2, 6)))
    }

    @Test fun allLimitsOffFallsBackToSongs() {
        val lib = library(20, 20)
        assertEquals(2, firstBlock(lib, 0, BlockLimits(useSongs = false, minSongs = 2, maxSongs = 2,
            useTime = false, usePercent = false)))
    }

    @Test fun defaultsAreOff25To50() {
        val d = BlockLimits()
        assertEquals(false, d.usePercent); assertEquals(25, d.minPercent); assertEquals(50, d.maxPercent)
    }
}
