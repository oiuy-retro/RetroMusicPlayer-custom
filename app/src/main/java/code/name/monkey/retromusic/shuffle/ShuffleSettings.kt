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
    const val GENRE_MIN = "shuffle_genre_min_minutes"
    const val GENRE_MAX = "shuffle_genre_max_minutes"
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
        groupMinSongs = p.getInt(GROUP_MIN, 3),
        groupMaxSongs = p.getInt(GROUP_MAX, 6),
        groupInOrder = p.getString(GROUP_ORDER, ORDER_IN_ORDER) == ORDER_IN_ORDER,
        artistMinSongs = p.getInt(ARTIST_MIN, 3),
        artistMaxSongs = p.getInt(ARTIST_MAX, 6),
        artistInOrder = p.getString(ARTIST_ORDER, "random") == ORDER_IN_ORDER,
        genreMinMs = p.getInt(GENRE_MIN, 20) * 60_000L,
        genreMaxMs = p.getInt(GENRE_MAX, 40) * 60_000L,
        genreExcludeUnknown = p.getBoolean(GENRE_EXCLUDE_UNKNOWN, false),
        artistGap = p.getInt(ARTIST_GAP, 5),
        weightStrength = p.getInt(WEIGHT_STRENGTH, 5),
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
