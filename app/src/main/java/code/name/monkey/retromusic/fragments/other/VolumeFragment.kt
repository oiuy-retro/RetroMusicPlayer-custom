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
package code.name.monkey.retromusic.fragments.other

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.retromusic.PLAYER_VOLUME
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.FragmentVolumeBinding
import code.name.monkey.retromusic.extensions.applyColor
import code.name.monkey.retromusic.util.PreferenceUtil
import com.google.android.material.slider.Slider

/**
 * Now playing volume slider. Controls Retro's own player volume (0..100 %, saved in
 * [PreferenceUtil.playerVolume]) instead of the system media volume, so Retro can be turned
 * down relative to other apps. Hardware volume buttons keep changing the system volume.
 */
class VolumeFragment : Fragment(), Slider.OnChangeListener, View.OnClickListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var _binding: FragmentVolumeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentVolumeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTintable(ThemeStore.accentColor(requireContext()))
        binding.volumeSeekBar.valueFrom = 0f
        binding.volumeSeekBar.valueTo = MAX_VOLUME.toFloat()
        binding.volumeSeekBar.stepSize = 1f
        binding.volumeSeekBar.setLabelFormatter { "${it.toInt()}%" }
        binding.volumeSeekBar.addOnChangeListener(this)
        binding.volumeDown.setOnClickListener(this)
        binding.volumeUp.setOnClickListener(this)
    }

    override fun onResume() {
        super.onResume()
        // Reflect the saved level (it may have been changed on another screen or theme)
        showVolume(PreferenceUtil.playerVolume)
        PreferenceUtil.registerOnSharedPreferenceChangedListener(this)
    }

    override fun onPause() {
        super.onPause()
        PreferenceUtil.unregisterOnSharedPreferenceChangedListener(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == PLAYER_VOLUME) showVolume(PreferenceUtil.playerVolume)
    }

    private fun showVolume(volume: Int) {
        val binding = _binding ?: return
        val value = volume.coerceIn(0, MAX_VOLUME).toFloat()
        if (binding.volumeSeekBar.value != value) {
            binding.volumeSeekBar.value = value
        }
        updateIcon(volume)
    }

    private fun updateIcon(volume: Int) {
        _binding?.volumeDown?.setImageResource(
            if (volume == 0) R.drawable.ic_volume_off else R.drawable.ic_volume_down
        )
    }

    override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
        val volume = value.toInt()
        if (fromUser && volume != PreferenceUtil.playerVolume) {
            // MusicService listens for this preference and applies the gain right away
            PreferenceUtil.playerVolume = volume
        }
        updateIcon(volume)
    }

    override fun onClick(view: View) {
        val current = PreferenceUtil.playerVolume
        val newVolume = when (view.id) {
            R.id.volumeDown -> current - STEP
            R.id.volumeUp -> current + STEP
            else -> return
        }.coerceIn(0, MAX_VOLUME)
        PreferenceUtil.playerVolume = newVolume
        showVolume(newVolume)
    }

    fun tintWhiteColor() {
        val color = Color.WHITE
        binding.volumeDown.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        binding.volumeUp.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        binding.volumeSeekBar.applyColor(color)
    }

    fun setTintable(color: Int) {
        binding.volumeSeekBar.applyColor(color)
    }

    fun setTintableColor(color: Int) {
        binding.volumeDown.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        binding.volumeUp.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        // TintHelper.setTint(volumeSeekBar, color, false)
        binding.volumeSeekBar.applyColor(color)
    }

    companion object {
        private const val MAX_VOLUME = 100
        private const val STEP = 5

        fun newInstance(): VolumeFragment {
            return VolumeFragment()
        }
    }
}
