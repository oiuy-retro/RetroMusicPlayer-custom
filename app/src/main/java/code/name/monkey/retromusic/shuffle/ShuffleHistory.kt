/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.shuffle

import android.content.Context
import java.io.File

/**
 * Song ids already played for the "No repeats" option. Stored as one id per line in
 * files/shuffle_history.txt so it survives restarts. Small and only touched off the main thread
 * by the service, apart from the settings reset button.
 */
object ShuffleHistory {
    private const val FILE = "shuffle_history.txt"
    private var cache: MutableSet<Long>? = null

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    fun played(context: Context): Set<Long> = load(context).toSet()

    @Synchronized
    fun markPlayed(context: Context, id: Long) {
        if (id < 0) return
        if (load(context).add(id)) save(context)
    }

    @Synchronized
    fun forget(context: Context, ids: Collection<Long>) {
        if (load(context).removeAll(ids.toSet())) save(context)
    }

    @Synchronized
    fun reset(context: Context) {
        cache = mutableSetOf()
        file(context).delete()
    }

    private fun load(context: Context): MutableSet<Long> {
        cache?.let { return it }
        val f = file(context)
        val set = if (f.exists()) {
            runCatching { f.readLines().mapNotNull { it.trim().toLongOrNull() }.toMutableSet() }
                .getOrDefault(mutableSetOf())
        } else mutableSetOf()
        cache = set
        return set
    }

    private fun save(context: Context) {
        runCatching { file(context).writeText(load(context).joinToString("\n")) }
    }
}
