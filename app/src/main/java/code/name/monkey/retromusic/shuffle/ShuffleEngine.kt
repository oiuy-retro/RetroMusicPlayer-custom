/*
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */
package code.name.monkey.retromusic.shuffle

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Plain description of one song for the shuffle algorithms. No Android types, so the
 * algorithms can be unit tested with fake songs and a seeded [Random].
 *
 * @param artistKeys normalised artist names; this fork splits multi-artist tags on ';'
 *   so a song can have several. The first entry is the primary artist.
 * @param genreKey null when the song has no genre tag ("Unknown").
 * @param weight base preference score in 0..1 used by [ShuffleMode.WEIGHTED].
 */
data class ShuffleTrack(
    val id: Long,
    val durationMs: Long = 0,
    val albumKey: String = "",
    val folderKey: String = "",
    val path: String = "",
    val trackNumber: Int = 0,
    val artistKeys: List<String> = emptyList(),
    val genreKey: String? = null,
    val weight: Double = 0.0,
) {
    val primaryArtist: String get() = artistKeys.firstOrNull() ?: ""
}

enum class ShuffleMode { TRUE_RANDOM, GROUP_BLOCKS, GENRE_BLOCKS, ARTIST_BLOCKS, SPREAD_ARTISTS, WEIGHTED, SPOTIFY }

enum class BlockGrouping { ALBUM, FOLDER }

data class ShuffleConfig(
    val mode: ShuffleMode = ShuffleMode.TRUE_RANDOM,
    val grouping: BlockGrouping = BlockGrouping.ALBUM,
    val groupMinSongs: Int = 3,
    val groupMaxSongs: Int = 6,
    val groupInOrder: Boolean = true,
    val artistMinSongs: Int = 3,
    val artistMaxSongs: Int = 6,
    val artistInOrder: Boolean = false,
    val genreMinMs: Long = 20 * 60_000L,
    val genreMaxMs: Long = 40 * 60_000L,
    val genreExcludeUnknown: Boolean = false,
    val artistGap: Int = 5,
    /** 0 = no bias (plain random), 10 = strongest bias. */
    val weightStrength: Int = 5,
)

object ShuffleEngine {

    /**
     * Returns a new order as indices into [tracks]. If [current] >= 0 that index is
     * always first. Every index appears exactly once unless a mode filters songs
     * (only Genre blocks with "exclude unknown" does, and never the current song).
     */
    fun order(tracks: List<ShuffleTrack>, current: Int, config: ShuffleConfig, random: Random): List<Int> {
        if (tracks.isEmpty()) return emptyList()
        val cur = if (current in tracks.indices) current else -1
        val rest = tracks.indices.filter { it != cur }
        return orderSubset(tracks, cur, rest, config, random)
    }

    /**
     * Orders [pool] (indices into [tracks], not containing [current]). [current] (or -1)
     * is placed first and is used as the first pick of block modes.
     */
    fun orderSubset(
        tracks: List<ShuffleTrack>,
        current: Int,
        pool: List<Int>,
        config: ShuffleConfig,
        random: Random,
    ): List<Int> {
        val head = if (current >= 0) listOf(current) else emptyList()
        return when (config.mode) {
            ShuffleMode.TRUE_RANDOM -> head + pool.toMutableList().also { it.shuffle(random) }
            ShuffleMode.GROUP_BLOCKS -> {
                val key: (ShuffleTrack) -> String =
                    if (config.grouping == BlockGrouping.ALBUM) { t -> t.albumKey } else { t -> t.folderKey }
                val sort: Comparator<Int> = if (config.grouping == BlockGrouping.ALBUM) {
                    compareBy({ tracks[it].trackNumber }, { tracks[it].path })
                } else {
                    compareBy { tracks[it].path }
                }
                countBlocks(tracks, current, pool, key, sort, config.groupMinSongs, config.groupMaxSongs, config.groupInOrder, random)
            }
            ShuffleMode.ARTIST_BLOCKS -> countBlocks(
                tracks, current, pool, { it.primaryArtist },
                compareBy({ tracks[it].albumKey }, { tracks[it].trackNumber }, { tracks[it].path }),
                config.artistMinSongs, config.artistMaxSongs, config.artistInOrder, random
            )
            ShuffleMode.GENRE_BLOCKS -> genreBlocks(tracks, current, pool, config, random)
            ShuffleMode.SPREAD_ARTISTS -> spreadArtists(tracks, current, pool, config.artistGap, random)
            ShuffleMode.WEIGHTED -> head + weighted(tracks, pool, config.weightStrength, random)
            ShuffleMode.SPOTIFY -> head + spotify(tracks, pool, random)
        }
    }

