/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.preferences

import java.util.Locale
import kotlin.math.roundToInt

/** Pure parse/clamp for typed slider values. */
object SliderInput {
    /**
     * Returns the value to apply for [text] typed into a slider's box: digits only, clamped to
     * [min]..[max]. Empty or invalid input returns [current].
     */
    fun parse(text: CharSequence?, current: Int, min: Int, max: Int): Int {
        val s = text?.toString()?.trim().orEmpty()
        if (s.isEmpty() || !s.all { it.isDigit() }) return current
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        // Very long numbers overflow Int; treat them as "as big as possible".
        val n = s.trimStart('0').let { if (it.length > 9) Long.MAX_VALUE else (it.toLongOrNull() ?: 0L) }
        return n.coerceIn(lo.toLong(), hi.toLong()).toInt()
    }

    /** Formats a stored half-second step count (1 = 0.5 s) as one decimal place, e.g. 6 → "3.0". */
    fun formatHalfSecondSteps(steps: Int): String =
        String.format(Locale.getDefault(), "%.1f", steps.coerceAtLeast(0) * 0.5)

    /**
     * Parses a typed seconds value for a half-second-step slider. Accepts one decimal (`.` or `,`),
     * rounds to the nearest 0.5 s, clamps to [min]..[max] steps, and returns the stored step count.
     * Empty or invalid input returns [current].
     */
    fun parseHalfSecondSteps(text: CharSequence?, current: Int, min: Int, max: Int): Int {
        val s = text?.toString()?.trim().orEmpty()
        if (s.isEmpty()) return current
        val normalized = s.replace(',', '.')
        if (!HALF_SECOND_PATTERN.matches(normalized)) return current
        val seconds = normalized.toDoubleOrNull() ?: return current
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        return (seconds * 2.0).roundToInt().coerceIn(lo, hi)
    }

    private val HALF_SECOND_PATTERN = Regex("""\d+(\.\d+)?|\.\d+""")
}
