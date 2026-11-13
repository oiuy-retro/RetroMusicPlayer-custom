package code.name.monkey.retromusic.loudness

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStreamReader

/**
 * Runs every entry of the exporter's jle-audio-v1 vectors.json (pinned commit
 * 6af8e0c8d4f2e6e011369daaf81aaf80e7dbeefc) against the copied fixture clips.
 */
class JleAudioHashTest {
    private fun fixture(name: String): File {
        val url = javaClass.classLoader!!.getResource("jle-audio/$name")
            ?: error("missing fixture $name")
        return File(url.toURI())
    }

    private data class Case(val file: String, val group: String?, val hash: String?)

    private fun cases(): List<Case> {
        val stream = javaClass.classLoader!!.getResourceAsStream("jle-audio/vectors.json")!!
        val root = JsonParser.parseReader(InputStreamReader(stream, Charsets.UTF_8)).asJsonObject
        assertEquals(JleAudioHash.ALGORITHM, root.get("algorithm").asString)
        return root.getAsJsonArray("cases").map { el ->
            val o = el.asJsonObject
            Case(
                file = o.get("file").asString,
                group = o.get("group")?.takeUnless { it.isJsonNull }?.asString,
                hash = o.get("audioSha256")?.takeUnless { it.isJsonNull }?.asString,
            )
        }
    }

    @Test
    fun allVectorsMatch() {
        val all = cases()
        assertEquals(39, all.size)
        val failures = ArrayList<String>()
        for (c in all) {
            val got = JleAudioHash.hashFile(fixture(c.file)).hexOrNull
            if (got != c.hash) failures.add("${c.file}: expected ${c.hash}, got $got")
        }
        println("jle-audio-v1 vectors: ${all.size - failures.size}/${all.size} passed")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun groupsShareOneHash() {
        cases().filter { it.group != null }.groupBy { it.group }.forEach { (g, list) ->
            val hashes = list.map { JleAudioHash.hashFile(fixture(it.file)).hexOrNull }.toSet()
            assertEquals("group $g", 1, hashes.size)
        }
    }

    @Test
    fun missingFileIsUnreadable() {
        val r = JleAudioHash.hashFile(File("/nonexistent/jle-audio-test.flac"))
        assertEquals(JleAudioHash.Result.NoHash("unreadable"), r)
    }

    @Test
    fun emptyAndTinyInputsGiveNoHash() {
        for (bytes in listOf(ByteArray(0), "OggS".toByteArray(), "ID3".toByteArray(), byteArrayOf(-1, -5))) {
            val f = File.createTempFile("jle", ".bin")
            try {
                f.writeBytes(bytes)
                assertNull(JleAudioHash.hashFile(f).hexOrNull)
            } finally {
                f.delete()
            }
        }
    }

    @Test
    fun hexValidation() {
        assertTrue(JleAudioHash.isValidHex("0".repeat(64)))
        assertTrue(!JleAudioHash.isValidHex("A".repeat(64)))
        assertTrue(!JleAudioHash.isValidHex("0".repeat(63)))
        assertTrue(!JleAudioHash.isValidHex(null))
    }
}
