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
}
