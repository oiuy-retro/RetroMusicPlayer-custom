package code.name.monkey.retromusic.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class SliderInputTest {
    @Test fun inRangeIsKept() = assertEquals(7, SliderInput.parse("7", 3, 1, 50))
    @Test fun aboveMaxClamps() = assertEquals(50, SliderInput.parse("80", 3, 1, 50))
    @Test fun belowMinClamps() = assertEquals(1, SliderInput.parse("0", 3, 1, 50))
    @Test fun emptyReverts() = assertEquals(3, SliderInput.parse("", 3, 1, 50))
    @Test fun nullReverts() = assertEquals(3, SliderInput.parse(null, 3, 1, 50))
    @Test fun nonDigitsRevert() {
        assertEquals(3, SliderInput.parse("-5", 3, 1, 50))
        assertEquals(3, SliderInput.parse("4.5", 3, 1, 50))
        assertEquals(3, SliderInput.parse("abc", 3, 1, 50))
    }
    @Test fun whitespaceAndLeadingZeros() = assertEquals(12, SliderInput.parse(" 0012 ", 3, 1, 50))
    @Test fun hugeNumberClampsToMax() = assertEquals(240, SliderInput.parse("99999999999999999999", 3, 1, 240))
    @Test fun allZerosIsZero() = assertEquals(0, SliderInput.parse("000", 3, 0, 10))

    @Test fun halfSecondFormat() {
        assertEquals("0.5", SliderInput.formatHalfSecondSteps(1))
        assertEquals("3.0", SliderInput.formatHalfSecondSteps(6))
        assertEquals("20.0", SliderInput.formatHalfSecondSteps(40))
    }

    @Test fun halfSecondParseExact() {
        assertEquals(6, SliderInput.parseHalfSecondSteps("3", 1, 1, 40))
        assertEquals(6, SliderInput.parseHalfSecondSteps("3.0", 1, 1, 40))
        assertEquals(7, SliderInput.parseHalfSecondSteps("3.5", 1, 1, 40))
        assertEquals(1, SliderInput.parseHalfSecondSteps("0.5", 6, 1, 40))
        assertEquals(40, SliderInput.parseHalfSecondSteps("20", 6, 1, 40))
    }

    @Test fun halfSecondParseRoundsToNearestHalf() {
        assertEquals(6, SliderInput.parseHalfSecondSteps("3.2", 1, 1, 40))
        assertEquals(7, SliderInput.parseHalfSecondSteps("3.3", 1, 1, 40))
        assertEquals(7, SliderInput.parseHalfSecondSteps("3,3", 1, 1, 40))
    }

    @Test fun halfSecondParseClamps() {
        assertEquals(1, SliderInput.parseHalfSecondSteps("0", 6, 1, 40))
        assertEquals(1, SliderInput.parseHalfSecondSteps("0.1", 6, 1, 40))
        assertEquals(40, SliderInput.parseHalfSecondSteps("99", 6, 1, 40))
    }

    @Test fun halfSecondParseInvalidReverts() {
        assertEquals(6, SliderInput.parseHalfSecondSteps("", 6, 1, 40))
        assertEquals(6, SliderInput.parseHalfSecondSteps(null, 6, 1, 40))
        assertEquals(6, SliderInput.parseHalfSecondSteps("abc", 6, 1, 40))
        assertEquals(6, SliderInput.parseHalfSecondSteps("3.5.1", 6, 1, 40))
        assertEquals(6, SliderInput.parseHalfSecondSteps("-3", 6, 1, 40))
    }
}
