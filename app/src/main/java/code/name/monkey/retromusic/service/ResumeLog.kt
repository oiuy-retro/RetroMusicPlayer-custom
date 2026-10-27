package code.name.monkey.retromusic.service

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Temporary diagnostic for "Resume after other media": keeps the last [MAX_LINES] events in
 * memory and in a small file in private storage, so they survive a restart and can be copied
 * from Settings > Audio > Copy resume log.
 */
object ResumeLog {
    const val ENABLED_KEY = "resume_log_enabled"
    const val TAG = "ResumeAfterFocus"
    private const val MAX_LINES = 200
    private const val FILE_NAME = "resume_log.txt"

    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private var appContext: Context? = null
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        appContext = context.applicationContext
        val f = File(context.filesDir, FILE_NAME)
        file = f
        try {
            if (f.exists()) f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun d(message: String) {
        Log.d(TAG, message)
        val ctx = appContext ?: return
        if (!isEnabled(ctx)) return
        lines.addLast("${format.format(Date())} $message")
        while (lines.size > MAX_LINES) lines.removeFirst()
        try {
            file?.writeText(lines.joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {
        }
    }

    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(ENABLED_KEY, false)

    /** Forget all stored events (used when the log is turned off). */
    @Synchronized
    fun clear(context: Context) {
        init(context)
        lines.clear()
        file?.delete()
    }

    @Synchronized
    fun text(context: Context): String {
        init(context)
        return if (lines.isEmpty()) "(resume log is empty)" else lines.joinToString("\n")
    }
}
