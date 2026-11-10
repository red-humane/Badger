package com.humnspace.integral.accessibility

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.PathInterpolator

/**
 * Essential Key — Minimal Blended Rounded-Rectangle Indicator
 *
 * Micro-subtle, smooth, and tactile bezel indicator:
 * - Reduced peek out to a micro-lip: ~6.5dp (ultra-subtle, barely intrusive)
 * - Distinct, elegant animation handling for:
 *   1. Single Press: 1 clean, calm pulse (180ms reveal, 220ms retract).
 *   2. Double Press: 2 distinct, rhythmic pulses (Pulse 1: ~5.2dp, Pulse 2: ~7.0dp).
 *   3. Triple Press: 3 sequential, rhythmic pulses with progressive variation.
 *   4. Long Press / Holding: Glides out to ~7.5dp, remains steadily held while key is down,
 *      and retracts smoothly upon release.
 * - Symmetrical pill-shaped inner corners (4.5dp radius)
 * - Pure AMOLED black (#000000), hardware-accelerated, zero borders/gradients/blur.
 */
class ButtonPressIndicatorOverlayView(
    context: Context,
    private val onDismissRequest: () -> Unit
) : View(context) {

    enum class PressType {
        DOWN,
        UP,
        SINGLE,
        DOUBLE,
        TRIPLE,
        LONG_HOLD,
        LONG_RELEASE
    }

    private val density = resources.displayMetrics.density

    // Micro-subtle proportions ("reduce that peek out even a little bit")
    private val rectLengthDp = 48f     // Compact length along bezel
    private val cornerRadiusDp = 4.5f  // Smooth rounded inner corners
    private val maxProtrusionDp = 6.5f // Ultra-subtle micro-peek (~6.5dp)

    // Animation & State
    private var revealProgress = 0f
    private var currentIntensity = 1.0f
    private var isKeyDown = false
    private var isHolding = false

    private val amoledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val shapePath = Path()
    private val shapeRect = RectF()
    private val cornerRadii = FloatArray(8)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { dismissImmediate() }

    private var revealAnimator: ValueAnimator? = null
    private var pulseSequenceAnimator: ValueAnimator? = null

    // Smooth, decelerating cubic Bézier easing curves
    private val smoothRevealInterpolator = PathInterpolator(0.18f, 1f, 0.32f, 1f)
    private val smoothRetractInterpolator = PathInterpolator(0.36f, 0f, 0.24f, 1f)

    init {
        setWillNotDraw(false)
    }

    fun triggerPress(type: PressType, tapIndex: Int = 0) {
        mainHandler.removeCallbacks(autoDismissRunnable)

        when (type) {
            PressType.DOWN -> onKeyDown(tapIndex)
            PressType.UP -> onKeyUp()
            PressType.SINGLE -> playSinglePress()
            PressType.DOUBLE -> playDoublePress()
            PressType.TRIPLE -> playTriplePress()
            PressType.LONG_HOLD -> playLongHold()
            PressType.LONG_RELEASE -> playLongRelease()
        }
    }

    private fun cancelActiveAnimators() {
        revealAnimator?.cancel()
        revealAnimator = null
        pulseSequenceAnimator?.cancel()
        pulseSequenceAnimator = null
    }

    /**
     * Touch DOWN: Key pressed down.
     * Glides out smoothly from the bezel over 180ms.
     */
    private fun onKeyDown(tapIndex: Int) {
        cancelActiveAnimators()
        isKeyDown = true
        mainHandler.removeCallbacks(autoDismissRunnable)
        mainHandler.postDelayed(autoDismissRunnable, 3000L) // Safety watchdog

        currentIntensity = when (tapIndex) {
            0 -> 0.90f  // First tap (~5.8dp)
            1 -> 1.05f  // Second tap (~6.8dp)
            else -> 1.15f // Third tap (~7.5dp)
        }

        BadgerMotionEngine.Haptics.performPressurePoint(context)

        val start = revealProgress
        revealAnimator = ValueAnimator.ofFloat(start, 1f).apply {
            duration = (180L * (1f - start)).toLong().coerceIn(70L, 180L)
            interpolator = smoothRevealInterpolator
            addUpdateListener {
                revealProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    /**
     * Touch UP: Key released.
     * Retracts smoothly back into the bezel over 220ms.
     */
    private fun onKeyUp() {
        if (isHolding) return // Maintained if still holding
        isKeyDown = false

        val start = revealProgress
        if (start <= 0.01f) {
            dismissImmediate()
            return
        }

        revealAnimator?.cancel()
        revealAnimator = ValueAnimator.ofFloat(start, 0f).apply {
            duration = (220L * start).toLong().coerceIn(70L, 220L)
            interpolator = smoothRetractInterpolator
            addUpdateListener {
                revealProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    dismissImmediate()
                }
            })
            start()
        }
    }

    /**
     * Single Press:
     * Glides out smoothly (180ms) and retracts cleanly (220ms).
     */
    fun playSinglePress() {
        cancelActiveAnimators()
        isHolding = false
        mainHandler.removeCallbacks(autoDismissRunnable)
        mainHandler.postDelayed(autoDismissRunnable, 1200L)

        currentIntensity = 0.95f
        revealProgress = 0f

        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = smoothRevealInterpolator
            addUpdateListener {
                revealProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    revealAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                        duration = 220L
                        interpolator = smoothRetractInterpolator
                        addUpdateListener {
                            revealProgress = it.animatedValue as Float
                            postInvalidateOnAnimation()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                dismissImmediate()
                            }
                        })
                        start()
                    }
                }
            })
            start()
        }
    }

    /**
     * Double Press:
     * Two distinct, rhythmic pulses:
     * - Pulse 1: emerges to ~5.2dp (160ms), dips to ~1.5dp (120ms)
     * - Pulse 2: emerges to ~7.0dp (180ms), retracts to 0 (240ms)
     */
    fun playDoublePress() {
        cancelActiveAnimators()
        isHolding = false
        mainHandler.removeCallbacks(autoDismissRunnable)
        mainHandler.postDelayed(autoDismissRunnable, 1600L)

        currentIntensity = 0.80f
        revealProgress = 0f

        // Pulse 1 Expand (160ms)
        pulseSequenceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160L
            interpolator = smoothRevealInterpolator
            addUpdateListener {
                revealProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Pulse 1 Retract dip (120ms)
                    pulseSequenceAnimator = ValueAnimator.ofFloat(1f, 0.22f).apply {
                        duration = 120L
                        interpolator = smoothRetractInterpolator
                        addUpdateListener {
                            revealProgress = it.animatedValue as Float
                            postInvalidateOnAnimation()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                // Pulse 2 Expand slightly more (180ms)
                                currentIntensity = 1.08f
                                pulseSequenceAnimator = ValueAnimator.ofFloat(0.22f, 1f).apply {
                                    duration = 180L
                                    interpolator = smoothRevealInterpolator
                                    addUpdateListener {
                                        revealProgress = it.animatedValue as Float
                                        postInvalidateOnAnimation()
                                    }
                                    addListener(object : AnimatorListenerAdapter() {
                                        override fun onAnimationEnd(animation: Animator) {
                                            // Pulse 2 Final Retract (240ms)
                                            pulseSequenceAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                                                duration = 240L
                                                interpolator = smoothRetractInterpolator
                                                addUpdateListener {
                                                    revealProgress = it.animatedValue as Float
                                                    postInvalidateOnAnimation()
                                                }
                                                addListener(object : AnimatorListenerAdapter() {
                                                    override fun onAnimationEnd(animation: Animator) {
                                                        dismissImmediate()
                                                    }
                                                })
                                                start()
                                            }
                                        }
                                    })
                                    start()
                                }
                            }
                        })
                        start()
                    }
                }
            })
            start()
        }
    }

    /**
     * Triple Press:
     * Three distinct, sequential pulses with progressive variation:
     * - Pulse 1: emerges to ~4.8dp (140ms), dips to ~1.4dp (110ms)
     * - Pulse 2: emerges to ~6.0dp (150ms), dips to ~1.4dp (110ms)
     * - Pulse 3: emerges to ~7.5dp (180ms), retracts to 0 (240ms)
     */
    fun playTriplePress() {
        cancelActiveAnimators()
        isHolding = false
        mainHandler.removeCallbacks(autoDismissRunnable)
        mainHandler.postDelayed(autoDismissRunnable, 2200L)

        currentIntensity = 0.74f
        revealProgress = 0f

        fun runPulse(index: Int) {
            val expandDuration = if (index == 2) 180L else 145L
            val retractDuration = if (index == 2) 240L else 110L
            val targetRetract = if (index == 2) 0f else 0.20f

            pulseSequenceAnimator = ValueAnimator.ofFloat(revealProgress, 1f).apply {
                duration = expandDuration
                interpolator = smoothRevealInterpolator
                addUpdateListener {
                    revealProgress = it.animatedValue as Float
                    postInvalidateOnAnimation()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        pulseSequenceAnimator = ValueAnimator.ofFloat(1f, targetRetract).apply {
                            duration = retractDuration
                            interpolator = smoothRetractInterpolator
                            addUpdateListener {
                                revealProgress = it.animatedValue as Float
                                postInvalidateOnAnimation()
                            }
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    if (index == 0) {
                                        currentIntensity = 0.92f
                                        runPulse(1)
                                    } else if (index == 1) {
                                        currentIntensity = 1.15f
                                        runPulse(2)
                                    } else {
                                        dismissImmediate()
                                    }
                                }
                            })
                            start()
                        }
                    }
                })
                start()
            }
        }

        runPulse(0)
    }

    /**
     * Long Press / Holding:
     * Glides out over 240ms to ~7.5dp and remains steadily held against the bezel.
     */
    fun playLongHold() {
        cancelActiveAnimators()
        isKeyDown = true
        isHolding = true
        currentIntensity = 1.15f
        mainHandler.removeCallbacks(autoDismissRunnable)
        mainHandler.postDelayed(autoDismissRunnable, 6000L) // Safety watchdog for hold

        val start = revealProgress
        revealAnimator = ValueAnimator.ofFloat(start, 1f).apply {
            duration = (240L * (1f - start)).toLong().coerceIn(80L, 240L)
            interpolator = smoothRevealInterpolator
            addUpdateListener {
                revealProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    /**
     * Long Release:
     * Retracts smoothly from held state back into bezel over 220ms.
     */
    fun playLongRelease() {
        isKeyDown = false
        isHolding = false

        val start = revealProgress
        if (start <= 0.01f) {
            dismissImmediate()
            return
        }

        revealAnimator?.cancel()
        revealAnimator = ValueAnimator.ofFloat(start, 0f).apply {
            duration = (220L * start).toLong().coerceIn(80L, 220L)
            interpolator = smoothRetractInterpolator
            addUpdateListener {
                revealProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    dismissImmediate()
                }
            })
            start()
        }
    }

    fun dismissImmediate() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        cancelActiveAnimators()
        revealProgress = 0f
        isKeyDown = false
        isHolding = false
        postInvalidateOnAnimation()
        onDismissRequest()
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        cancelActiveAnimators()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = revealProgress
        if (p <= 0.002f || width <= 0 || height <= 0) return

        val loc = DeviceOrientationHelper.getButtonScreenLocation(context, width.toFloat(), height.toFloat())
        val cx = loc.x
        val cy = loc.y
        val w = width.toFloat()
        val h = height.toFloat()

        // Ultra-subtle micro-protrusion: ~6.5dp peak
        val maxDepthPx = maxProtrusionDp * density * currentIntensity
        val curDepthPx = maxDepthPx * p

        val lengthPx = rectLengthDp * density
        // Inner corner radius smoothly adapts up to cornerRadiusDp
        val radiusPx = (cornerRadiusDp * density).coerceAtMost(lengthPx / 2f).coerceAtMost(curDepthPx * 1.5f)

        // Offscreen margin to ensure zero edge gap along bezel
        val offscreenBleed = 20f * density

        shapePath.reset()

        when (loc.edge) {
            ScreenEdge.RIGHT -> {
                val left = w - curDepthPx
                val right = w + offscreenBleed
                val top = cy - lengthPx / 2f
                val bottom = cy + lengthPx / 2f
                shapeRect.set(left, top, right, bottom)

                // Only inner corners (Top-Left, Bottom-Left) are rounded
                cornerRadii[0] = radiusPx; cornerRadii[1] = radiusPx // Top-Left
                cornerRadii[2] = 0f;       cornerRadii[3] = 0f       // Top-Right (offscreen)
                cornerRadii[4] = 0f;       cornerRadii[5] = 0f       // Bottom-Right (offscreen)
                cornerRadii[6] = radiusPx; cornerRadii[7] = radiusPx // Bottom-Left
            }
            ScreenEdge.LEFT -> {
                val left = -offscreenBleed
                val right = curDepthPx
                val top = cy - lengthPx / 2f
                val bottom = cy + lengthPx / 2f
                shapeRect.set(left, top, right, bottom)

                // Only inner corners (Top-Right, Bottom-Right) are rounded
                cornerRadii[0] = 0f;       cornerRadii[1] = 0f       // Top-Left (offscreen)
                cornerRadii[2] = radiusPx; cornerRadii[3] = radiusPx // Top-Right
                cornerRadii[4] = radiusPx; cornerRadii[5] = radiusPx // Bottom-Right
                cornerRadii[6] = 0f;       cornerRadii[7] = 0f       // Bottom-Left (offscreen)
            }
            ScreenEdge.TOP -> {
                val left = cx - lengthPx / 2f
                val right = cx + lengthPx / 2f
                val top = -offscreenBleed
                val bottom = curDepthPx
                shapeRect.set(left, top, right, bottom)

                // Only inner corners (Bottom-Left, Bottom-Right) are rounded
                cornerRadii[0] = 0f;       cornerRadii[1] = 0f       // Top-Left (offscreen)
                cornerRadii[2] = 0f;       cornerRadii[3] = 0f       // Top-Right (offscreen)
                cornerRadii[4] = radiusPx; cornerRadii[5] = radiusPx // Bottom-Right
                cornerRadii[6] = radiusPx; cornerRadii[7] = radiusPx // Bottom-Left
            }
            ScreenEdge.BOTTOM -> {
                val left = cx - lengthPx / 2f
                val right = cx + lengthPx / 2f
                val top = h - curDepthPx
                val bottom = h + offscreenBleed
                shapeRect.set(left, top, right, bottom)

                // Only inner corners (Top-Left, Top-Right) are rounded
                cornerRadii[0] = radiusPx; cornerRadii[1] = radiusPx // Top-Left
                cornerRadii[2] = radiusPx; cornerRadii[3] = radiusPx // Top-Right
                cornerRadii[4] = 0f;       cornerRadii[5] = 0f       // Bottom-Right (offscreen)
                cornerRadii[6] = 0f;       cornerRadii[7] = 0f       // Bottom-Left (offscreen)
            }
        }

        shapePath.addRoundRect(shapeRect, cornerRadii, Path.Direction.CW)

        // Draw pure AMOLED black minimal blended rounded rectangle
        canvas.drawPath(shapePath, amoledPaint)
    }
}
