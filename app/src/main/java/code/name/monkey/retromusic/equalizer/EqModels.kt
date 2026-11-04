/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.equalizer

/** Settings for the effects of one profile. Levels are in millibels, strengths 0-1000. */
data class EqSettings(
    val bandLevels: List<Int> = emptyList(),
    /** Name of the preset last picked, or null when the bands were moved by hand. */
    val presetName: String? = null,
    val bassBoost: Int = 0,
    val virtualizer: Int = 0,
    /** LoudnessEnhancer target gain in millibels (0 = off). */
    val loudnessGain: Int = 0,
) {
    /** Band levels resized to [count] bands (missing bands are flat). */
    fun levelsFor(count: Int): List<Int> =
        if (bandLevels.size == count) bandLevels else List(count) { 0 }
}

/** A saved profile for one output device. */
data class DeviceProfile(
    val key: String,
    val name: String,
    val settings: EqSettings,
)

/** A user saved preset. */
data class CustomPreset(
    val name: String,
    val bandLevels: List<Int>,
)

enum class OutputKind { SPEAKER, WIRED, BLUETOOTH, OTHER }

/** The output Retro is currently playing to, reduced to what profile selection needs. */
data class OutputDevice(
    val kind: OutputKind,
    /** Stable key used to store the profile. */
    val key: String,
    /** Default display name. */
    val label: String,
)
