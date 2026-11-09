package code.name.monkey.retromusic.loudness

import org.junit.Assert.assertEquals
import org.junit.Test

class LoudnessGainMathTest {
    @Test
    fun strengthScalesGain() {
        val applied = LoudnessGainMath.appliedGainDb(
            trackGainDb = -10.0,
            albumGainDb = null,
            preferAlbum = false,
            strengthPercent = 50,
            maxBoostDb = 6.0,
            maxCutDb = 20.0,
            truePeakDbtp = -1.0,
        )
        assertEquals(-5.0, applied, 1e-9)
    }

    @Test
    fun missingPeakBlocksBoost() {
        val applied = LoudnessGainMath.appliedGainDb(
            trackGainDb = 3.0,
            albumGainDb = null,
            preferAlbum = false,
            strengthPercent = 100,
            maxBoostDb = 6.0,
            maxCutDb = 20.0,
            truePeakDbtp = null,
        )
        assertEquals(0.0, applied, 0.0)
    }

    @Test
    fun peakLimitsBoost() {
        val applied = LoudnessGainMath.appliedGainDb(
            trackGainDb = 5.0,
            albumGainDb = null,
            preferAlbum = false,
            strengthPercent = 100,
            maxBoostDb = 12.0,
            maxCutDb = 20.0,
            truePeakDbtp = -1.5,
        )
        assertEquals(1.5, applied, 1e-9)
    }

    @Test
    fun splitBoostUsesEnhancer() {
        val (att, mb) = LoudnessGainMath.splitForAndroidVolume(2.0)
        assertEquals(1f, att, 0f)
        assertTrue(mb > 0)
    }

    private fun assertTrue(b: Boolean) {
        org.junit.Assert.assertTrue(b)
    }
}
