package code.name.monkey.retromusic.loudness

import kotlin.math.pow

/**
 * Playback gain math. Whole-track loudness *normalization*, not dynamic-range compression.
 *
 * Positive boost is **disabled**: EQ, crossfade peaks, and ducking can add energy beyond
 * file true-peak headroom, and this build has no proven brickwall limiter on the output path.
 * Only attenuation (applied dB <= 0) is applied.
 */
object LoudnessGainMath {
    const val DEFAULT_STRENGTH_PERCENT = 100
    const val DEFAULT_MAX_BOOST_DB = 0.0
    const val DEFAULT_MAX_CUT_DB = 20.0

    /** Positive boost is never considered safe in this build. */
    const val POSITIVE_BOOST_ALLOWED = false

    fun gainFromLufs(measuredLufs: Double, referenceLufs: Double = LoudnessSchema.REFERENCE_LUFS): Double =
        referenceLufs - measuredLufs

    /**
     * @return applied dB after strength and max cut. Positive values are forced to 0.
     * Never fabricates LUFS from ambiguous gains.
     */
    fun appliedGainDb(
        trackGainDb: Double?,
        albumGainDb: Double?,
        preferAlbum: Boolean,
        strengthPercent: Int,
        maxBoostDb: Double,
        maxCutDb: Double,
        truePeakDbtp: Double? = null,
        allowBoostWithoutPeak: Boolean = false,
    ): Double {
        val raw = when {
            preferAlbum && albumGainDb != null -> albumGainDb
            trackGainDb != null -> trackGainDb
            albumGainDb != null -> albumGainDb
            else -> return 0.0
        }
        if (!raw.isFinite()) return 0.0
        val strength = (strengthPercent.coerceIn(0, 100)) / 100.0
        var applied = raw * strength
        // Conservative: never boost. truePeak alone is insufficient with EQ/crossfade.
        if (applied > 0.0) {
            applied = 0.0
        }
        applied = applied.coerceIn(-maxCutDb.coerceAtLeast(0.0), 0.0)
        // maxBoostDb ignored while POSITIVE_BOOST_ALLOWED is false
        return applied
    }

    fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    /**
     * Attenuation-only split: linear in 0..1, boost millibels always 0.
     */
    fun splitForAndroidVolume(linear: Double): Pair<Float, Int> {
        if (!linear.isFinite() || linear <= 0.0) return 0f to 0
        return linear.coerceAtMost(1.0).toFloat() to 0
    }
}
