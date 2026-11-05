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
}
