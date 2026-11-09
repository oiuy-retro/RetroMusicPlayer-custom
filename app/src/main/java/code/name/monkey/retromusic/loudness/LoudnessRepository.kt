package code.name.monkey.retromusic.loudness

import code.name.monkey.retromusic.db.LoudnessDao
import code.name.monkey.retromusic.db.LoudnessEntity
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LoudnessRepository(private val dao: LoudnessDao) {
    private val gson = Gson()

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    suspend fun lookup(rootId: String, relativePath: String): LoudnessEntity? =
        withContext(Dispatchers.IO) { dao.get(rootId, relativePath) }

    /** Exact primary match: try configured rootId + relativePath; also scan same relativePath under other roots. */
    suspend fun lookupExact(rootId: String, relativePath: String): LoudnessEntity? =
        withContext(Dispatchers.IO) {
            dao.get(rootId, relativePath) ?: dao.getByRelativePath(relativePath).singleOrNull()
        }

    /** Exact relativePath suffix match against absolute song path; ambiguous matches return null. */
    suspend fun findBySongPath(songPath: String): LoudnessEntity? = withContext(Dispatchers.IO) {
        val norm = PathValidation.normalizeSeparators(songPath)
        val all = dao.getAll()
        val hits = all.filter { e ->
            val rel = e.relativePath
            norm == rel || norm.endsWith("/$rel")
        }
        hits.singleOrNull()
    }

    suspend fun importDocument(document: LoudnessDocument): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val rows = document.tracks.map { t ->
            LoudnessEntity(
                rootId = t.rootId,
                relativePath = t.relativePath,
                trackGainDb = t.trackGainDb,
                albumGainDb = t.albumGainDb,
                measuredLufs = t.measuredLufs,
                truePeakDbtp = t.truePeakDbtp,
                sourceItemId = t.sourceItemId,
                sourceAlbumId = t.sourceAlbumId,
                documentKind = document.source.kind,
                documentGainOrigin = document.source.gainOrigin,
                trackSourceJson = t.source?.let { gson.toJson(it) },
                updatedAt = now,
            )
        }
        dao.replaceAllAtomic(rows)
        rows.size
    }

    suspend fun upsertMeasured(
        rootId: String,
        relativePath: String,
        measuredLufs: Double,
        truePeakDbtp: Double?,
        trackGainDb: Double,
    ) = withContext(Dispatchers.IO) {
        val existing = dao.get(rootId, relativePath)
        dao.upsert(
            LoudnessEntity(
                rootId = rootId,
                relativePath = relativePath,
                trackGainDb = trackGainDb,
                albumGainDb = existing?.albumGainDb,
                measuredLufs = measuredLufs,
                truePeakDbtp = truePeakDbtp,
                sourceItemId = existing?.sourceItemId,
                sourceAlbumId = existing?.sourceAlbumId,
                documentKind = LoudnessSchema.KIND_ANDROID,
                documentGainOrigin = LoudnessSchema.GAIN_ORIGIN_MEASURED,
                trackSourceJson = gson.toJson(
                    TrackSource(
                        kind = LoudnessSchema.KIND_ANDROID,
                        analyzer = LoudnessSchema.ANALYZER_LIBEBUR128,
                        analyzerVersion = LoudnessSchema.ANALYZER_VERSION,
                        measuredAt = LoudnessJson.nowExportedAt(),
                        gainOrigin = LoudnessSchema.GAIN_ORIGIN_MEASURED,
                    )
                ),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun exportDocument(
        producerVersion: String,
        rootIdFilter: String? = null,
    ): LoudnessDocument = withContext(Dispatchers.IO) {
        val all = dao.getAll().let { list ->
            if (rootIdFilter == null) list else list.filter { it.rootId == rootIdFilter }
        }
        // Collision gate jf-ck-v1
        val groups = all.groupBy { CollisionKey.build(it.rootId, it.relativePath) }
        val kept = all.filter { (groups[CollisionKey.build(it.rootId, it.relativePath)]?.size ?: 0) == 1 }
        val collisions = all.size - kept.size
        val roots = kept.map { it.rootId }.distinct().sorted().map { RootInfo(it) }
        var missingTrack = 0
        var missingAlbum = 0
        var missingBoth = 0
        var outOfRange = 0
        val tracks = kept.map { e ->
            if (e.trackGainDb == null) missingTrack++
            if (e.albumGainDb == null) missingAlbum++
            if (e.trackGainDb == null && e.albumGainDb == null) missingBoth++
            val g = listOfNotNull(e.trackGainDb, e.albumGainDb)
            if (g.any { kotlin.math.abs(it) > LoudnessSchema.OUT_OF_RANGE_ABS }) outOfRange++
            TrackEntry(
                rootId = e.rootId,
                relativePath = e.relativePath,
                sourceItemId = e.sourceItemId,
                sourceAlbumId = e.sourceAlbumId,
                trackGainDb = e.trackGainDb,
                albumGainDb = e.albumGainDb,
                measuredLufs = e.measuredLufs,
                truePeakDbtp = e.truePeakDbtp,
                source = e.trackSourceJson?.let {
                    try { gson.fromJson(it, TrackSource::class.java) } catch (_: Exception) { null }
                },
            )
        }
        val kind = kept.firstOrNull()?.documentKind ?: LoudnessSchema.KIND_ANDROID
        val gainOrigin = kept.firstOrNull()?.documentGainOrigin
            ?: if (kind == LoudnessSchema.KIND_ANDROID) LoudnessSchema.GAIN_ORIGIN_MEASURED
            else LoudnessSchema.GAIN_ORIGIN_JELLYFIN
        LoudnessDocument(
            format = LoudnessSchema.FORMAT,
            schemaVersion = LoudnessSchema.SCHEMA_VERSION,
            exportedAt = LoudnessJson.nowExportedAt(),
            collisionKeyAlgorithm = LoudnessSchema.COLLISION_ALGORITHM,
            collisionKey = LoudnessJson.androidCollisionKeyInfo(),
            source = DocumentSource(
                kind = kind,
                gainOrigin = gainOrigin,
                gainUnits = LoudnessSchema.GAIN_UNITS_DB,
                referenceLufs = if (kind == LoudnessSchema.KIND_ANDROID) LoudnessSchema.REFERENCE_LUFS else null,
                lufsDerivedReferenceLufs = if (kind == LoudnessSchema.KIND_JELLYFIN) LoudnessSchema.REFERENCE_LUFS else null,
                analyzer = if (kind == LoudnessSchema.KIND_ANDROID) LoudnessSchema.ANALYZER_LIBEBUR128 else null,
                analyzerVersion = if (kind == LoudnessSchema.KIND_ANDROID) LoudnessSchema.ANALYZER_VERSION else null,
                producer = if (kind == LoudnessSchema.KIND_ANDROID) LoudnessSchema.PRODUCER_NAME else null,
                producerVersion = if (kind == LoudnessSchema.KIND_ANDROID) producerVersion else null,
            ),
            roots = roots.ifEmpty { listOf(RootInfo("music")) },
            tracks = tracks,
            coverage = CoverageInfo(
                partial = false,
                itemsSeen = all.size,
                entriesExported = tracks.size,
                missingTrackGain = missingTrack,
                missingAlbumGain = missingAlbum,
                missingBothGains = missingBoth,
                unmatched = 0,
                collisions = collisions,
                outOfRangeGains = outOfRange,
                rejected = 0,
            ),
        )
    }
}
