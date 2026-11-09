package code.name.monkey.retromusic.loudness

import kotlin.math.pow

/**
 * Playback gain math. This is loudness *normalization* (whole-track gain), not
 * dynamic-range compression.
 */
object LoudnessGainMath {
    const val DEFAULT_STRENGTH_PERCENT = 100
    const val DEFAULT_MAX_BOOST_DB = 6.0
    const val DEFAULT_MAX_CUT_DB = 20.0

    fun gainFromLufs(measuredLufs: Double, referenceLufs: Double = LoudnessSchema.REFERENCE_LUFS): Double =
        referenceLufs - measuredLufs

    /**
     * @return applied dB after strength and max boost/cut. Never fabricates LUFS from ambiguous gains.
     * Positive boost is forced to 0 when [truePeakDbtp] is null (attenuation-only safety).
     */
    fun appliedGainDb(
        trackGainDb: Double?,
        albumGainDb: Double?,
        preferAlbum: Boolean,
        strengthPercent: Int,
        maxBoostDb: Double,
        maxCutDb: Double,
        truePeakDbtp: Double?,
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
        if (applied > 0.0 && truePeakDbtp == null && !allowBoostWithoutPeak) {
            applied = 0.0
        }
        if (applied > 0.0 && truePeakDbtp != null) {
            // Headroom: do not boost past true peak reaching 0 dBTP (approx).
            val headroom = (-truePeakDbtp).coerceAtLeast(0.0)
            applied = minOf(applied, headroom)
        }
        applied = applied.coerceIn(-maxCutDb.coerceAtLeast(0.0), maxBoostDb.coerceAtLeast(0.0))
        return applied
    }

    fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    /** Split into MediaPlayer-safe attenuation (0..1) and LoudnessEnhancer boost millibels. */
    fun splitForAndroidVolume(linear: Double): Pair<Float, Int> {
        if (!linear.isFinite() || linear <= 0.0) return 0f to 0
        return if (linear <= 1.0) {
            linear.toFloat() to 0
        } else {
            val boostDb = 20.0 * kotlin.math.log10(linear)
            1f to (boostDb * 100.0).toInt().coerceAtLeast(0)
        }
    }
}
