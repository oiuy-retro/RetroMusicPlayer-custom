/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.shuffle

import android.content.SharedPreferences

/** Preference keys and readers for the Shuffle settings screen (pref_shuffle.xml). */
object ShuffleSettings {
    const val MODE = "shuffle_mode_type"
    const val GROUPING = "shuffle_group_by"
    const val GROUP_MIN = "shuffle_group_min"
    const val GROUP_MAX = "shuffle_group_max"
    const val GROUP_ORDER = "shuffle_group_order"
    // Existing keys are reused, so values saved by the previous version carry over.
    const val GROUP_USE_SONGS = "shuffle_group_use_songs"
    const val GROUP_USE_TIME = "shuffle_group_use_time"
    const val GROUP_MIN_MINUTES = "shuffle_group_min_minutes"
    const val GROUP_MAX_MINUTES = "shuffle_group_max_minutes"
    const val GENRE_USE_SONGS = "shuffle_genre_use_songs"
    const val GENRE_USE_TIME = "shuffle_genre_use_time"
    const val GENRE_MIN_SONGS = "shuffle_genre_min"
    const val GENRE_MAX_SONGS = "shuffle_genre_max"
    const val GENRE_MIN = "shuffle_genre_min_minutes"
    const val GENRE_MAX = "shuffle_genre_max_minutes"
    const val ARTIST_USE_SONGS = "shuffle_artist_use_songs"
    const val ARTIST_USE_TIME = "shuffle_artist_use_time"
    const val ARTIST_MIN_MINUTES = "shuffle_artist_min_minutes"
    const val ARTIST_MAX_MINUTES = "shuffle_artist_max_minutes"
    const val GENRE_EXCLUDE_UNKNOWN = "shuffle_genre_exclude_unknown"
    const val ARTIST_MIN = "shuffle_artist_min"
    const val ARTIST_MAX = "shuffle_artist_max"
    const val ARTIST_ORDER = "shuffle_artist_order"
    const val ARTIST_GAP = "shuffle_artist_gap"
    const val WEIGHT_BY = "shuffle_weight_by"
    const val WEIGHT_STRENGTH = "shuffle_weight_strength"
    const val NO_REPEATS = "shuffle_no_repeats"
    const val NO_REPEATS_PERCENT = "shuffle_no_repeats_percent"
    const val RESET_HISTORY = "shuffle_reset_history"
    const val RESHUFFLE_ON_LOOP = "shuffle_reshuffle_on_loop"
    const val MIN_LENGTH = "shuffle_min_length_seconds"
    const val SKIP_COUNTS = "shuffle_skip_counts_as_played"

    const val ORDER_IN_ORDER = "in_order"
    const val WEIGHT_FAVORITES = "favorites"
    const val WEIGHT_MOST_PLAYED = "most_played"
    const val WEIGHT_LEAST_PLAYED = "least_played"

    fun mode(p: SharedPreferences): ShuffleMode = when (p.getString(MODE, "true_random")) {
        "group_blocks" -> ShuffleMode.GROUP_BLOCKS
        "genre_blocks" -> ShuffleMode.GENRE_BLOCKS
        "artist_blocks" -> ShuffleMode.ARTIST_BLOCKS
        "spread_artists" -> ShuffleMode.SPREAD_ARTISTS
        "weighted" -> ShuffleMode.WEIGHTED
        "spotify" -> ShuffleMode.SPOTIFY
        else -> ShuffleMode.TRUE_RANDOM
    }

    fun config(p: SharedPreferences) = ShuffleConfig(
        mode = mode(p),
        grouping = if (p.getString(GROUPING, "album") == "folder") BlockGrouping.FOLDER else BlockGrouping.ALBUM,
        groupLimits = limits(p, GROUP_USE_SONGS, true, GROUP_MIN, GROUP_MAX, 2, 4,
            GROUP_USE_TIME, false, GROUP_MIN_MINUTES, GROUP_MAX_MINUTES, 7, 15),
        groupInOrder = p.getString(GROUP_ORDER, ORDER_IN_ORDER) == ORDER_IN_ORDER,
        artistLimits = limits(p, ARTIST_USE_SONGS, true, ARTIST_MIN, ARTIST_MAX, 2, 4,
            ARTIST_USE_TIME, false, ARTIST_MIN_MINUTES, ARTIST_MAX_MINUTES, 7, 15),
        artistInOrder = p.getString(ARTIST_ORDER, "random") == ORDER_IN_ORDER,
        genreLimits = limits(p, GENRE_USE_SONGS, false, GENRE_MIN_SONGS, GENRE_MAX_SONGS, 2, 4,
            GENRE_USE_TIME, true, GENRE_MIN, GENRE_MAX, 7, 15),
        genreExcludeUnknown = p.getBoolean(GENRE_EXCLUDE_UNKNOWN, false),
        artistGap = p.getInt(ARTIST_GAP, 5),
        weightStrength = p.getInt(WEIGHT_STRENGTH, 5),
    )

    private fun limits(
        p: SharedPreferences,
        useSongsKey: String, useSongsDef: Boolean, minKey: String, maxKey: String, minDef: Int, maxDef: Int,
        useTimeKey: String, useTimeDef: Boolean, minMinKey: String, maxMinKey: String, minMinDef: Int, maxMinDef: Int,
    ) = BlockLimits(
        useSongs = p.getBoolean(useSongsKey, useSongsDef),
        minSongs = p.getInt(minKey, minDef),
        maxSongs = p.getInt(maxKey, maxDef),
        useTime = p.getBoolean(useTimeKey, useTimeDef),
        minMs = p.getInt(minMinKey, minMinDef) * 60_000L,
        maxMs = p.getInt(maxMinKey, maxMinDef) * 60_000L,
    )

    fun weightBy(p: SharedPreferences): String = p.getString(WEIGHT_BY, WEIGHT_FAVORITES) ?: WEIGHT_FAVORITES
    fun noRepeats(p: SharedPreferences) = p.getBoolean(NO_REPEATS, false)
    fun noRepeatsPercent(p: SharedPreferences) = p.getInt(NO_REPEATS_PERCENT, 100)
    fun reshuffleOnLoop(p: SharedPreferences) = p.getBoolean(RESHUFFLE_ON_LOOP, false)
    fun minLengthSeconds(p: SharedPreferences) = p.getInt(MIN_LENGTH, 0)
    fun skipCountsAsPlayed(p: SharedPreferences) = p.getBoolean(SKIP_COUNTS, false)

    /** True when every option is at its default, i.e. the original shuffle should run unchanged. */
    fun isPlainShuffle(p: SharedPreferences) =
        mode(p) == ShuffleMode.TRUE_RANDOM && !noRepeats(p) && minLengthSeconds(p) == 0
}
