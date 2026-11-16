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
