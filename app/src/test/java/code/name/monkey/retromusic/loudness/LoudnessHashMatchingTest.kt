package code.name.monkey.retromusic.loudness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader

class LoudnessHashMatchingTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun load() {
            val stream = LoudnessHashMatchingTest::class.java.classLoader!!
                .getResourceAsStream("CaseFolding-15.1.0.txt")!!
            CollisionKey.loadFromReader(BufferedReader(InputStreamReader(stream, Charsets.UTF_8)))
        }

        private val H1 = "1".repeat(64)
        private val H2 = "2".repeat(64)
        private val H3 = "3".repeat(64)

        fun doc(algorithm: String?, vararg tracks: Pair<String, String?>): String = buildString {
            append("""{"format":"jellyfin-loudness-export","schemaVersion":1,"exportedAt":"2026-11-12T00:00:00Z",""")
            if (algorithm != null) append(""""audioHashAlgorithm":"$algorithm",""")
            append(""""source":{"kind":"jellyfin-api","gainOrigin":"lufs-or-replaygain-unspecified","gainUnits":"dB"},""")
            append(""""roots":[{"id":"music"}],"tracks":[""")
            append(tracks.joinToString(",") { (p, h) ->
                """{"rootId":"music","relativePath":"$p","trackGainDb":-2.0,"futureField":{"x":1}""" +
                    (if (h != null) ""","audioSha256":"$h"""" else "") + "}"
            })
            val hashed = tracks.count { it.second != null }
            append("""],"coverage":{"partial":false,"itemsSeen":${tracks.size},"entriesExported":${tracks.size},""")
            append(""""hashed":$hashed,"hashUnavailable":${tracks.size - hashed}}}""")
        }

        fun parse(s: String) = (LoudnessJson.parse(s.reader()) as ParseResult.Ok).document
    }

    @Test
    fun parsesAdditiveHashFields() {
        val d = parse(doc("jle-audio-v1", "a.flac" to H1, "b.flac" to null))
        assertEquals("jle-audio-v1", d.audioHashAlgorithm)
        assertTrue(d.audioHashesUsable)
        assertEquals(H1, d.tracks[0].audioSha256)
        assertNull(d.tracks[1].audioSha256)
        assertEquals(1, d.coverage!!.hashed)
    }

    @Test
    fun invalidHashIsDroppedAndCounted() {
        val d = parse(doc("jle-audio-v1", "a.flac" to "A".repeat(64), "b.flac" to "12"))
        assertNull(d.tracks[0].audioSha256)
        assertEquals(2, d.ignoredInvalidAudioHashes)
    }

    @Test
    fun hashFirstThenPathThenUnmatched() {
        val d = parse(doc("jle-audio-v1", "a.flac" to H1, "b.flac" to H2, "c.flac" to null, "d.flac" to H3))
        // H1 is on the phone twice (byte-identical copies); H2 not on phone but its path exists.
        val s = LoudnessMatching.summarize(d, mapOf(H1 to 2), pathExists = { it.relativePath != "d.flac" })
        assertEquals(LoudnessMatching.HashStatus.USED, s.hashStatus)
        assertEquals(1, s.matchedByHash)
        assertEquals(2, s.phoneFilesMatchedByHash)
        assertEquals(2, s.matchedByPath) // b (hash miss, path hit) and c (no hash, path hit)
        assertEquals(1, s.unmatched) // d
        assertEquals(1, s.hashUnavailable) // c
    }

    @Test
    fun unknownAlgorithmFallsBackToPaths() {
        val d = parse(doc("jle-audio-v9", "a.flac" to H1))
        val s = LoudnessMatching.summarize(d, mapOf(H1 to 1), pathExists = { true })
        assertEquals(LoudnessMatching.HashStatus.UNKNOWN_ALGORITHM, s.hashStatus)
        assertEquals(0, s.matchedByHash)
        assertEquals(1, s.matchedByPath)
        assertEquals(0, s.hashUnavailable)
    }

    @Test
    fun missingAlgorithmIgnoresStrayHashes() {
        val d = parse(doc(null, "a.flac" to H1))
        val s = LoudnessMatching.summarize(d, mapOf(H1 to 1), pathExists = null)
        assertEquals(LoudnessMatching.HashStatus.ABSENT, s.hashStatus)
        assertEquals(0, s.matchedByHash)
        assertEquals(1, s.unmatched)
    }

    @Test
    fun writeRoundTripsHashes() {
        val d = parse(doc("jle-audio-v1", "a.flac" to H1, "b.flac" to null))
        val again = parse(LoudnessJson.write(d))
        assertEquals("jle-audio-v1", again.audioHashAlgorithm)
        assertEquals(H1, again.tracks[0].audioSha256)
        assertNull(again.tracks[1].audioSha256)
        assertEquals(1, again.coverage!!.hashed)
        assertEquals(1, again.coverage!!.hashUnavailable)
    }

    @Test
    fun writeWithoutAlgorithmOmitsHashFields() {
        val d = parse(doc("jle-audio-v1", "a.flac" to H1)).copy(audioHashAlgorithm = null)
        val text = LoudnessJson.write(d)
        assertTrue(!text.contains("audioSha256") && !text.contains("hashUnavailable"))
    }
}
