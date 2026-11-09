package code.name.monkey.retromusic.loudness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader

class LoudnessJsonTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun load() {
            val stream = LoudnessJsonTest::class.java.classLoader!!
                .getResourceAsStream("CaseFolding-15.1.0.txt")!!
            CollisionKey.loadFromReader(BufferedReader(InputStreamReader(stream, Charsets.UTF_8)))
        }
    }

    @Test
    fun parsesPinnedSharedValidExport() {
        val text = readFixture("loudness-format/shared-valid-export.json")
        val result = LoudnessJson.parse(text.reader())
        assertTrue(result is ParseResult.Ok)
        val doc = (result as ParseResult.Ok).document
        assertEquals(LoudnessSchema.FORMAT, doc.format)
        assertEquals(2, doc.tracks.size)
        assertNull(doc.tracks[1].trackGainDb) // omitted != 0
        assertEquals("jellyfin-api", doc.source.kind)
    }

    @Test
    fun rejectsUnsupportedSchema() {
        val text = readFixture("loudness-format/shared-valid-export.json")
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val result = LoudnessJson.parse(text.reader())
        assertTrue(result is ParseResult.Error)
    }

    @Test
    fun rejectsAbsoluteRelativePath() {
        val bad = """
            {"format":"jellyfin-loudness-export","schemaVersion":1,"exportedAt":"2026-11-08T00:00:00Z",
             "source":{"kind":"android-ebur128","gainOrigin":"measured-lufs","gainUnits":"dB"},
             "roots":[{"id":"music"}],
             "tracks":[{"rootId":"music","relativePath":"/etc/passwd","trackGainDb":-1.0}]}
        """.trimIndent()
        val result = LoudnessJson.parse(bad.reader())
        assertTrue(result is ParseResult.Error)
    }

    @Test
    fun androidRoundTripPreservesMeasuredFields() {
        val doc = LoudnessDocument(
            format = LoudnessSchema.FORMAT,
            schemaVersion = 1,
            exportedAt = "2026-11-08T00:00:00Z",
            collisionKeyAlgorithm = LoudnessSchema.COLLISION_ALGORITHM,
            collisionKey = LoudnessJson.androidCollisionKeyInfo(),
            source = DocumentSource(
                kind = LoudnessSchema.KIND_ANDROID,
                gainOrigin = LoudnessSchema.GAIN_ORIGIN_MEASURED,
                gainUnits = LoudnessSchema.GAIN_UNITS_DB,
                referenceLufs = LoudnessSchema.REFERENCE_LUFS,
                analyzer = LoudnessSchema.ANALYZER_LIBEBUR128,
                analyzerVersion = LoudnessSchema.ANALYZER_VERSION,
                producer = LoudnessSchema.PRODUCER_NAME,
                producerVersion = "test",
            ),
            roots = listOf(RootInfo("music")),
            tracks = listOf(
                TrackEntry(
                    rootId = "music",
                    relativePath = "A/b.flac",
                    trackGainDb = -2.0,
                    measuredLufs = -16.0,
                    truePeakDbtp = -1.0,
                    source = TrackSource(
                        kind = LoudnessSchema.KIND_ANDROID,
                        analyzer = LoudnessSchema.ANALYZER_LIBEBUR128,
                        analyzerVersion = LoudnessSchema.ANALYZER_VERSION,
                        gainOrigin = LoudnessSchema.GAIN_ORIGIN_MEASURED,
                    ),
                )
            ),
            coverage = CoverageInfo(entriesExported = 1, itemsSeen = 1),
        )
        val json = LoudnessJson.write(doc)
        val parsed = LoudnessJson.parse(json.reader()) as ParseResult.Ok
        assertEquals(-16.0, parsed.document.tracks[0].measuredLufs!!, 0.0)
        assertEquals(-1.0, parsed.document.tracks[0].truePeakDbtp!!, 0.0)
        assertEquals(LoudnessSchema.KIND_ANDROID, parsed.document.source.kind)
    }

    private fun readFixture(path: String): String =
        LoudnessJsonTest::class.java.classLoader!!.getResourceAsStream(path)!!.reader().readText()
}
