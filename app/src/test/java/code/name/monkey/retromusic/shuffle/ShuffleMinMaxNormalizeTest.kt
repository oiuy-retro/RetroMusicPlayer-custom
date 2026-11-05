package code.name.monkey.retromusic.shuffle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleMinMaxNormalizeTest {

    @Test
    fun invertedPairsAreSwapped() {
        val n = BlockLimits(minSongs = 9, maxSongs = 3, minMs = 20 * 60_000L, maxMs = 5 * 60_000L,
            minPercent = 80, maxPercent = 10).normalized()
        assertEquals(3, n.minSongs); assertEquals(9, n.maxSongs)
        assertEquals(5 * 60_000L, n.minMs); assertEquals(20 * 60_000L, n.maxMs)
        assertEquals(10, n.minPercent); assertEquals(80, n.maxPercent)
    }

    @Test
    fun orderedAndEqualPairsAreUnchanged() {
        val l = BlockLimits(minSongs = 4, maxSongs = 4, minPercent = 25, maxPercent = 50)
        assertEquals(l, l.normalized())
        assertEquals(BlockLimits(), BlockLimits().normalized())
    }

    @Test
    fun invertedLimitsDoNotCrashAndStayInRange() {
        val lib = (0 until 40).map { i ->
            ShuffleTrack(id = i.toLong(), durationMs = 180_000, albumKey = "a${i / 20}", folderKey = "/f${i / 20}",
                path = "/f${i / 20}/$i.mp3", trackNumber = i % 20 + 1, artistKeys = listOf("x"))
        }
        val inverted = BlockLimits(minSongs = 5, maxSongs = 2, usePercent = true, minPercent = 90, maxPercent = 5,
            useTime = true, minMs = 30 * 60_000L, maxMs = 1 * 60_000L)
        for (seed in 0 until 20) {
            val cfg = ShuffleConfig(mode = ShuffleMode.GROUP_BLOCKS, groupLimits = inverted, groupInOrder = true)
            val order = ShuffleEngine.order(lib, 0, cfg, Random(seed))
            assertEquals(lib.size, order.toSet().size)
            val first = order.takeWhile { lib[it].albumKey == "a0" }.size
            assertTrue("block $first", first in 1..5)
        }
    }
}
