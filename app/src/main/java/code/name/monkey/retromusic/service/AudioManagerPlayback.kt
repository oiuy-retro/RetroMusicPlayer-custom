package code.name.monkey.retromusic.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.CallSuper
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.service.playback.Playback
import code.name.monkey.retromusic.util.PreferenceUtil.isAudioFocusEnabled
import code.name.monkey.retromusic.util.PreferenceUtil.isResumeAfterFocusLossEnabled

abstract class AudioManagerPlayback(val context: Context) : Playback {

    private val audioManager: AudioManager? = context.getSystemService()

    /** App-level gain from the now playing volume slider (0..1, 1 = unity). */
    @Volatile
    protected var playerGain: Float = PlayerVolume.currentGain
        private set

    /** Last volume requested through [setVolume] (fades / ducking), before [playerGain]. */
    @Volatile
    protected var requestedVolume: Float = Volume.NORMAL

    /** [vol] scaled by the player gain, so fades and ducking never exceed the user's level. */
    protected fun scaledVolume(vol: Float): Float = (vol * playerGain).coerceIn(0f, 1f)

    override fun setPlayerGain(gain: Float) {
        playerGain = gain.coerceIn(0f, 1f)
        onPlayerGainChanged()
    }

    /** Re-apply the current volume after [playerGain] changed. */
    protected abstract fun onPlayerGainChanged()
    private var isPausedByTransientLossOfFocus = false

    /**
     * True while Retro is paused because another app took audio focus permanently
     * (AUDIOFOCUS_LOSS) and "Resume after other media" is on. Cleared by any explicit
     * start / pause / stop, so a pause by the user is never undone automatically.
     */
    @Volatile
    private var isPausedByLossOfFocus = false

    /** True while Retro is waiting to resume after other media stops. */
    val isResumePending: Boolean get() = isPausedByLossOfFocus

    private val mainHandler = Handler(Looper.getMainLooper())

    /** True once other media has been seen playing since the focus loss. */
    @Volatile
    private var otherMediaSeen = false

    /** Consecutive legacy (API 24-25) checks in which no other media was playing. */
    private var idleChecks = 0

    private val focusResumeLock = Any()

