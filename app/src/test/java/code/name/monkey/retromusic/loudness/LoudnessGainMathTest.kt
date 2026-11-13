package code.name.monkey.retromusic.loudness

import org.junit.Assert.assertEquals
import org.junit.Test

class LoudnessGainMathTest {
    @Test
    fun strengthScalesAttenuation() {
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
    fun positiveGainForcedToZeroEvenWithPeak() {
        val applied = LoudnessGainMath.appliedGainDb(
            trackGainDb = 5.0,
            albumGainDb = null,
            preferAlbum = false,
            strengthPercent = 100,
            maxBoostDb = 12.0,
            maxCutDb = 20.0,
            truePeakDbtp = -1.5,
        )
        assertEquals(0.0, applied, 0.0)
    }

    @Test
    fun missingPeakStillZeroBoost() {
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
    fun maxCutCapsAttenuation() {
        val applied = LoudnessGainMath.appliedGainDb(
            trackGainDb = -40.0,
            albumGainDb = null,
            preferAlbum = false,
            strengthPercent = 100,
            maxBoostDb = 0.0,
            maxCutDb = 20.0,
        )
        assertEquals(-20.0, applied, 1e-9)
    }

    @Test
    fun preferAlbumWhenPresent() {
        val applied = LoudnessGainMath.appliedGainDb(
            trackGainDb = -10.0,
            albumGainDb = -4.0,
            preferAlbum = true,
            strengthPercent = 100,
            maxBoostDb = 0.0,
            maxCutDb = 20.0,
        )
        assertEquals(-4.0, applied, 1e-9)
    }

    @Test
    fun splitNeverBoosts() {
        val (att, mb) = LoudnessGainMath.splitForAndroidVolume(2.0)
        assertEquals(1f, att, 0f)
        assertEquals(0, mb)
        val (att2, mb2) = LoudnessGainMath.splitForAndroidVolume(0.5)
        assertEquals(0.5f, att2, 1e-6f)
        assertEquals(0, mb2)
    }

    @Test
    fun gainFromLufsUsesReference() {
        assertEquals(6.0, LoudnessGainMath.gainFromLufs(-24.0), 1e-9)
        assertEquals(-2.0, LoudnessGainMath.gainFromLufs(-16.0), 1e-9)
    }
}
