package code.name.monkey.retromusic.service

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.content.getSystemService

/**
 * Optional, more reliable "is other media playing" signal for Resume after other media.
 * With notification access granted, Retro can read other apps' media session playback state
 * (for example a paused web video in a browser, whose audio stream stays open). Only the
 * playback state is read; notifications themselves are never read and nothing is stored.
 */
class MediaSessionWatcher(
    private val context: Context,
    private val handler: Handler,
    private val onChange: () -> Unit,
) {
    private val manager: MediaSessionManager? = context.getSystemService()
    private val component = ComponentName(context, MediaStateListenerService::class.java)
    private val controllers = mutableListOf<MediaController>()
    private var started = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            onChange()
        }

        override fun onSessionDestroyed() {
            onChange()
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list ->
            attach(list.orEmpty())
            onChange()
        }

    /** True if the user granted notification access, so session states can be read. */
    val isAvailable: Boolean get() = hasAccess(context)

    fun start() {
        if (started || manager == null || !isAvailable) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            attach(manager.getActiveSessions(component))
            started = true
        } catch (e: SecurityException) {
            ResumeLog.d("session watch unavailable: ${e.message}")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers.clear()
    }

    private fun attach(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers.clear()
        list.filter { it.packageName != context.packageName }.forEach {
            it.registerCallback(controllerCallback, handler)
            controllers.add(it)
        }
    }

    /** Null if session states can't be read, otherwise whether another app's session plays. */
    fun isOtherSessionPlaying(): Boolean? {
        if (!started) return null
        val states = controllers.map { it.packageName to it.playbackState?.state }
        ResumeLog.d("sessions: " + states.joinToString { "${it.first}=${stateName(it.second)}" })
        return states.any {
            it.second == PlaybackState.STATE_PLAYING ||
                    it.second == PlaybackState.STATE_BUFFERING ||
                    it.second == PlaybackState.STATE_FAST_FORWARDING ||
                    it.second == PlaybackState.STATE_REWINDING
        }
    }

    private fun stateName(s: Int?) = when (s) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_NONE, null -> "none"
        else -> s.toString()
    }

    companion object {
        fun hasAccess(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val name = ComponentName(context, MediaStateListenerService::class.java)
            return flat.split(":").any { ComponentName.unflattenFromString(it) == name }
        }
    }
}

/**
 * Empty listener: it only exists so the user can grant notification access, which Android
 * requires before an app may read other apps' media session playback state. It ignores all
 * notifications.
 */
class MediaStateListenerService : NotificationListenerService()
