package com.humnspace.integral.accessibility

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.PathParser
import kotlin.math.sin

/**
 * Shape-Morphing Dynamic Ringer Chassis Overlay.
 *
 * Implements the new Badger motion philosophy:
 * 1. Morph, don't expand — the bezel indicator becomes the capsule's leading edge,
 *    stretching horizontally from the physical button (100ms stretch, 220ms formation).
 * 2. Corners gradually transition from a narrow pill into the final rounded capsule.
 * 3. Icon & mode label appear only after the container reaches ~65% of its final width.
 * 4. Mode morphs smoothly between Bell, Vibration waves, and Silent without disappearing.
 * 5. Drag-to-switch interaction: User slides vertically to select mode with haptic boundary detents.
 * 6. Magnetic retraction: Retracts into the exact hardware bezel edge over 190ms.
 */
class RingerExpansionOverlayView(
    context: Context,
    private var mode: String = "ring",
    private val sourceRect: RectF? = null,
    private val onDismissComplete: (RingerExpansionOverlayView) -> Unit
) : View(context) {

    val currentMode: String get() = mode

    enum class State {
        IDLE,
        EXPANDING,
        SETTLED,
        COLLAPSING,
        DISMISSED
    }

    private val density = resources.displayMetrics.density

    // Physical dimensions (Circle for Ring, Squircle for Vibrate)
    private val capsuleW = 62f * density
    private val capsuleH = 62f * density
    private val capsuleCorner = 18f * density
    private val marginFromBezel = 14f * density

    private var state = State.IDLE
    val isShowing: Boolean get() = (state != State.IDLE && state != State.DISMISSED)
    val isCollapsing: Boolean get() = (state == State.COLLAPSING)

    private var slideProgress = 0f      // 0f (inside bezel) to 1f (fully expanded)
    private var iconMorphProgress = when (mode) {
        "vibrate" -> 1f
        "silent" -> 2f
        else -> 0f
    }
    private var iconPulse = 1f

    private var slideAnimator: ValueAnimator? = null
    private var morphAnimator: ValueAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { dismissWithCollapse() }

    private var buttonLocation: ButtonScreenLocation? = null
    private val restingCapsuleRect = RectF()

    // Drag-to-switch state
    private var touchStartY = 0f
    private var isDraggingMode = false

    // Paints
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
        maskFilter = BlurMaskFilter(14f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val containerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val containerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
    }

    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = 0.08f
    }

    private val ringSoundPath: Path by lazy {
        try {
            // Material Symbols notifications (filled, bold, perfectly centered 24x24)
            PathParser.createPathFromPathData("M12 22c1.1 0 2-.9 2-2h-4c0 1.1.9 2 2 2zm6-6v-5c0-3.07-1.63-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.64 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z")
        } catch (_: Exception) { Path() }
    }

    private val mobileVibratePath: Path by lazy {
        try {
            // Material Symbols vibration (bold phone + vibration waves, centered 24x24)
            PathParser.createPathFromPathData("M0 15h2V9H0v6zm3 2h2V7H3v10zm19-8v6h2V9h-2zm-3 8h2V7h-2v10zM17 3H7c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 15H7V6h10v12z")
        } catch (_: Exception) { Path() }
    }

    private val capsulePath = Path()
    private val tempRect = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && state == State.IDLE) {
            calculateCoordinates(w.toFloat(), h.toFloat())
            startSlideOutAnimation()
        }
    }

    private fun calculateCoordinates(widthPx: Float, heightPx: Float) {
        val loc = DeviceOrientationHelper.getButtonScreenLocation(context, widthPx, heightPx)
        buttonLocation = loc

        when (loc.edge) {
            ScreenEdge.RIGHT -> {
                val cy = loc.y.coerceIn(marginFromBezel + capsuleH / 2f, heightPx - marginFromBezel - capsuleH / 2f)
                val targetRight = widthPx - marginFromBezel
                val targetLeft = targetRight - capsuleW
                restingCapsuleRect.set(targetLeft, cy - capsuleH / 2f, targetRight, cy + capsuleH / 2f)
            }
            ScreenEdge.LEFT -> {
                val cy = loc.y.coerceIn(marginFromBezel + capsuleH / 2f, heightPx - marginFromBezel - capsuleH / 2f)
                val targetLeft = marginFromBezel
                val targetRight = targetLeft + capsuleW
                restingCapsuleRect.set(targetLeft, cy - capsuleH / 2f, targetRight, cy + capsuleH / 2f)
            }
            ScreenEdge.TOP -> {
                val cx = loc.x.coerceIn(marginFromBezel + capsuleW / 2f, widthPx - marginFromBezel - capsuleW / 2f)
                val targetTop = marginFromBezel
                val targetBottom = targetTop + capsuleH
                restingCapsuleRect.set(cx - capsuleW / 2f, targetTop, cx + capsuleW / 2f, targetBottom)
            }
            ScreenEdge.BOTTOM -> {
                val cx = loc.x.coerceIn(marginFromBezel + capsuleW / 2f, widthPx - marginFromBezel - capsuleW / 2f)
                val targetBottom = heightPx - marginFromBezel
                val targetTop = targetBottom - capsuleH
                restingCapsuleRect.set(cx - capsuleW / 2f, targetTop, cx + capsuleW / 2f, targetBottom)
            }
        }
    }

    /**
     * Shape-Morphing Chassis Animation:
     * - Initial 100ms: Stretches horizontally from the physical button origin.
     * - 220ms: Corners morph from pill to capsule; width expands to full 92dp.
     */
    private fun startSlideOutAnimation() {
        state = State.EXPANDING
        slideProgress = 0f

        slideAnimator?.cancel()

        NothingGlyphController.onRingerChanged()
        when (mode) {
            "ring" -> IntegralAcoustics.playRingerRing(context)
            "vibrate" -> IntegralAcoustics.playRingerVibrate(context)
            "silent" -> IntegralAcoustics.playRingerSilent(context)
            else -> IntegralAcoustics.playOverlayOpen(context)
        }

        var wasCancelled = false
        slideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BadgerMotionEngine.Timings.CAPSULE_FORMATION
            interpolator = BadgerMotionEngine.Physics.SpringOvershoot
            addUpdateListener {
                slideProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    wasCancelled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (!wasCancelled) {
                        state = State.SETTLED
                        handler.removeCallbacks(autoDismissRunnable)
                        handler.postDelayed(autoDismissRunnable, 900L)
                    }
                }
            })
            start()
        }
    }

    fun updateMode(newMode: String) {
        mode = newMode

        // If it was collapsing or expanding, cancel retraction and seamlessly re-expand to 1f
        if (state == State.COLLAPSING || state == State.EXPANDING) {
            slideAnimator?.cancel()
            state = State.SETTLED
            val currentP = slideProgress
            if (currentP < 1f) {
                slideAnimator = ValueAnimator.ofFloat(currentP, 1f).apply {
                    duration = ((1f - currentP) * BadgerMotionEngine.Timings.CAPSULE_FORMATION).toLong().coerceIn(60L, 200L)
                    interpolator = BadgerMotionEngine.Physics.SmoothDecelerate
                    addUpdateListener {
                        slideProgress = it.animatedValue as Float
                        postInvalidateOnAnimation()
                    }
                    start()
                }
            } else {
                slideProgress = 1f
                postInvalidateOnAnimation()
            }
        }

        NothingGlyphController.onRingerChanged()
        when (newMode) {
            "ring" -> IntegralAcoustics.playRingerRing(context)
            "vibrate" -> IntegralAcoustics.playRingerVibrate(context)
            "silent" -> IntegralAcoustics.playRingerSilent(context)
        }
        handler.removeCallbacks(autoDismissRunnable)
        handler.postDelayed(autoDismissRunnable, 900L)

        // 180ms Fluid Path & Icon Morph
        val targetMorph = when (mode) {
            "vibrate" -> 1f
            "silent" -> 2f
            else -> 0f
        }
        morphAnimator?.cancel()
        morphAnimator = ValueAnimator.ofFloat(iconMorphProgress, targetMorph).apply {
            duration = BadgerMotionEngine.Timings.ICON_MORPH
            interpolator = BadgerMotionEngine.Physics.SpringOvershoot
            addUpdateListener {
                iconMorphProgress = it.animatedValue as Float
                iconPulse = 1f + 0.14f * sin((it.animatedFraction * Math.PI).toFloat())
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    /**
     * Drag-to-Switch Interaction:
     * Slide vertically to change modes with haptic boundary clicks.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (tempRect.contains(event.x, event.y)) {
                    touchStartY = event.y
                    isDraggingMode = true
                    handler.removeCallbacks(autoDismissRunnable)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingMode) {
                    val dy = event.y - touchStartY
                    val threshold = 28f * density
                    val targetMode = when {
                        dy < -threshold -> "ring"
                        dy > threshold -> "silent"
                        else -> "vibrate"
                    }
                    if (targetMode != mode) {
                        BadgerMotionEngine.Haptics.performBoundaryDetent(context)
                        updateMode(targetMode)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingMode) {
                    isDraggingMode = false
                    applySystemRinger(mode)
                    BadgerMotionEngine.Haptics.performGestureConfirmed(context)
                    handler.removeCallbacks(autoDismissRunnable)
                    handler.postDelayed(autoDismissRunnable, 900L)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applySystemRinger(targetMode: String) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            when (targetMode) {
                "ring" -> audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
                "vibrate" -> audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                "silent" -> audio.ringerMode = AudioManager.RINGER_MODE_SILENT
            }
        } catch (_: Exception) {}
    }

    /**
     * Magnetic Retraction into Bezel (190ms)
     */
    fun dismissWithCollapse() {
        if (state == State.COLLAPSING || state == State.DISMISSED) return
        state = State.COLLAPSING
        IntegralAcoustics.playOverlayDismiss(context)
        handler.removeCallbacks(autoDismissRunnable)
        slideAnimator?.cancel()
        morphAnimator?.cancel()

        var wasCancelled = false
        val currentP = slideProgress
        slideAnimator = ValueAnimator.ofFloat(currentP, 0f).apply {
            duration = BadgerMotionEngine.Timings.CHASSIS_RETRACTION
            interpolator = BadgerMotionEngine.Physics.MagneticRetract
            addUpdateListener {
                slideProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    wasCancelled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (!wasCancelled) {
                        state = State.DISMISSED
                        onDismissComplete(this@RingerExpansionOverlayView)
                    }
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (state == State.DISMISSED || width <= 0 || height <= 0) return

        val p = slideProgress
        if (p <= 0.01f && state == State.COLLAPSING) return

        val loc = buttonLocation ?: return
        val resting = restingCapsuleRect

        // Shape-morphing chassis geometry:
        val curWidth = capsuleW * p.coerceIn(0.04f, 1f)
        val curHeight = capsuleH

        // Morph between Circle (for Ring, m=0) and Squircle (for Vibrate, m=1)
        val m = iconMorphProgress.coerceIn(0f, 1f)
        val maxRadius = curHeight / 2f
        val squircleRadius = capsuleCorner
        val targetRestingCorner = maxRadius + (squircleRadius - maxRadius) * m
        val curRadius = maxRadius + (targetRestingCorner - maxRadius) * p.coerceIn(0f, 1f)

        when (loc.edge) {
            ScreenEdge.RIGHT -> {
                val right = width.toFloat() - marginFromBezel * p.coerceIn(0f, 1f)
                val left = right - curWidth
                val cy = resting.centerY()
                tempRect.set(left, cy - curHeight / 2f, right, cy + curHeight / 2f)
            }
            ScreenEdge.LEFT -> {
                val left = marginFromBezel * p.coerceIn(0f, 1f)
                val right = left + curWidth
                val cy = resting.centerY()
                tempRect.set(left, cy - curHeight / 2f, right, cy + curHeight / 2f)
            }
            ScreenEdge.TOP -> {
                val top = marginFromBezel * p.coerceIn(0f, 1f)
                val bottom = top + curHeight * p.coerceIn(0.04f, 1f)
                val cx = resting.centerX()
                tempRect.set(cx - capsuleW / 2f, top, cx + capsuleW / 2f, bottom)
            }
            ScreenEdge.BOTTOM -> {
                val bottom = height.toFloat() - marginFromBezel * p.coerceIn(0f, 1f)
                val top = bottom - curHeight * p.coerceIn(0.04f, 1f)
                val cx = resting.centerX()
                tempRect.set(cx - capsuleW / 2f, top, cx + capsuleW / 2f, bottom)
            }
        }

        // 1. Soft atmospheric drop shadow matching contour
        if (p > 0.2f) {
            val shadowA = (60 * p).toInt().coerceIn(0, 80)
            shadowPaint.color = Color.argb(shadowA, 0, 0, 0)
            canvas.drawRoundRect(tempRect, curRadius, curRadius, shadowPaint)
        }

        // 2. Container surface:
        // - Ring: Circle with Accent Yellow (#FFB300)
        // - Vibrate: Squircle with Pure White (#FFFFFF)
        capsulePath.reset()
        capsulePath.addRoundRect(tempRect, curRadius, curRadius, Path.Direction.CW)

        val rBg = 255
        val gBg = (179 + (255 - 179) * m).toInt().coerceIn(0, 255)
        val bBg = (0 + (255 - 0) * m).toInt().coerceIn(0, 255)
        containerFillPaint.color = Color.argb((255 * p.coerceIn(0f, 1f)).toInt(), rBg, gBg, bBg)
        canvas.drawPath(capsulePath, containerFillPaint)

        // 3. Crisp subtle border for edge definition against bright backgrounds
        val borderA = (22 * p.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        containerBorderPaint.color = Color.argb(borderA, 0, 0, 0)
        canvas.drawRoundRect(tempRect, curRadius, curRadius, containerBorderPaint)

        // 4. Centered big black morphing icon
        if (p >= 0.30f) {
            val contentAlpha = ((p - 0.30f) / 0.70f).coerceIn(0f, 1f)
            val iconX = tempRect.centerX()
            val iconY = tempRect.centerY()
            drawMorphingIcon(canvas, iconX, iconY, contentAlpha)
        }
    }

    private fun drawMorphingIcon(canvas: Canvas, cx: Float, cy: Float, alpha: Float) {
        val m = iconMorphProgress.coerceIn(0f, 1f)
        val alphaInt = (255 * alpha).toInt().coerceIn(0, 255)
        val pulse = iconPulse

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(pulse, pulse)

        val iconSize = 34f * density // Big bold icon
        val scaleF = iconSize / 24f

        val isSilent = (mode == "silent" || iconMorphProgress >= 1.5f)
        val ringWeight = if (isSilent) 0f else (1f - m).coerceIn(0f, 1f)
        val vibWeight = if (isSilent) 0f else m.coerceIn(0f, 1f)

        // Pure black icons for both Ring and Vibrate
        if (ringWeight > 0.01f) {
            val rA = (alphaInt * ringWeight).toInt().coerceIn(0, 255)
            iconFillPaint.color = Color.argb(rA, 0, 0, 0)
            canvas.save()
            canvas.scale(scaleF, scaleF)
            canvas.translate(-12f, -12f)
            canvas.drawPath(ringSoundPath, iconFillPaint)
            canvas.restore()
        }

        if (vibWeight > 0.01f) {
            val vA = (alphaInt * vibWeight).toInt().coerceIn(0, 255)
            iconFillPaint.color = Color.argb(vA, 0, 0, 0)
            canvas.save()
            canvas.scale(scaleF, scaleF)
            canvas.translate(-12f, -12f)
            canvas.drawPath(mobileVibratePath, iconFillPaint)
            canvas.restore()
        }

        if (isSilent) {
            val sA = alphaInt
            iconFillPaint.color = Color.argb(sA, 0, 0, 0)
            canvas.save()
            canvas.scale(scaleF, scaleF)
            canvas.translate(-12f, -12f)
            canvas.drawPath(ringSoundPath, iconFillPaint)
            canvas.restore()

            val slashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.argb(sA, 0, 0, 0)
                strokeCap = Paint.Cap.ROUND
                strokeWidth = 2.4f * density
            }
            canvas.drawLine(-11f * density, -11f * density, 11f * density, 10f * density, slashPaint)
        }

        canvas.restore()
    }

    fun start() {
        if (state == State.IDLE && width > 0 && height > 0) {
            calculateCoordinates(width.toFloat(), height.toFloat())
            startSlideOutAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(autoDismissRunnable)
        slideAnimator?.cancel()
        morphAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
