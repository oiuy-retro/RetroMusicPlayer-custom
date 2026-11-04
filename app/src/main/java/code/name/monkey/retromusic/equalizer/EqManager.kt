/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.equalizer

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.service.RetroAudioSession

/**
 * Owns Retro's built-in audio effects. The effects are attached to the shared audio session
 * ([RetroAudioSession]) so they keep working across track changes, crossfades and player
 * re-creation. Started and stopped by MusicService; the equalizer screen talks to it directly.
 */
object EqManager {
    private const val TAG = "EqManager"

    private lateinit var appContext: Context
    lateinit var store: EqStore
        private set

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var attachedSession = 0
    private var running = false
    private val main = Handler(Looper.getMainLooper())

    var currentDevice: OutputDevice? = null
        private set

    /** Notified on the main thread when the device or the active settings change. */
    val listeners = mutableSetOf<() -> Unit>()

    fun init(context: Context) {
        if (::store.isInitialized) return
        appContext = context.applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        store = EqStore(PrefsKeyValue(prefs))
    }

    /** Called by MusicService when it is created. */
    fun start(context: Context) {
        init(context)
        if (running) return
        running = true
        val am = appContext.getSystemService(AudioManager::class.java)
        am.registerAudioDeviceCallback(deviceCallback, main)
        refreshDevice()
        applyCurrent()
    }

    /** Called by MusicService when it is destroyed. Releases every effect. */
    fun stop() {
        if (!running) return
        running = false
        runCatching {
            appContext.getSystemService(AudioManager::class.java)
                .unregisterAudioDeviceCallback(deviceCallback)
        }
        releaseEffects()
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onDevicesChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onDevicesChanged()
    }

    private fun onDevicesChanged() {
        val before = currentDevice?.key
        refreshDevice()
        if (currentDevice?.key != before) {
            applyCurrent()
        }
    }

