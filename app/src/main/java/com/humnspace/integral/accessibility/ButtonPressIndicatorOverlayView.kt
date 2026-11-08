package com.humnspace.integral.accessibility

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * Button Press Indicator:
 * A sleek button-sized rectangle with blended/rounded inner edges, attached flat to the device screen bezel.
 *
 * Appears with smooth in-animation when the physical button is pressed down.
 * Contracts with smooth out-animation and disappears immediately as soon as the button is released.
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

    // Button-sized rectangle dimensions
    private val buttonHeightPx = 42f * density   // Matches physical hardware key height
    private val maxDepthPx = 8f * density        // Inward protrusion depth from the bezel edge
    private val cornerRadiusPx = 6f * density    // Blended corner radius on the inner edge

    private var currentDepthPx = 0f

    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
        maskFilter = BlurMaskFilter(3.0f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val buttonPath = Path()
    private val tempRect = RectF()
    private val cornerRadii = FloatArray(8)

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { dismissImmediate() }

    private var currentAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
    }

    fun triggerPress(type: PressType) {
        mainHandler.removeCallbacks(autoDismissRunnable)
        currentAnimator?.cancel()

        when (type) {
            PressType.DOWN -> animateIn()
            PressType.UP, PressType.LONG_RELEASE -> animateOut()
            PressType.SINGLE, PressType.DOUBLE, PressType.TRIPLE -> animateOut()
            PressType.LONG_HOLD -> animateHold()
        }
    }

    /**
     * Smooth In-Animation:
     * Slides / expands out from the bezel edge to full buttonDepth over 80ms.
     */
    private fun animateIn() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        mainHandler.postDelayed(autoDismissRunnable, 900L) // Safety watchdog
        BadgerMotionEngine.Haptics.performPressurePoint(context)

        val anim = ValueAnimator.ofFloat(currentDepthPx, maxDepthPx).apply {
            duration = 80L
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                currentDepthPx = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
        }
        currentAnimator = anim
        anim.start()
    }

    /**
     * Smooth Out-Animation:
     * Immediately slides back into the bezel edge as soon as button is released (105ms).
     */
    private fun animateOut() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        val start = currentDepthPx
        if (start <= 0.1f) {
            onDismissRequest()
            return
        }

        val anim = ValueAnimator.ofFloat(start, 0f).apply {
            duration = 105L
            interpolator = AccelerateInterpolator(1.3f)
            addUpdateListener {
                currentDepthPx = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onDismissRequest()
                }
            })
        }
        currentAnimator = anim
        anim.start()
    }

    private fun animateHold() {
        if (currentDepthPx < maxDepthPx) {
            val anim = ValueAnimator.ofFloat(currentDepthPx, maxDepthPx).apply {
                duration = 60L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    currentDepthPx = it.animatedValue as Float
                    postInvalidateOnAnimation()
                }
            }
            currentAnimator = anim
            anim.start()
        }
    }

    fun dismissImmediate() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        currentAnimator?.cancel()
        currentDepthPx = 0f
        postInvalidateOnAnimation()
        onDismissRequest()
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        currentAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = currentDepthPx
        if (d <= 0.05f || width <= 0 || height <= 0) return

        val loc = DeviceOrientationHelper.getButtonScreenLocation(context, width.toFloat(), height.toFloat())
        val cx = loc.x
        val cy = loc.y
        val h = buttonHeightPx
        val r = cornerRadiusPx.coerceAtMost(d)

        buttonPath.reset()

        when (loc.edge) {
            ScreenEdge.RIGHT -> {
                val edgeX = width.toFloat()
                val peakX = edgeX - d
                val topY = cy - h / 2f
                val botY = cy + h / 2f

                tempRect.set(peakX, topY, edgeX, botY)
                // Top-left: r, Top-right: 0 (against bezel), Bottom-right: 0, Bottom-left: r
                cornerRadii[0] = r; cornerRadii[1] = r
                cornerRadii[2] = 0f; cornerRadii[3] = 0f
                cornerRadii[4] = 0f; cornerRadii[5] = 0f
                cornerRadii[6] = r; cornerRadii[7] = r
                buttonPath.addRoundRect(tempRect, cornerRadii, Path.Direction.CW)
            }
            ScreenEdge.LEFT -> {
                val edgeX = 0f
                val peakX = d
                val topY = cy - h / 2f
                val botY = cy + h / 2f

                tempRect.set(edgeX, topY, peakX, botY)
                // Top-left: 0 (against bezel), Top-right: r, Bottom-right: r, Bottom-left: 0
                cornerRadii[0] = 0f; cornerRadii[1] = 0f
                cornerRadii[2] = r; cornerRadii[3] = r
                cornerRadii[4] = r; cornerRadii[5] = r
                cornerRadii[6] = 0f; cornerRadii[7] = 0f
                buttonPath.addRoundRect(tempRect, cornerRadii, Path.Direction.CW)
            }
            ScreenEdge.TOP -> {
                val leftX = cx - h / 2f
                val rightX = cx + h / 2f
                val peakY = d

                tempRect.set(leftX, 0f, rightX, peakY)
                // Top-left: 0, Top-right: 0, Bottom-right: r, Bottom-left: r
                cornerRadii[0] = 0f; cornerRadii[1] = 0f
                cornerRadii[2] = 0f; cornerRadii[3] = 0f
                cornerRadii[4] = r; cornerRadii[5] = r
                cornerRadii[6] = r; cornerRadii[7] = r
                buttonPath.addRoundRect(tempRect, cornerRadii, Path.Direction.CW)
            }
            ScreenEdge.BOTTOM -> {
                val leftX = cx - h / 2f
                val rightX = cx + h / 2f
                val peakY = height.toFloat() - d

                tempRect.set(leftX, peakY, rightX, height.toFloat())
                // Top-left: r, Top-right: r, Bottom-right: 0, Bottom-left: 0
                cornerRadii[0] = r; cornerRadii[1] = r
                cornerRadii[2] = r; cornerRadii[3] = r
                cornerRadii[4] = 0f; cornerRadii[5] = 0f
                cornerRadii[6] = 0f; cornerRadii[7] = 0f
                buttonPath.addRoundRect(tempRect, cornerRadii, Path.Direction.CW)
            }
        }

        // Draw soft ambient shadow along the rounded edge
        if (d > 1.2f) {
            canvas.drawPath(buttonPath, shadowPaint)
        }

        // Draw solid black button rectangle attached to bezel
        canvas.drawPath(buttonPath, rectPaint)
    }
}
