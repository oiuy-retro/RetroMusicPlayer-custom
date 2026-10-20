package code.name.monkey.retromusic.service

import code.name.monkey.retromusic.util.PreferenceUtil

/**
 * Maps the now playing volume slider (0..100 %) to a linear player gain.
 *
 * A squared curve is used so the lower part of the slider stays usable:
 * 100 % = 1.0 (unity, never a boost), 50 % = 0.25 (about -12 dB), 10 % = 0.01 (-40 dB), 0 % = silent.
 */
object PlayerVolume {
    fun gainForPercent(percent: Int): Float {
        val fraction = percent.coerceIn(0, 100) / 100f
        return (fraction * fraction).coerceIn(0f, 1f)
    }

    val currentGain: Float
        get() = gainForPercent(PreferenceUtil.playerVolume)
}
