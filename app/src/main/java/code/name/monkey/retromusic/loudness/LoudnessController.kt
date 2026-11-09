package code.name.monkey.retromusic.loudness

import android.media.audiofx.LoudnessEnhancer
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
 * Attenuation goes through the volume multiplier; boost uses [LoudnessEnhancer].
 * Missing peak => no positive boost. Not dynamic-range compression.
 */
class LoudnessController(
    private val repository: LoudnessRepository,
    private val scope: CoroutineScope,
) {
    @Volatile var attenuationLinear: Float = 1f
        private set
    @Volatile var boostMillibels: Int = 0
        private set

    private var enhancer: LoudnessEnhancer? = null
    private var job: Job? = null
    private var targetAttenuation = 1f
    private var targetBoostMb = 0

    fun onAudioSession(sessionId: Int) {
        releaseEnhancer()
        if (sessionId != 0) {
            enhancer = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()?.also {
                it.enabled = boostMillibels > 0
                if (boostMillibels > 0) it.setTargetGain(boostMillibels)
            }
        }
    }

    fun releaseEnhancer() {
        runCatching { enhancer?.release() }
        enhancer = null
    }

    fun clear() {
        job?.cancel()
        attenuationLinear = 1f
        boostMillibels = 0
        targetAttenuation = 1f
        targetBoostMb = 0
        LoudnessPlaybackState.attenuationLinear = 1f
        applyEnhancer(0)
    }

    fun refreshForSong(song: Song?, onApplied: () -> Unit) {
        job?.cancel()
        if (song == null || !PreferenceUtil.loudnessEnabled) {
            clear()
            onApplied()
            return
        }
        if (!LoudnessSchedule.isActiveNow(
                PreferenceUtil.loudnessScheduleEnabled,
                PreferenceUtil.loudnessScheduleStart,
                PreferenceUtil.loudnessScheduleEnd,
            )
        ) {
            clear()
            onApplied()
            return
        }
        val rootId = PreferenceUtil.loudnessRootId
        val rootUri = PreferenceUtil.loudnessRootUri
        job = scope.launch(Dispatchers.IO) {
            val relative = relativeForSong(song, rootUri)
            val entity = when {
                relative != null -> repository.lookupExact(rootId, relative)
                else -> repository.findBySongPath(song.data)
            }
            val appliedDb = computeAppliedDb(entity)
            val linear = LoudnessGainMath.dbToLinear(appliedDb)
            val (att, boost) = LoudnessGainMath.splitForAndroidVolume(linear)
            targetAttenuation = att
            targetBoostMb = boost
            attenuationLinear = att
            boostMillibels = boost
            LoudnessPlaybackState.attenuationLinear = att
            launch(Dispatchers.Main) {
                applyEnhancer(boost)
                onApplied()
            }
        }
    }

    fun reevaluateSchedule(onApplied: () -> Unit) {
        // Caller should pass current song again; this just clears if schedule inactive.
        if (!PreferenceUtil.loudnessEnabled ||
            !LoudnessSchedule.isActiveNow(
                PreferenceUtil.loudnessScheduleEnabled,
                PreferenceUtil.loudnessScheduleStart,
                PreferenceUtil.loudnessScheduleEnd,
            )
        ) {
            clear()
            onApplied()
        }
    }

    private fun applyEnhancer(mb: Int) {
        val e = enhancer ?: return
        runCatching {
            if (mb > 0) {
                e.setTargetGain(mb)
                e.enabled = true
            } else {
                e.setTargetGain(0)
                e.enabled = false
            }
        }
    }

    private fun computeAppliedDb(entity: LoudnessEntity?): Double {
        if (entity == null) return 0.0
        return LoudnessGainMath.appliedGainDb(
            trackGainDb = entity.trackGainDb,
            albumGainDb = entity.albumGainDb,
            preferAlbum = PreferenceUtil.loudnessPreferAlbum,
            strengthPercent = PreferenceUtil.loudnessStrength,
            maxBoostDb = PreferenceUtil.loudnessMaxBoostDb.toDouble(),
            maxCutDb = PreferenceUtil.loudnessMaxCutDb.toDouble(),
            truePeakDbtp = entity.truePeakDbtp,
            allowBoostWithoutPeak = false,
        )
    }

    companion object {
        fun relativeForSong(song: Song, rootUriOrPath: String): String? {
            if (rootUriOrPath.isBlank()) {
                // Fall back: use path after last common music folder heuristic — require mapped root.
                return null
            }
            // File path songs
            val data = song.data
            if (data.startsWith('/') || data.contains("://").not() && data.contains('/')) {
                return PathValidation.stripRootPrefix(data, rootUriOrPath)
            }
            // For content URIs, relative path must come from tree mapping stored separately.
            return null
        }
    }
}
