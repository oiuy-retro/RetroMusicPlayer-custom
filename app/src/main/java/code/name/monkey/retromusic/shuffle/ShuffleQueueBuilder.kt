/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.shuffle

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.helper.ShuffleHelper
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.providers.SongPlayCountStore
import code.name.monkey.retromusic.repository.Repository
import code.name.monkey.retromusic.util.ArtistSeparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.koin.java.KoinJavaComponent.get
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Android side of the shuffle modes: reads the settings and library data (genres, favorites,
 * play counts, the no-repeat history), turns songs into [ShuffleTrack]s and reorders the queue
 * with [ShuffleEngine]. With default settings it runs the original [ShuffleHelper] code, so
 * "True random" behaves exactly as before.
 */
object ShuffleQueueBuilder {

    fun shuffle(context: Context, list: MutableList<Song>, current: Int) {
        if (list.isEmpty()) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (ShuffleSettings.isPlainShuffle(prefs)) {
            ShuffleHelper.makeShuffleList(list, current)
            return
        }
        val result = runCatching { build(context, list, current) }.getOrNull()
        if (result == null) {
            ShuffleHelper.makeShuffleList(list, current)
            return
        }
        list.clear()
        list.addAll(result)
    }

    private fun build(context: Context, songs: List<Song>, current: Int): List<Song> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val config = ShuffleSettings.config(prefs)
        val cur = if (current in songs.indices) current else -1

        // Skip short songs (never the current one).
        val minMs = ShuffleSettings.minLengthSeconds(prefs) * 1000L
        val kept = songs.filterIndexed { i, s -> i == cur || minMs <= 0 || s.duration >= minMs }
        val keptCur = if (cur >= 0) kept.indexOf(songs[cur]) else -1

        val genres = if (config.mode == ShuffleMode.GENRE_BLOCKS) genreMap(context) else emptyMap()
        val weight: (Long) -> Double =
            if (config.mode == ShuffleMode.WEIGHTED) weights(context, ShuffleSettings.weightBy(prefs)) else { _ -> 0.0 }
        val tracks = kept.map { s ->
            ShuffleTrack(
                id = s.id,
                durationMs = s.duration,
                albumKey = s.albumId.toString(),
                folderKey = File(s.data).parent ?: "",
                path = s.data,
                trackNumber = s.trackNumber,
                artistKeys = ArtistSeparator.splitLibrary(s.artistName).map { ArtistSeparator.key(it) }
                    .ifEmpty { listOf(ArtistSeparator.key(s.artistName)) },
                genreKey = genres[s.id],
                weight = weight(s.id),
            )
        }
        val random = Random.Default
        val order = if (ShuffleSettings.noRepeats(prefs)) {
            val ids = tracks.map { it.id }
            var played = ShuffleHistory.played(context)
            if (ShuffleEngine.historyComplete(ids, played, ShuffleSettings.noRepeatsPercent(prefs))) {
                ShuffleHistory.forget(context, ids)
                played = ShuffleHistory.played(context)
            }
            ShuffleEngine.orderNoRepeat(tracks, keptCur, played, config, random)
        } else {
            ShuffleEngine.order(tracks, keptCur, config, random)
        }
        return order.map { kept[it] }
    }

    /** Song id -> lower-case genre name. Songs without a genre tag are absent ("Unknown"). */
    private fun genreMap(context: Context): Map<Long, String> {
        val map = HashMap<Long, String>()
        val resolver = context.contentResolver
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.GENRE),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val g = c.getString(1)?.trim()
                        if (!g.isNullOrEmpty()) map[c.getLong(0)] = g.lowercase(Locale.ROOT)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                resolver.query(
                    MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
                    null, null, null
                )?.use { gc ->
                    while (gc.moveToNext()) {
                        val name = gc.getString(1)?.trim()
                        if (name.isNullOrEmpty()) continue
                        resolver.query(
                            MediaStore.Audio.Genres.Members.getContentUri("external", gc.getLong(0)),
                            arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID), null, null, null
                        )?.use { mc ->
                            while (mc.moveToNext()) map[mc.getLong(0)] = name.lowercase(Locale.ROOT)
                        }
                    }
                }
            }
        }
        return map
    }

    /**
     * Song id -> score in 0..1 from the existing favorites playlist or play-count store.
     * Most played: rank-based, the top song scores 1. Least played: the reverse, and songs that
     * were never counted score 1.
     */
    private fun weights(context: Context, by: String): (Long) -> Double {
        return runCatching<(Long) -> Double> {
            if (by == ShuffleSettings.WEIGHT_FAVORITES) {
                val repo = get<Repository>(Repository::class.java)
                val favs = runBlocking(Dispatchers.IO) { repo.favoritePlaylistSongs() }.map { it.id }.toHashSet()
                return@runCatching { id -> if (id in favs) 1.0 else 0.0 }
            }
            val ranked = ArrayList<Long>()
            SongPlayCountStore.getInstance(context).getTopPlayedResults(0)?.use { c ->
                while (c.moveToNext()) ranked.add(c.getLong(0))
            }
            val n = ranked.size.coerceAtLeast(1)
            val most = HashMap<Long, Double>()
            ranked.forEachIndexed { r, id -> most[id] = 1.0 - r.toDouble() / n }
            if (by == ShuffleSettings.WEIGHT_MOST_PLAYED) { id -> most[id] ?: 0.0 }
            else { id -> 1.0 - (most[id] ?: 0.0) }
        }.getOrDefault { _ -> 0.0 }
    }

    /** Called when a song stops being current, for the "No repeats" history. */
    fun onSongFinished(context: Context, song: Song, playedEnough: Boolean) {
        if (song.id < 0) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!ShuffleSettings.noRepeats(prefs)) return
        if (playedEnough || ShuffleSettings.skipCountsAsPlayed(prefs)) ShuffleHistory.markPlayed(context, song.id)
    }
}
