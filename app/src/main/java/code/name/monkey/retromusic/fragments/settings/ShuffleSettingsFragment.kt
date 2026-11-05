/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.fragments.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.preferences.EditableSeekBarPreference
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

    /** Shuffle settings when the page was opened; compared on leave. */
    private var snapshot: Map<String, Any?>? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_shuffle)
    }

    private fun shuffleSettings(): Map<String, Any?> =
        PreferenceManager.getDefaultSharedPreferences(requireContext()).all
            .filterKeys { it.startsWith("shuffle_") && it != ShuffleSettings.RESET_HISTORY }

    override fun onResume() {
        super.onResume()
        if (snapshot == null) snapshot = shuffleSettings()
    }

    /** Apply any number typed into a slider box but not confirmed yet, before the onStop compare. */
    override fun onPause() {
        commitTypedValues(preferenceScreen)
        super.onPause()
    }

    private fun commitTypedValues(group: PreferenceGroup?) {
        group ?: return
        for (i in 0 until group.preferenceCount) {
            when (val p = group.getPreference(i)) {
                is EditableSeekBarPreference -> p.commitPending()
                is PreferenceGroup -> commitTypedValues(p)
            }
        }
    }

    /** Leaving the page: reshuffle the live queue once, only if a shuffle setting changed. */
    override fun onStop() {
        super.onStop()
        val before = snapshot ?: return
        snapshot = null
        if (shuffleSettings() != before) MusicPlayerRemote.musicService?.reshuffleRemaining()
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
        linkMinMax(ShuffleSettings.GROUP_MIN_PERCENT, ShuffleSettings.GROUP_MAX_PERCENT)
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
        // Save while dragging so both sliders move together. Saving no longer reshuffles; the
        // queue is reshuffled once when the page is left (see onStop).
        min.updatesContinuously = true
        max.updatesContinuously = true
        // Repair pairs saved inverted by older versions.
        if (min.value > max.value) max.value = min.value
        // Setting .value does not call change listeners; the flag also guards against any loop.
        var syncing = false
        min.setOnPreferenceChangeListener { _, v ->
            val n = v as Int
            if (!syncing && n > max.value) { syncing = true; try { max.value = n } finally { syncing = false } }
            true
        }
        max.setOnPreferenceChangeListener { _, v ->
            val n = v as Int
            if (!syncing && n < min.value) { syncing = true; try { min.value = n } finally { syncing = false } }
            true
        }
    }
}

