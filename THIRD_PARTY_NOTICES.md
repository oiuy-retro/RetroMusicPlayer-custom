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


## libebur128 (EBU R128 loudness)

- Project: https://github.com/jiixyj/libebur128
- Version vendored as source: 1.2.6 under `third_party/libebur128/`
- License: MIT (see `third_party/libebur128/COPYING`)
- Used via Android NDK/JNI for on-phone integrated loudness and true-peak measurement.

## Unicode CaseFolding 15.1.0

- Source: https://www.unicode.org/Public/15.1.0/ucd/CaseFolding.txt
- Used for portable loudness collision keys (`jf-ck-v1`), status C+F only (non-Turkic).
