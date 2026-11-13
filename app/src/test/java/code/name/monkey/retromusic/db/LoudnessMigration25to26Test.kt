package code.name.monkey.retromusic.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Builds a database, turns it back into the v25 loudness layout with one stored row,
 * then opens it with Room at v26 through MIGRATION_25_26. Room validates the migrated
 * schema against the entities, so a wrong column or index fails here instead of on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LoudnessMigration25to26Test {
    @Test
    fun migratesAndKeepsRows() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val name = "migration-test.db"
        ctx.deleteDatabase(name)
        Room.databaseBuilder(ctx, RetroDatabase::class.java, name).build().apply {
            openHelper.writableDatabase
            close()
        }
        val raw = SQLiteDatabase.openDatabase(ctx.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
        raw.execSQL("DROP TABLE AudioHashCacheEntity")
        raw.execSQL("DROP TABLE LoudnessEntity")
        raw.execSQL("DROP TABLE IF EXISTS room_master_table")
        // v25 loudness table exactly as MIGRATION_24_25 creates it.
        raw.execSQL(
            """
            CREATE TABLE IF NOT EXISTS LoudnessEntity (
                rootId TEXT NOT NULL, relativePath TEXT NOT NULL, trackGainDb REAL, albumGainDb REAL,
                measuredLufs REAL, truePeakDbtp REAL, sourceItemId TEXT, sourceAlbumId TEXT,
                documentKind TEXT, documentGainOrigin TEXT, trackSourceJson TEXT, updatedAt INTEGER NOT NULL,
                PRIMARY KEY(rootId, relativePath))
            """.trimIndent()
        )
        raw.execSQL("CREATE INDEX IF NOT EXISTS index_LoudnessEntity_relativePath ON LoudnessEntity(relativePath)")
        raw.execSQL(
            "INSERT INTO LoudnessEntity (rootId, relativePath, trackGainDb, updatedAt) VALUES ('music', 'A/b.flac', -3.5, 1)"
        )
        raw.version = 25
        raw.close()

        val db = Room.databaseBuilder(ctx, RetroDatabase::class.java, name)
            .addMigrations(MIGRATION_25_26)
            .build()
        val dao = db.loudnessDao()
        val row = dao.get("music", "A/b.flac")!!
        assertEquals(-3.5, row.trackGainDb!!, 0.0)
        assertNull(row.audioSha256)
        val h = "a".repeat(64)
        dao.upsert(row.copy(audioSha256 = h))
        assertEquals("A/b.flac", dao.getByAudioHash(h)!!.relativePath)
        dao.upsertHashCache(AudioHashCacheEntity("/x/b.flac", 10, 20, "jle-audio-v1", h, null, 30))
        dao.upsertHashCache(AudioHashCacheEntity("/x/c.flac", 10, 20, "jle-audio-v1", h, null, 30))
        dao.upsertHashCache(AudioHashCacheEntity("/x/d.wma", 10, 20, "jle-audio-v1", null, "unsupported", 30))
        assertEquals(listOf(PhoneHashCount(h, 2)), dao.phoneHashCounts("jle-audio-v1"))
        assertEquals(2, dao.countPhoneHashed("jle-audio-v1"))
        assertEquals(1, dao.countPhoneNoHash("jle-audio-v1"))
        db.close()
        ctx.deleteDatabase(name)
        Unit
    }
}
