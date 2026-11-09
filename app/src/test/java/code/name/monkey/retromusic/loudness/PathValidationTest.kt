package code.name.monkey.retromusic.loudness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PathValidationTest {
    @Test
    fun rejectsTraversalAndAbsolute() {
        assertEquals("dot_segment", PathValidation.validateRelative("../x.flac"))
        assertEquals("absolute_path", PathValidation.validateRelative("C:/Music/a.flac"))
        assertEquals("leading_slash", PathValidation.validateRelative("/a.flac"))
        assertEquals("nul_in_path", PathValidation.validateRelative("a\u0000b.flac"))
        assertNull(PathValidation.validateRelative("Artist/Album/01.flac"))
    }

    @Test
    fun stripRootBoundaryAware() {
        assertEquals(
            "A/b.flac",
            PathValidation.stripRootPrefix("/storage/emulated/0/Music/A/b.flac", "/storage/emulated/0/Music")
        )
        assertNull(PathValidation.stripRootPrefix("/other/A/b.flac", "/storage/emulated/0/Music"))
        // Must not treat MusicBackup as under Music
        assertNull(
            PathValidation.stripRootPrefix(
                "/storage/emulated/0/MusicBackup/a.flac",
                "/storage/emulated/0/Music"
            )
        )
        // Case-sensitive ordinal match
        assertNull(
            PathValidation.stripRootPrefix(
                "/storage/emulated/0/music/a.flac",
                "/storage/emulated/0/Music"
            )
        )
    }
}