    private fun randomBetween(a: Int, b: Int, random: Random): Int {
        val lo = max(1, min(a, b)); val hi = max(lo, max(a, b))
        return random.nextInt(lo, hi + 1)
    }

    /**
     * Album/folder/artist blocks: pick a random song, play between min and max songs of its
     * group (all of it if the group is smaller), then pick a random song from a different
     * group. In-order blocks continue forward from the pick in the group's natural order,
     * wrapping to the group's earlier songs if needed.
     */
    internal fun countBlocks(
        tracks: List<ShuffleTrack>,
        current: Int,
        pool: List<Int>,
        key: (ShuffleTrack) -> String,
        naturalOrder: Comparator<Int>,
        minSongs: Int,
        maxSongs: Int,
        inOrder: Boolean,
        random: Random,
    ): List<Int> {
        val groups = LinkedHashMap<String, MutableList<Int>>()
        for (i in pool) groups.getOrPut(key(tracks[i])) { mutableListOf() }.add(i)
        groups.values.forEach { it.sortWith(naturalOrder) }
        val remaining = pool.toMutableList().also { it.shuffle(random) }
        val removed = HashSet<Int>()
        val result = ArrayList<Int>(pool.size + 1)
        var lastGroup: String? = null

        fun take(i: Int) { result.add(i); removed.add(i); groups[key(tracks[i])]?.remove(i) }

        var firstPick = current
        while (result.size < pool.size + (if (current >= 0) 1 else 0)) {
            val pick: Int = if (firstPick >= 0) {
                firstPick.also { firstPick = -1 }
            } else {
                remaining.removeAll { it in removed }
                if (remaining.isEmpty()) break
                remaining.firstOrNull { key(tracks[it]) != lastGroup } ?: remaining.first()
            }
            val g = key(tracks[pick])
            val members = groups[g] ?: mutableListOf()
            // Position of the pick in its group's natural order (before removing it).
            val all = (members + pick).distinct().sortedWith(naturalOrder)
            val pickPos = all.indexOf(pick)
            take(pick)
            val size = randomBetween(minSongs, maxSongs, random)
            val extra = min(size - 1, members.size)
            if (extra > 0) {
                val chosen: List<Int> = if (inOrder) {
                    val after = all.drop(pickPos + 1) + all.take(pickPos)
                    after.take(extra)
                } else {
                    members.shuffled(random).take(extra)
                }
                chosen.forEach { take(it) }
            }
            lastGroup = g
        }
        return result
    }

    /**
     * Genre blocks: pick a random song, keep adding random songs of its genre until the block's
     * total duration (a random target between min and max) is reached. The song that crosses the
     * target is kept whole. Then pick again, preferring a different genre.
     */
    internal fun genreBlocks(
        tracks: List<ShuffleTrack>,
        current: Int,
        pool: List<Int>,
        config: ShuffleConfig,
        random: Random,
    ): List<Int> {
        val unknown = "\u0000unknown"
        val usable = if (config.genreExcludeUnknown) pool.filter { tracks[it].genreKey != null } else pool
        fun g(i: Int) = tracks[i].genreKey ?: unknown
        val groups = HashMap<String, MutableList<Int>>()
        val shuffled = usable.shuffled(random)
        for (i in shuffled) groups.getOrPut(g(i)) { mutableListOf() }.add(i)
        val removed = HashSet<Int>()
        val remaining = shuffled.toMutableList()
        val result = ArrayList<Int>()
        val lo = min(config.genreMinMs, config.genreMaxMs)
        val hi = max(config.genreMinMs, config.genreMaxMs)
        var lastGenre: String? = null
        var firstPick = current
        while (true) {
            val pick: Int = if (firstPick >= 0) {
                firstPick.also { firstPick = -1 }
            } else {
                remaining.removeAll { it in removed }
                if (remaining.isEmpty()) break
                remaining.firstOrNull { g(it) != lastGenre } ?: remaining.first()
            }
            val genre = g(pick)
            val target = if (hi > lo) random.nextLong(lo, hi + 1) else lo
            result.add(pick); removed.add(pick); groups[genre]?.remove(pick)
            var elapsed = tracks[pick].durationMs
            val members = groups[genre] ?: mutableListOf()
            while (elapsed < target && members.isNotEmpty()) {
                val next = members.removeAt(0) // already in random order
                result.add(next); removed.add(next)
                elapsed += tracks[next].durationMs
            }
            lastGenre = genre
        }
        return result
    }

