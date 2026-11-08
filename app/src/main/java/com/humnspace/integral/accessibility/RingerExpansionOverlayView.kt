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

    // Physical dimensions (Refined Nothing squircle)
    private val capsuleW = 60f * density
    private val capsuleH = 60f * density
    private val capsuleCorner = 18f * density
    private val marginFromBezel = 14f * density

    private var state = State.IDLE
    val isShowing: Boolean get() = (state != State.IDLE && state != State.DISMISSED)
    val isCollapsing: Boolean get() = (state == State.COLLAPSING)

    private var slideProgress = 0f      // 0f (inside bezel) to 1f (fully expanded)
    private var shimmerProgress = 0f    // 0f to 1f specular border sweep
    private var iconMorphProgress = when (mode) {
        "vibrate" -> 1f
        "silent" -> 2f
        else -> 0f
    }
    private var iconPulse = 1f

    private var slideAnimator: ValueAnimator? = null
    private var shimmerAnimator: ValueAnimator? = null
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
        maskFilter = BlurMaskFilter(16f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val glassContainerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val metallicBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
    }

    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = 0.08f
    }

    private val ringSoundPath: Path by lazy {
        try {
            PathParser.createPathFromPathData("M12 22q-.825 0-1.412-.587T10 20h4q0 .825-.587 1.413T12 22m-8-3v-2h2v-7q0-2.075 1.25-3.687T10.5 4.2v-.7q0-.625.438-1.062T12 2t1.063.438T13.5 3.5v.7q.725.175 1.338.513t1.137.787L13 8.45v4.5L19.05 19zm14.9-3l-1.425-1.4q.725-.725 1.125-1.75t.4-2.15t-.4-2.15t-1.125-1.75l1.425-1.4q1.1 1.1 1.7 2.55t.6 3.1t-.6 3.1t-1.7 2.55M15.05 14l-1.425-1.4q.35-.35.55-.825t.2-.975t-.2-.975t-.55-.825L15.05 7.6q.7.7 1.075 1.625T16.5 11t-.375 1.775T15.05 14")
        } catch (_: Exception) { Path() }
    }

    private val mobileVibratePath: Path by lazy {
        try {
            PathParser.createPathFromPathData("M2 17.25q-.425 0-.712-.288T1 16.25v-8.5q0-.425.288-.712T2 6.75q.425 0 .713.288T3 7.75v8.5q0 .425-.288.713T2 17.25Zm20 0q-.425 0-.712-.288T21 16.25v-8.5q0-.425.288-.712T22 6.75q.425 0 .713.288T23 7.75v8.5q0 .425-.288.713T22 17.25ZM7 21q-.825 0-1.412-.587T5 19V5q0-.825.588-1.412T7 3h10q.825 0 1.413.588T19 5v14q0 .825-.587 1.413T17 21Zm0-2h10V5H7Zm5-3.5q.625 0 1.063-.438T13.5 15q0-.625-.437-1.063T12 13.5q-.625 0-1.062.437T10.5 15q0 .625.438 1.063T12 16.5ZM7 5v14z")
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
        shimmerProgress = 0f

        slideAnimator?.cancel()
        shimmerAnimator?.cancel()

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

        // Shimmer sweep across perimeter
        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340L
            interpolator = BadgerMotionEngine.Physics.SmoothDecelerate
            addUpdateListener {
                shimmerProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
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

        // Shimmer re-trigger
        shimmerAnimator?.cancel()
        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L
            interpolator = BadgerMotionEngine.Physics.SmoothDecelerate
            addUpdateListener {
                shimmerProgress = it.animatedValue as Float
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
        shimmerAnimator?.cancel()
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
        // Bezel indicator becomes the leading edge; stretches horizontally from physical button
        val curWidth = capsuleW * p.coerceIn(0.04f, 1f)
        val curHeight = capsuleH

        // Corners gradually morph from a narrow pill (curHeight / 2) into final capsule corner
        val maxRadius = curHeight / 2f
        val curRadius = maxRadius + (capsuleCorner - maxRadius) * p.coerceIn(0f, 1f)

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


        // 1. Soft atmospheric drop shadow
        if (p > 0.2f) {
            val shadowA = (80 * p).toInt().coerceIn(0, 100)
            shadowPaint.color = Color.argb(shadowA, 0, 0, 0)
            canvas.drawRoundRect(tempRect, curRadius, curRadius, shadowPaint)
        }

        // 2. Pitch black squircle glass surface
        capsulePath.reset()
        capsulePath.addRoundRect(tempRect, curRadius, curRadius, Path.Direction.CW)
        glassContainerPaint.color = Color.argb((255 * p.coerceIn(0f, 1f)).toInt(), 12, 12, 14)
        canvas.drawPath(capsulePath, glassContainerPaint)

        // 3. Shimmering amber metallic border
        drawShimmeringMetallicBorder(canvas, tempRect, p.coerceIn(0f, 1f), curRadius)

        // 4. Centered morphing icon (Material Symbols notification_sound / mobile_vibrate)
        if (p >= 0.35f) {
            val contentAlpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
            val iconX = tempRect.centerX()
            val iconY = tempRect.centerY()
            drawMorphingIcon(canvas, iconX, iconY, contentAlpha)
        }
    }

    private fun drawShimmeringMetallicBorder(canvas: Canvas, rect: RectF, alpha: Float, radius: Float) {
        val s = shimmerProgress.coerceIn(0f, 1f)
        val shimmerX = rect.left + (rect.width() + 40f * density) * s - 20f * density
        val shimmerSpan = 32f * density

        val baseBorderA = (48 * alpha).toInt().coerceIn(0, 255)
        val flashA = (180 * alpha).toInt().coerceIn(0, 255)
        val amberDark = Color.argb(baseBorderA, 255, 179, 0)
        val amberFlash = Color.argb(flashA, 255, 220, 110)

        metallicBorderPaint.shader = LinearGradient(
            shimmerX - shimmerSpan, rect.top, shimmerX + shimmerSpan, rect.bottom,
            intArrayOf(amberDark, amberDark, amberFlash, amberDark, amberDark),
            floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )

        canvas.drawRoundRect(rect, radius, radius, metallicBorderPaint)
        metallicBorderPaint.shader = null
    }

    private fun drawMorphingIcon(canvas: Canvas, cx: Float, cy: Float, alpha: Float) {
        val m = iconMorphProgress.coerceIn(0f, 1f)
        val alphaInt = (255 * alpha).toInt().coerceIn(0, 255)
        val pulse = iconPulse

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(pulse, pulse)

        val iconSize = 26f * density
        val scaleF = iconSize / 24f

        val isSilent = (mode == "silent" || iconMorphProgress >= 1.5f)
        val ringWeight = if (isSilent) 1f else (1f - m).coerceIn(0f, 1f)
        val vibWeight = if (isSilent) 0f else m.coerceIn(0f, 1f)

        if (ringWeight > 0.01f) {
            val rA = (alphaInt * ringWeight).toInt().coerceIn(0, 255)
            iconFillPaint.color = Color.argb(rA, 255, 179, 0)
            canvas.save()
            canvas.scale(scaleF, scaleF)
            canvas.translate(-12f, -12f)
            canvas.drawPath(ringSoundPath, iconFillPaint)
            canvas.restore()

            if (isSilent) {
                val slashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = Color.argb(rA, 255, 179, 0)
                    strokeCap = Paint.Cap.ROUND
                    strokeWidth = 2.2f * density
                }
                canvas.drawLine(-9f * density, -9f * density, 9f * density, 8f * density, slashPaint)
            }
        }

        if (vibWeight > 0.01f) {
            val vA = (alphaInt * vibWeight).toInt().coerceIn(0, 255)
            iconFillPaint.color = Color.argb(vA, 255, 179, 0)
            canvas.save()
            canvas.scale(scaleF, scaleF)
            canvas.translate(-12f, -12f)
            canvas.drawPath(mobileVibratePath, iconFillPaint)
            canvas.restore()
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
        shimmerAnimator?.cancel()
        morphAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
