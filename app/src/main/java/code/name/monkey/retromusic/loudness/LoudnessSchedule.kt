package code.name.monkey.retromusic.loudness

import java.util.Calendar

/**
 * Daily device-local window. Overnight (e.g. 22:00–07:00) is active when
 * now >= start OR now < end. Equal start/end is invalid (ambiguous).
 */
object LoudnessSchedule {
    fun parseHhMm(value: String): Int? {
        val parts = value.trim().split(':')
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun formatHhMm(minutes: Int): String {
        val m = ((minutes % (24 * 60)) + (24 * 60)) % (24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    fun validateWindow(startHhMm: String, endHhMm: String): String? {
        val s = parseHhMm(startHhMm) ?: return "invalid_start"
        val e = parseHhMm(endHhMm) ?: return "invalid_end"
        if (s == e) return "equal_start_end"
        return null
    }

    fun isActiveNow(
        enabled: Boolean,
        startHhMm: String,
        endHhMm: String,
        nowMinutes: Int = currentMinutes(),
    ): Boolean {
        if (!enabled) return true // schedule off => always allow when normalization itself is on
        if (validateWindow(startHhMm, endHhMm) != null) return false
        val start = parseHhMm(startHhMm)!!
        val end = parseHhMm(endHhMm)!!
        return if (start < end) {
            nowMinutes in start until end
        } else {
            // overnight
            nowMinutes >= start || nowMinutes < end
        }
    }

    fun currentMinutes(cal: Calendar = Calendar.getInstance()): Int =
        cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
}
