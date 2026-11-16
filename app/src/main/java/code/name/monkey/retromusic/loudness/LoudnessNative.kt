package code.name.monkey.retromusic.loudness

/**
 * JNI bridge to MIT-licensed libebur128 (third_party/libebur128).
 * Measures EBU R128 integrated loudness and true peak.
 */
object LoudnessNative {
    @Volatile private var loaded = false
    @Volatile private var loadError: String? = null

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            return try {
                System.loadLibrary("retroloudness")
                loaded = true
                true
            } catch (t: Throwable) {
                loadError = t.message
                false
            }
        }
    }

    fun loadErrorMessage(): String? = loadError

    external fun nativeInit(channels: Int, sampleRate: Int): Long
    external fun nativeAddFrames(handle: Long, samples: FloatArray, frames: Int)
    external fun nativeIntegratedLoudness(handle: Long): Double
    external fun nativeTruePeak(handle: Long): Double
    external fun nativeDestroy(handle: Long)
}