    fun isActive(): Boolean = ::store.isInitialized && store.enabled

    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    fun refreshDevice() {
        if (!::store.isInitialized) return
        val am = appContext.getSystemService(AudioManager::class.java)
        val canReadAddress = hasBluetoothPermission()
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }
            // The earpiece and telephony outputs are never used for music.
            .filter { it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && it.type != AudioDeviceInfo.TYPE_TELEPHONY }
            .map { info ->
                val address = if (canReadAddress && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { info.address }.getOrNull()
                } else null
                DeviceKeys.describe(info.type, address, info.productName?.toString())
            }
        currentDevice = DeviceKeys.pickActive(outputs)
        notifyListeners()
    }

    /** The settings that apply right now. */
    fun currentSettings(): EqSettings = store.settingsFor(currentDevice)

    /** Saves [settings] to the active profile and applies them. */
    fun update(settings: EqSettings) {
        store.saveSettingsFor(currentDevice, settings)
        applyCurrent()
    }

    fun setEnabled(on: Boolean) {
        store.enabled = on
        applyCurrent()
    }

    fun setPerDevice(on: Boolean) {
        store.perDevice = on
        refreshDevice()
        applyCurrent()
    }

    /** Re-applies the active profile, creating or releasing effects as needed. */
    fun applyCurrent() {
        if (!::store.isInitialized) return
        if (!store.enabled || !running) {
            releaseEffects()
            notifyListeners()
            return
        }
        val session = RetroAudioSession.get(appContext)
        if (session == 0) return
        if (attachedSession != session || equalizer == null) {
            releaseEffects()
            createEffects(session)
        }
        val s = currentSettings()
        equalizer?.let { eq ->
            runCatching {
                val levels = s.levelsFor(eq.numberOfBands.toInt())
                val range = eq.bandLevelRange
                levels.forEachIndexed { i, level ->
                    eq.setBandLevel(i.toShort(), level.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                }
                eq.enabled = true
            }.onFailure { Log.w(TAG, "equalizer", it) }
        }
        bassBoost?.let { bb ->
            runCatching {
                bb.setStrength(s.bassBoost.coerceIn(0, 1000).toShort())
                bb.enabled = s.bassBoost > 0
            }
        }
        virtualizer?.let { v ->
            runCatching {
                v.setStrength(s.virtualizer.coerceIn(0, 1000).toShort())
                v.enabled = s.virtualizer > 0
            }
        }
        loudness?.let { l ->
            runCatching {
                l.setTargetGain(s.loudnessGain.coerceIn(0, MAX_LOUDNESS_MB))
                l.enabled = s.loudnessGain > 0
            }
        }
        notifyListeners()
    }

    private fun createEffects(session: Int) {
        attachedSession = session
        equalizer = runCatching { Equalizer(0, session) }.onFailure { Log.w(TAG, "no equalizer", it) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, session) }.getOrNull()
            ?.let { if (it.strengthSupported) it else { it.release(); null } }
        virtualizer = runCatching { Virtualizer(0, session) }.getOrNull()
            ?.let { if (it.strengthSupported) it else { it.release(); null } }
        loudness = runCatching { LoudnessEnhancer(session) }.getOrNull()
    }

    private fun releaseEffects() {
        listOf(equalizer, bassBoost, virtualizer, loudness).forEach { effect ->
            runCatching { effect?.enabled = false }
            runCatching { effect?.release() }
        }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudness = null
        attachedSession = 0
    }

    private fun notifyListeners() {
        listeners.toList().forEach { runCatching { it() } }
    }

    /** Band and preset info for the UI. Uses a short-lived Equalizer when none is attached. */
    data class EqInfo(
        val centerFreqsHz: List<Int>,
        val minLevel: Int,
        val maxLevel: Int,
        val systemPresets: List<String>,
        val bassBoostSupported: Boolean,
        val virtualizerSupported: Boolean,
    )

    fun info(): EqInfo? {
        val existing = equalizer
        val eq = existing ?: runCatching { Equalizer(0, RetroAudioSession.get(appContext)) }.getOrNull() ?: return null
        return try {
            val bands = eq.numberOfBands.toInt()
            EqInfo(
                centerFreqsHz = (0 until bands).map { eq.getCenterFreq(it.toShort()) / 1000 },
                minLevel = eq.bandLevelRange[0].toInt(),
                maxLevel = eq.bandLevelRange[1].toInt(),
                systemPresets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) },
                bassBoostSupported = bassBoost != null || existing == null && probe { BassBoost(0, attachedOr()).let { e -> try { e.strengthSupported } finally { e.release() } } },
                virtualizerSupported = virtualizer != null || existing == null && probe { Virtualizer(0, attachedOr()).let { e -> try { e.strengthSupported } finally { e.release() } } },
            )
        } catch (e: Exception) {
            Log.w(TAG, "info", e)
            null
        } finally {
            if (existing == null) runCatching { eq.release() }
        }
    }

    private fun attachedOr() = RetroAudioSession.get(appContext)

    private fun probe(make: () -> Boolean): Boolean = runCatching { make() }.getOrDefault(false)

    /** Band levels of a built-in device preset. */
    fun systemPresetLevels(index: Int): List<Int>? {
        val existing = equalizer
        val eq = existing ?: runCatching { Equalizer(0, RetroAudioSession.get(appContext)) }.getOrNull() ?: return null
        return try {
            val before = (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() }
            eq.usePreset(index.toShort())
            val levels = (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() }
            if (existing == null) Unit else before.forEachIndexed { i, l -> eq.setBandLevel(i.toShort(), l.toShort()) }
            levels
        } catch (e: Exception) {
            null
        } finally {
            if (existing == null) runCatching { eq.release() }
        }
    }

    const val MAX_LOUDNESS_MB = 1500

    private class PrefsKeyValue(private val prefs: SharedPreferences) : KeyValue {
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun putString(key: String, value: String?) = prefs.edit { putString(key, value) }
        override fun getBoolean(key: String, def: Boolean) = prefs.getBoolean(key, def)
        override fun putBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }
    }
}
