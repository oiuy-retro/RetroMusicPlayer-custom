package code.name.monkey.retromusic.loudness

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

data class ScanProgress(
    val processed: Int,
    val total: Int,
    val currentPath: String?,
    val lastError: String?,
)

data class ScanResult(
    val measuredLufs: Double,
    val truePeakDbtp: Double?,
    val trackGainDb: Double,
)

/**
 * On-device EBU R128 scan via MediaExtractor/MediaCodec PCM + libebur128.
 * Streaming bounded buffers; cancellable between tracks and within decode loops.
 */
class LoudnessScanner(
    private val context: Context,
    private val repository: LoudnessRepository,
) {
    suspend fun scanFiles(
        rootId: String,
        files: List<Pair<String /*relative*/, String /*absoluteOrUri*/>>,
        onProgress: (ScanProgress) -> Unit,
    ): Int = withContext(Dispatchers.Default) {
        if (!LoudnessNative.ensureLoaded()) {
            throw IllegalStateException(
                "Native loudness library unavailable: ${LoudnessNative.loadErrorMessage()}"
            )
        }
        var ok = 0
        files.forEachIndexed { index, (relative, path) ->
            coroutineContext.ensureActive()
            onProgress(ScanProgress(index, files.size, relative, null))
            try {
                val measured = measurePath(path)
                if (measured != null) {
                    repository.upsertMeasured(
                        rootId = rootId,
                        relativePath = relative,
                        measuredLufs = measured.measuredLufs,
                        truePeakDbtp = measured.truePeakDbtp,
                        trackGainDb = measured.trackGainDb,
                    )
                    ok++
                } else {
                    onProgress(ScanProgress(index + 1, files.size, relative, "unsupported_or_failed"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onProgress(ScanProgress(index + 1, files.size, relative, e.message))
            }
        }
        onProgress(ScanProgress(files.size, files.size, null, null))
        ok
    }

    fun measurePath(pathOrUri: String): ScanResult? {
        if (!LoudnessNative.ensureLoaded()) return null
        val extractor = MediaExtractor()
        try {
            if (pathOrUri.startsWith("content:") || pathOrUri.startsWith("file:")) {
                extractor.setDataSource(context, Uri.parse(pathOrUri), null)
            } else {
                extractor.setDataSource(pathOrUri)
            }
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val handle = LoudnessNative.nativeInit(channels, sampleRate)
            if (handle == 0L) {
                codec.release()
                return null
            }
            try {
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                val timeoutUs = 10_000L
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(timeoutUs)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex)!!
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inIndex, 0, sampleSize, extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                    if (outIndex >= 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0) {
                            feedPcm(handle, outBuf, info, channels)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
                val lufs = LoudnessNative.nativeIntegratedLoudness(handle)
                if (!lufs.isFinite()) return null
                val peak = LoudnessNative.nativeTruePeak(handle).takeIf { it.isFinite() }
                val gain = LoudnessGainMath.gainFromLufs(lufs)
                return ScanResult(lufs, peak, gain)
            } finally {
                LoudnessNative.nativeDestroy(handle)
                codec.stop()
                codec.release()
            }
        } catch (_: Exception) {
            return null
        } finally {
            extractor.release()
        }
    }

    private fun feedPcm(
        handle: Long,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
    ) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        // Assume 16-bit PCM if odd size not float; MediaCodec audio commonly returns PCM 16-bit.
        val remaining = buffer.remaining()
        if (remaining <= 0) return
        val shorts = remaining / 2
        val floatSamples = FloatArray(shorts)
        val order = buffer.order()
        var i = 0
        while (buffer.remaining() >= 2) {
            val s = buffer.short
            floatSamples[i++] = s / 32768f
        }
        val frames = i / channels
        if (frames > 0) {
            LoudnessNative.nativeAddFrames(handle, floatSamples, frames)
        }
        buffer.order(order)
    }

    companion object {
        fun listAudioFiles(rootDir: File): List<Pair<String, String>> {
            if (!rootDir.isDirectory) return emptyList()
            val rootPath = rootDir.absolutePath.trimEnd('/')
            val out = ArrayList<Pair<String, String>>()
            rootDir.walkTopDown().forEach { f ->
                if (!f.isFile) return@forEach
                val name = f.name.lowercase()
                if (name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".m4a") ||
                    name.endsWith(".ogg") || name.endsWith(".wav") || name.endsWith(".opus") ||
                    name.endsWith(".aac") || name.endsWith(".wma")
                ) {
                    val abs = f.absolutePath
                    val rel = abs.removePrefix("$rootPath/").replace('\\', '/')
                    if (PathValidation.validateRelative(rel) == null) {
                        out.add(rel to abs)
                    }
                }
            }
            return out
        }
    }
}
