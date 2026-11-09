package code.name.monkey.retromusic.loudness

data class LoudnessDocument(
    val format: String,
    val schemaVersion: Int,
    val exportedAt: String,
    val collisionKeyAlgorithm: String?,
    val collisionKey: CollisionKeyInfo?,
    val source: DocumentSource,
    val roots: List<RootInfo>,
    val tracks: List<TrackEntry>,
    val coverage: CoverageInfo?,
)

data class CollisionKeyInfo(
    val caseFold: String?,
    val nfc: String?,
    val rootId: String?,
    val relativePath: String?,
)

data class DocumentSource(
    val kind: String,
    val gainOrigin: String,
    val gainUnits: String,
    val serverVersion: String? = null,
    val lufsDerivedReferenceLufs: Double? = null,
    val referenceLufs: Double? = null,
    val analyzer: String? = null,
    val analyzerVersion: String? = null,
    val producer: String? = null,
    val producerVersion: String? = null,
)

data class RootInfo(val id: String)

data class TrackSource(
    val kind: String? = null,
    val analyzer: String? = null,
    val analyzerVersion: String? = null,
    val measuredAt: String? = null,
    val gainOrigin: String? = null,
)

data class TrackEntry(
    val rootId: String,
    val relativePath: String,
    val sourceItemId: String? = null,
    val sourceAlbumId: String? = null,
    val trackGainDb: Double? = null,
    val albumGainDb: Double? = null,
    val measuredLufs: Double? = null,
    val truePeakDbtp: Double? = null,
    val source: TrackSource? = null,
)

data class CoverageInfo(
    val partial: Boolean = false,
    val itemsSeen: Int = 0,
    val entriesExported: Int = 0,
    val missingTrackGain: Int = 0,
    val missingAlbumGain: Int = 0,
    val missingBothGains: Int = 0,
    val unmatched: Int = 0,
    val collisions: Int = 0,
    val outOfRangeGains: Int = 0,
    val rejected: Int = 0,
)

data class ImportPreview(
    val document: LoudnessDocument,
    val matched: Int,
    val unmatchedPaths: List<String>,
    val collisionsDropped: Int,
    val rejected: Int,
    val warnings: List<String>,
)

sealed class ParseResult {
    data class Ok(val document: LoudnessDocument) : ParseResult()
    data class Error(val message: String) : ParseResult()
}
