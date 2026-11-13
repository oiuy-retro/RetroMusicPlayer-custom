package code.name.monkey.retromusic.loudness

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import code.name.monkey.retromusic.db.AudioHashCacheEntity
import code.name.monkey.retromusic.db.LoudnessDao
import code.name.monkey.retromusic.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Computes and caches jle-audio-v1 hashes of phone files. Cache rows are keyed by the
 * song's data path and reused only while size and modification time are unchanged.
 * Files are read directly when possible, otherwise through the content resolver
 * (MediaStore item or a content:// data path), so hashing does not need a mapped
 * filesystem root.
 */
class PhoneAudioHasher(context: Context, private val dao: LoudnessDao) {
    private val appContext = context.applicationContext

    private data class Stat(val size: Long, val mtimeMs: Long)

    /** Cached valid hash for [song], computing it when [computeIfMissing]. Null = no hash. */
    suspend fun hashForSong(song: Song, computeIfMissing: Boolean): String? = withContext(Dispatchers.IO) {
        val key = song.data.ifBlank { return@withContext null }
        val file = if (key.startsWith("content:")) null else File(key)
        val uri = contentUriFor(song)
        val stat = file?.let { statFile(it) } ?: uri?.let { statUri(it, song) } ?: return@withContext null
        val cached = validCache(key, stat)
        if (cached != null) return@withContext cached.audioSha256
        if (!computeIfMissing) return@withContext null
        var result = if (file != null && file.isFile) JleAudioHash.hashFile(file) else null
        if ((result == null || result == JleAudioHash.Result.NoHash("unreadable")) && uri != null) {
            result = hashUri(uri)
        }
        store(key, stat, result ?: JleAudioHash.Result.NoHash("unreadable"))
    }

    /** Same for a plain file path (used by Export for rows under the mapped root). */
    suspend fun hashForFile(file: File, computeIfMissing: Boolean): String? = withContext(Dispatchers.IO) {
        val stat = statFile(file) ?: return@withContext null
        val key = file.absolutePath
        validCache(key, stat)?.let { return@withContext it.audioSha256 }
        if (!computeIfMissing) return@withContext null
        store(key, stat, JleAudioHash.hashFile(file))
    }

    /** True when [song]'s cache row is present and still valid (no re-hash needed). */
    suspend fun isCachedFresh(song: Song): Boolean = withContext(Dispatchers.IO) {
        val key = song.data.ifBlank { return@withContext false }
        val file = if (key.startsWith("content:")) null else File(key)
        val stat = file?.let { statFile(it) } ?: contentUriFor(song)?.let { statUri(it, song) }
            ?: return@withContext false
        validCache(key, stat) != null
    }

    /** Removes cache rows for paths no longer in the library. */
    suspend fun prune(keepPaths: Set<String>) = withContext(Dispatchers.IO) {
        val stale = dao.hashCachePaths().filter { it !in keepPaths }
        stale.chunked(500).forEach { dao.deleteHashCache(it) }
        stale.size
    }

    private suspend fun validCache(key: String, stat: Stat): AudioHashCacheEntity? {
        val row = dao.getHashCache(key) ?: return null
        return row.takeIf {
            it.algorithm == JleAudioHash.ALGORITHM && it.sizeBytes == stat.size && it.mtimeMs == stat.mtimeMs
        }
    }

    private suspend fun store(key: String, stat: Stat, result: JleAudioHash.Result): String? {
        val hex = result.hexOrNull
        dao.upsertHashCache(
            AudioHashCacheEntity(
                path = key,
                sizeBytes = stat.size,
                mtimeMs = stat.mtimeMs,
                algorithm = JleAudioHash.ALGORITHM,
                audioSha256 = hex,
                noHashReason = (result as? JleAudioHash.Result.NoHash)?.reason,
                hashedAt = System.currentTimeMillis(),
            )
        )
        return hex
    }

    private fun statFile(f: File): Stat? = try {
        if (f.isFile) Stat(f.length(), f.lastModified()) else null
    } catch (_: SecurityException) {
        null
    }

    private fun contentUriFor(song: Song): Uri? = when {
        song.data.startsWith("content:") -> Uri.parse(song.data)
        song.id > 0 -> ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
        else -> null
    }

    private fun statUri(uri: Uri, song: Song): Stat? = try {
        appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val size = pfd.statSize
            if (size < 0) null else Stat(size, song.dateModified * 1000L)
        }
    } catch (_: Exception) {
        null
    }

    private fun hashUri(uri: Uri): JleAudioHash.Result = try {
        val pfd = appContext.contentResolver.openFileDescriptor(uri, "r")
            ?: return JleAudioHash.Result.NoHash("unreadable")
        val fis = FileInputStream(pfd.fileDescriptor)
        JleAudioHash.ChannelSource(fis.channel, pfd).use { JleAudioHash.hash(it) }
    } catch (_: Exception) {
        JleAudioHash.Result.NoHash("unreadable")
    }
}
