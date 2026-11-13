package code.name.monkey.retromusic.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenAlbumArtistsTest {
    private val keys = setOf("artist a")

    @Test fun usesAlbumArtistFirst() {
        assertEquals(listOf("Artist A"), HiddenAlbumArtists.artistsFor("Artist A", "Someone Else"))
        assertTrue(HiddenAlbumArtists.isHidden("Artist A", "Someone Else", keys))
        // Album artist set to someone else: track artist alone does not hide it.
        assertFalse(HiddenAlbumArtists.isHidden("Various Artists", "Artist A", keys))
    }

    @Test fun fallsBackToArtistWhenAlbumArtistBlank() {
        assertTrue(HiddenAlbumArtists.isHidden(null, "Artist A", keys))
        assertTrue(HiddenAlbumArtists.isHidden("  ", "Artist A", keys))
    }

    @Test fun caseInsensitive() = assertTrue(HiddenAlbumArtists.isHidden("ARTIST a ", null, keys))

    @Test fun multiArtistSplitsOnSemicolonOnly() {
        assertEquals(listOf("Artist A", "Artist B"), HiddenAlbumArtists.artistsFor("Artist A; Artist B", null))
        assertTrue(HiddenAlbumArtists.isHidden("Artist B;Artist A", null, keys))
        assertEquals(listOf("AC/DC"), HiddenAlbumArtists.artistsFor("AC/DC", null))
        assertFalse(HiddenAlbumArtists.isHidden("Artist A/Artist C", null, keys))
    }

    @Test fun nothingHiddenWithNoKeys() = assertFalse(HiddenAlbumArtists.isHidden("Artist A", null, emptySet()))

    @Test fun expiry() {
        val now = 1_000_000L
        val list = listOf(
            HiddenArtist("Forever", HiddenArtist.FOREVER),
            HiddenArtist("Past", now - 1),
            HiddenArtist("Exactly now", now),
            HiddenArtist("Future", now + 1)
        )
        assertEquals(listOf("Forever", "Future"), HiddenAlbumArtists.prune(list, now).map { it.name })
    }

    @Test fun untilFor() {
        assertEquals(HiddenArtist.FOREVER, HiddenAlbumArtists.untilFor(0, 5L))
        assertEquals(5L + 7 * HiddenAlbumArtists.DAY_MS, HiddenAlbumArtists.untilFor(7, 5L))
        assertEquals(5L + 365 * HiddenAlbumArtists.DAY_MS, HiddenAlbumArtists.untilFor(365, 5L))
    }

    @Test fun addUpdatesExistingAndDedupes() {
        val start = listOf(HiddenArtist("Artist A", 10L))
        val result = HiddenAlbumArtists.add(start, listOf("artist a", "Artist B", "Artist B", " "), 99L)
        assertEquals(listOf(HiddenArtist("Artist A", 99L), HiddenArtist("Artist B", 99L)), result)
    }

    @Test fun remove() {
        val list = listOf(HiddenArtist("Artist A", 0L), HiddenArtist("Artist B", 0L))
        assertEquals(listOf("Artist B"), HiddenAlbumArtists.remove(list, setOf("artist a")).map { it.name })
        assertTrue(HiddenAlbumArtists.remove(list, list.map { it.key }.toSet()).isEmpty())
    }
}
