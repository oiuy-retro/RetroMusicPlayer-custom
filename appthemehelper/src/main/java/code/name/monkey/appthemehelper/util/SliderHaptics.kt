/*
 * Slider haptics shared by every slider in Retro.
 *
 * The step tick is adapted from HeliBoard-custom (oiuy-retro/HeliBoard-custom,
 * app/src/main/java/helium314/keyboard/settings/dialogs/SliderDialog.kt, performSliderStepHaptic),
 * Copyright HeliBoard contributors, licensed under the Apache License 2.0.
 * See THIRD_PARTY_NOTICES.md.
 */
package code.name.monkey.appthemehelper.util

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.google.android.material.slider.Slider
import java.util.WeakHashMap
import kotlin.math.floor

object SliderHaptics {
    /** Same values as HeliBoard: at most one tick per 12 ms, a 5 ms one-shot at amplitude 65. */
    private const val STEP_HAPTIC_MIN_INTERVAL_MS = 12L
    private const val STEP_VIBRATION_MS = 5L
    private const val STEP_VIBRATION_AMPLITUDE = 65

    private var lastStepHapticUptimeMs = 0L
    private val lastBucket = WeakHashMap<View, Long>()

    @Suppress("DEPRECATION")
    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** Light tick for one +1 / -1 step while dragging (HeliBoard's performSliderStepHaptic). */
    @Suppress("DEPRECATION")
    fun step(view: View) {
        val now = SystemClock.uptimeMillis()
        if (now - lastStepHapticUptimeMs < STEP_HAPTIC_MIN_INTERVAL_MS) return
        lastStepHapticUptimeMs = now
        val feedbackConstant = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 -> HapticFeedbackConstants.TEXT_HANDLE_MOVE
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(feedbackConstant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        val vibrator = runCatching { vibrator(view.context) }.getOrNull()
        if (vibrator?.hasVibrator() == true) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(STEP_VIBRATION_MS, STEP_VIBRATION_AMPLITUDE))
                } else {
                    vibrator.vibrate(STEP_VIBRATION_MS)
                }
            }
        }
    }

    /**
     * Call from a value-change listener. Ticks when a user drag moves the value into a new
     * step of size [stepSize] (e.g. 1 for percent sliders, 5000 for a seek bar in ms).
     */
    fun onValue(view: View, value: Float, fromUser: Boolean, stepSize: Float = 1f) {
        val bucket = floor(value / (if (stepSize > 0f) stepSize else 1f)).toLong()
        val previous = lastBucket.put(view, bucket)
        if (fromUser && previous != null && previous != bucket) step(view)
    }

    /** Adds haptics to a Material [Slider]. [stepSize] 0 = the slider's own step (or 1). */
    fun attach(slider: Slider, stepSize: Float = 0f) {
        lastBucket[slider] = Long.MIN_VALUE
        slider.addOnChangeListener { s, value, fromUser ->
            val step = if (stepSize > 0f) stepSize else s.stepSize.takeIf { it > 0f } ?: 1f
            onValue(s, value, fromUser, step)
        }
    }

    /** Seek bar steps: one tick per 5 seconds of song position. */
    const val SEEK_STEP_MS = 5_000f
}
