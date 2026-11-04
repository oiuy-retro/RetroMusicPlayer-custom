/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.equalizer

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Minimal key/value storage so the store can be unit tested without SharedPreferences. */
interface KeyValue {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, def: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

/**
 * Persists the equalizer state: master switch, per-device switch, the default profile, saved
 * device profiles and user presets. Profile selection logic lives here too.
 */
class EqStore(private val kv: KeyValue) {
    private val gson = Gson()

    var enabled: Boolean
        get() = kv.getBoolean(KEY_ENABLED, false)
        set(v) = kv.putBoolean(KEY_ENABLED, v)

    var perDevice: Boolean
        get() = kv.getBoolean(KEY_PER_DEVICE, false)
        set(v) = kv.putBoolean(KEY_PER_DEVICE, v)

    var defaultSettings: EqSettings
        get() = kv.getString(KEY_DEFAULT)?.let { parse(it, EqSettings::class.java) } ?: EqSettings()
        set(v) = kv.putString(KEY_DEFAULT, gson.toJson(v))

    fun profiles(): List<DeviceProfile> =
        kv.getString(KEY_PROFILES)?.let {
            runCatching<List<DeviceProfile>> {
                gson.fromJson(it, object : TypeToken<List<DeviceProfile>>() {}.type)
            }.getOrNull()
        }?.filterNotNull()?.map { it.sanitized() } ?: emptyList()

    private fun saveProfiles(list: List<DeviceProfile>) =
        kv.putString(KEY_PROFILES, gson.toJson(list))

    fun profile(key: String): DeviceProfile? = profiles().firstOrNull { it.key == key }

    fun putProfile(profile: DeviceProfile) {
        val list = profiles().filter { it.key != profile.key } + profile
        saveProfiles(list)
    }

    fun rename(key: String, name: String) {
        val p = profile(key) ?: return
        putProfile(p.copy(name = name.trim().ifEmpty { p.name }))
    }

    fun delete(key: String) = saveProfiles(profiles().filter { it.key != key })

    /** Copies the settings of [fromKey] (or the default profile when null) into [toKey]. */
    fun copyFrom(fromKey: String?, toKey: String) {
        val src = if (fromKey == null) defaultSettings else profile(fromKey)?.settings ?: return
        val target = profile(toKey) ?: return
        putProfile(target.copy(settings = src))
    }

    /**
     * Settings to use for [device]. With per-device off, or no device, the default profile.
     * A device seen for the first time gets a profile created from the default one.
     */
    fun settingsFor(device: OutputDevice?): EqSettings {
        if (!perDevice || device == null) return defaultSettings
        profile(device.key)?.let { return it.settings }
        val created = DeviceProfile(device.key, device.label, defaultSettings)
        putProfile(created)
        return created.settings
    }

    /** Saves [settings] to the profile in use for [device]. */
    fun saveSettingsFor(device: OutputDevice?, settings: EqSettings) {
        if (!perDevice || device == null) {
            defaultSettings = settings
            return
        }
        val existing = profile(device.key)
        putProfile(existing?.copy(settings = settings)
            ?: DeviceProfile(device.key, device.label, settings))
    }

    fun customPresets(): List<CustomPreset> =
        kv.getString(KEY_PRESETS)?.let {
            runCatching<List<CustomPreset>> {
                gson.fromJson(it, object : TypeToken<List<CustomPreset>>() {}.type)
            }.getOrNull()
        }?.filterNotNull()?.map { it.copy(bandLevels = it.bandLevels ?: emptyList()) } ?: emptyList()

    fun saveCustomPreset(preset: CustomPreset) {
        val list = customPresets().filter { it.name != preset.name } + preset
        kv.putString(KEY_PRESETS, gson.toJson(list))
    }

    fun deleteCustomPreset(name: String) {
        kv.putString(KEY_PRESETS, gson.toJson(customPresets().filter { it.name != name }))
    }

    private fun <T> parse(json: String, cls: Class<T>): T? = runCatching { gson.fromJson(json, cls) }.getOrNull()

    // Gson can leave non-null Kotlin fields null when JSON is missing them.
    @Suppress("SENSELESS_COMPARISON")
    private fun DeviceProfile.sanitized(): DeviceProfile = DeviceProfile(
        key = key,
        name = name ?: key,
        settings = settings?.let { if (it.bandLevels == null) it.copy(bandLevels = emptyList()) else it }
            ?: EqSettings(),
    )

    companion object {
        const val KEY_ENABLED = "builtin_eq_enabled"
        const val KEY_PER_DEVICE = "builtin_eq_per_device"
        const val KEY_DEFAULT = "builtin_eq_default"
        const val KEY_PROFILES = "builtin_eq_profiles"
        const val KEY_PRESETS = "builtin_eq_presets"
    }
}
