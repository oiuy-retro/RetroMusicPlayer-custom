package code.name.monkey.retromusic.loudness

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    val skipped: Int = 0,
)

data class ScanResult(
    val measuredLufs: Double,
    val truePeakDbtp: Double?,
    val trackGainDb: Double,
    val frames: Long,
)

/**
 * On-device EBU R128 scan via MediaExtractor/MediaCodec PCM + libebur128.
 * Streaming bounded buffers; cancellable; incremental skip of already-measured tracks.
 */
class LoudnessScanner(
    private val context: Context,
    private val repository: LoudnessRepository,
) {
    suspend fun scanFiles(
        rootId: String,
        files: List<Pair<String, String>>,
        skipAlreadyMeasured: Boolean = true,
        onProgress: (ScanProgress) -> Unit,
    ): Int = withContext(Dispatchers.Default) {
        if (!LoudnessNative.ensureLoaded()) {
            throw IllegalStateException(
                "Native loudness library unavailable: ${LoudnessNative.loadErrorMessage()}"
            )
        }
        var ok = 0
        var skipped = 0
        files.forEachIndexed { index, (relative, path) ->
            coroutineContext.ensureActive()
            if (skipAlreadyMeasured && repository.hasMeasured(rootId, relative)) {
                skipped++
                onProgress(ScanProgress(index + 1, files.size, relative, null, skipped))
                return@forEachIndexed
            }
            onProgress(ScanProgress(index, files.size, relative, null, skipped))
            try {
                val measured = measurePath(path) {
                    coroutineContext.ensureActive()
                }
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
                    onProgress(ScanProgress(index + 1, files.size, relative, "unsupported_or_failed", skipped))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onProgress(ScanProgress(index + 1, files.size, relative, e.message, skipped))
            }
        }
        onProgress(ScanProgress(files.size, files.size, null, null, skipped))
        ok
    }

    /**
     * @param cancelCheck invoked during decode; may throw CancellationException
     */
    fun measurePath(pathOrUri: String, cancelCheck: () -> Unit = {}): ScanResult? {
        if (!LoudnessNative.ensureLoaded()) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var handle = 0L
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
            var format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (sampleRate <= 0 || channels <= 0) return null

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            handle = LoudnessNative.nativeInit(channels, sampleRate)
            if (handle == 0L) return null

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmEncoding = if (Build.VERSION.SDK_INT >= 24) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING, /*AudioFormat.ENCODING_PCM_16BIT*/ 2)
            } else {
                2
            }
            var totalFrames = 0L
            val timeoutUs = 10_000L
            var idleRounds = 0

            while (!outputDone) {
                cancelCheck()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        idleRounds++
                        if (inputDone && idleRounds > 200) {
                            // Decoder stalled after EOS — treat as done to avoid hang
                            outputDone = true
                        }
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        idleRounds = 0
                        val of = codec.outputFormat
                        val newRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        val newCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        if (Build.VERSION.SDK_INT >= 24) {
                            pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                        }
                        if (newRate != sampleRate || newCh != channels) {
                            // Restart analyzer with actual output geometry; discard partial (strict).
                            LoudnessNative.nativeDestroy(handle)
                            sampleRate = newRate
                            channels = newCh
                            handle = LoudnessNative.nativeInit(channels, sampleRate)
                            if (handle == 0L) return null
                            totalFrames = 0L
                        }
                        format = of
                    }
                    else -> {
                        if (outIndex >= 0) {
                            idleRounds = 0
                            val outBuf = codec.getOutputBuffer(outIndex)
                            if (outBuf != null && info.size > 0) {
                                totalFrames += feedPcm(handle, outBuf, info, channels, pcmEncoding)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }

            if (totalFrames < sampleRate / 10) {
                // Less than ~100ms of audio — treat as silence/too short / decode failure
                return null
            }
            val lufs = LoudnessNative.nativeIntegratedLoudness(handle)
            if (!lufs.isFinite()) return null
            // True peak optional; double precision from libebur128. Playback does not boost from it.
            val peak = LoudnessNative.nativeTruePeak(handle).takeIf { it.isFinite() }
            val gain = LoudnessGainMath.gainFromLufs(lufs)
            return ScanResult(lufs, peak, gain, totalFrames)
        } catch (_: CancellationException) {
            throw CancellationException("scan_cancelled")
        } catch (_: Exception) {
            return null
        } finally {
            if (handle != 0L) LoudnessNative.nativeDestroy(handle)
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }

    /** @return frames contributed */
    private fun feedPcm(
        handle: Long,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
        pcmEncoding: Int,
    ): Long {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val remaining = buffer.remaining()
        if (remaining <= 0 || channels <= 0) return 0
        // ENCODING_PCM_FLOAT = 4
        val floatSamples: FloatArray
        val sampleCount: Int
        if (pcmEncoding == 4) {
            sampleCount = remaining / 4
            floatSamples = FloatArray(sampleCount)
            buffer.order(ByteOrder.nativeOrder())
            var i = 0
            while (buffer.remaining() >= 4 && i < sampleCount) {
                floatSamples[i++] = buffer.float
            }
        } else {
            // Default / PCM_16BIT
            sampleCount = remaining / 2
            floatSamples = FloatArray(sampleCount)
            buffer.order(ByteOrder.nativeOrder())
            var i = 0
            while (buffer.remaining() >= 2 && i < sampleCount) {
                floatSamples[i++] = buffer.short / 32768f
            }
        }
        val frames = (sampleCount / channels).toLong()
        if (frames > 0) {
            // Bound native call to complete frames only
            val framedSamples = (frames * channels).toInt()
            val toSend = if (framedSamples == floatSamples.size) floatSamples
            else floatSamples.copyOf(framedSamples)
            LoudnessNative.nativeAddFrames(handle, toSend, frames.toInt())
        }
        return frames
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
                    val rel = PathValidation.stripRootPrefix(abs, rootPath) ?: return@forEach
                    out.add(rel to abs)
                }
            }
            return out
        }
    }
}
