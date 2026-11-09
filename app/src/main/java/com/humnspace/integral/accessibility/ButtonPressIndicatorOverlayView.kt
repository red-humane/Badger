package com.humnspace.integral.accessibility

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Essential Key — Organic Black Morph Press Animation
 *
 * Recreates the distinctive black, curved, mountain-like deformation when the
 * physical Essential Key is pressed against the screen bezel.
 *
 * Visual characteristics:
 * - Pure AMOLED black (#000000)
 * - Flowing, asymmetric mountain silhouette with continuous cubic Bézier curves
 * - Tangents enter and leave flush with the screen bezel (zero visual seams)
 * - Multi-phase organic morphing on emergence and smooth elastic settling
 * - Extremely subtle, calm organic contour breathing during held state
 * - Smooth cubic Bézier collapse upon release
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

    // Proportions calibrated for Nothing Phone & Android display ratios
    private val maxDepthDp = 28.5f      // Peak protrusion into display
    private val hTopDp = 70f            // Asymmetric upper slope span
    private val hBotDp = 96f            // Asymmetric lower slope span

    private val maxDepthPx = maxDepthDp * density
    private val hTopPx = hTopDp * density
    private val hBotPx = hBotDp * density

    // Morph state
    private var morphProgress = 0f
    private var heldPhase = 0f
    private var isKeyDown = false

    private val amoledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val mountainPath = Path()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { dismissImmediate() }

    private var morphAnimator: ValueAnimator? = null
    private var heldAnimator: ValueAnimator? = null

    // Smooth cubic Bézier easing curves
    private val emergenceInterpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val releaseInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    init {
        setWillNotDraw(false)
    }

    fun triggerPress(type: PressType) {
        mainHandler.removeCallbacks(autoDismissRunnable)

        when (type) {
            PressType.DOWN -> {
                isKeyDown = true
                animateIn()
            }
            PressType.UP, PressType.LONG_RELEASE, PressType.SINGLE, PressType.DOUBLE, PressType.TRIPLE -> {
                isKeyDown = false
                animateOut()
            }
            PressType.LONG_HOLD -> {
                isKeyDown = true
                animateHold()
            }
        }
    }

    /**
     * State B — Key Press (Touch Down):
     * Emerges from the bezel and morphs into the asymmetric mountain silhouette (~210ms).
     */
    private fun animateIn() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        mainHandler.postDelayed(autoDismissRunnable, 1500L) // Safety watchdog
        BadgerMotionEngine.Haptics.performPressurePoint(context)

        heldAnimator?.cancel()
        morphAnimator?.cancel()

        val start = morphProgress
        val anim = ValueAnimator.ofFloat(start, 1f).apply {
            duration = (210L * (1f - start)).toLong().coerceIn(80L, 210L)
            interpolator = emergenceInterpolator
            addUpdateListener {
                morphProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (isKeyDown && morphProgress >= 0.98f) {
                        startHeldBreathing()
                    }
                }
            })
        }
        morphAnimator = anim
        anim.start()
    }

    /**
     * State C — Active Press (Held):
     * Maintains the stable mountain silhouette with an extremely subtle living contour wave.
     */
    private fun startHeldBreathing() {
        heldAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = 3400L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                heldPhase = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
        }
        heldAnimator = anim
        anim.start()
    }

    private fun animateHold() {
        if (!isKeyDown) {
            isKeyDown = true
        }
        if (morphProgress < 0.98f) {
            animateIn()
        } else if (heldAnimator?.isRunning != true) {
            startHeldBreathing()
        }
    }

    /**
     * State D — Release:
     * Contours collapse smoothly and retract back into the bezel (190ms).
     */
    private fun animateOut() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        heldAnimator?.cancel()
        morphAnimator?.cancel()

        val start = morphProgress
        if (start <= 0.01f) {
            dismissImmediate()
            return
        }

        val anim = ValueAnimator.ofFloat(start, 0f).apply {
            duration = (190L * start).toLong().coerceIn(70L, 190L)
            interpolator = releaseInterpolator
            addUpdateListener {
                morphProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    dismissImmediate()
                }
            })
        }
        morphAnimator = anim
        anim.start()
    }

    fun dismissImmediate() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        heldAnimator?.cancel()
        morphAnimator?.cancel()
        morphProgress = 0f
        isKeyDown = false
        postInvalidateOnAnimation()
        onDismissRequest()
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        heldAnimator?.cancel()
        morphAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = morphProgress
        if (p <= 0.005f || width <= 0 || height <= 0) return

        val loc = DeviceOrientationHelper.getButtonScreenLocation(context, width.toFloat(), height.toFloat())
        val cx = loc.x
        val cy = loc.y
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Organic Morph Interpolations (Non-linear multi-point expansion)
        val pPeak = (p / 0.85f).coerceAtMost(1f)
        val pUpper = ((p - 0.12f) / 0.88f).coerceIn(0f, 1f)
        val pLower = ((p - 0.08f) / 0.92f).coerceIn(0f, 1f)

        // Vertical envelope expansion along the screen bezel
        val spanScale = 0.35f + 0.65f * Math.pow(p.toDouble(), 0.8).toFloat()
        val curHTop = hTopPx * spanScale
        val curHBot = hBotPx * spanScale

        // Peak depth with subtle held living breathing
        var curDepth = maxDepthPx * Math.pow(pPeak.toDouble(), 0.9).toFloat()
        if (p >= 0.95f && isKeyDown) {
            curDepth += 0.9f * density * sin(heldPhase.toDouble()).toFloat()
        }

        // Secondary feature morph weights
        val uUpper = 12f * density * Math.pow(pUpper.toDouble(), 1.1).toFloat()
        val uLower = 11f * density * Math.pow(pLower.toDouble(), 1.05).toFloat()
        val uUpperCtrl = 5f * density * pUpper
        val uLowerCtrl = 18f * density * pLower

        // Vertical positions for control points relative to center
        val vP1 = -28f * density * (0.4f + 0.6f * p)
        val vP2 = 40f * density * (0.4f + 0.6f * p)
        val vPeak = -6f * density

        // Control point Ys
        val c1v = -curHTop + 20f * density * Math.pow(p.toDouble(), 0.7).toFloat()
        val c2v = -38f * density * (0.4f + 0.6f * p)
        val c3v = vP1 + (vP1 - c2v) * 0.8f
        val c4v = -18f * density * (0.5f + 0.5f * p)

        val c5v = 8f * density * (0.5f + 0.5f * p)
        val c6v = 24f * density * (0.4f + 0.6f * p)
        val c7v = vP2 + (vP2 - c6v) * 0.7f
        val c8v = curHBot - 20f * density * Math.pow(p.toDouble(), 0.7).toFloat()

        // 2. Build continuous cubic Bézier mountain silhouette mapped to screen edge
        mountainPath.reset()

        when (loc.edge) {
            ScreenEdge.RIGHT -> {
                // p0 on bezel
                mountainPath.moveTo(w, cy - curHTop)
                // Segment 1: Ingress from top bezel to upper shoulder
                mountainPath.cubicTo(
                    w, cy + c1v,
                    w - uUpperCtrl, cy + c2v,
                    w - uUpper, cy + vP1
                )
                // Segment 2: Upper shoulder to mountain peak
                mountainPath.cubicTo(
                    w - (uUpper + (uUpper - uUpperCtrl) * 0.8f), cy + c3v,
                    w - curDepth, cy + c4v,
                    w - curDepth, cy + vPeak
                )
                // Segment 3: Mountain peak to lower shoulder
                mountainPath.cubicTo(
                    w - curDepth, cy + c5v,
                    w - uLowerCtrl, cy + c6v,
                    w - uLower, cy + vP2
                )
                // Segment 4: Lower shoulder returning smoothly tangent to bottom bezel
                mountainPath.cubicTo(
                    w - (uLower + (uLower - uLowerCtrl) * 0.7f), cy + c7v,
                    w, cy + c8v,
                    w, cy + curHBot
                )
                // Close along right screen bezel
                mountainPath.lineTo(w, cy - curHTop)
                mountainPath.close()
            }
            ScreenEdge.LEFT -> {
                // p0 on bezel
                mountainPath.moveTo(0f, cy - curHTop)
                // Segment 1
                mountainPath.cubicTo(
                    0f, cy + c1v,
                    uUpperCtrl, cy + c2v,
                    uUpper, cy + vP1
                )
                // Segment 2
                mountainPath.cubicTo(
                    uUpper + (uUpper - uUpperCtrl) * 0.8f, cy + c3v,
                    curDepth, cy + c4v,
                    curDepth, cy + vPeak
                )
                // Segment 3
                mountainPath.cubicTo(
                    curDepth, cy + c5v,
                    uLowerCtrl, cy + c6v,
                    uLower, cy + vP2
                )
                // Segment 4
                mountainPath.cubicTo(
                    uLower + (uLower - uLowerCtrl) * 0.7f, cy + c7v,
                    0f, cy + c8v,
                    0f, cy + curHBot
                )
                // Close along left screen bezel
                mountainPath.lineTo(0f, cy - curHTop)
                mountainPath.close()
            }
            ScreenEdge.TOP -> {
                mountainPath.moveTo(cx - curHTop, 0f)
                mountainPath.cubicTo(
                    cx + c1v, 0f,
                    cx + c2v, uUpperCtrl,
                    cx + vP1, uUpper
                )
                mountainPath.cubicTo(
                    cx + c3v, uUpper + (uUpper - uUpperCtrl) * 0.8f,
                    cx + c4v, curDepth,
                    cx + vPeak, curDepth
                )
                mountainPath.cubicTo(
                    cx + c5v, curDepth,
                    cx + c6v, uLowerCtrl,
                    cx + vP2, uLower
                )
                mountainPath.cubicTo(
                    cx + c7v, uLower + (uLower - uLowerCtrl) * 0.7f,
                    cx + c8v, 0f,
                    cx + curHBot, 0f
                )
                mountainPath.lineTo(cx - curHTop, 0f)
                mountainPath.close()
            }
            ScreenEdge.BOTTOM -> {
                mountainPath.moveTo(cx - curHTop, h)
                mountainPath.cubicTo(
                    cx + c1v, h,
                    cx + c2v, h - uUpperCtrl,
                    cx + vP1, h - uUpper
                )
                mountainPath.cubicTo(
                    cx + c3v, h - (uUpper + (uUpper - uUpperCtrl) * 0.8f),
                    cx + c4v, h - curDepth,
                    cx + vPeak, h - curDepth
                )
                mountainPath.cubicTo(
                    cx + c5v, h - curDepth,
                    cx + c6v, h - uLowerCtrl,
                    cx + vP2, h - uLower
                )
                mountainPath.cubicTo(
                    cx + c7v, h - (uLower + (uLower - uLowerCtrl) * 0.7f),
                    cx + c8v, h,
                    cx + curHBot, h
                )
                mountainPath.lineTo(cx - curHTop, h)
                mountainPath.close()
            }
        }

        // Draw deep pure AMOLED black organic silhouette
        canvas.drawPath(mountainPath, amoledPaint)
    }
}
