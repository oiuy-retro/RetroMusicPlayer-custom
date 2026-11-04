package code.name.monkey.retromusic.shuffle

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import code.name.monkey.retromusic.helper.ShuffleHelper
import code.name.monkey.retromusic.model.Song
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Songs tab FAB: LibraryViewModel.shuffleSongs -> openAndShuffleQueue -> MusicService shuffles. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FabShufflePathTest {

    private fun library(): List<Song> = (0 until 60).map { i ->
        val album = i / 12
        Song(i.toLong() + 1, "t$i", i % 12 + 1, 2020, 200_000, "/m/a$album/${i % 12}.mp3", 0,
            album.toLong() + 100, "Album $album", album.toLong() + 200, "Artist $album", null, null)
    }

    private fun runs(q: List<Song>, key: (Song) -> Any): List<Int> {
        val out = mutableListOf<Int>(); var last: Any? = null
        for (s in q) { val k = key(s); if (k == last) out[out.size - 1]++ else out.add(1); last = k }
        return out
    }

    private fun drive(mode: String, minKey: String, maxKey: String, key: (Song) -> Any) {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear()
            .putString("shuffle_mode_type", mode)
            .putInt(minKey, 2).putInt(maxKey, 2)
            .commit()
        repeat(10) {
            // Same steps as MusicService.openQueue (shuffle on) then setShuffleMode(SHUFFLE).
            val queue = ArrayList(library())
            val start = it * 5
            ShuffleHelper.makeShuffleList(ctx, queue, start)
            ShuffleHelper.makeShuffleList(ctx, queue, 0)
            val r = runs(queue, key)
            r.dropLast(1).forEach { n -> assertTrue("$mode runs $r", n <= 2) }
            // The block engine ran (not the plain-random fallback): nearly all blocks have 2 songs.
            assertTrue("$mode runs $r", r.count { n -> n == 2 } >= 25)
        }
    }

    @Test fun albumBlocksLimitTwo() = drive("group_blocks", "shuffle_group_min", "shuffle_group_max") { it.albumId }
    @Test fun artistBlocksLimitTwo() = drive("artist_blocks", "shuffle_artist_min", "shuffle_artist_max") { it.artistName }
}
