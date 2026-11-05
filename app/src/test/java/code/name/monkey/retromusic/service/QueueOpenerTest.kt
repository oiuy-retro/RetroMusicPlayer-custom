package code.name.monkey.retromusic.service

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import code.name.monkey.retromusic.helper.ShuffleHelper
import code.name.monkey.retromusic.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class QueueOpenerTest {

    private val songs = (0 until 48).map { i ->
        Song(i + 1L, "t$i", i % 12 + 1, 2020, 200_000, "/m/a${i / 12}/$i.mp3", 0,
            i / 12 + 100L, "Album", i / 12 + 200L, "Artist ${i / 12}", null, null)
    }

    @Test
    fun shuffledExactlyOnceWithStartSongFirst() {
        var calls = 0
        val opened = QueueOpener.open(songs, 17, true) { list, cur ->
            calls++
            assertEquals(songs[17], list[cur])
            val s = list.removeAt(cur); list.shuffle(); list.add(0, s)
        }!!
        assertEquals(1, calls)
        assertEquals(0, opened.position)
        assertEquals(songs[17], opened.playing[0])
        assertEquals(songs, opened.original)
        assertEquals(songs.toSet(), opened.playing.toSet())
    }

    @Test
    fun startSongIsFirstEvenIfShufflerMovesIt() {
        val opened = QueueOpener.open(songs, 5, true) { list, _ -> list.reverse() }!!
        assertEquals(songs[5], opened.playing[0])
        assertEquals(48, opened.playing.size)
    }

    @Test
    fun shuffleOffKeepsOrderAndTappedPosition() {
        var calls = 0
        val opened = QueueOpener.open(songs, 9, false) { _, _ -> calls++ }!!
        assertEquals(0, calls)
        assertEquals(9, opened.position)
        assertEquals(songs, opened.playing)
    }

    @Test
    fun invalidInputIsIgnored() {
        assertNull(QueueOpener.open(emptyList<Song>(), 0, true) { _, _ -> })
        assertNull(QueueOpener.open(songs, 48, true) { _, _ -> })
    }

    /** Real shuffle code with album blocks of 2: playing song first, Up Next = the rest of the same list. */
    @Test
    fun realShuffleUpNextMatchesQueue() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear()
            .putString("shuffle_mode_type", "group_blocks")
            .putInt("shuffle_group_min", 2).putInt("shuffle_group_max", 2).commit()
        repeat(10) { start ->
            var calls = 0
            val opened = QueueOpener.open(songs, start * 4, true) { list, cur ->
                calls++; ShuffleHelper.makeShuffleList(ctx, list, cur)
            }!!
            assertEquals(1, calls)
            assertEquals(songs[start * 4], opened.playing[opened.position])
            val upNext = opened.playing.drop(opened.position + 1)
            assertEquals(47, upNext.size)
            assertTrue(songs[start * 4] !in upNext)
            // Blocks of at most 2 from one album (the last album left may run together).
            val runs = mutableListOf<Int>(); var last = -1L
            for (s in opened.playing) { if (s.albumId == last) runs[runs.size - 1]++ else runs.add(1); last = s.albumId }
            runs.dropLast(1).forEach { assertTrue("$runs", it <= 2) }
        }
    }
}
