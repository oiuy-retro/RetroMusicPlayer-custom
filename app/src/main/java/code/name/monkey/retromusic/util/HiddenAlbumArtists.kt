/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.util

/**
 * One artist hidden from the Albums tab. [untilMillis] is the wall-clock time (ms since epoch)
 * when the hide ends, or [FOREVER] for "until I unhide".
 */
data class HiddenArtist(val name: String, val untilMillis: Long) {
    val key: String get() = ArtistSeparator.key(name)
    val isForever: Boolean get() = untilMillis == FOREVER
    fun isExpired(now: Long): Boolean = !isForever && untilMillis <= now

    companion object {
        const val FOREVER = 0L
    }
}

/**
 * Pure rules for "Hide artist's albums" in the Albums tab.
 *
 * Which artist an album belongs to: the album's album artist tag, or the track artist when the
 * album artist tag is empty (the same name the Albums grid shows under each album). That name is
 * split the way the library splits artists, on ";" only, so "Artist A; Artist B" counts as both
 * Artist A and Artist B, while "AC/DC" stays one artist. Names match case-insensitively.
 *
 * An album is hidden when any of its artists is hidden, so hiding Artist A also hides an
 * "Artist A; Artist B" album.
 */
object HiddenAlbumArtists {
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val CUSTOM_DAYS_MIN = 1
    const val CUSTOM_DAYS_MAX = 365
    const val CUSTOM_DAYS_DEFAULT = 14

    /** Artist names for an album, from its album artist (or artist, if album artist is blank). */
    fun artistsFor(albumArtist: String?, artistName: String?): List<String> {
        val source = if (albumArtist.isNullOrBlank()) artistName else albumArtist
        return ArtistSeparator.splitLibrary(source)
    }

    fun isHidden(albumArtist: String?, artistName: String?, hiddenKeys: Set<String>): Boolean {
        if (hiddenKeys.isEmpty()) return false
        return artistsFor(albumArtist, artistName).any { ArtistSeparator.key(it) in hiddenKeys }
    }

    /** Drops hides whose time has run out. */
    fun prune(hidden: List<HiddenArtist>, now: Long): List<HiddenArtist> =
        hidden.filterNot { it.isExpired(now) }

    /** End time for a hide lasting [days] days from [now]; days <= 0 means until unhidden. */
    fun untilFor(days: Int, now: Long): Long =
        if (days <= 0) HiddenArtist.FOREVER else now + days * DAY_MS

    /**
     * Adds [names] with the same end time. An artist already hidden is updated to the new
     * end time, keeping one entry per artist. Order: existing entries first, then new ones.
     */
    fun add(hidden: List<HiddenArtist>, names: Collection<String>, untilMillis: Long): List<HiddenArtist> {
        val byKey = LinkedHashMap<String, HiddenArtist>()
        hidden.forEach { byKey[it.key] = it }
        names.map { it.trim() }.filter { it.isNotEmpty() }.forEach { name ->
            val key = ArtistSeparator.key(name)
            val existingName = byKey[key]?.name ?: name
            byKey[key] = HiddenArtist(existingName, untilMillis)
        }
        return byKey.values.toList()
    }

    fun remove(hidden: List<HiddenArtist>, keys: Set<String>): List<HiddenArtist> =
        hidden.filterNot { it.key in keys }
}
