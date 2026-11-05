package code.name.monkey.appthemehelper.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure display-bucket / should-tick logic; no Android View needed. */
class SliderHapticsTest {

    @Test
    fun displayBucket_integerSteps() {
        assertEquals(0L, SliderHaptics.displayBucket(0f, 1f))
        assertEquals(3L, SliderHaptics.displayBucket(3f, 1f))
        assertEquals(3L, SliderHaptics.displayBucket(3.9f, 1f))
    }

    @Test
    fun displayBucket_eqTenthDbInMillibels() {
        // 0.1 dB = 10 millibels; display %.1f changes every 10 mB.
        assertEquals(0L, SliderHaptics.displayBucket(0f, 10f))
        assertEquals(0L, SliderHaptics.displayBucket(9f, 10f))
        assertEquals(1L, SliderHaptics.displayBucket(10f, 10f))
        assertEquals(15L, SliderHaptics.displayBucket(150f, 10f)) // +1.5 dB
    }

    @Test
    fun displayBucket_seekOneSecond() {
        assertEquals(0L, SliderHaptics.displayBucket(999f, 1000f))
        assertEquals(1L, SliderHaptics.displayBucket(1000f, 1000f))
        assertEquals(65L, SliderHaptics.displayBucket(65_432f, 1000f))
    }

    @Test
    fun displayBucket_halfSecondResumeDelay() {
        // Stored as half-second steps; display changes every step (0.5 s).
        assertEquals(6L, SliderHaptics.displayBucket(6f, 1f))
        assertEquals(7L, SliderHaptics.displayBucket(7f, 1f))
    }

    @Test
    fun displayBucket_zeroOrNegativeStepTreatedAsOne() {
        assertEquals(5L, SliderHaptics.displayBucket(5f, 0f))
        assertEquals(5L, SliderHaptics.displayBucket(5f, -2f))
    }

    @Test
    fun shouldTick_onlyWhenUserMovesDisplay() {
        assertFalse(SliderHaptics.shouldTick(null, "1.0", true))
        assertFalse(SliderHaptics.shouldTick("1.0", "1.0", true))
        assertFalse(SliderHaptics.shouldTick("1.0", "1.1", false))
        assertTrue(SliderHaptics.shouldTick("1.0", "1.1", true))
        assertTrue(SliderHaptics.shouldTick(0L, 1L, true))
        assertFalse(SliderHaptics.shouldTick(0L, 0L, true))
    }

    @Test
    fun seekStepMs_isOneSecond() {
        assertEquals(1_000f, SliderHaptics.SEEK_STEP_MS)
    }
}
