/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.service

import android.content.Context
import android.media.AudioManager

/**
 * One audio session id shared by every local player Retro creates (ExoPlayer and both
 * crossfade MediaPlayers). Audio effects are attached to an audio session, so sharing one id
 * lets the built-in equalizer survive track changes, crossfades and player re-creation.
 */
object RetroAudioSession {
    @Volatile
    private var sessionId: Int = 0

    fun get(context: Context): Int {
        if (sessionId == 0) {
            synchronized(this) {
                if (sessionId == 0) {
                    val am = context.applicationContext.getSystemService(AudioManager::class.java)
                    val id = am?.generateAudioSessionId() ?: AudioManager.ERROR
                    sessionId = if (id > 0) id else 0
                }
            }
        }
        return sessionId
    }
}
