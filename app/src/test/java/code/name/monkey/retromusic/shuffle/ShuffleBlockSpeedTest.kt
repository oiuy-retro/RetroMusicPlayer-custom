package code.name.monkey.retromusic.shuffle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Big library with tiny blocks (time + percent limits): used to be O(n^2) and froze the app. */
class ShuffleBlockSpeedTest {

    private fun library(folders: Int, perFolder: Int, durationMs: Long) = (0 until folders * perFolder).map { i ->
        val f = i / perFolder
        ShuffleTrack(id = i.toLong(), durationMs = durationMs, albumKey = "a$f", folderKey = "/f$f",
            path = "/f$f/${i % perFolder}.mp3", trackNumber = i % perFolder + 1, artistKeys = listOf("x$f"))
    }

    private fun run(lib: List<ShuffleTrack>, grouping: BlockGrouping, inOrder: Boolean, limits: BlockLimits) {
        val cfg = ShuffleConfig(mode = ShuffleMode.GROUP_BLOCKS, grouping = grouping, groupLimits = limits, groupInOrder = inOrder)
        val t0 = System.nanoTime()
        val order = ShuffleEngine.order(lib, 0, cfg, Random(7))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(lib.size, order.size)
        assertEquals(lib.size, order.toSet().size)
        assertEquals(0, order.first())
        assertTrue("took $ms ms", ms < 3000)
    }

    private val tiny = BlockLimits(useSongs = false, useTime = true, minMs = 60_000, maxMs = 60_000,
        usePercent = true, minPercent = 1, maxPercent = 1)

    @Test fun oneHugeFolderTimeAndPercent() {
        val lib = library(1, 60_000, 600_000) // every song is longer than the time target
        run(lib, BlockGrouping.FOLDER, true, tiny)
        run(lib, BlockGrouping.FOLDER, false, tiny)
    }

    @Test fun manyFoldersTimeAndPercent() {
        val lib = library(3_000, 20, 600_000)
        run(lib, BlockGrouping.ALBUM, true, tiny)
        run(lib, BlockGrouping.FOLDER, false, tiny)
    }

    @Test fun zeroDurationSongsTimeOnly() {
        val lib = library(200, 50, 0) // unknown lengths never reach a time target; block = whole group
        run(lib, BlockGrouping.ALBUM, true, tiny.copy(usePercent = false))
    }
}
