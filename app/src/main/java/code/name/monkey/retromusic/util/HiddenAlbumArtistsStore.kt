/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.util

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saves the artists hidden from the Albums tab in their own SharedPreferences file as JSON:
 * `[{"name": "Artist", "until": 0}, ...]`, where until 0 means until unhidden.
 * Only the Albums tab grid reads this; songs, other tabs, playlists, search and shuffle ignore it.
 */
object HiddenAlbumArtistsStore {
    private const val PREFS = "hidden_album_artists"
    private const val KEY = "artists"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<HiddenArtist> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim()
                if (name.isEmpty()) null else HiddenArtist(name, o.optLong("until", HiddenArtist.FOREVER))
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, hidden: List<HiddenArtist>) {
        val array = JSONArray()
        hidden.forEach { array.put(JSONObject().put("name", it.name).put("until", it.untilMillis)) }
        prefs(context).edit { putString(KEY, array.toString()) }
    }

    /** Loads, drops expired hides (saving if any were dropped) and returns what is still hidden. */
    fun loadActive(context: Context, now: Long = System.currentTimeMillis()): List<HiddenArtist> {
        val all = load(context)
        val active = HiddenAlbumArtists.prune(all, now)
        if (active.size != all.size) save(context, active)
        return active
    }
}
