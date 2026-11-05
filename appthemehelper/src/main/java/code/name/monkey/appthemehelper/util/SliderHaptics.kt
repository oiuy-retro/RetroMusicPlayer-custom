/*
 * Slider haptics shared by every slider in Retro.
 *
 * The step tick is adapted from HeliBoard-custom (oiuy-retro/HeliBoard-custom,
 * app/src/main/java/helium314/keyboard/settings/dialogs/SliderDialog.kt, performSliderStepHaptic),
 * Copyright HeliBoard contributors, licensed under the Apache License 2.0.
 * See THIRD_PARTY_NOTICES.md.
 *
 * Rule: tick whenever the value the user sees changes (the formatted number or label),
 * not on every raw progress unit. Callers pass either the display label ([onLabel]) or a
 * value plus the display step size ([onValue]). A 12 ms rate limit keeps very fast drags
 * from buzzing continuously; slow drags still tick on every displayed change.
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
    /** Last display key per slider view (label string or numeric bucket). */
    private val lastDisplay = WeakHashMap<View, Any?>()

    @Suppress("DEPRECATION")
    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** Light tick for one display-step while dragging (HeliBoard's performSliderStepHaptic). */
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
     * Bucket a raw slider [value] at display precision [stepSize] (same units as [value]).
     * Pure; safe to unit-test. [stepSize] <= 0 is treated as 1.
     */
    fun displayBucket(value: Float, stepSize: Float): Long {
        val step = if (stepSize > 0f) stepSize else 1f
        return floor(value / step).toLong()
    }

    /**
     * Whether a user drag should tick when the display key moves from [previous] to [current].
     * Pure; safe to unit-test. First observation (previous == null) never ticks.
     */
    fun shouldTick(previous: Any?, current: Any?, fromUser: Boolean): Boolean =
        fromUser && previous != null && previous != current

    /**
     * Core entry: tick when the displayed key for this slider changes during a user drag.
     * [displayKey] should be whatever the user sees (formatted label, or a bucket at display
     * precision). Pass a non-user update first so the baseline is recorded without a tick.
     */
    fun onDisplay(view: View, displayKey: Any?, fromUser: Boolean) {
        val previous = lastDisplay.put(view, displayKey)
        if (shouldTick(previous, displayKey, fromUser)) step(view)
    }

    /** Convenience: tick when the formatted label string changes. */
    fun onLabel(view: View, label: CharSequence?, fromUser: Boolean) {
        onDisplay(view, label?.toString(), fromUser)
    }

    /**
     * Convenience: tick when [value] crosses a new step of size [stepSize]
     * (e.g. 1 for integer percent, 10 for 0.1 dB in millibels, 1000 for one second in ms).
     */
    fun onValue(view: View, value: Float, fromUser: Boolean, stepSize: Float = 1f) {
        onDisplay(view, displayBucket(value, stepSize), fromUser)
    }

    /** Adds haptics to a Material [Slider]. [stepSize] 0 = the slider's own step (or 1). */
    fun attach(slider: Slider, stepSize: Float = 0f) {
        lastDisplay[slider] = null
        slider.addOnChangeListener { s, value, fromUser ->
            val step = if (stepSize > 0f) stepSize else s.stepSize.takeIf { it > 0f } ?: 1f
            onValue(s, value, fromUser, step)
        }
    }

    /** Clear the remembered display key (e.g. when a view is rebound). */
    fun reset(view: View) {
        lastDisplay.remove(view)
    }

    /** Player seek bars: time text is MM:SS, so one tick per displayed second. */
    const val SEEK_STEP_MS = 1_000f
}
