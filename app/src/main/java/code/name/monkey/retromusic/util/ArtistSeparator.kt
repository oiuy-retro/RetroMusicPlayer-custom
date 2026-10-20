package code.name.monkey.retromusic.util

import code.name.monkey.retromusic.model.Song
import java.util.Locale

/**
 * Utility for splitting multi-artist strings into individual artist names.
 * 
 * Handles common separators used in audio tags like "/", "; ", ";", ",", "feat.", "ft."
 */
object ArtistSeparator {

    /**
     * Array of separators commonly used to delimit multiple artists in metadata.
     * "; " (and a bare ";") is the standard multi-value separator for artist tags.
     */
    val SEPARATORS: Array<String> = arrayOf("; ", ";", "/", ",", "feat.", "ft.")

    private val regex = SEPARATORS.joinToString("|") { 
        Regex.escape(it) 
    }.toRegex(RegexOption.IGNORE_CASE)

    /**
     * Separator used when grouping songs into library artists (artist list, album artists,
     * artist details, search). Deliberately limited to ";" (with any surrounding whitespace,
     * so "A; B", "A;B" and "A ; B" all split) so that names such as "AC/DC" or
     * "Earth, Wind & Fire" keep showing up as a single artist in the library.
     */
    private val libraryRegex = "\\s*;\\s*".toRegex()

    /** Synthetic artist ids (for artists that only exist inside multi-artist tags) are <= -this. */
    private const val SYNTHETIC_ID_OFFSET = 1000L

    /**
     * Splits artist names by common separators.
     * 
     * Examples:
     * - `"Artist1 / Artist2"` → `["Artist1", "Artist2"]`
     * - `"Artist1; Artist2"` → `["Artist1", "Artist2"]`
     * - `"Artist1 feat. Artist2"` → `["Artist1", "Artist2"]`
     * - `"Artist1, Artist2, Artist3"` → `["Artist1", "Artist2", "Artist3"]`
     * 
     * @param artistString The artist name(s) to split
     * @return List of individual artist names, trimmed and deduplicated. 
     *         Returns empty list if input is null or empty.
     */
    fun split(artistString: String?): List<String> {
        if (artistString.isNullOrEmpty()) {
            return emptyList()
        }

        return artistString.split(regex)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * Splits an artist tag into the artists used for the music library, using "; " / ";".
     *
     * - `"Artist A; Artist B"` → `["Artist A", "Artist B"]`
     * - `"Artist A;Artist B;"` → `["Artist A", "Artist B"]`
     * - `"AC/DC"` → `["AC/DC"]`
     */
    fun splitLibrary(artistString: String?): List<String> {
        if (artistString.isNullOrBlank()) {
            return emptyList()
        }
        return artistString.split(libraryRegex)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { key(it) }
    }

    /** True when the tag names more than one library artist (e.g. "Artist A; Artist B"). */
    fun isMultiArtist(artistString: String?): Boolean = splitLibrary(artistString).size > 1

    /** True when [artistString] names [artistName] as one of its library artists. */
    fun containsArtist(artistString: String?, artistName: String): Boolean {
        val target = key(artistName)
        return splitLibrary(artistString).any { key(it) == target }
    }

    /** Normalised key used to match artist names case-insensitively. */
    fun key(artistName: String): String = artistName.trim().lowercase(Locale.ROOT)

    /** Stable negative id for an artist that only exists as part of a multi-artist tag. */
    fun syntheticArtistId(artistName: String): Long =
        -(SYNTHETIC_ID_OFFSET + (key(artistName).hashCode().toLong() and 0x7FFFFFFFL))

    fun isSyntheticArtistId(artistId: Long): Boolean = artistId <= -SYNTHETIC_ID_OFFSET

    /**
     * Artist id to open for [song]. Single-artist songs keep using their MediaStore artist id.
     * For multi-artist songs the artist matching [clickedName] (or the first one) is used.
     */
    fun navigationArtistId(song: Song, clickedName: String? = null): Long {
        val names = splitLibrary(song.artistName)
        if (names.size <= 1) return song.artistId
        val target = clickedName?.let { clicked ->
            names.firstOrNull { key(it) == key(clicked) }
                ?: names.firstOrNull { key(it).contains(key(clicked)) }
        } ?: names.first()
        return syntheticArtistId(target)
    }
}
