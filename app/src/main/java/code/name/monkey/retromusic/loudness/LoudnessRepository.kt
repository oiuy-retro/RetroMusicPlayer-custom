package code.name.monkey.retromusic.loudness

import code.name.monkey.retromusic.db.LoudnessDao
import code.name.monkey.retromusic.db.LoudnessEntity
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LoudnessRepository(private val dao: LoudnessDao) {
    private val gson = Gson()

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    /** Exact primary match only: ordinal rootId + exact relativePath. No suffix/basename fallback. */
    suspend fun lookupExact(rootId: String, relativePath: String): LoudnessEntity? =
        withContext(Dispatchers.IO) { dao.get(rootId, relativePath) }

    suspend fun lookupByAudioHash(audioSha256: String): LoudnessEntity? =
        withContext(Dispatchers.IO) { dao.getByAudioHash(audioSha256) }

    suspend fun countWithAudioHash(): Int = withContext(Dispatchers.IO) { dao.countWithAudioHash() }

    suspend fun hasMeasured(rootId: String, relativePath: String): Boolean =
        withContext(Dispatchers.IO) {
            val e = dao.get(rootId, relativePath) ?: return@withContext false
            e.measuredLufs != null && e.measuredLufs.isFinite()
        }

    suspend fun importDocument(document: LoudnessDocument): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val keepHashes = document.audioHashesUsable
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
                audioSha256 = if (keepHashes) t.audioSha256 else null,
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
                albumGainDb = existing?.albumGainDb, // preserve imported album gain; no album scan
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
                audioSha256 = existing?.audioSha256,
            )
        )
    }

    suspend fun exportDocument(
        producerVersion: String,
        rootIdFilter: String? = null,
        /** When set, each exported track gets this phone-computed jle-audio-v1 hash (or none). */
        audioHashFor: (suspend (LoudnessEntity) -> String?)? = null,
    ): LoudnessDocument = withContext(Dispatchers.IO) {
        val all = dao.getAll().let { list ->
            if (rootIdFilter == null) list else list.filter { it.rootId == rootIdFilter }
        }
        val groups = all.groupBy { CollisionKey.build(it.rootId, it.relativePath) }
        val kept = all.filter { (groups[CollisionKey.build(it.rootId, it.relativePath)]?.size ?: 0) == 1 }
        val collisions = all.size - kept.size
        val roots = kept.map { it.rootId }.distinct().sorted().map { RootInfo(it) }
        var missingTrack = 0
        var missingAlbum = 0
        var missingBoth = 0
        var outOfRange = 0
        val tracks = kept.map { e ->
            val hash = audioHashFor?.invoke(e)
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
                audioSha256 = hash,
                source = e.trackSourceJson?.let {
                    try { gson.fromJson(it, TrackSource::class.java) } catch (_: Exception) { null }
                },
            )
        }
        // Prefer android producer shape when any measured rows exist; else preserve imported kind.
        val measuredAny = kept.any { it.documentKind == LoudnessSchema.KIND_ANDROID || it.measuredLufs != null }
        val kind = when {
            measuredAny && kept.all { it.documentKind == LoudnessSchema.KIND_ANDROID || it.measuredLufs != null } ->
                LoudnessSchema.KIND_ANDROID
            kept.firstOrNull()?.documentKind != null -> kept.first().documentKind!!
            else -> LoudnessSchema.KIND_ANDROID
        }
        val gainOrigin = when (kind) {
            LoudnessSchema.KIND_ANDROID -> LoudnessSchema.GAIN_ORIGIN_MEASURED
            else -> kept.firstOrNull()?.documentGainOrigin ?: LoudnessSchema.GAIN_ORIGIN_JELLYFIN
        }
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
                // Android uses referenceLufs only; never emit Jellyfin's lufsDerivedReferenceLufs from this producer.
                // Jellyfin re-export omits both unless we later persist the imported informational fields.
                referenceLufs = if (kind == LoudnessSchema.KIND_ANDROID) LoudnessSchema.REFERENCE_LUFS else null,
                lufsDerivedReferenceLufs = null,
                analyzer = if (kind == LoudnessSchema.KIND_ANDROID) LoudnessSchema.ANALYZER_LIBEBUR128 else null,
                analyzerVersion = if (kind == LoudnessSchema.KIND_ANDROID) LoudnessSchema.ANALYZER_VERSION else null,
                producer = if (kind == LoudnessSchema.KIND_ANDROID) LoudnessSchema.PRODUCER_NAME else null,
                producerVersion = if (kind == LoudnessSchema.KIND_ANDROID) producerVersion else null,
            ),
            roots = roots.ifEmpty { listOf(RootInfo("music")) },
            tracks = tracks,
            coverage = CoverageInfo(
                // partial only for explicitly filtered / incomplete producer runs
                partial = rootIdFilter != null,
                itemsSeen = all.size,
                entriesExported = tracks.size,
                missingTrackGain = missingTrack,
                missingAlbumGain = missingAlbum,
                missingBothGains = missingBoth,
                unmatched = 0,
                collisions = collisions,
                outOfRangeGains = outOfRange,
                rejected = 0,
                hashed = if (audioHashFor != null) tracks.count { it.audioSha256 != null } else null,
                hashUnavailable = if (audioHashFor != null) tracks.count { it.audioSha256 == null } else null,
            ),
            audioHashAlgorithm = if (audioHashFor != null) JleAudioHash.ALGORITHM else null,
        )
    }
}
