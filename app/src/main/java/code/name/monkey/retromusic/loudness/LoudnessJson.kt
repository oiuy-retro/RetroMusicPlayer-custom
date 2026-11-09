package code.name.monkey.retromusic.loudness

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonWriter
import java.io.Reader
import java.io.StringWriter
import java.time.Instant
import java.util.Locale

object LoudnessJson {
    fun parse(reader: Reader, requireJellyfinSourceIds: Boolean = false): ParseResult {
        val root = try {
            JsonParser.parseReader(reader).asJsonObject
        } catch (e: Exception) {
            return ParseResult.Error("malformed_json: ${e.message}")
        }
        return parseObject(root, requireJellyfinSourceIds)
    }

    fun parseObject(root: JsonObject, requireJellyfinSourceIds: Boolean = false): ParseResult {
        val format = root.getString("format") ?: return ParseResult.Error("missing_format")
        if (format != LoudnessSchema.FORMAT) return ParseResult.Error("unsupported_format")
        val schemaVersion = root.getInt("schemaVersion") ?: return ParseResult.Error("missing_schemaVersion")
        if (schemaVersion != LoudnessSchema.SCHEMA_VERSION) {
            return ParseResult.Error("unsupported_schemaVersion:$schemaVersion")
        }
        val exportedAt = root.getString("exportedAt") ?: return ParseResult.Error("missing_exportedAt")
        if (!exportedAt.endsWith("Z")) return ParseResult.Error("exportedAt_not_utc_z")

        val sourceEl = root.get("source") ?: return ParseResult.Error("missing_source")
        if (!sourceEl.isJsonObject) return ParseResult.Error("invalid_source")
        val sourceObj = sourceEl.asJsonObject
        val kind = sourceObj.getString("kind") ?: return ParseResult.Error("missing_source_kind")
        val gainOrigin = sourceObj.getString("gainOrigin") ?: return ParseResult.Error("missing_gainOrigin")
        val gainUnits = sourceObj.getString("gainUnits") ?: return ParseResult.Error("missing_gainUnits")
        if (gainUnits != LoudnessSchema.GAIN_UNITS_DB) return ParseResult.Error("unsupported_gainUnits")

        val source = DocumentSource(
            kind = kind,
            gainOrigin = gainOrigin,
            gainUnits = gainUnits,
            serverVersion = sourceObj.getString("serverVersion"),
            lufsDerivedReferenceLufs = sourceObj.getFiniteDoubleOrNull("lufsDerivedReferenceLufs"),
            referenceLufs = sourceObj.getFiniteDoubleOrNull("referenceLufs"),
            analyzer = sourceObj.getString("analyzer"),
            analyzerVersion = sourceObj.getString("analyzerVersion"),
            producer = sourceObj.getString("producer"),
            producerVersion = sourceObj.getString("producerVersion"),
        )

        val rootsArr = root.getAsJsonArray("roots") ?: return ParseResult.Error("missing_roots")
        val roots = ArrayList<RootInfo>()
        val rootIds = HashSet<String>()
        for (el in rootsArr) {
            if (!el.isJsonObject) return ParseResult.Error("invalid_root")
            val id = el.asJsonObject.getString("id") ?: return ParseResult.Error("missing_root_id")
            if (id.isEmpty()) return ParseResult.Error("empty_root_id")
            if (!rootIds.add(id)) return ParseResult.Error("duplicate_root_id")
            roots.add(RootInfo(id))
        }
        if (roots.isEmpty()) return ParseResult.Error("empty_roots")

        val tracksArr = root.getAsJsonArray("tracks") ?: return ParseResult.Error("missing_tracks")
        val tracks = ArrayList<TrackEntry>(tracksArr.size())
        val exactKeys = HashSet<String>()
        val collisionGroups = HashMap<String, Int>()
        var rejected = 0

        for (el in tracksArr) {
            if (!el.isJsonObject) return ParseResult.Error("invalid_track")
            val t = el.asJsonObject
            val rootId = t.getString("rootId") ?: return ParseResult.Error("missing_rootId")
            if (rootId !in rootIds) return ParseResult.Error("unknown_rootId")
            val relativePath = t.getString("relativePath") ?: return ParseResult.Error("missing_relativePath")
            PathValidation.validateRelative(relativePath)?.let {
                return ParseResult.Error("bad_relative_path:$it")
            }
            val trackGain = t.getFiniteDoubleOrMissing("trackGainDb")
            val albumGain = t.getFiniteDoubleOrMissing("albumGainDb")
            if (trackGain is GainRead.Invalid) return ParseResult.Error("nonfinite_trackGainDb")
            if (albumGain is GainRead.Invalid) return ParseResult.Error("nonfinite_albumGainDb")
            val measured = t.getFiniteDoubleOrMissing("measuredLufs")
            val peak = t.getFiniteDoubleOrMissing("truePeakDbtp")
            if (measured is GainRead.Invalid) return ParseResult.Error("nonfinite_measuredLufs")
            if (peak is GainRead.Invalid) return ParseResult.Error("nonfinite_truePeakDbtp")

            // Never invent LUFS from Jellyfin ambiguous gains.
            if (kind == LoudnessSchema.KIND_JELLYFIN && measured is GainRead.Value) {
                // Allow only if explicitly present; do not derive. Keep as-is.
            }

            val sourceItemId = t.getString("sourceItemId")
            if (requireJellyfinSourceIds && kind == LoudnessSchema.KIND_JELLYFIN && sourceItemId.isNullOrEmpty()) {
                return ParseResult.Error("missing_sourceItemId")
            }

            val trackSource = t.get("source")?.takeIf { it.isJsonObject }?.asJsonObject?.let { s ->
                TrackSource(
                    kind = s.getString("kind"),
                    analyzer = s.getString("analyzer"),
                    analyzerVersion = s.getString("analyzerVersion"),
                    measuredAt = s.getString("measuredAt"),
                    gainOrigin = s.getString("gainOrigin"),
                )
            }

            val exact = rootId + "\u0000" + relativePath
            if (!exactKeys.add(exact)) return ParseResult.Error("duplicate_exact_path")

            val ck = try {
                CollisionKey.build(rootId, relativePath)
            } catch (e: Exception) {
                return ParseResult.Error("collision_key_failed")
            }
            collisionGroups[ck] = (collisionGroups[ck] ?: 0) + 1

            tracks.add(
                TrackEntry(
                    rootId = rootId,
                    relativePath = relativePath,
                    sourceItemId = sourceItemId,
                    sourceAlbumId = t.getString("sourceAlbumId"),
                    trackGainDb = (trackGain as? GainRead.Value)?.value,
                    albumGainDb = (albumGain as? GainRead.Value)?.value,
                    measuredLufs = (measured as? GainRead.Value)?.value,
                    truePeakDbtp = (peak as? GainRead.Value)?.value,
                    source = trackSource,
                )
            )
        }

        // Collision gate when algorithm claimed
        val algo = root.getString("collisionKeyAlgorithm")
        val collisionInfo = root.get("collisionKey")?.takeIf { it.isJsonObject }?.asJsonObject?.let {
            CollisionKeyInfo(
                caseFold = it.getString("caseFold"),
                nfc = it.getString("nfc"),
                rootId = it.getString("rootId"),
                relativePath = it.getString("relativePath"),
            )
        }
        if (algo == LoudnessSchema.COLLISION_ALGORITHM) {
            val colliding = collisionGroups.filter { it.value > 1 }.keys
            if (colliding.isNotEmpty()) {
                // Producers claiming jf-ck-v1 must omit colliding members. Reject document.
                return ParseResult.Error("collision_gate_failed:${colliding.size}")
            }
        }

        val coverage = root.get("coverage")?.takeIf { it.isJsonObject }?.asJsonObject?.let { c ->
            CoverageInfo(
                partial = c.get("partial")?.asBoolean ?: false,
                itemsSeen = c.getInt("itemsSeen") ?: 0,
                entriesExported = c.getInt("entriesExported") ?: tracks.size,
                missingTrackGain = c.getInt("missingTrackGain") ?: 0,
                missingAlbumGain = c.getInt("missingAlbumGain") ?: 0,
                missingBothGains = c.getInt("missingBothGains") ?: 0,
                unmatched = c.getInt("unmatched") ?: 0,
                collisions = c.getInt("collisions") ?: 0,
                outOfRangeGains = c.getInt("outOfRangeGains") ?: 0,
                rejected = c.getInt("rejected") ?: rejected,
            )
        }

        return ParseResult.Ok(
            LoudnessDocument(
                format = format,
                schemaVersion = schemaVersion,
                exportedAt = exportedAt,
                collisionKeyAlgorithm = algo,
                collisionKey = collisionInfo,
                source = source,
                roots = roots,
                tracks = tracks,
                coverage = coverage,
            )
        )
    }

