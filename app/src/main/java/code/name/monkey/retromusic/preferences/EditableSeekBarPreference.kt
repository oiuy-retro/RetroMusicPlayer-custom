/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.preferences

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.widget.doAfterTextChanged
import androidx.preference.PreferenceViewHolder
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.appthemehelper.common.prefs.supportv7.ATESeekBarPreference
import code.name.monkey.appthemehelper.util.TintHelper
import code.name.monkey.retromusic.R

/**
 * Slider with a number box instead of the value label. Typing a number and pressing Done (or
 * leaving the box) clamps it to the slider's range and saves it through the normal change
 * listener, so linked min/max pairs still push each other. The hidden value label is kept so the
 * slider haptics keep ticking while dragging.
 */
class EditableSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = -1,
    defStyleRes: Int = -1
) : ATESeekBarPreference(context, attrs, defStyleAttr, defStyleRes) {

    private var box: EditText? = null

    override fun onBindViewHolder(view: PreferenceViewHolder) {
        super.onBindViewHolder(view)
        val label = view.findViewById(androidx.preference.R.id.seekbar_value) as TextView
        label.visibility = View.GONE
        val edit = view.findViewById(R.id.seekbar_edit) as EditText
        (view.findViewById(R.id.seekbar_unit) as TextView).apply {
            text = unit.trim()
            visibility = if (unit.isBlank()) View.GONE else View.VISIBLE
        }
        TintHelper.setTintAuto(edit, ThemeStore.accentColor(context), false)
        box = edit
        edit.tag = this
        edit.onFocusChangeListener = null
        edit.setOnEditorActionListener(null)
        edit.setText(value.toString())
        // Dragging updates the (hidden) label live; mirror it into the box unless the user is typing.
        (label.getTag(R.id.seekbar_edit) as? android.text.TextWatcher)?.let(label::removeTextChangedListener)
        val watcher = label.doAfterTextChanged {
            if (edit.tag !== this || edit.hasFocus()) return@doAfterTextChanged
            val digits = it.toString().filter { c -> c.isDigit() }
            if (digits.isNotEmpty() && edit.text.toString() != digits) edit.setText(digits)
        }
        label.setTag(R.id.seekbar_edit, watcher)
        edit.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit(edit) }
        edit.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commit(edit)
                v.context.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                true
            } else false
        }
    }

    /** Applies a typed value that hasn't been confirmed yet (used when leaving the page). */
    fun commitPending() {
        val edit = box ?: return
        if (edit.tag === this) commit(edit)
    }

    private fun commit(edit: EditText) {
        if (edit.tag !== this) return
        val n = SliderInput.parse(edit.text, value, min, max)
        if (n != value && callChangeListener(n)) value = n
        if (edit.text.toString() != value.toString()) edit.setText(value.toString())
    }
}
