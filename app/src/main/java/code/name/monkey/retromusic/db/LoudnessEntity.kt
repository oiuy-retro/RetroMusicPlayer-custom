package code.name.monkey.retromusic.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "LoudnessEntity",
    primaryKeys = ["rootId", "relativePath"],
    indices = [Index(value = ["relativePath"]), Index(value = ["audioSha256"])],
)
data class LoudnessEntity(
    val rootId: String,
    val relativePath: String,
    val trackGainDb: Double?,
    val albumGainDb: Double?,
    val measuredLufs: Double?,
    val truePeakDbtp: Double?,
    val sourceItemId: String?,
    val sourceAlbumId: String?,
    val documentKind: String?,
    val documentGainOrigin: String?,
    val trackSourceJson: String?,
    val updatedAt: Long,
    /** jle-audio-v1 hash from an imported file whose audioHashAlgorithm is jle-audio-v1; else null. */
    val audioSha256: String? = null,
)