    fun write(document: LoudnessDocument): String {
        val sw = StringWriter()
        JsonWriter(sw).use { w ->
            w.setIndent("  ")
            w.beginObject()
            w.name("format").value(document.format)
            w.name("schemaVersion").value(document.schemaVersion)
            w.name("exportedAt").value(document.exportedAt)
            document.collisionKeyAlgorithm?.let { w.name("collisionKeyAlgorithm").value(it) }
            document.collisionKey?.let { ck ->
                w.name("collisionKey").beginObject()
                ck.caseFold?.let { w.name("caseFold").value(it) }
                ck.nfc?.let { w.name("nfc").value(it) }
                ck.rootId?.let { w.name("rootId").value(it) }
                ck.relativePath?.let { w.name("relativePath").value(it) }
                w.endObject()
            }
            w.name("source").beginObject()
            val s = document.source
            w.name("kind").value(s.kind)
            w.name("gainOrigin").value(s.gainOrigin)
            w.name("gainUnits").value(s.gainUnits)
            s.serverVersion?.let { w.name("serverVersion").value(it) }
            s.lufsDerivedReferenceLufs?.let { w.name("lufsDerivedReferenceLufs").value(it) }
            s.referenceLufs?.let { w.name("referenceLufs").value(it) }
            s.analyzer?.let { w.name("analyzer").value(it) }
            s.analyzerVersion?.let { w.name("analyzerVersion").value(it) }
            s.producer?.let { w.name("producer").value(it) }
            s.producerVersion?.let { w.name("producerVersion").value(it) }
            w.endObject()
            w.name("roots").beginArray()
            for (r in document.roots) {
                w.beginObject().name("id").value(r.id).endObject()
            }
            w.endArray()
            w.name("tracks").beginArray()
            for (t in document.tracks) {
                w.beginObject()
                w.name("rootId").value(t.rootId)
                w.name("relativePath").value(t.relativePath)
                t.sourceItemId?.let { w.name("sourceItemId").value(it) }
                t.sourceAlbumId?.let { w.name("sourceAlbumId").value(it) }
                t.trackGainDb?.let { w.name("trackGainDb").value(it) }
                t.albumGainDb?.let { w.name("albumGainDb").value(it) }
                t.measuredLufs?.let { w.name("measuredLufs").value(it) }
                t.truePeakDbtp?.let { w.name("truePeakDbtp").value(it) }
                t.source?.let { ts ->
                    w.name("source").beginObject()
                    ts.kind?.let { w.name("kind").value(it) }
                    ts.analyzer?.let { w.name("analyzer").value(it) }
                    ts.analyzerVersion?.let { w.name("analyzerVersion").value(it) }
                    ts.measuredAt?.let { w.name("measuredAt").value(it) }
                    ts.gainOrigin?.let { w.name("gainOrigin").value(it) }
                    w.endObject()
                }
                w.endObject()
            }
            w.endArray()
            document.coverage?.let { c ->
                w.name("coverage").beginObject()
                w.name("partial").value(c.partial)
                w.name("itemsSeen").value(c.itemsSeen)
                w.name("entriesExported").value(c.entriesExported)
                w.name("missingTrackGain").value(c.missingTrackGain)
                w.name("missingAlbumGain").value(c.missingAlbumGain)
                w.name("missingBothGains").value(c.missingBothGains)
                w.name("unmatched").value(c.unmatched)
                w.name("collisions").value(c.collisions)
                w.name("outOfRangeGains").value(c.outOfRangeGains)
                w.name("rejected").value(c.rejected)
                w.name("errors").beginArray().endArray()
                w.endObject()
            }
            w.endObject()
        }
        return sw.toString()
    }

