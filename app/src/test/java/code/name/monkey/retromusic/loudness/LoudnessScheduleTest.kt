package code.name.monkey.retromusic.loudness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessScheduleTest {
    @Test
    fun overnightWindow() {
        assertTrue(LoudnessSchedule.isActiveNow(true, "22:00", "07:00", 23 * 60))
        assertTrue(LoudnessSchedule.isActiveNow(true, "22:00", "07:00", 3 * 60))
        assertFalse(LoudnessSchedule.isActiveNow(true, "22:00", "07:00", 12 * 60))
    }

    @Test
    fun equalTimesInvalid() {
        assertEqualsError()
    }

    private fun assertEqualsError() {
        org.junit.Assert.assertEquals("equal_start_end", LoudnessSchedule.validateWindow("08:00", "08:00"))
    }

    @Test
    fun scheduleOffAlwaysActiveWhenAsked() {
        assertTrue(LoudnessSchedule.isActiveNow(false, "22:00", "07:00", 12 * 60))
    }
}
