/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.fragments.albums

import android.text.format.DateUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import code.name.monkey.appthemehelper.util.SliderHaptics
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.DialogHideArtistAlbumsBinding
import code.name.monkey.retromusic.extensions.accent
import code.name.monkey.retromusic.extensions.colorButtons
import code.name.monkey.retromusic.extensions.materialDialog
import code.name.monkey.retromusic.preferences.SliderInput
import code.name.monkey.retromusic.util.HiddenAlbumArtists
import code.name.monkey.retromusic.util.HiddenArtist

/**
 * Asks how long to hide [artists]' albums. [onHide] gets the number of days, 0 = until unhidden.
 */
fun Fragment.showHideArtistAlbumsDialog(artists: List<String>, onHide: (days: Int) -> Unit) {
    val binding = DialogHideArtistAlbumsBinding.inflate(layoutInflater)
    binding.hideArtistsMessage.text =
        getString(R.string.hide_artist_albums_message, artists.joinToString(", "))
    val slider = binding.hideDaysSlider
    val box: EditText = binding.hideDaysEdit
    slider.accent()
    slider.valueFrom = HiddenAlbumArtists.CUSTOM_DAYS_MIN.toFloat()
    slider.valueTo = HiddenAlbumArtists.CUSTOM_DAYS_MAX.toFloat()
    slider.value = HiddenAlbumArtists.CUSTOM_DAYS_DEFAULT.toFloat()
    // One tick for each whole day the shown number changes while dragging.
    SliderHaptics.attach(slider, 1f)
    box.setText(HiddenAlbumArtists.CUSTOM_DAYS_DEFAULT.toString())
    slider.addOnChangeListener { _, value, _ ->
        val shown = value.toInt().toString()
        if (!box.hasFocus() && box.text.toString() != shown) box.setText(shown)
    }

    fun commitBox(): Int {
        val n = SliderInput.parse(
            box.text,
            slider.value.toInt(),
            HiddenAlbumArtists.CUSTOM_DAYS_MIN,
            HiddenAlbumArtists.CUSTOM_DAYS_MAX
        )
        slider.value = n.toFloat()
        val shown = n.toString()
        if (box.text.toString() != shown) box.setText(shown)
        return n
    }
    box.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitBox() }
    box.setOnEditorActionListener { v, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            commitBox()
            v.context.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus()
            true
        } else false
    }

    fun syncCustomEnabled() {
        val custom = binding.hideDurationGroup.checkedRadioButtonId == R.id.hide_custom
        slider.isEnabled = custom
        box.isEnabled = custom
    }
    binding.hideDurationGroup.setOnCheckedChangeListener { _, _ -> syncCustomEnabled() }
    syncCustomEnabled()

    materialDialog(R.string.action_hide_artist_albums)
        .setView(binding.root)
        .setPositiveButton(R.string.action_hide) { _, _ ->
            val days = when (binding.hideDurationGroup.checkedRadioButtonId) {
                R.id.hide_one_day -> 1
                R.id.hide_one_week -> 7
                R.id.hide_one_month -> 30
                R.id.hide_custom -> commitBox()
                else -> 0
            }
            onHide(days)
        }
        .setNegativeButton(R.string.action_cancel, null)
        .create()
        .colorButtons()
        .show()
}

/** Lists [hidden] artists with their end time; [onUnhide] gets the keys to unhide. */
fun Fragment.showUnhideArtistsDialog(hidden: List<HiddenArtist>, onUnhide: (keys: Set<String>) -> Unit) {
    if (hidden.isEmpty()) return
    val sorted = hidden.sortedBy { it.key }
    val labels = sorted.map {
        if (it.isForever) {
            getString(R.string.hidden_artist_until_unhidden, it.name)
        } else {
            val date = DateUtils.formatDateTime(
                requireContext(), it.untilMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME
            )
            getString(R.string.hidden_artist_until_date, it.name, date)
        }
    }.toTypedArray()
    val checked = BooleanArray(sorted.size)
    materialDialog(R.string.action_unhide_artists)
        .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
        .setPositiveButton(R.string.unhide_selected) { _, _ ->
            val keys = sorted.filterIndexed { i, _ -> checked[i] }.map { it.key }.toSet()
            if (keys.isNotEmpty()) onUnhide(keys)
        }
        .setNeutralButton(R.string.unhide_all) { _, _ -> onUnhide(sorted.map { it.key }.toSet()) }
        .setNegativeButton(R.string.action_cancel, null)
        .create()
        .colorButtons()
        .show()
}
