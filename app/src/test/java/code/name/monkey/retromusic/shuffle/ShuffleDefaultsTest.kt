package code.name.monkey.retromusic.shuffle

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ShuffleDefaultsTest {
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun newDefaultsAreTwoToFourSongsAndSevenToFifteenMinutes() {
        prefs.edit().clear().commit()
        val c = ShuffleSettings.config(prefs)
        for (l in listOf(c.groupLimits, c.artistLimits, c.genreLimits)) {
            assertEquals(2, l.minSongs); assertEquals(4, l.maxSongs)
            assertEquals(7 * 60_000L, l.minMs); assertEquals(15 * 60_000L, l.maxMs)
        }
        // Switch defaults per mode are unchanged.
        assertEquals(true to false, c.groupLimits.useSongs to c.groupLimits.useTime)
        assertEquals(true to false, c.artistLimits.useSongs to c.artistLimits.useTime)
        assertEquals(false to true, c.genreLimits.useSongs to c.genreLimits.useTime)
    }

    @Test
    fun savedValuesAreKept() {
        prefs.edit().clear().putInt("shuffle_group_min", 3).putInt("shuffle_group_max", 9)
            .putInt("shuffle_genre_min_minutes", 20).putInt("shuffle_genre_max_minutes", 40).commit()
        val c = ShuffleSettings.config(prefs)
        assertEquals(3, c.groupLimits.minSongs); assertEquals(9, c.groupLimits.maxSongs)
        assertEquals(20 * 60_000L, c.genreLimits.minMs); assertEquals(40 * 60_000L, c.genreLimits.maxMs)
    }
}
