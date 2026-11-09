package code.name.monkey.retromusic.loudness

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader

class CollisionKeyTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun load() {
            val stream = CollisionKeyTest::class.java.classLoader!!
                .getResourceAsStream("CaseFolding-15.1.0.txt")!!
            CollisionKey.loadFromReader(BufferedReader(InputStreamReader(stream, Charsets.UTF_8)))
        }
    }

    @Test
    fun pinnedVectorsMatch() {
        val json = CollisionKeyTest::class.java.classLoader!!
            .getResourceAsStream("loudness-format/collision-key-vectors.json")!!
            .reader().readText()
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals("jf-ck-v1", root.get("algorithm").asString)
        for (el in root.getAsJsonArray("vectors")) {
            val v = el.asJsonObject
            val rootId = v.get("rootId").asString
            val relative = v.get("relativePath").asString
            val expected = v.get("expectedCollisionKey").asString
            assertEquals(v.get("name").asString, expected, CollisionKey.build(rootId, relative))
        }
    }

    @Test
    fun rootIdOrdinalNotFolded() {
        assertNotEquals(
            CollisionKey.build("music", "a.flac"),
            CollisionKey.build("Music", "a.flac"),
        )
    }
}
