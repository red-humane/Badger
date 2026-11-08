package com.humnspace.integral.accessibility

import android.animation.TimeInterpolator
import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/**
 * Shared Badger Motion Engine.
 *
 * Implements the core motion design language:
 * 1. Morph, don't expand — surfaces transform continuously from the physical button anchor.
 * 2. Preserve spatial continuity — transform existing structures rather than dismiss/recreate.
 * 3. Light as interaction feedback — amber strictly for active, focused, or confirmed states.
 * 4. Motion hierarchy — shell moves first, content follows, secondary chrome appears last.
 * 5. Magnetic dismissal — acceleration into the exact physical button origin.
 */
object BadgerMotionEngine {

    enum class State {
        IDLE,
        PRESSING,
        RECOGNIZING,
        EXPANDING,
        ACTIVE,
        TRANSFORMING,
        COLLAPSING
    }

    object Timings {
        // Micro (80–140ms): Press, selection, feedback
        const val PRESSURE_POINT = 45L
        const val LINE_FORMATION = 110L
        const val GESTURE_CONFIRM = 90L
        const val RELEASE_CONTRACTION = 160L
        const val SELECTION_DETENT = 120L

        // Standard (180–260ms): Capsule, chassis, cards, icons
        const val CHASSIS_STRETCH = 100L
        const val CAPSULE_FORMATION = 220L
        const val ICON_MORPH = 180L
        const val LABEL_TRANSITION = 130L
        const val CHASSIS_RETRACTION = 190L
        const val CARD_STAGGER_MS = 35L

        // Spatial (280–400ms): Screen analysis, browser transformations
        const val OPTICAL_SCAN_BEAM = 320L
        const val SPATIAL_MORPH = 300L
        const val BROWSER_SURFACE_EXPAND = 360L

        // Waveform & Audio dynamics
        const val AUDIO_ATTACK_MS = 70L
        const val AUDIO_RELEASE_MS = 220L
        const val PAUSE_TRANSITION_MS = 180L
        const val RESUME_TRANSITION_MS = 140L
    }

    object Physics {
        /** Spring-like overshoot for capsule emergence and active reveals */
        val SpringOvershoot: TimeInterpolator = OvershootInterpolator(1.22f)

        /** Clean decelerate for line formation and optical sweeps */
        val SmoothDecelerate: TimeInterpolator = DecelerateInterpolator(1.5f)

        /** Accelerated curve for magnetic retraction into the hardware key */
        val MagneticRetract: TimeInterpolator = AccelerateInterpolator(1.55f)

        /** Apple-style fluid bezier curve */
        val FluidCurve: TimeInterpolator = PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f)
    }

    object Haptics {
        fun performPressurePoint(context: Context) {
            val vibrator = getVibrator(context) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(12L)
            }
        }

        fun performBoundaryDetent(context: Context) {
            val vibrator = getVibrator(context) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(20L)
            }
        }

        fun performGestureConfirmed(context: Context) {
            val vibrator = getVibrator(context) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Dual crisp tap confirmation
                val timings = longArrayOf(0, 16, 32, 24)
                val amplitudes = intArrayOf(0, 140, 0, 255)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(35L)
            }
        }

        fun performViewClick(view: View) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        private fun getVibrator(context: Context): Vibrator? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }
    }

    object OriginController {
        /**
         * Resolves the hardware button coordinate on the active screen frame.
         */
        fun getAnchor(context: Context, width: Float, height: Float): PointF {
            val loc = DeviceOrientationHelper.getButtonScreenLocation(context, width, height)
            return PointF(loc.x, loc.y)
        }

        /**
         * Computes a magnetic pull vector towards the button edge for dismissal.
         * The closer an element is to the origin, the higher the acceleration.
         */
        fun computeMagneticPull(progress: Float): Float {
            // progress: 1.0 (settled) -> 0.0 (fully inside chassis)
            // Quadratic ease-in: pulls gently first, then snaps with speed over the last 20%
            return (progress * progress).coerceIn(0f, 1f)
        }
    }
}

