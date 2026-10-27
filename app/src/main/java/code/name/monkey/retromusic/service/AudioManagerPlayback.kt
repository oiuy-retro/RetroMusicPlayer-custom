package code.name.monkey.retromusic.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
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

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Consecutive checks in which no other media was playing. */
    private var idleChecks = 0

    /**
     * Android removes an app from the focus stack after a permanent focus loss, so
     * AUDIOFOCUS_GAIN usually never comes back. While waiting, poll whether any media is
     * still playing (Retro itself is paused, so this only sees other apps) and resume once
     * it has been silent for [RESUME_IDLE_CHECKS] checks in a row.
     */
    private val resumeCheck = object : Runnable {
        override fun run() {
            if (!isPausedByLossOfFocus) return
            val am = audioManager
            if (am == null || isPlaying || isAudioFocusEnabled || !isResumeAfterFocusLossEnabled) {
                clearPendingFocusResume()
                return
            }
            val otherMediaActive = am.isMusicActive || am.mode != AudioManager.MODE_NORMAL
            idleChecks = if (otherMediaActive) 0 else idleChecks + 1
            if (idleChecks >= RESUME_IDLE_CHECKS) {
                // Only resume if focus is actually granted (not during a call, for example).
                if (requestFocus()) {
                    resumeAfterFocusLoss()
                    return
                }
                idleChecks = 0
            }
            mainHandler.postDelayed(this, RESUME_CHECK_INTERVAL_MS)
        }
    }

    private fun resumeAfterFocusLoss() {
        clearPendingFocusResume()
        if (!isPlaying && isInitialized) {
            start()
            callbacks?.onPlayStateChanged()
        }
    }

    /** Forget a pending automatic resume after a permanent focus loss. */
    fun clearPendingFocusResume() {
        isPausedByLossOfFocus = false
        idleChecks = 0
        mainHandler.removeCallbacks(resumeCheck)
    }
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
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
                        isPausedByLossOfFocus = true
                        idleChecks = 0
                        mainHandler.postDelayed(resumeCheck, RESUME_FIRST_CHECK_DELAY_MS)
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
        /** Wait before the first check, so the other app has started its playback. */
        const val RESUME_FIRST_CHECK_DELAY_MS = 3000L
        const val RESUME_CHECK_INTERVAL_MS = 1000L
        /** Seconds of silence from other apps before resuming (bridges short gaps). */
        const val RESUME_IDLE_CHECKS = 3
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