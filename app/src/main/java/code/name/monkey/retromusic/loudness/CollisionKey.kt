package code.name.monkey.retromusic.loudness

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicReference

/**
 * jf-ck-v1: rootId (ordinal exact) + U+001F + FullCaseFold_Unicode15.1_C+F(NFC_runtime(relativePath)).
 * No NFC after folding. Android NFC uses java.text.Normalizer (runtime, not a pinned UCD table).
 */
object CollisionKey {
    private const val SEP = '\u001F'
    private val mapRef = AtomicReference<Map<Int, IntArray>?>(null)

    fun ensureLoaded(context: Context) {
        if (mapRef.get() != null) return
        synchronized(this) {
            if (mapRef.get() != null) return
            mapRef.set(loadCaseFold(context))
        }
    }

    /** For JVM unit tests that load the table from a classpath resource. */
    fun loadFromReader(reader: BufferedReader) {
        mapRef.set(parseCaseFold(reader))
    }

    fun build(rootId: String, relativePath: String): String {
        return rootId + SEP + fullCaseFoldNfc(relativePath)
    }

    fun fullCaseFoldNfc(relativePath: String): String {
        if (relativePath.isEmpty()) return relativePath
        failClosedUtf16(relativePath)
        val nfc = try {
            Normalizer.normalize(relativePath, Normalizer.Form.NFC)
        } catch (e: Exception) {
            throw IllegalArgumentException("NFC failed", e)
        }
        failClosedUtf16(nfc)
        return fullCaseFold(nfc)
    }

    fun fullCaseFold(s: String): String {
        val map = mapRef.get() ?: error("CaseFolding table not loaded")
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val mapped = map[cp]
            if (mapped != null) {
                for (m in mapped) out.appendCodePoint(m)
            } else {
                out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    private fun failClosedUtf16(s: String) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) {
                    throw IllegalArgumentException("lone surrogate")
                }
                i += 2
            } else if (Character.isLowSurrogate(c)) {
                throw IllegalArgumentException("lone surrogate")
            } else {
                i++
            }
        }
    }

    private fun loadCaseFold(context: Context): Map<Int, IntArray> {
        context.assets.open("loudness/CaseFolding-15.1.0.txt").use { stream ->
            return parseCaseFold(BufferedReader(InputStreamReader(stream, Charsets.UTF_8)))
        }
    }

    private fun parseCaseFold(reader: BufferedReader): Map<Int, IntArray> {
        val map = HashMap<Int, IntArray>()
        reader.useLines { lines ->
            for (line in lines) {
                val trimmed = line.substringBefore('#').trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split(';').map { it.trim() }
                if (parts.size < 3) continue
                val status = parts[1]
                if (status != "C" && status != "F") continue // exclude T (Turkic)
                val from = parts[0].toInt(16)
                val to = parts[2].split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16) }.toIntArray()
                map[from] = to
            }
        }
        return map
    }
}
