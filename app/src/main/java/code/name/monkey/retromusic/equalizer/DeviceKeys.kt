/*
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.equalizer

/**
 * Pure logic that turns an output device description into a profile key and picks which of
 * several connected outputs is the one being played to. No Android classes, so it is unit tested.
 */
object DeviceKeys {
    const val KEY_SPEAKER = "speaker"
    const val KEY_WIRED = "wired"
    const val KEY_BLUETOOTH_SHARED = "bt:shared"
    const val KEY_OTHER = "other"

    // Mirrors android.media.AudioDeviceInfo.TYPE_* values.
    const val TYPE_BUILTIN_SPEAKER = 2
    const val TYPE_WIRED_HEADSET = 3
    const val TYPE_WIRED_HEADPHONES = 4
    const val TYPE_BLUETOOTH_SCO = 7
    const val TYPE_BLUETOOTH_A2DP = 8
    const val TYPE_USB_DEVICE = 11
    const val TYPE_USB_ACCESSORY = 12
    const val TYPE_USB_HEADSET = 22
    const val TYPE_HEARING_AID = 23
    const val TYPE_BLE_HEADSET = 26
    const val TYPE_BLE_SPEAKER = 27
    const val TYPE_BLE_BROADCAST = 30
    const val TYPE_LINE_ANALOG = 5
    const val TYPE_LINE_DIGITAL = 6

    fun kindOf(type: Int): OutputKind = when (type) {
        TYPE_BUILTIN_SPEAKER -> OutputKind.SPEAKER
        TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES, TYPE_USB_DEVICE, TYPE_USB_ACCESSORY,
        TYPE_USB_HEADSET, TYPE_LINE_ANALOG, TYPE_LINE_DIGITAL -> OutputKind.WIRED
        TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO, TYPE_HEARING_AID, TYPE_BLE_HEADSET,
        TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST -> OutputKind.BLUETOOTH
        else -> OutputKind.OTHER
    }

    /**
     * @param address Bluetooth MAC address, or null/blank when it can't be read
     *   (no Nearby devices permission, or Android returned an anonymized address).
     * @param productName name Android reports for the device (may be the phone model for the
     *   built-in speaker; ignored for non-Bluetooth outputs).
     */
    fun describe(type: Int, address: String?, productName: String?): OutputDevice {
        return when (val kind = kindOf(type)) {
            OutputKind.SPEAKER -> OutputDevice(kind, KEY_SPEAKER, "Phone speaker")
            OutputKind.WIRED -> OutputDevice(kind, KEY_WIRED, "Wired / USB headphones")
            OutputKind.OTHER -> OutputDevice(kind, KEY_OTHER, "Other output")
            OutputKind.BLUETOOTH -> {
                val name = productName?.trim().orEmpty()
                val addr = address?.trim()?.uppercase().orEmpty()
                when {
                    isUsableAddress(addr) ->
                        OutputDevice(kind, "bt:addr:$addr", name.ifEmpty { "Bluetooth $addr" })
                    name.isNotEmpty() -> OutputDevice(kind, "bt:name:$name", name)
                    else -> OutputDevice(kind, KEY_BLUETOOTH_SHARED, "Bluetooth")
                }
            }
        }
    }

    /** Android returns 02:00:00:00:00:00 or blanks when the real address is hidden. */
    fun isUsableAddress(addr: String): Boolean {
        if (addr.isEmpty()) return false
        if (!Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$").matches(addr)) return false
        return addr != "02:00:00:00:00:00" && addr != "00:00:00:00:00:00"
    }

    /** Which output is in use when several are listed: Bluetooth, then wired/USB, then speaker. */
    fun pickActive(devices: List<OutputDevice>): OutputDevice? {
        fun rank(k: OutputKind) = when (k) {
            OutputKind.BLUETOOTH -> 0
            OutputKind.WIRED -> 1
            OutputKind.SPEAKER -> 2
            OutputKind.OTHER -> 3
        }
        return devices.minByOrNull { rank(it.kind) }
    }
}
