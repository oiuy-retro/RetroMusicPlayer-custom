package code.name.monkey.retromusic.shuffle

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Bug report: "set to two, Up Next goes through an entire album". */
class ShuffleLimitRegressionTest {

    private class FakePrefs(val map: Map<String, Any>) : SharedPreferences {
        override fun getAll() = map.toMutableMap()
        override fun getString(k: String, d: String?) = map[k] as String? ?: d
        override fun getStringSet(k: String, d: MutableSet<String>?) = d
        override fun getInt(k: String, d: Int) = map[k] as Int? ?: d
        override fun getLong(k: String, d: Long) = map[k] as Long? ?: d
        override fun getFloat(k: String, d: Float) = map[k] as Float? ?: d
        override fun getBoolean(k: String, d: Boolean) = map[k] as Boolean? ?: d
        override fun contains(k: String) = k in map
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    /** 6 albums of 12 songs, 4 minutes each. */
    private val library = (0 until 72).map { i ->
        ShuffleTrack(id = i.toLong(), durationMs = 240_000, albumKey = "a${i / 12}", folderKey = "/f${i / 12}",
            path = "/f${i / 12}/${i % 12}.mp3", trackNumber = i % 12 + 1, artistKeys = listOf("x${i / 12}"))
    }

    private fun runs(order: List<Int>): List<Int> {
        val out = mutableListOf<Int>()
        var last: String? = null
        for (i in order) {
            val k = library[i].albumKey
            if (k == last) out[out.size - 1]++ else out.add(1)
            last = k
        }
        return out
    }

    private fun check(prefs: Map<String, Any>) {
        val config = ShuffleSettings.config(FakePrefs(prefs))
        assertEquals(ShuffleMode.GROUP_BLOCKS, config.mode)
        for (seed in 0 until 40) {
            val order = ShuffleEngine.order(library, seed % 72, config, Random(seed))
            assertEquals(72, order.toSet().size)
            // At the very end only one album may be left, so its leftovers can run together.
            val r = runs(order)
            r.dropLast(1).forEach { assertTrue("block of $it in $r", it <= 2) }
            assertTrue(r.size >= 30)
        }
    }

    @Test fun limitTwoFromSavedPrefsInOrder() = check(mapOf(
        "shuffle_mode_type" to "group_blocks", "shuffle_group_min" to 2, "shuffle_group_max" to 2,
        "shuffle_group_order" to "in_order"))

    @Test fun limitTwoRandomWithinAlbum() = check(mapOf(
        "shuffle_mode_type" to "group_blocks", "shuffle_group_min" to 2, "shuffle_group_max" to 2,
        "shuffle_group_order" to "random"))

    @Test fun limitTwoWithTimeLimitAlsoOn() = check(mapOf(
        "shuffle_mode_type" to "group_blocks", "shuffle_group_min" to 2, "shuffle_group_max" to 2,
        "shuffle_group_use_time" to true, "shuffle_group_min_minutes" to 120, "shuffle_group_max_minutes" to 240))

    @Test fun maxTwoWithLargerMinIsClamped() = check(mapOf(
        "shuffle_mode_type" to "group_blocks", "shuffle_group_min" to 1, "shuffle_group_max" to 2))
}