    fun nowExportedAt(): String = Instant.now().toString().let {
        if (it.endsWith("Z")) it else Instant.now().toString()
    }.let { s ->
        // Ensure Zulu form
        if (s.endsWith("Z")) s else s + "Z"
    }

    fun androidCollisionKeyInfo() = CollisionKeyInfo(
        caseFold = LoudnessSchema.CASE_FOLD_SPEC,
        nfc = LoudnessSchema.NFC_SPEC_ANDROID,
        rootId = LoudnessSchema.ROOT_ID_SPEC,
        relativePath = "NFC(runtime) then unicode-15.1 full C+F case fold; stored relativePath unchanged",
    )

    private sealed class GainRead {
        data object Missing : GainRead()
        data class Value(val value: Double) : GainRead()
        data object Invalid : GainRead()
    }

    private fun JsonObject.getString(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.getInt(name: String): Int? {
        val el = get(name) ?: return null
        if (!el.isJsonPrimitive) return null
        val p = el.asJsonPrimitive
        return when {
            p.isNumber -> p.asInt
            else -> null
        }
    }

    private fun JsonObject.getFiniteDoubleOrNull(name: String): Double? =
        when (val g = getFiniteDoubleOrMissing(name)) {
            is GainRead.Value -> g.value
            else -> null
        }

    private fun JsonObject.getFiniteDoubleOrMissing(name: String): GainRead {
        if (!has(name) || get(name).isJsonNull) return GainRead.Missing
        val el = get(name)
        if (!el.isJsonPrimitive) return GainRead.Invalid
        val p = el.asJsonPrimitive
        if (!p.isNumber) return GainRead.Invalid
        // Gson may parse non-finite if present as strings; reject NaN/Infinity via string check
        val raw = p.toString()
        if (raw.contains("NaN", true) || raw.contains("Infinity", true)) return GainRead.Invalid
        val d = p.asDouble
        return if (d.isFinite()) GainRead.Value(d) else GainRead.Invalid
    }
}
