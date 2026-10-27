/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.fragments.settings

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.media.audiofx.AudioEffect
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import androidx.preference.Preference
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.retromusic.BLUETOOTH_PLAYBACK
import code.name.monkey.retromusic.EQUALIZER
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.base.AbsBaseActivity.Companion.BLUETOOTH_PERMISSION_REQUEST
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.service.MediaSessionWatcher
import code.name.monkey.retromusic.service.ResumeLog
import code.name.monkey.retromusic.util.NavigationUtil
import code.name.monkey.retromusic.util.PreferenceUtil

/**
 * @author Hemanth S (h4h13).
 */

class AudioSettings : AbsSettingsFragment() {
    override fun invalidateSettings() {
        val eqPreference: Preference? = findPreference(EQUALIZER)
        if (!hasEqualizer()) {
            eqPreference?.isEnabled = false
            eqPreference?.summary = resources.getString(R.string.no_equalizer)
        } else {
            eqPreference?.isEnabled = true
        }
        eqPreference?.setOnPreferenceClickListener {
            NavigationUtil.openEqualizer(requireActivity())
            true
        }
        findPreference<Preference>("media_state_access")?.apply {
            setSummary(
                if (MediaSessionWatcher.hasAccess(requireContext())) R.string.pref_summary_media_state_access_on
                else R.string.pref_summary_media_state_access
            )
            setOnPreferenceClickListener {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                true
            }
        }
        findPreference<Preference>("resume_timed_fallback_delay")?.apply {
            summary = delayText(PreferenceUtil.resumeTimedFallbackDelayMs)
            setOnPreferenceChangeListener { pref, value ->
                pref.summary = delayText((value as Int).coerceIn(1, 40) * 500L)
                true
            }
        }
        // Temporary diagnostic for Resume after other media
        findPreference<Preference>(ResumeLog.ENABLED_KEY)?.setOnPreferenceChangeListener { _, on ->
            if (on == false) ResumeLog.clear(requireContext())
            true
        }
        findPreference<Preference>("copy_resume_log")?.setOnPreferenceClickListener {
            val ctx = requireContext()
            ctx.getSystemService<ClipboardManager>()
                ?.setPrimaryClip(ClipData.newPlainText("Resume log", ResumeLog.text(ctx)))
            ctx.showToast(R.string.resume_log_copied)
            true
        }
        val bluetoothPreference: Preference? = findPreference(BLUETOOTH_PLAYBACK)
        if (VersionUtils.hasS()) {
            bluetoothPreference?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    if (ActivityCompat.checkSelfPermission(
                            requireContext(),
                            BLUETOOTH_CONNECT
                        ) != PERMISSION_GRANTED
                    ) {
                        ActivityCompat.requestPermissions(
                            requireActivity(), arrayOf(
                                BLUETOOTH_CONNECT
                            ), BLUETOOTH_PERMISSION_REQUEST
                        )
                    }
                }
                return@setOnPreferenceChangeListener true
            }
        }
    }

    private fun delayText(ms: Long) = String.format(java.util.Locale.getDefault(), "%.1f s", ms / 1000f)

    private fun hasEqualizer(): Boolean {
        val effects = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)

        val pm = requireActivity().packageManager
        val ri = pm.resolveActivity(effects, 0)
        return ri != null
    }

    override fun onResume() {
        super.onResume()
        invalidateSettings()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_audio)
    }
}