    /** Optional session-state watcher (needs notification access). */
    private val sessionWatcher = MediaSessionWatcher(context, mainHandler) {
        val am = audioManager
        if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            onOtherMediaChanged(am.activePlaybackConfigurations)
        } else {
            onOtherMediaChanged(emptyList())
        }
    }

    /** API 26+ playback listener, registered only while a resume is pending. */
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    /**
     * Android removes an app from the focus stack after a permanent focus loss, so
     * AUDIOFOCUS_GAIN usually never comes back. Instead, Retro watches whether other media
     * is still playing and resumes once it has stopped.
     *
     * On API 26+ this is event driven: an [AudioManager.AudioPlaybackCallback] reports every
     * playback change, and [debouncedResume] runs once nothing else has played for
     * [RESUME_DEBOUNCE_MS] (cancelled if playback starts again). On API 24-25 a check runs
     * once a second instead.
     */
    private fun startWaitingForOtherMedia() {
        ResumeLog.d("permanent focus loss, waiting for other media to stop")
        isPausedByLossOfFocus = true
        focusRetries = 0
        mainHandler.post {
            sessionWatcher.start()
            ResumeLog.d("session watch ${if (sessionWatcher.isAvailable) "on" else "off (no notification access)"}")
        }
        otherMediaSeen = false
        idleChecks = 0
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            synchronized(focusResumeLock) {
                if (playbackCallback == null) {
                    val callback = object : AudioManager.AudioPlaybackCallback() {
                        override fun onPlaybackConfigChanged(
                            configs: MutableList<AudioPlaybackConfiguration>?
                        ) {
                            onOtherMediaChanged(configs.orEmpty())
                        }
                    }
                    am.registerAudioPlaybackCallback(callback, mainHandler)
                    playbackCallback = callback
                }
            }
            // Check once right away; the callback only fires on the next change.
            mainHandler.post { onOtherMediaChanged(am.activePlaybackConfigurations) }
        } else {
            mainHandler.postDelayed(legacyResumeCheck, RESUME_DEBOUNCE_MS)
        }
    }

    /** True if another app is playing media, or a call / ringtone is in progress. */
    private fun isOtherMediaActive(configs: List<AudioPlaybackConfiguration>?): Boolean {
        val am = audioManager ?: return false
        if (am.mode != AudioManager.MODE_NORMAL) return true
        // The playback config list also contains paused and stopped players (Retro's own
        // and the video app's), and apps can't read a player's state, so the list can't
        // tell whether anything is actually playing. It is only used as a change trigger;
        // isMusicActive reports whether any media audio is really being played.
        // A paused browser video can keep its audio stream open, so isMusicActive stays
        // true. If notification access is granted, other apps' media session states are
        // used instead, which do report paused.
        sessionWatcher.isOtherSessionPlaying()?.let { return it }
        return am.isMusicActive
    }

    /** Called when Always play or Resume after other media is changed. */
    fun onFocusResumeSettingsChanged() {
        if (isPausedByLossOfFocus) shouldKeepWaiting()
    }

    /** False (and clears the pending resume) if waiting no longer makes sense. */
    private fun shouldKeepWaiting(): Boolean {
        if (!isPausedByLossOfFocus) return false
        if (audioManager == null || isPlaying || isAudioFocusEnabled ||
            !isResumeAfterFocusLossEnabled
        ) {
            clearPendingFocusResume("no longer applies (playing=$isPlaying alwaysPlay=$isAudioFocusEnabled resumeOn=$isResumeAfterFocusLossEnabled)")
            return false
        }
        return true
    }

    private fun onOtherMediaChanged(configs: List<AudioPlaybackConfiguration>) {
        if (!shouldKeepWaiting()) return
        mainHandler.removeCallbacks(debouncedResume)
        focusRetries = 0
        val active = isOtherMediaActive(configs)
        ResumeLog.d("playback changed: configs=${configs.size} otherActive=$active")
        if (active) {
            otherMediaSeen = true
        } else {
            mainHandler.postDelayed(debouncedResume, resumeDelay())
        }
    }

    /**
     * Until other media has actually been seen, wait a bit longer, because the other app
     * usually takes focus a moment before its playback starts.
     */
    private fun resumeDelay(): Long =
        if (otherMediaSeen) RESUME_DEBOUNCE_MS else RESUME_INITIAL_GRACE_MS

    private val debouncedResume = Runnable {
        if (!shouldKeepWaiting()) return@Runnable
        val am = audioManager ?: return@Runnable
        if (isOtherMediaActive(am.activePlaybackConfigurations)) {
            // isMusicActive can lag a moment behind a stop; check again a few times.
            otherMediaSeen = true
            retryLater("other media still active")
            return@Runnable
        }
        // Only resume if focus is actually granted. Android 15+ can refuse focus to an
        // app in the background, so retry a few times before waiting for the next change.
        val granted = requestFocus()
        ResumeLog.d("focus request granted=$granted")
        if (granted) {
            resumeAfterFocusLoss()
        } else {
            retryLater("focus request not granted")
        }
    }

    private var focusRetries = 0

    private fun retryLater(reason: String) {
        if (focusRetries < MAX_RETRIES) {
            focusRetries++
            ResumeLog.d("$reason, retry $focusRetries/$MAX_RETRIES")
            mainHandler.postDelayed(debouncedResume, RESUME_DEBOUNCE_MS)
        } else {
            ResumeLog.d("$reason, waiting for next playback change")
        }
    }

    /** API 24-25 fallback: check once a second. */
    private val legacyResumeCheck = object : Runnable {
        override fun run() {
            if (!shouldKeepWaiting()) return
            if (isOtherMediaActive(null)) {
                otherMediaSeen = true
                idleChecks = 0
            } else {
                idleChecks++
                val needed = resumeDelay() / RESUME_DEBOUNCE_MS
                if (idleChecks >= needed) {
                    if (requestFocus()) {
                        resumeAfterFocusLoss()
                        return
                    }
                    idleChecks = 0
                }
            }
            mainHandler.postDelayed(this, RESUME_DEBOUNCE_MS)
        }
    }

    private fun resumeAfterFocusLoss() {
        ResumeLog.d("resume: isPlaying=$isPlaying initialized=$isInitialized")
        clearPendingFocusResume()
        if (!isPlaying && isInitialized) {
            start()
            callbacks?.onPlayStateChanged()
        }
    }

    /** Forget a pending automatic resume after a permanent focus loss. */
    fun clearPendingFocusResume(reason: String = "explicit play/pause/stop") {
        if (isPausedByLossOfFocus) ResumeLog.d("pending resume cleared: $reason")
        isPausedByLossOfFocus = false
        otherMediaSeen = false
        idleChecks = 0
        mainHandler.removeCallbacks(debouncedResume)
        mainHandler.removeCallbacks(legacyResumeCheck)
        mainHandler.post { sessionWatcher.stop() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            synchronized(focusResumeLock) {
                playbackCallback?.let { audioManager?.unregisterAudioPlaybackCallback(it) }
                playbackCallback = null
            }
        }
    }
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        ResumeLog.d("focus change $focusChange (alwaysPlay=$isAudioFocusEnabled resumeOn=$isResumeAfterFocusLossEnabled playing=$isPlaying)")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (isPausedByLossOfFocus) {
                    resumeAfterFocusLoss()
                }
                if (!isPlaying && isPausedByTransientLossOfFocus) {
                    start()
                    callbacks?.onPlayStateChanged()
                    isPausedByTransientLossOfFocus = false
                }
                setVolume(Volume.NORMAL)
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                // Lost focus for an unbounded amount of time: stop playback and release media playback
                if (!isAudioFocusEnabled) {
                    val wasPlaying = isPlaying
                    pause()
                    callbacks?.onPlayStateChanged()
                    if (wasPlaying && isResumeAfterFocusLossEnabled) {
                        // Keep the focus request (don't abandon it) and wait for the other
                        // media to stop. pause() above cleared any earlier pending resume.
                        startWaitingForOtherMedia()
                    }
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Lost focus for a short time, but we have to stop
                // playback. We don't release the media playback because playback
                // is likely to resume
                val wasPlaying = isPlaying
                pause()
                callbacks?.onPlayStateChanged()
                isPausedByTransientLossOfFocus = wasPlaying
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Lost focus for a short time, but it's ok to keep playing
                // at an attenuated level
                setVolume(Volume.DUCK)
            }
        }
    }
    private val audioFocusRequest: AudioFocusRequestCompat =
        AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    // Fix provided by : Zak (github: @arrhenius975, mail: zakariatalukdar123@gmail.com)
                    // Definition and fix: Ensure correct audio routing for focus requests
                    .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                    .build()
            ).build()

    private val becomingNoisyReceiverIntentFilter =
        IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)

    private var becomingNoisyReceiverRegistered = false
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != null
                && intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY
            ) {
                val serviceIntent = Intent(context, MusicService::class.java)
                serviceIntent.action = MusicService.ACTION_PAUSE
                context.startService(serviceIntent)
            }
        }
    }

    protected fun unregisterBecomingNoisyReceiver() {
        if (becomingNoisyReceiverRegistered) {
            context.unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
        }
    }

    protected fun registerBecomingNoisyReceiver() {
        if (!becomingNoisyReceiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                becomingNoisyReceiver,
                becomingNoisyReceiverIntentFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
            becomingNoisyReceiverRegistered = true
        }
    }

    protected fun requestFocus(): Boolean {
        return AudioManagerCompat.requestAudioFocus(
            audioManager!!,
            audioFocusRequest
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    protected fun abandonFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager!!, audioFocusRequest)
    }

    @CallSuper
    override fun start(): Boolean {
        clearPendingFocusResume()
        if (!requestFocus()) {
            context.showToast(R.string.audio_focus_denied)
        }
        registerBecomingNoisyReceiver()
        return true
    }

    @CallSuper
    override fun stop() {
        clearPendingFocusResume()
        abandonFocus()
        unregisterBecomingNoisyReceiver()
    }

    @CallSuper
    override fun pause(): Boolean {
        clearPendingFocusResume()
        unregisterBecomingNoisyReceiver()
        return true
    }

    private companion object {
        /** Extra 1s checks after the debounce if media is still active or focus is refused. */
        const val MAX_RETRIES = 5

        /** How long other media must stay stopped before Retro resumes. */
        const val RESUME_DEBOUNCE_MS = 1000L

        /** Used instead until other media has been seen playing after the focus loss. */
        const val RESUME_INITIAL_GRACE_MS = 3000L
    }

    object Volume {
        /**
         * The volume we set the media player to when we lose audio focus, but are
         * allowed to reduce the volume instead of stopping playback.
         */
        const val DUCK = 0.2f

        /** The volume we set the media player when we have audio focus.  */
        const val NORMAL = 1.0f
    }
}