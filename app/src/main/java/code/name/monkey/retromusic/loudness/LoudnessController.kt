package code.name.monkey.retromusic.loudness

import android.os.Handler
import android.os.Looper
import code.name.monkey.retromusic.db.LoudnessEntity
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.PreferenceUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Applies per-track loudness normalization on top of player volume / fade / duck.
 * Attenuation-only (no LoudnessEnhancer): EQ/crossfade can exceed file true-peak
 * headroom and this build has no proven output limiter.
 * Exact match only: configured filesystem root + exact relativePath.
 */
class LoudnessController(
    private val repository: LoudnessRepository,
    private val scope: CoroutineScope,
) {
    @Volatile var attenuationLinear: Float = 1f
        private set

    private var job: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rampRunnable: Runnable? = null

    fun onAudioSession(sessionId: Int) {
        // Intentionally no LoudnessEnhancer (avoids EQ conflict; boost disabled).
    }

    fun releaseEnhancer() {
        // No-op retained for MusicService call sites.
    }

    fun clear(onApplied: (() -> Unit)? = null) {
        job?.cancel()
        attenuationLinear = 1f
        rampTo(1f, onApplied)
    }

    fun refreshForSong(song: Song?, onApplied: () -> Unit) {
        job?.cancel()
        if (song == null || !PreferenceUtil.loudnessEnabled) {
            clear(onApplied)
            return
        }
        if (!LoudnessSchedule.isActiveNow(
                PreferenceUtil.loudnessScheduleEnabled,
                PreferenceUtil.loudnessScheduleStart,
                PreferenceUtil.loudnessScheduleEnd,
            )
        ) {
            clear(onApplied)
            return
        }
        val rootId = PreferenceUtil.loudnessRootId
        val rootPath = PreferenceUtil.loudnessRootUri
        if (rootId.isBlank() || rootPath.isBlank()) {
            clear(onApplied)
            return
        }
        job = scope.launch(Dispatchers.IO) {
            val relative = relativeForSong(song, rootPath)
            val entity = if (relative != null) repository.lookupExact(rootId, relative) else null
            val appliedDb = computeAppliedDb(entity)
            val att = LoudnessGainMath.dbToLinear(appliedDb).coerceIn(0.0, 1.0).toFloat()
            launch(Dispatchers.Main) {
                attenuationLinear = att
                rampTo(att, onApplied)
            }
        }
    }

    fun reevaluateSchedule(onApplied: () -> Unit) {
        if (!PreferenceUtil.loudnessEnabled ||
            !LoudnessSchedule.isActiveNow(
                PreferenceUtil.loudnessScheduleEnabled,
                PreferenceUtil.loudnessScheduleStart,
                PreferenceUtil.loudnessScheduleEnd,
            )
        ) {
            clear(onApplied)
        }
    }

    private fun cancelRamp() {
        rampRunnable?.let { mainHandler.removeCallbacks(it) }
        rampRunnable = null
    }

    /** Short multi-step ramp so large attenuation jumps are less clicky. */
    private fun rampTo(target: Float, onStep: (() -> Unit)? = null) {
        cancelRamp()
        val start = LoudnessPlaybackState.attenuationLinear
        if (abs(start - target) < 0.001f) {
            LoudnessPlaybackState.attenuationLinear = target
            onStep?.invoke()
            return
        }
        val steps = 8
        var i = 0
        val r = object : Runnable {
            override fun run() {
                i++
                val t = (i.toFloat() / steps).coerceAtMost(1f)
                LoudnessPlaybackState.attenuationLinear = start + (target - start) * t
                onStep?.invoke()
                if (i < steps) {
                    mainHandler.postDelayed(this, 12L)
                } else {
                    rampRunnable = null
                }
            }
        }
        rampRunnable = r
        mainHandler.post(r)
    }

    private fun computeAppliedDb(entity: LoudnessEntity?): Double {
        if (entity == null) return 0.0
        return LoudnessGainMath.appliedGainDb(
            trackGainDb = entity.trackGainDb,
            albumGainDb = entity.albumGainDb,
            preferAlbum = PreferenceUtil.loudnessPreferAlbum,
            strengthPercent = PreferenceUtil.loudnessStrength,
            maxBoostDb = 0.0,
            maxCutDb = PreferenceUtil.loudnessMaxCutDb.toDouble(),
            truePeakDbtp = entity.truePeakDbtp,
        )
    }

    companion object {
        fun relativeForSong(song: Song, rootAbsolutePath: String): String? {
            if (rootAbsolutePath.isBlank()) return null
            val data = song.data
            if (data.isBlank() || data.startsWith("content:")) return null
            return PathValidation.stripRootPrefix(data, rootAbsolutePath)
        }
    }
}
