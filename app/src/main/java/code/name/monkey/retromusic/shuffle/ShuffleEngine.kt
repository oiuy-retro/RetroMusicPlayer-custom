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

/**
 * When a block ends. Each limit has its own switch. With both on, the block ends at whichever
 * target is reached first. With both off, the song limit is used (so a block always ends).
 * A random target is picked between min and max for each limit, once per block.
 */
data class BlockLimits(
    val useSongs: Boolean = true,
    val minSongs: Int = 2,
    val maxSongs: Int = 4,
    val useTime: Boolean = false,
    val minMs: Long = 7 * 60_000L,
    val maxMs: Long = 15 * 60_000L,
    /** Percent of the group (album/folder) size; only offered for Folder/album blocks. */
    val usePercent: Boolean = false,
    val minPercent: Int = 25,
    val maxPercent: Int = 50,
) {
    /** Same limits with every min/max pair in order (swapped if saved inverted). */
    fun normalized(): BlockLimits = copy(
        minSongs = min(minSongs, maxSongs), maxSongs = max(minSongs, maxSongs),
        minMs = min(minMs, maxMs), maxMs = max(minMs, maxMs),
        minPercent = min(minPercent, maxPercent), maxPercent = max(minPercent, maxPercent),
    )
}

data class ShuffleConfig(
    val mode: ShuffleMode = ShuffleMode.TRUE_RANDOM,
    val grouping: BlockGrouping = BlockGrouping.ALBUM,
    val groupLimits: BlockLimits = BlockLimits(),
    val groupInOrder: Boolean = true,
    val artistLimits: BlockLimits = BlockLimits(),
    val artistInOrder: Boolean = false,
    val genreLimits: BlockLimits = BlockLimits(useSongs = false, useTime = true),
    val genreExcludeUnknown: Boolean = false,
    val artistGap: Int = 5,
    /** 0 = no bias (plain random), 10 = strongest bias. */
    val weightStrength: Int = 5,
)

object ShuffleEngine {

