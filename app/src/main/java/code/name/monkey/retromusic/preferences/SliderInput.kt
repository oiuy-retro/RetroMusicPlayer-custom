/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.preferences

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
}
