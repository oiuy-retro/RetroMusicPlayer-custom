package code.name.monkey.retromusic.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface LoudnessDao {
    @Query("SELECT * FROM LoudnessEntity WHERE rootId = :rootId AND relativePath = :relativePath LIMIT 1")
    suspend fun get(rootId: String, relativePath: String): LoudnessEntity?

    @Query("SELECT * FROM LoudnessEntity WHERE relativePath = :relativePath")
    suspend fun getByRelativePath(relativePath: String): List<LoudnessEntity>

    @Query("SELECT * FROM LoudnessEntity")
    suspend fun getAll(): List<LoudnessEntity>

    @Query("SELECT COUNT(*) FROM LoudnessEntity")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<LoudnessEntity>)

    @Query("DELETE FROM LoudnessEntity")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAllAtomic(rows: List<LoudnessEntity>) {
        deleteAll()
        if (rows.isNotEmpty()) upsertAll(rows)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LoudnessEntity)

    @Query("SELECT * FROM LoudnessEntity WHERE audioSha256 = :audioSha256 ORDER BY rootId, relativePath LIMIT 1")
    suspend fun getByAudioHash(audioSha256: String): LoudnessEntity?

    @Query("SELECT COUNT(*) FROM LoudnessEntity WHERE audioSha256 IS NOT NULL")
    suspend fun countWithAudioHash(): Int

    // ---- phone audio hash cache

    @Query("SELECT * FROM AudioHashCacheEntity WHERE path = :path LIMIT 1")
    suspend fun getHashCache(path: String): AudioHashCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHashCache(row: AudioHashCacheEntity)

    @Query("SELECT path FROM AudioHashCacheEntity")
    suspend fun hashCachePaths(): List<String>

    @Query("DELETE FROM AudioHashCacheEntity WHERE path IN (:paths)")
    suspend fun deleteHashCache(paths: List<String>)

    @Query(
        "SELECT audioSha256, COUNT(*) AS files FROM AudioHashCacheEntity " +
            "WHERE algorithm = :algorithm AND audioSha256 IS NOT NULL GROUP BY audioSha256"
    )
    suspend fun phoneHashCounts(algorithm: String): List<PhoneHashCount>

    @Query("SELECT COUNT(*) FROM AudioHashCacheEntity WHERE algorithm = :algorithm AND audioSha256 IS NOT NULL")
    suspend fun countPhoneHashed(algorithm: String): Int

    @Query("SELECT COUNT(*) FROM AudioHashCacheEntity WHERE algorithm = :algorithm AND audioSha256 IS NULL")
    suspend fun countPhoneNoHash(algorithm: String): Int
}
