/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.fragments.settings

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import code.name.monkey.appthemehelper.util.SliderHaptics
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.equalizer.CustomPreset
import code.name.monkey.retromusic.equalizer.EqManager
import code.name.monkey.retromusic.equalizer.EqSettings
import code.name.monkey.retromusic.util.NavigationUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** Settings > Audio > Built-in equalizer. Views are built in code; there are few of them. */
class EqualizerFragment : Fragment() {

    private lateinit var root: LinearLayout
    private var info: EqManager.EqInfo? = null
    private val listener: () -> Unit = { view?.post { if (isAdded) render() } }
    private var suppress = false

    private val btPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        EqManager.refreshDevice()
        EqManager.applyCurrent()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        EqManager.init(requireContext())
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16)
            setPadding(p, p, p, p)
        }
        return NestedScrollView(requireContext()).apply { addView(root) }
    }

    override fun onStart() {
        super.onStart()
        info = EqManager.info()
        EqManager.listeners.add(listener)
        EqManager.refreshDevice()
        render()
    }

    override fun onStop() {
        EqManager.listeners.remove(listener)
        super.onStop()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 14f): TextView = TextView(requireContext()).apply {
        text = s
        textSize = size
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        MaterialSwitch(requireContext()).apply {
            text = label
            isChecked = checked
            setOnCheckedChangeListener { _, b -> if (!suppress) onChange(b) }
        }

    private fun button(label: String, onClick: () -> Unit) = MaterialButton(requireContext()).apply {
        text = label
        setOnClickListener { onClick() }
    }

    /**
     * [displayOf] returns the number the user sees for a progress value. Haptics tick whenever
     * that displayed string changes (same [SliderHaptics] rule as every other slider).
     */
    private fun slider(
        max: Int,
        value: Int,
        displayOf: (Int) -> String,
        onStop: (Int) -> Unit,
        onMove: (Int) -> Unit = {},
    ) = SeekBar(requireContext()).apply {
            this.max = max
            progress = value
            SliderHaptics.onLabel(this, displayOf(value), false)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    SliderHaptics.onLabel(sb, displayOf(p), fromUser)
                    if (fromUser) onMove(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) = onStop(sb.progress)
            })
        }

    private fun render() {
        suppress = true
        root.removeAllViews()
        val store = EqManager.store
        val s = EqManager.currentSettings()
        val eqInfo = info

        root.addView(switch(getString(R.string.eq_enable), store.enabled) { EqManager.setEnabled(it) })
        if (eqInfo == null) {
            root.addView(text(getString(R.string.eq_not_supported)))
        }

        root.addView(switch(getString(R.string.eq_per_device), store.perDevice) { on ->
            EqManager.setPerDevice(on)
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !EqManager.hasBluetoothPermission()) {
                btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        })
        val dev = EqManager.currentDevice
        val profileName = if (store.perDevice && dev != null) store.profile(dev.key)?.name ?: dev.label
        else getString(R.string.eq_default_profile)
        root.addView(text(getString(R.string.eq_editing_profile, profileName)))
        if (store.perDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !EqManager.hasBluetoothPermission()) {
            root.addView(text(getString(R.string.eq_bt_shared_note), 12f))
        }

        if (eqInfo != null) {
            val presetLabel = s.presetName ?: getString(R.string.eq_preset_custom_unsaved)
            root.addView(button(getString(R.string.eq_preset, presetLabel)) { pickPreset(eqInfo) })
            root.addView(button(getString(R.string.eq_save_preset)) { savePreset(eqInfo) })

            val levels = s.levelsFor(eqInfo.centerFreqsHz.size).toMutableList()
            eqInfo.centerFreqsHz.forEachIndexed { i, hz ->
                val label = text("")
                fun setLabel(mb: Int) {
                    val f = if (hz >= 1000) "%.1f kHz".format(hz / 1000f) else "$hz Hz"
                    label.text = "$f: %+.1f dB".format(mb / 100f)
                }
                setLabel(levels[i])
                root.addView(label)
                val range = eqInfo.maxLevel - eqInfo.minLevel
                root.addView(slider(range, levels[i] - eqInfo.minLevel,
                    displayOf = { p -> "%+.1f".format((p + eqInfo.minLevel) / 100f) },
                    onStop = { p ->
                        levels[i] = p + eqInfo.minLevel
                        EqManager.update(EqManager.currentSettings().copy(bandLevels = levels.toList(), presetName = null))
                    },
                    onMove = { p -> setLabel(p + eqInfo.minLevel) }))
            }
            root.addView(button(getString(R.string.eq_reset_flat)) {
                EqManager.update(s.copy(bandLevels = List(eqInfo.centerFreqsHz.size) { 0 }, presetName = null))
            })
        }

        if (eqInfo?.bassBoostSupported != false) {
            val l = text(getString(R.string.eq_bass_boost, s.bassBoost / 10))
            root.addView(l)
            root.addView(slider(1000, s.bassBoost,
                displayOf = { (it / 10).toString() },
                onStop = { EqManager.update(EqManager.currentSettings().copy(bassBoost = it)) },
                onMove = { l.text = getString(R.string.eq_bass_boost, it / 10) }))
        }
        if (eqInfo?.virtualizerSupported == true) {
            val l = text(getString(R.string.eq_virtualizer, s.virtualizer / 10))
            root.addView(l)
            root.addView(slider(1000, s.virtualizer,
                displayOf = { (it / 10).toString() },
                onStop = { EqManager.update(EqManager.currentSettings().copy(virtualizer = it)) },
                onMove = { l.text = getString(R.string.eq_virtualizer, it / 10) }))
        }
        val ll = text(getString(R.string.eq_loudness, s.loudnessGain / 100f))
        root.addView(ll)
        root.addView(slider(EqManager.MAX_LOUDNESS_MB, s.loudnessGain,
            displayOf = { "%.1f".format(it / 100f) },
            onStop = { EqManager.update(EqManager.currentSettings().copy(loudnessGain = it)) },
            onMove = { ll.text = getString(R.string.eq_loudness, it / 100f) }))

        root.addView(button(getString(R.string.eq_manage_profiles)) { manageProfiles() })
        root.addView(button(getString(R.string.eq_open_system)) { NavigationUtil.openEqualizer(requireActivity()) })
        root.gravity = Gravity.START
        suppress = false
    }

    private fun pickPreset(eqInfo: EqManager.EqInfo) {
        val custom = EqManager.store.customPresets()
        val names = eqInfo.systemPresets + custom.map { it.name + " ★" }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.eq_presets)
            .setItems(names.toTypedArray()) { _, which ->
                val cur = EqManager.currentSettings()
                if (which < eqInfo.systemPresets.size) {
                    EqManager.systemPresetLevels(which)?.let {
                        EqManager.update(cur.copy(bandLevels = it, presetName = eqInfo.systemPresets[which]))
                    }
                } else {
                    val p = custom[which - eqInfo.systemPresets.size]
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(p.name)
                        .setPositiveButton(R.string.eq_apply) { _, _ ->
                            EqManager.update(cur.copy(bandLevels = p.bandLevels, presetName = p.name))
                        }
                        .setNegativeButton(R.string.eq_delete) { _, _ ->
                            EqManager.store.deleteCustomPreset(p.name); render()
                        }
                        .show()
                }
            }
            .show()
    }

    private fun savePreset(eqInfo: EqManager.EqInfo) {
        askName(getString(R.string.eq_save_preset), "") { name ->
            val cur = EqManager.currentSettings()
            val levels = cur.levelsFor(eqInfo.centerFreqsHz.size)
            EqManager.store.saveCustomPreset(CustomPreset(name, levels))
            EqManager.update(cur.copy(presetName = name))
        }
    }

    private fun manageProfiles() {
        val store = EqManager.store
        val profiles = store.profiles()
        if (profiles.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.eq_manage_profiles)
                .setMessage(R.string.eq_no_profiles)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val current = EqManager.currentDevice?.key
        val labels = profiles.map { if (it.key == current) "${it.name} •" else it.name }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.eq_manage_profiles)
            .setItems(labels.toTypedArray()) { _, which ->
                val p = profiles[which]
                val actions = arrayOf(getString(R.string.eq_rename), getString(R.string.eq_copy_from), getString(R.string.eq_delete))
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(p.name)
                    .setItems(actions) { _, a ->
                        when (a) {
                            0 -> askName(getString(R.string.eq_rename), p.name) { store.rename(p.key, it); EqManager.applyCurrent() }
                            1 -> {
                                val sources = profiles.filter { it.key != p.key }
                                val srcLabels = listOf(getString(R.string.eq_default_profile)) + sources.map { it.name }
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle(R.string.eq_copy_from)
                                    .setItems(srcLabels.toTypedArray()) { _, i ->
                                        store.copyFrom(if (i == 0) null else sources[i - 1].key, p.key)
                                        EqManager.applyCurrent()
                                    }.show()
                            }
                            2 -> MaterialAlertDialogBuilder(requireContext())
                                .setMessage(getString(R.string.eq_delete_confirm, p.name))
                                .setPositiveButton(R.string.eq_delete) { _, _ -> store.delete(p.key); EqManager.applyCurrent() }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }.show()
            }
            .show()
    }

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(requireContext()).apply { setText(initial); setSingleLine() }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

}