    /** How many songs block modes look past to find one from a different group. */
    private const val LOOKAHEAD = 64

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
                countBlocks(tracks, current, pool, key, sort, config.groupLimits, config.groupInOrder, random)
            }
            ShuffleMode.ARTIST_BLOCKS -> countBlocks(
                tracks, current, pool, { it.primaryArtist },
                compareBy({ tracks[it].albumKey }, { tracks[it].trackNumber }, { tracks[it].path }),
                config.artistLimits, config.artistInOrder, random
            )
            ShuffleMode.GENRE_BLOCKS -> {
                // Genre blocks pick block members at random. "Exclude unknown" drops untagged songs
                // (never the current one).
                val usable = if (config.genreExcludeUnknown) pool.filter { tracks[it].genreKey != null } else pool
                countBlocks(tracks, current, usable, { it.genreKey ?: "\u0000unknown" },
                    compareBy { tracks[it].path }, config.genreLimits, false, random)
            }
            ShuffleMode.SPREAD_ARTISTS -> spreadArtists(tracks, current, pool, config.artistGap, random)
            ShuffleMode.WEIGHTED -> head + weighted(tracks, pool, config.weightStrength, random)
            ShuffleMode.SPOTIFY -> head + spotify(tracks, pool, random)
        }
    }

    /** [percent] of a group of [size] songs, rounded up, at least 1. */
    internal fun percentToSongs(size: Int, percent: Int): Int =
        max(1, (size.toLong() * percent + 99).div(100).toInt())

    private fun randomBetweenMs(a: Long, b: Long, random: Random): Long {
        val lo = max(0L, min(a, b)); val hi = max(lo, max(a, b))
        return if (hi > lo) random.nextLong(lo, hi + 1) else lo
    }

    private fun randomBetween(a: Int, b: Int, random: Random): Int {
        val lo = max(1, min(a, b)); val hi = max(lo, max(a, b))
        return random.nextInt(lo, hi + 1)
    }

    /**
     * Album/folder/artist/genre blocks: pick a random song, then keep adding songs of its group
     * until the block reaches its song target or its time target, whichever comes first (all of
     * the group if it is smaller). Time counts song lengths; the song that crosses the time target
     * is kept whole. Then pick a random song from a different group. In-order blocks continue forward from the pick in the group's natural order,
     * wrapping to the group's earlier songs if needed.
     */
    internal fun countBlocks(
        tracks: List<ShuffleTrack>,
        current: Int,
        pool: List<Int>,
        key: (ShuffleTrack) -> String,
        naturalOrder: Comparator<Int>,
        limits: BlockLimits,
        inOrder: Boolean,
        random: Random,
    ): List<Int> {
        // Runs in O(n log n): every structure below is walked forward once or queried by TreeSet,
        // so big libraries with tiny blocks (e.g. 1-song percent/time blocks) stay fast.
        val groupAll = LinkedHashMap<String, MutableList<Int>>()
        for (i in pool) groupAll.getOrPut(key(tracks[i])) { mutableListOf() }.add(i)
        if (current >= 0) groupAll.getOrPut(key(tracks[current])) { mutableListOf() }.add(current)
        groupAll.values.forEach { it.sortWith(naturalOrder) }
        val posInGroup = HashMap<Int, Int>()
        val left = HashMap<String, java.util.TreeSet<Int>>() // positions not yet taken, per group
        groupAll.forEach { (g, list) ->
            list.forEachIndexed { p, i -> posInGroup[i] = p }
            left[g] = java.util.TreeSet<Int>().also { set -> list.indices.forEach { set.add(it) } }
        }
        val shuffledGroup = HashMap<String, MutableList<Int>>()
        val shufPtr = HashMap<String, Int>()
        if (!inOrder) groupAll.forEach { (g, list) -> shuffledGroup[g] = list.shuffled(random).toMutableList(); shufPtr[g] = 0 }
        val remaining = pool.toMutableList().also { it.shuffle(random) }
        var ptr = 0
        val removed = HashSet<Int>()
        val total = pool.size + (if (current >= 0) 1 else 0)
        val result = ArrayList<Int>(total)
        var lastGroup: String? = null

        fun take(i: Int) {
            result.add(i); removed.add(i)
            left[key(tracks[i])]?.remove(posInGroup[i] ?: -1)
        }

        var firstPick = current
        while (result.size < total) {
            val pick: Int = if (firstPick >= 0) {
                firstPick.also { firstPick = -1 }
            } else {
                while (ptr < remaining.size && remaining[ptr] in removed) ptr++
                if (ptr >= remaining.size) break
                // Prefer a song from a different group; look ahead a bounded number of songs.
                var chosen = remaining[ptr]
                if (key(tracks[chosen]) == lastGroup) {
                    var j = ptr + 1; var seen = 0
                    while (j < remaining.size && seen < LOOKAHEAD) {
                        val c = remaining[j]
                        if (c !in removed) {
                            if (key(tracks[c]) != lastGroup) { chosen = c; break }
                            seen++
                        }
                        j++
                    }
                }
                chosen
            }
            val g = key(tracks[pick])
            val all = groupAll[g] ?: listOf(pick)
            val pickPos = posInGroup[pick] ?: 0
            val groupLeft = left[g] ?: java.util.TreeSet()
            take(pick)
            // If every limit is off, the song limit is used.
            val useSongs = limits.useSongs || (!limits.useTime && !limits.usePercent)
            var songTarget = if (useSongs) randomBetween(limits.minSongs, limits.maxSongs, random) else Int.MAX_VALUE
            if (limits.usePercent) {
                val pct = randomBetween(limits.minPercent.coerceIn(1, 100), limits.maxPercent.coerceIn(1, 100), random)
                songTarget = min(songTarget, percentToSongs(all.size, pct))
            }
            val timeTarget = if (limits.useTime) randomBetweenMs(limits.minMs, limits.maxMs, random) else Long.MAX_VALUE
            var count = 1
            var elapsed = tracks[pick].durationMs
            // Add a song only while the block is below both targets: whichever is reached first ends it.
            while (count < songTarget && elapsed < timeTarget && groupLeft.isNotEmpty()) {
                val next: Int = if (inOrder) {
                    all[groupLeft.ceiling(pickPos + 1) ?: groupLeft.first()]
                } else {
                    val list = shuffledGroup[g] ?: break
                    var k = shufPtr[g] ?: 0
                    while (k < list.size && list[k] in removed) k++
                    shufPtr[g] = k
                    if (k >= list.size) break
                    list[k]
                }
                take(next)
                count++
                elapsed += tracks[next].durationMs
            }
            lastGroup = g
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
