/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.fragments.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SeekBarPreference
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.shuffle.ShuffleHistory
import code.name.monkey.retromusic.shuffle.ShuffleSettings

/** Settings > Shuffle: mode picker plus the options of the selected mode. */
class ShuffleSettingsFragment : AbsSettingsFragment() {

    private val modeCategories = mapOf(
        "group_blocks" to "shuffle_cat_group_blocks",
        "genre_blocks" to "shuffle_cat_genre_blocks",
        "artist_blocks" to "shuffle_cat_artist_blocks",
        "spread_artists" to "shuffle_cat_spread_artists",
        "weighted" to "shuffle_cat_weighted",
    )

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_shuffle)
    }

    override fun invalidateSettings() {
        val mode: ListPreference? = findPreference(ShuffleSettings.MODE)
        mode?.setOnPreferenceChangeListener { _, newValue ->
            showModeOptions(newValue as String)
            true
        }
        showModeOptions(mode?.value ?: "true_random")

        // Keep min <= max: moving one slider past the other drags the other along.
        linkMinMax(ShuffleSettings.GROUP_MIN, ShuffleSettings.GROUP_MAX)
        linkMinMax(ShuffleSettings.ARTIST_MIN, ShuffleSettings.ARTIST_MAX)
        linkMinMax(ShuffleSettings.GENRE_MIN, ShuffleSettings.GENRE_MAX)
        linkMinMax(ShuffleSettings.GENRE_MIN_SONGS, ShuffleSettings.GENRE_MAX_SONGS)
        linkMinMax(ShuffleSettings.GROUP_MIN_MINUTES, ShuffleSettings.GROUP_MAX_MINUTES)
        linkMinMax(ShuffleSettings.ARTIST_MIN_MINUTES, ShuffleSettings.ARTIST_MAX_MINUTES)

        findPreference<Preference>(ShuffleSettings.RESET_HISTORY)?.setOnPreferenceClickListener {
            ShuffleHistory.reset(requireContext())
            Toast.makeText(requireContext(), R.string.shuffle_history_cleared, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun showModeOptions(value: String) {
        val mode: ListPreference? = findPreference(ShuffleSettings.MODE)
        val name = mode?.entries?.getOrNull(mode.findIndexOfValue(value)) ?: ""
        mode?.summary = name.toString() + "\n" + getString(
            when (value) {
                "group_blocks" -> R.string.shuffle_mode_summary_group_blocks
                "genre_blocks" -> R.string.shuffle_mode_summary_genre_blocks
                "artist_blocks" -> R.string.shuffle_mode_summary_artist_blocks
                "spread_artists" -> R.string.shuffle_mode_summary_spread_artists
                "weighted" -> R.string.shuffle_mode_summary_weighted
                "spotify" -> R.string.shuffle_mode_summary_spotify
                else -> R.string.shuffle_mode_summary_true_random
            }
        )
        modeCategories.forEach { (m, key) -> findPreference<Preference>(key)?.isVisible = m == value }
    }

    private fun linkMinMax(minKey: String, maxKey: String) {
        val min: SeekBarPreference = findPreference(minKey) ?: return
        val max: SeekBarPreference = findPreference(maxKey) ?: return
        min.setOnPreferenceChangeListener { _, v -> if ((v as Int) > max.value) max.value = v; true }
        max.setOnPreferenceChangeListener { _, v -> if ((v as Int) < min.value) min.value = v; true }
    }
}

