package code.name.monkey.retromusic.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE LyricsEntity")
        database.execSQL("DROP TABLE BlackListStoreEntity")
    }
}

val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS LoudnessEntity (
                rootId TEXT NOT NULL,
                relativePath TEXT NOT NULL,
                trackGainDb REAL,
                albumGainDb REAL,
                measuredLufs REAL,
                truePeakDbtp REAL,
                sourceItemId TEXT,
                sourceAlbumId TEXT,
                documentKind TEXT,
                documentGainOrigin TEXT,
                trackSourceJson TEXT,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(rootId, relativePath)
            )
            """.trimIndent()
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS index_LoudnessEntity_relativePath ON LoudnessEntity(relativePath)")
    }
}

/** Song-hash matching: imported audioSha256 column plus the phone hash cache. */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE LoudnessEntity ADD COLUMN audioSha256 TEXT")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_LoudnessEntity_audioSha256 ON LoudnessEntity(audioSha256)")
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS AudioHashCacheEntity (
                path TEXT NOT NULL,
                sizeBytes INTEGER NOT NULL,
                mtimeMs INTEGER NOT NULL,
                algorithm TEXT NOT NULL,
                audioSha256 TEXT,
                noHashReason TEXT,
                hashedAt INTEGER NOT NULL,
                PRIMARY KEY(path)
            )
            """.trimIndent()
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS index_AudioHashCacheEntity_audioSha256 ON AudioHashCacheEntity(audioSha256)")
    }
}
