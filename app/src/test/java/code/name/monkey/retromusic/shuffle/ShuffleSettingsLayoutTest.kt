package code.name.monkey.retromusic.shuffle

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Regression: in beta.2/beta.3 the "Maximum songs per block" slider of Folder/album and Artist
 * blocks (and Genre's maximum minutes) ended up below the time sliders and without its switch
 * dependency, so the screen read "limit by songs: minimum ..., limit by time ..., maximum ...".
 */
class ShuffleSettingsLayoutTest {
    private val android = "http://schemas.android.com/apk/res/android"

    @Test
    fun eachLimitGroupIsSwitchMinMaxInOrder() {
        val f = listOf("src/main/res/xml/pref_shuffle.xml", "app/src/main/res/xml/pref_shuffle.xml")
            .map(::File).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f)
        val all = doc.getElementsByTagName("*")
        val keys = ArrayList<Pair<String, String>>()
        for (i in 0 until all.length) {
            val e = all.item(i) as org.w3c.dom.Element
            val k = e.getAttributeNS(android, "key")
            if (k.isNotEmpty()) keys += k to e.getAttributeNS(android, "dependency")
        }
        fun check(m: String, minS: String, maxS: String, minT: String, maxT: String) {
            val i = keys.indexOfFirst { it.first == "shuffle_${m}_use_songs" }
            assertEquals(
                listOf("shuffle_${m}_use_songs" to "", minS to "shuffle_${m}_use_songs", maxS to "shuffle_${m}_use_songs",
                    "shuffle_${m}_use_time" to "", minT to "shuffle_${m}_use_time", maxT to "shuffle_${m}_use_time"),
                keys.subList(i, i + 6))
        }
        val i = keys.indexOfFirst { it.first == "shuffle_group_max_minutes" }
        assertEquals(listOf("shuffle_group_use_percent" to "",
            "shuffle_group_min_percent" to "shuffle_group_use_percent",
            "shuffle_group_max_percent" to "shuffle_group_use_percent"), keys.subList(i + 1, i + 4))
        check("group", "shuffle_group_min", "shuffle_group_max", "shuffle_group_min_minutes", "shuffle_group_max_minutes")
        check("artist", "shuffle_artist_min", "shuffle_artist_max", "shuffle_artist_min_minutes", "shuffle_artist_max_minutes")
        check("genre", "shuffle_genre_min", "shuffle_genre_max", "shuffle_genre_min_minutes", "shuffle_genre_max_minutes")
    }
}
