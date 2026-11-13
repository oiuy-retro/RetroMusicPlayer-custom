package code.name.monkey.retromusic.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached jle-audio-v1 hash of one phone file. A row is valid only while the file's
 * size and modification time still equal [sizeBytes] and [mtimeMs] and [algorithm]
 * matches; otherwise the file is hashed again.
 */
@Entity(
    tableName = "AudioHashCacheEntity",
    indices = [Index(value = ["audioSha256"])],
)
data class AudioHashCacheEntity(
    /** Song data path as the library reports it (usually an absolute file path). */
    @PrimaryKey val path: String,
    val sizeBytes: Long,
    val mtimeMs: Long,
    val algorithm: String,
    /** Null when the file gets no hash (unsupported, malformed or unreadable). */
    val audioSha256: String?,
    val noHashReason: String?,
    val hashedAt: Long,
)

/** Room projection: one phone hash and how many cached files have it. */
data class PhoneHashCount(
    val audioSha256: String,
    val files: Int,
)
