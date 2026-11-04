# Third-party notices

This custom build of Retro Music Player is licensed under the GNU General Public License v3.
It includes code adapted from the projects below.

## HeliBoard (HeliBoard-custom)

- Source: https://github.com/oiuy-retro/HeliBoard-custom (fork of https://github.com/HeliBorg/HeliBoard)
- File: `app/src/main/java/helium314/keyboard/settings/dialogs/SliderDialog.kt`
  (`performSliderStepHaptic`)
- Used in: `appthemehelper/src/main/java/code/name/monkey/appthemehelper/util/SliderHaptics.kt`
- License: Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0). Copyright HeliBoard contributors.
- Changes: ported to a plain Kotlin object for Views (Material `Slider`, `SeekBar`,
  `SeekBarPreference`), with ticks per value step.