    /**
     * Random order where no artist repeats within [gap] songs. Greedy: take the first song from a
     * shuffled pool that shares no artist with the last [gap] songs. If none fits (small library or
     * one dominant artist), take the song whose last conflicting play is furthest back.
     */
    internal fun spreadArtists(
        tracks: List<ShuffleTrack>,
        current: Int,
        pool: List<Int>,
        gap: Int,
        random: Random,
    ): List<Int> {
        val remaining = pool.shuffled(random).toMutableList()
        val result = ArrayList<Int>(pool.size + 1)
        if (current >= 0) result.add(current)
        val lastSeen = HashMap<String, Int>() // artist -> position in result
        result.forEachIndexed { pos, i -> tracks[i].artistKeys.forEach { lastSeen[it] = pos } }
        while (remaining.isNotEmpty()) {
            val pos = result.size
            fun distance(i: Int): Int = tracks[i].artistKeys
                .mapNotNull { lastSeen[it] }.maxOrNull()?.let { pos - it } ?: Int.MAX_VALUE
            var bestIdx = -1
            var bestDist = -1
            for ((k, i) in remaining.withIndex()) {
                val d = distance(i)
                if (d > gap) { bestIdx = k; break }
                if (d > bestDist) { bestDist = d; bestIdx = k }
            }
            val chosen = remaining.removeAt(bestIdx)
            result.add(chosen)
            tracks[chosen].artistKeys.forEach { lastSeen[it] = pos }
        }
        return result
    }

    /**
     * Weighted random order (Efraimidis-Spirakis sampling without replacement): each song gets the
     * key u^(1/w) with u uniform in (0,1); sorting by key descending makes heavier songs come up
     * earlier more often while every song still appears. w = 1 + strength * weight, so strength 0
     * is plain random and strength 10 makes a top-scored song up to 11 times as likely to be next.
     */
    internal fun weighted(tracks: List<ShuffleTrack>, pool: List<Int>, strength: Int, random: Random): List<Int> {
        val s = strength.coerceIn(0, 10)
        return pool.map { i ->
            val w = 1.0 + s * tracks[i].weight.coerceIn(0.0, 1.0)
            val u = random.nextDouble().coerceAtLeast(Double.MIN_VALUE)
            i to u.pow(1.0 / w)
        }.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Spotify-style shuffle, after Lukáš Poláček, "How to shuffle songs?", Spotify Engineering
     * (Spotify Labs), 28 February 2014:
     * https://engineering.atspotify.com/2014/02/how-to-shuffle-songs/
     * (archived: https://web.archive.org/web/20220215030739/https://engineering.atspotify.com/2014/02/how-to-shuffle-songs/)
     *
     * Inspired by dithering: each artist's songs are shuffled among themselves and then spread as
     * evenly as possible along the line [0, 1). With n songs they are about 1/n apart; each gap
     * varies randomly (here by up to +/-20% of 1/n, so 4 songs land roughly 20-30% apart, as in the
     * post) and each artist gets a random starting offset in [0, 1/n) so first songs don't bunch at
     * the start. All songs are then sorted by position.
     */
    internal fun spotify(tracks: List<ShuffleTrack>, pool: List<Int>, random: Random): List<Int> {
        val byArtist = LinkedHashMap<String, MutableList<Int>>()
        for (i in pool) byArtist.getOrPut(tracks[i].primaryArtist) { mutableListOf() }.add(i)
        val positioned = ArrayList<Pair<Int, Double>>(pool.size)
        for (songs in byArtist.values) {
            songs.shuffle(random)
            val n = songs.size
            val spacing = 1.0 / n
            val offset = random.nextDouble() * spacing
            songs.forEachIndexed { k, i ->
                val jitter = (random.nextDouble() * 0.4 - 0.2) * spacing
                positioned.add(i to offset + k * spacing + jitter)
            }
        }
        return positioned.sortedBy { it.second }.map { it.first }
    }

    /**
     * No repeats: songs whose id is in [played] go after every unplayed song, so nothing repeats
     * until the unplayed ones are used up. Each part is ordered by the active mode. [current] stays
     * first either way.
     */
    fun orderNoRepeat(
        tracks: List<ShuffleTrack>,
        current: Int,
        played: Set<Long>,
        config: ShuffleConfig,
        random: Random,
    ): List<Int> {
        val cur = if (current in tracks.indices) current else -1
        val rest = tracks.indices.filter { it != cur }
        val (seen, fresh) = rest.partition { tracks[it].id in played }
        val first = orderSubset(tracks, cur, fresh, config, random)
        return first + orderSubset(tracks, -1, seen, config, random)
    }

    /**
     * True when the history should be cleared: at least [percent]% of the eligible songs
     * (ids in [eligibleIds]) have already been played.
     */
    fun historyComplete(eligibleIds: Collection<Long>, played: Set<Long>, percent: Int): Boolean {
        if (eligibleIds.isEmpty()) return false
        val count = eligibleIds.count { it in played }
        return count * 100 >= eligibleIds.size * percent.coerceIn(1, 100)
    }
}
