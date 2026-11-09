package code.name.monkey.retromusic.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "LoudnessEntity",
    primaryKeys = ["rootId", "relativePath"],
    indices = [Index(value = ["relativePath"])],
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
)
