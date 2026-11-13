package code.name.monkey.retromusic.loudness

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Pure JVM implementation of the `jle-audio-v1` audio hash (no Android dependencies).
 *
 * Authoritative spec: oiuy-retro/jellyfin-loudness-exporter docs/FORMAT.md, section
 * "Audio hash jle-audio-v1", at commit 6af8e0c8d4f2e6e011369daaf81aaf80e7dbeefc
 * (reference implementation tests/tools/jle_audio_hash.py at the same commit).
 *
 * The file is only read, never written, and is streamed: only small headers, one
 * MPEG frame, one Ogg page body and one Ogg packet (at most 16 MiB) are held in memory.
 */
object JleAudioHash {
    const val ALGORITHM = "jle-audio-v1"

    private const val OGG_MAX_PACKET = 16L * 1024 * 1024
    private const val CHUNK = 1 shl 20

    sealed class Result {
        data class Hash(val hex: String) : Result()
        /** No hash. [reason] is a short code: unsupported, malformed or unreadable. */
        data class NoHash(val reason: String) : Result()

        val hexOrNull: String? get() = (this as? Hash)?.hex
    }

    /** Positional, read-only byte source. */
    interface Source : Closeable {
        val length: Long

        /** Reads up to [len] bytes at [pos]; returns the count read, or -1 at end of data. */
        @Throws(IOException::class)
        fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Int
    }

    /** Source over a [FileChannel] (works for files and Android ParcelFileDescriptors). */
    class ChannelSource(private val channel: FileChannel, private val closeable: Closeable? = null) : Source {
        override val length: Long = channel.size()
        override fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Int =
            channel.read(ByteBuffer.wrap(dst, off, len), pos)

        override fun close() {
            try { channel.close() } finally { closeable?.close() }
        }
    }

    fun hashFile(file: File): Result = try {
        RandomAccessFile(file, "r").use { raf ->
            hash(ChannelSource(raf.channel))
        }
    } catch (e: IOException) {
        Result.NoHash("unreadable")
    } catch (e: SecurityException) {
        Result.NoHash("unreadable")
    }

    /** Hashes [source]. Does not close it. */
    fun hash(source: Source): Result = try {
        Result.Hash(Hasher(source).run())
    } catch (e: NoHash) {
        Result.NoHash(e.reason)
    } catch (e: IOException) {
        Result.NoHash("unreadable")
    }

    private class NoHash(val reason: String) : Exception(reason) {
        override fun fillInStackTrace(): Throwable = this
    }

    private fun need(cond: Boolean, reason: String) {
        if (!cond) throw NoHash(reason)
    }

    private class Hasher(private val src: Source) {
        private val length = src.length

        // Small read-ahead window for header-sized reads.
        private val window = ByteArray(64 * 1024)
        private var windowStart = 0L
        private var windowLen = 0

        /** Exactly [n] bytes at [pos]; caller guarantees pos + n <= length. */
        fun bytes(pos: Long, n: Int): ByteArray {
            require(n >= 0)
            if (pos < 0 || pos + n > length) throw NoHash("malformed")
            val out = ByteArray(n)
            if (n <= window.size) {
                if (pos < windowStart || pos + n > windowStart + windowLen) fill(pos, n)
                System.arraycopy(window, (pos - windowStart).toInt(), out, 0, n)
            } else {
                readFully(pos, out, 0, n)
            }
            return out
        }

        private fun fill(pos: Long, need: Int) {
            val want = minOf(window.size.toLong(), length - pos).toInt()
            var got = 0
            while (got < want) {
                val r = src.readAt(pos + got, window, got, want - got)
                if (r <= 0) break
                got += r
            }
            windowStart = pos
            windowLen = got
            if (got < need) throw NoHash("unreadable")
        }

        private fun readFully(pos: Long, dst: ByteArray, off: Int, n: Int) {
            var got = 0
            while (got < n) {
                val r = src.readAt(pos + got, dst, off + got, n - got)
                if (r <= 0) throw NoHash("unreadable")
                got += r
            }
        }

        fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
        fun le32(b: ByteArray, i: Int): Long =
            (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or
                (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)
        fun be32(b: ByteArray, i: Int): Long =
            (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or
                (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()

        fun ascii(b: ByteArray, off: Int, s: String): Boolean {
            if (off + s.length > b.size) return false
            for (i in s.indices) if (b[off + i] != s[i].code.toByte()) return false
            return true
        }

        fun matches(pos: Long, s: String): Boolean =
            pos >= 0 && pos + s.length <= length && ascii(bytes(pos, s.length), 0, s)

        fun run(): String {
            val L = length
            // 7.1 WAV
            if (L >= 12 && matches(0, "RIFF") && matches(8, "WAVE")) return hashWav()
            // 7.2 Ogg
            if (L >= 4 && matches(0, "OggS")) return hashOgg()
            // 7.3 MP4
            if (L >= 8 && matches(4, "ftyp")) return hashMp4()
            // 7.4 / 7.5
            val p = skipLeadingId3v2()
            if (matches(p, "fLaC")) return hashFlac(p)
            if (p + 4 <= L) {
                val hdr = mpegHeader(bytes(p, 4))
                if (hdr != null) return hashMp3(p, hdr)
            }
            throw NoHash("unsupported")
        }

        // ------------------------------------------------------------ ranges
        fun hashRanges(ranges: List<LongArray>): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(CHUNK)
            var total = 0L
            for (r in ranges) {
                var pos = r[0]
                val end = r[1]
                need(pos in 0..end && end <= length, "malformed")
                while (pos < end) {
                    val n = minOf(CHUNK.toLong(), end - pos).toInt()
                    readFully(pos, buf, 0, n)
                    md.update(buf, 0, n)
                    pos += n
                    total += n
                }
            }
            need(total > 0, "malformed")
            return hex(md.digest())
        }

        // ------------------------------------------------------------ ID3v2
        fun skipLeadingId3v2(): Long {
            var pos = 0L
            while (true) {
                if (pos + 10 > length) return pos
                val h = bytes(pos, 10)
                if (!ascii(h, 0, "ID3")) return pos
                if (u8(h, 3) == 0xFF || u8(h, 4) == 0xFF) return pos
                if ((6..9).any { u8(h, it) >= 0x80 }) return pos
                val size = (u8(h, 6).toLong() shl 21) or (u8(h, 7).toLong() shl 14) or
                    (u8(h, 8).toLong() shl 7) or u8(h, 9).toLong()
                var t = 10 + size
                if (u8(h, 5) and 0x10 != 0) t += 10
                need(pos + t <= length, "malformed")
                pos += t
            }
        }

        // ------------------------------------------------------------ trailers
        fun stripTrailers(start: Long, endIn: Long): Long {
            var end = endIn
            while (true) {
                val a = end - start
                if (a >= 128 && matches(end - 128, "TAG")) {
                    end -= 128
                    if (end - start >= 227 && matches(end - 227, "TAG+")) end -= 227
                    continue
                }
                if (a >= 32 && matches(end - 32, "APETAGEX")) {
                    val f = bytes(end - 32, 32)
                    val size = le32(f, 12)
                    val flags = le32(f, 20)
                    need(size >= 32, "malformed")
                    val t = size + (if (flags and 0x80000000L != 0L) 32 else 0)
                    need(t <= a, "malformed")
                    end -= t
                    continue
                }
                if (a >= 15 && matches(end - 9, "LYRICS200")) {
                    val d = bytes(end - 15, 6)
                    var size = 0L
                    for (i in 0 until 6) {
                        val c = u8(d, i)
                        need(c in 0x30..0x39, "malformed")
                        size = size * 10 + (c - 0x30)
                    }
                    val t = size + 15
                    need(t <= a, "malformed")
                    need(matches(end - t, "LYRICSBEGIN"), "malformed")
                    end -= t
                    continue
                }
                if (a >= 10 && matches(end - 10, "3DI")) {
                    val f = bytes(end - 10, 10)
                    need(u8(f, 3) != 0xFF && u8(f, 4) != 0xFF, "malformed")
                    need((6..9).none { u8(f, it) >= 0x80 }, "malformed")
                    val size = (u8(f, 6).toLong() shl 21) or (u8(f, 7).toLong() shl 14) or
                        (u8(f, 8).toLong() shl 7) or u8(f, 9).toLong()
                    val t = size + 20
                    need(t <= a, "malformed")
                    need(matches(end - t, "ID3"), "malformed")
                    end -= t
                    continue
                }
                return end
            }
        }

        // ------------------------------------------------------------ MP3
        /** Returns (frameLength, sideInfoLength) or null. */
        fun mpegHeader(b: ByteArray): IntArray? {
            if (b.size < 4 || u8(b, 0) != 0xFF || (u8(b, 1) and 0xE0) != 0xE0) return null
            val ver = (u8(b, 1) shr 3) and 3
            val layer = (u8(b, 1) shr 1) and 3
            val bri = u8(b, 2) shr 4
            val sri = (u8(b, 2) shr 2) and 3
            val pad = (u8(b, 2) shr 1) and 1
            val mono = (u8(b, 3) shr 6) == 3
            if (ver == 1 || layer != 1 || bri == 0 || bri == 15 || sri == 3) return null
            val rate = when (ver) {
                3 -> intArrayOf(44100, 48000, 32000)
                2 -> intArrayOf(22050, 24000, 16000)
                else -> intArrayOf(11025, 12000, 8000)
            }[sri]
            return if (ver == 3) {
                intArrayOf(144000 * BR_V1[bri] / rate + pad, if (mono) 17 else 32)
            } else {
                intArrayOf(72000 * BR_V2[bri] / rate + pad, if (mono) 9 else 17)
            }
        }

        fun hashMp3(p: Long, hdr: IntArray): String {
            var s = p
            val e = stripTrailers(s, length)
            val fl = hdr[0]
            val si = hdr[1]
            need(fl.toLong() <= e - s, "malformed")
            val frame = bytes(s, fl)
            val x = 4 + si
            val xing = x + 4 <= fl && (ascii(frame, x, "Xing") || ascii(frame, x, "Info"))
            val vbri = fl >= 40 && ascii(frame, 36, "VBRI")
            if (xing || vbri) s += fl
            return hashRanges(listOf(longArrayOf(s, e)))
        }

        // ------------------------------------------------------------ FLAC
        fun hashFlac(start: Long): String {
            val end = stripTrailers(start, length)
            var pos = start + 4
            var first = true
            while (true) {
                need(pos + 4 <= end, "malformed")
                val h = bytes(pos, 4)
                val last = u8(h, 0) and 0x80 != 0
                val type = u8(h, 0) and 0x7F
                val len = (u8(h, 1).toLong() shl 16) or (u8(h, 2).toLong() shl 8) or u8(h, 3).toLong()
                need(type != 127, "malformed")
                if (first) {
                    need(type == 0, "malformed")
                    first = false
                }
                pos += 4 + len
                need(pos <= end, "malformed")
                if (last) break
            }
            return hashRanges(listOf(longArrayOf(pos, end)))
        }

        // ------------------------------------------------------------ MP4
        fun hashMp4(): String {
            val L = length
            var pos = 0L
            val ranges = ArrayList<LongArray>()
            while (pos < L) {
                need(pos + 8 <= L, "malformed")
                val h = bytes(pos, 8)
                var size = be32(h, 0)
                val isMdat = ascii(h, 4, "mdat")
                val hl: Long
                when (size) {
                    1L -> {
                        need(pos + 16 <= L, "malformed")
                        val b = bytes(pos + 8, 8)
                        size = (be32(b, 0) shl 32) or be32(b, 4)
                        // Unsigned 64-bit: a negative signed value is >= 2^63, larger than any file.
                        need(size >= 0, "malformed")
                        hl = 16
                        need(size >= 16, "malformed")
                    }
                    0L -> {
                        size = L - pos
                        hl = 8
                    }
                    else -> {
                        hl = 8
                        need(size >= 8, "malformed")
                    }
                }
                need(size <= L - pos, "malformed")
                if (isMdat) ranges.add(longArrayOf(pos + hl, pos + size))
                pos += size
            }
            need(ranges.isNotEmpty(), "malformed")
            return hashRanges(ranges)
        }

        // ------------------------------------------------------------ WAV
        fun hashWav(): String {
            val riffSize = le32(bytes(4, 4), 0)
            val extent = 8 + riffSize
            need(extent <= length, "malformed")
            var pos = 12L
            var data: LongArray? = null
            while (pos + 8 <= extent) {
                val h = bytes(pos, 8)
                val size = le32(h, 4)
                val end = pos + 8 + size
                need(end <= extent, "malformed")
                if (ascii(h, 0, "data")) {
                    need(data == null, "malformed")
                    data = longArrayOf(pos + 8, end)
                }
                pos = end + (size and 1L)
            }
            need(data != null, "malformed")
            return hashRanges(listOf(data!!))
        }

        // ------------------------------------------------------------ Ogg
        private class Packet {
            var buf = ByteArray(4096)
            var size = 0          // bytes buffered
            var length = 0L       // full packet length
            fun append(src: ByteArray, off: Int, n: Int) {
                if (size + n > buf.size) {
                    var cap = buf.size
                    while (cap < size + n) cap *= 2
                    buf = buf.copyOf(cap)
                }
                System.arraycopy(src, off, buf, size, n)
                size += n
            }
        }

        fun hashOgg(): String {
            val L = length
            val md = MessageDigest.getInstance("SHA-256")
            var pos = 0L
            var serial = 0L
            var seq0 = 0L
            var pageNo = 0L
            var pending: Packet? = null
            var eosSeen = false
            var index = 0L
            // 0 = undecided, 1 = Vorbis, 2 = Opus
            var codec = 0
            val lenBuf = ByteArray(8)

            fun completePacket(p: Packet) {
                when (index) {
                    0L -> {
                        codec = when {
                            ascii(p.buf.copyOf(p.size), 0, "\u0001vorbis") -> 1
                            ascii(p.buf.copyOf(p.size), 0, "OpusHead") -> 2
                            else -> throw NoHash("unsupported")
                        }
                        if (codec == 2) {
                            need(p.length >= 19, "malformed")
                            p.buf[16] = 0
                            p.buf[17] = 0
                        }
                    }
                    1L -> {
                        val head = p.buf.copyOf(p.size)
                        if (codec == 1) need(ascii(head, 0, "\u0003vorbis"), "malformed")
                        else need(ascii(head, 0, "OpusTags"), "malformed")
                    }
                    2L -> if (codec == 1) need(ascii(p.buf.copyOf(p.size), 0, "\u0005vorbis"), "malformed")
                }
                if (index != 1L) {
                    var v = p.length
                    for (i in 7 downTo 0) { lenBuf[i] = (v and 0xFF).toByte(); v = v ushr 8 }
                    md.update(lenBuf)
                    md.update(p.buf, 0, p.size)
                }
                index++
            }

            while (pos < L) {
                need(!eosSeen, "unsupported")
                need(pos + 27 <= L, "malformed")
                val h = bytes(pos, 27)
                need(ascii(h, 0, "OggS"), "malformed")
                need(u8(h, 4) == 0, "malformed")
                val flags = u8(h, 5)
                val pSerial = le32(h, 14)
                val pSeq = le32(h, 18)
                val nseg = u8(h, 26)
                need(pos + 27 + nseg <= L, "malformed")
                val lacing = bytes(pos + 27, nseg)
                var bodyLen = 0L
                for (i in 0 until nseg) bodyLen += u8(lacing, i)
                need(pos + 27 + nseg + bodyLen <= L, "malformed")
                if (pageNo == 0L) {
                    need(flags and 0x02 != 0, "malformed")
                    serial = pSerial
                    seq0 = pSeq
                } else {
                    need(pSerial == serial, "unsupported")
                    need(flags and 0x02 == 0, "unsupported")
                    need(pSeq == ((seq0 + pageNo) and 0xFFFFFFFFL), "malformed")
                }
                val cont = flags and 0x01 != 0
                need(cont == (pending != null), "malformed")
                val body = bytes(pos + 27 + nseg, bodyLen.toInt())
                var bpos = 0
                for (i in 0 until nseg) {
                    val lv = u8(lacing, i)
                    val p = pending ?: Packet().also { pending = it }
                    p.length += lv
                    if (index == 1L) {
                        val keep = minOf(lv, maxOf(0, 8 - p.size))
                        if (keep > 0) p.append(body, bpos, keep)
                    } else {
                        need(p.length <= OGG_MAX_PACKET, "malformed")
                        p.append(body, bpos, lv)
                    }
                    bpos += lv
                    if (lv < 255) {
                        completePacket(p)
                        pending = null
                    }
                }
                if (flags and 0x04 != 0) eosSeen = true
                pos += 27 + nseg + bodyLen
                pageNo++
            }
            need(pageNo > 0, "malformed")
            need(eosSeen, "malformed")
            need(pending == null, "malformed")
            need(index >= 1, "malformed")
            when (codec) {
                1 -> need(index >= 4, "malformed")
                2 -> need(index >= 3, "malformed")
                else -> throw NoHash("malformed")
            }
            return hex(md.digest())
        }
    }

    private val BR_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BR_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    private val HEX = "0123456789abcdef".toCharArray()
    fun hex(b: ByteArray): String {
        val c = CharArray(b.size * 2)
        for (i in b.indices) {
            val v = b[i].toInt() and 0xFF
            c[2 * i] = HEX[v ushr 4]
            c[2 * i + 1] = HEX[v and 0x0F]
        }
        return String(c)
    }

    private val HASH_RE = Regex("^[0-9a-f]{64}$")
    fun isValidHex(s: String?): Boolean = s != null && HASH_RE.matches(s)
}
