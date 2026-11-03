package code.name.monkey.retromusic.shuffle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleEngineTest {

    /** 8 albums x 7 songs, 6 artists, 4 genres (one untagged), 3-minute songs. */
    private val library: List<ShuffleTrack> = (0 until 56).map { i ->
        val album = i / 7
        ShuffleTrack(
            id = 1000L + i,
            durationMs = 180_000,
            albumKey = "album$album",
            folderKey = "/music/folder${album % 4}",
            path = "/music/folder${album % 4}/album$album/${i % 7}.mp3",
            trackNumber = i % 7 + 1,
            artistKeys = listOf("artist${album % 6}"),
            genreKey = listOf("rock", "jazz", "country", null)[album % 4],
            weight = if (i % 5 == 0) 1.0 else 0.0,
        )
    }

    private fun assertPermutation(order: List<Int>, size: Int = library.size) {
        assertEquals(size, order.size)
        assertEquals((0 until size).toSet(), order.toSet())
    }

    private fun allModes() = ShuffleMode.values().map { ShuffleConfig(mode = it) }

    @Test
    fun trueRandomMatchesOriginalAlgorithm() {
        for (seed in 0 until 20) {
            val current = seed % library.size
            val engine = ShuffleEngine.order(library, current, ShuffleConfig(), Random(seed))
            // The original ShuffleHelper: remove current, shuffle the rest, put current first.
            val old = library.indices.toMutableList()
            val song = old.removeAt(current)
            old.shuffle(Random(seed))
            old.add(0, song)
            assertEquals(old, engine)
        }
    }

    @Test
    fun currentSongStaysFirstInEveryMode() {
        for (config in allModes()) for (seed in 0 until 10) {
            val current = (seed * 7) % library.size
            val order = ShuffleEngine.order(library, current, config, Random(seed))
            assertEquals("mode ${config.mode}", current, order.first())
            assertPermutation(order)
        }
    }

    @Test
    fun everyModeIsAPermutationWithoutCurrent() {
        for (config in allModes()) for (seed in 0 until 10) {
            assertPermutation(ShuffleEngine.order(library, -1, config, Random(seed)))
        }
    }

    private fun blocks(order: List<Int>, key: (ShuffleTrack) -> String): List<List<Int>> {
        val out = mutableListOf<MutableList<Int>>()
        for (i in order) {
            if (out.isEmpty() || key(library[out.last().last()]) != key(library[i])) out.add(mutableListOf())
            out.last().add(i)
        }
        return out
    }

    @Test
    fun albumBlockSizesStayWithinMinMax() {
        val config = ShuffleConfig(mode = ShuffleMode.GROUP_BLOCKS, groupMinSongs = 2, groupMaxSongs = 4)
        for (seed in 0 until 50) {
            val order = ShuffleEngine.order(library, 3, config, Random(seed))
            assertPermutation(order)
            // Consecutive blocks from one album can merge only when no other album is left,
            // so check every run except the trailing ones.
            val runs = blocks(order) { it.albumKey }
            runs.dropLast(2).forEach { assertTrue("run ${it.size}", it.size in 1..4) }
        }
    }

    @Test
    fun blockNeverExceedsMaxAndRespectsMinWhenPossible() {
        val config = ShuffleConfig(mode = ShuffleMode.GROUP_BLOCKS, groupMinSongs = 3, groupMaxSongs = 3)
        val order = ShuffleEngine.order(library, -1, config, Random(1))
        val runs = blocks(order) { it.albumKey }
        // 56 songs, albums of 7: every block is 3 except the 1-song remainders of each album.
        runs.dropLast(2).forEach { assertTrue(it.size == 3 || it.size == 1) }
    }

    @Test
    fun inOrderBlocksFollowTrackNumbers() {
        val config = ShuffleConfig(mode = ShuffleMode.GROUP_BLOCKS, groupMinSongs = 7, groupMaxSongs = 7, groupInOrder = true)
        val order = ShuffleEngine.order(library, 2, config, Random(5))
        // Current is album0 track 3: the block continues 4,5,6,7 then wraps to 1,2.
        assertEquals(listOf(2, 3, 4, 5, 6, 0, 1), order.take(7))
    }

    @Test
    fun folderGroupingUsesFolders() {
        val config = ShuffleConfig(mode = ShuffleMode.GROUP_BLOCKS, grouping = BlockGrouping.FOLDER, groupMinSongs = 10, groupMaxSongs = 10)
        val order = ShuffleEngine.order(library, -1, config, Random(3))
        val first = order.take(10).map { library[it].folderKey }.toSet()
        assertEquals(1, first.size)
    }

    @Test
    fun artistBlocksStayWithinMax() {
        val config = ShuffleConfig(mode = ShuffleMode.ARTIST_BLOCKS, artistMinSongs = 2, artistMaxSongs = 5)
        for (seed in 0 until 30) {
            val order = ShuffleEngine.order(library, -1, config, Random(seed))
            assertPermutation(order)
            blocks(order) { it.primaryArtist }.dropLast(2).forEach { assertTrue(it.size in 1..5) }
        }
    }

    @Test
    fun genreBlocksLastBetweenMinAndMaxTime() {
        val config = ShuffleConfig(mode = ShuffleMode.GENRE_BLOCKS, genreMinMs = 9 * 60_000L, genreMaxMs = 12 * 60_000L)
        for (seed in 0 until 30) {
            val order = ShuffleEngine.order(library, -1, config, Random(seed))
            assertPermutation(order)
            // 3-minute songs: each block needs 3 or 4 songs to reach 9-12 minutes.
            blocks(order) { it.genreKey ?: "?" }.dropLast(2).forEach { assertTrue("${it.size}", it.size in 1..4) }
        }
    }

    @Test
    fun genreBlocksCanExcludeUnknown() {
        val config = ShuffleConfig(mode = ShuffleMode.GENRE_BLOCKS, genreExcludeUnknown = true)
        val order = ShuffleEngine.order(library, -1, config, Random(2))
        assertTrue(order.none { library[it].genreKey == null })
        assertEquals(library.count { it.genreKey != null }, order.size)
    }

    @Test
    fun spreadArtistsKeepsGap() {
        val gap = 5 // 6 artists with equal counts: a gap of 5 is always possible
        val config = ShuffleConfig(mode = ShuffleMode.SPREAD_ARTISTS, artistGap = gap)
        // Use one song per album so artists are balanced (8 albums -> some artists twice).
        for (seed in 0 until 30) {
            val order = ShuffleEngine.order(library, 0, config, Random(seed))
            assertPermutation(order)
            val artists = order.map { library[it].primaryArtist }
            // Until the pool runs low the gap must hold for the first 30 songs.
            for (p in 0 until 30) for (q in p + 1..minOf(p + gap, 29)) {
                assertTrue("seed $seed at $p,$q", artists[p] != artists[q])
            }
        }
    }

    @Test
    fun spreadArtistsFallsBackWithTinyLibrary() {
        val tiny = listOf("a", "a", "a", "b").mapIndexed { i, a -> ShuffleTrack(id = i.toLong(), artistKeys = listOf(a)) }
        val order = ShuffleEngine.order(tiny, -1, ShuffleConfig(mode = ShuffleMode.SPREAD_ARTISTS, artistGap = 10), Random(0))
        assertEquals(4, order.size)
        assertEquals(setOf(0, 1, 2, 3), order.toSet())
    }

    @Test
    fun noRepeatsPutsPlayedSongsLast() {
        val played = library.take(40).map { it.id }.toSet()
        for (config in allModes()) {
            val order = ShuffleEngine.orderNoRepeat(library, 50, played, config, Random(4))
            assertPermutation(order)
            assertEquals(50, order.first())
            val unplayedCount = library.size - 40 - 1
            assertTrue(order.drop(1).take(unplayedCount).none { library[it].id in played })
        }
    }

    @Test
    fun historyCompleteHonoursPercent() {
        val ids = library.map { it.id }
        assertTrue(!ShuffleEngine.historyComplete(ids, ids.take(27).toSet(), 50))
        assertTrue(ShuffleEngine.historyComplete(ids, ids.take(28).toSet(), 50))
        assertTrue(!ShuffleEngine.historyComplete(ids, ids.take(55).toSet(), 100))
        assertTrue(ShuffleEngine.historyComplete(ids, ids.toSet(), 100))
    }

    @Test
    fun weightedFavoursHeavySongs() {
        val config = ShuffleConfig(mode = ShuffleMode.WEIGHTED, weightStrength = 10)
        var heavyInTop = 0
        repeat(200) { seed ->
            val order = ShuffleEngine.order(library, -1, config, Random(seed))
            heavyInTop += order.take(10).count { library[it].weight > 0 }
        }
        // 12 of 56 songs are heavy (21%); with 11x weight they should fill well over that share.
        assertTrue("heavy share $heavyInTop", heavyInTop > 200 * 10 * 0.4)
    }

    @Test
    fun spotifySpreadsArtists() {
        val config = ShuffleConfig(mode = ShuffleMode.SPOTIFY)
        var adjacent = 0
        var adjacentRandom = 0
        repeat(100) { seed ->
            val a = ShuffleEngine.order(library, -1, config, Random(seed)).map { library[it].primaryArtist }
            val b = ShuffleEngine.order(library, -1, ShuffleConfig(), Random(seed)).map { library[it].primaryArtist }
            adjacent += a.zipWithNext().count { it.first == it.second }
            adjacentRandom += b.zipWithNext().count { it.first == it.second }
        }
        assertTrue("spotify $adjacent vs random $adjacentRandom", adjacent < adjacentRandom / 2)
    }
}
