package code.name.monkey.retromusic.equalizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapKeyValue : KeyValue {
    val map = HashMap<String, Any?>()
    override fun getString(key: String) = map[key] as String?
    override fun putString(key: String, value: String?) { map[key] = value }
    override fun getBoolean(key: String, def: Boolean) = map[key] as Boolean? ?: def
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
}

class DeviceKeysTest {
    @Test fun speakerAndWired() {
        assertEquals(DeviceKeys.KEY_SPEAKER, DeviceKeys.describe(DeviceKeys.TYPE_BUILTIN_SPEAKER, null, "Pixel").key)
        assertEquals(DeviceKeys.KEY_WIRED, DeviceKeys.describe(DeviceKeys.TYPE_USB_HEADSET, null, "DAC").key)
        assertEquals(DeviceKeys.KEY_WIRED, DeviceKeys.describe(DeviceKeys.TYPE_WIRED_HEADPHONES, null, null).key)
    }

    @Test fun bluetoothByAddressWhenAllowed() {
        val d = DeviceKeys.describe(DeviceKeys.TYPE_BLUETOOTH_A2DP, "aa:bb:cc:dd:ee:ff", "Buds")
        assertEquals("bt:addr:AA:BB:CC:DD:EE:FF", d.key)
        assertEquals("Buds", d.label)
    }

    @Test fun bluetoothFallsBackToNameThenShared() {
        assertEquals("bt:name:Buds", DeviceKeys.describe(DeviceKeys.TYPE_BLUETOOTH_A2DP, null, "Buds").key)
        assertEquals("bt:name:Buds", DeviceKeys.describe(DeviceKeys.TYPE_BLE_HEADSET, "02:00:00:00:00:00", "Buds").key)
        assertEquals(DeviceKeys.KEY_BLUETOOTH_SHARED, DeviceKeys.describe(DeviceKeys.TYPE_BLUETOOTH_A2DP, "", " ").key)
    }

    @Test fun pickActivePrefersBluetoothThenWired() {
        val sp = DeviceKeys.describe(DeviceKeys.TYPE_BUILTIN_SPEAKER, null, null)
        val w = DeviceKeys.describe(DeviceKeys.TYPE_WIRED_HEADSET, null, null)
        val bt = DeviceKeys.describe(DeviceKeys.TYPE_BLUETOOTH_A2DP, null, "Car")
        assertEquals(bt, DeviceKeys.pickActive(listOf(sp, w, bt)))
        assertEquals(w, DeviceKeys.pickActive(listOf(sp, w)))
        assertEquals(sp, DeviceKeys.pickActive(listOf(sp)))
        assertNull(DeviceKeys.pickActive(emptyList()))
    }
}

class EqStoreTest {
    private val car = OutputDevice(OutputKind.BLUETOOTH, "bt:name:Car", "Car")
    private val buds = OutputDevice(OutputKind.BLUETOOTH, "bt:name:Buds", "Buds")
    private val def = EqSettings(listOf(100, 0, -100), "Rock", bassBoost = 300)

    @Test fun perDeviceOffUsesDefault() {
        val s = EqStore(MapKeyValue())
        s.defaultSettings = def
        s.saveSettingsFor(car, def.copy(bassBoost = 900))
        assertEquals(900, s.settingsFor(car).bassBoost)
        assertEquals(900, s.defaultSettings.bassBoost)
        assertTrue(s.profiles().isEmpty())
    }

    @Test fun newDeviceStartsFromDefaultAndIsSaved() {
        val s = EqStore(MapKeyValue()).apply { perDevice = true; defaultSettings = def }
        assertEquals(def, s.settingsFor(car))
        assertEquals("Car", s.profile(car.key)?.name)
        s.saveSettingsFor(car, def.copy(loudnessGain = 600))
        assertEquals(600, s.settingsFor(car).loudnessGain)
        assertEquals(0, s.settingsFor(buds).loudnessGain)
        assertEquals(0, s.defaultSettings.loudnessGain)
    }

    @Test fun persistsAcrossInstances() {
        val kv = MapKeyValue()
        EqStore(kv).apply {
            enabled = true; perDevice = true
            saveSettingsFor(car, def)
            saveCustomPreset(CustomPreset("Mine", listOf(1, 2, 3)))
        }
        val s = EqStore(kv)
        assertTrue(s.enabled)
        assertEquals(def, s.settingsFor(car))
        assertEquals(listOf(1, 2, 3), s.customPresets().single().bandLevels)
    }

    @Test fun renameDeleteCopy() {
        val s = EqStore(MapKeyValue()).apply { perDevice = true }
        s.saveSettingsFor(car, def)
        s.settingsFor(buds)
        s.rename(car.key, "  My car ")
        assertEquals("My car", s.profile(car.key)?.name)
        s.rename(car.key, "   ")
        assertEquals("My car", s.profile(car.key)?.name)
        s.copyFrom(car.key, buds.key)
        assertEquals(def, s.profile(buds.key)?.settings)
        s.copyFrom(null, buds.key)
        assertEquals(EqSettings(), s.profile(buds.key)?.settings)
        s.delete(car.key)
        assertNull(s.profile(car.key))
        assertEquals(1, s.profiles().size)
    }

    @Test fun corruptDataIsIgnored() {
        val kv = MapKeyValue()
        kv.putString(EqStore.KEY_PROFILES, "not json")
        kv.putString(EqStore.KEY_DEFAULT, "{")
        val s = EqStore(kv)
        assertTrue(s.profiles().isEmpty())
        assertEquals(EqSettings(), s.defaultSettings)
        assertFalse(s.enabled)
    }

    @Test fun levelsResizeWhenBandCountDiffers() {
        assertEquals(listOf(0, 0, 0, 0, 0), def.levelsFor(5))
        assertEquals(listOf(100, 0, -100), def.levelsFor(3))
    }
}
