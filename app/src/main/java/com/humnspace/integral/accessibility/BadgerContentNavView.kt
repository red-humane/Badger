package com.humnspace.integral.accessibility

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * Badger Get-Content Navigation Pill.
 *
 * Designed directly after the user's reference designs (media_1793891084502_ad94c7e0.png):
 * - Ultra-minimal dark capsule surface (#121216) with subtle rim edge.
 * - Active item features a prominent, solid Nothing yellow (#F7D915) circle with solid black icon.
 * - Inactive items are rendered as clean, refined outline icons in silver/light gray (#A6A6AC).
 * - Smooth, physics-based spring slide when transitioning between modes.
 * - Fully accessible and compact.
 */
class BadgerContentNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    enum class NavMode { TEXT, NUMBERS, LINKS, IMAGES }

    var selectedMode: NavMode = NavMode.TEXT
        private set

    var onModeSelected: ((NavMode) -> Unit)? = null

    private val modes = NavMode.values()
    private var curThumbX = 0f
    private var targetThumbX = 0f
    private var thumbAnimator: ValueAnimator? = null

    // Geometry
    private val pillHeight = 48f * density
    private val circleRadius = 18f * density
    private val itemSpacing = 52f * density

    // Paints
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(240, 18, 18, 22)
    }

    private val frameRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(38, 255, 255, 255)
    }

    private val activeThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 179, 0) // Badger signature yellow
    }

    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Vibrator for tactile clicks
    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) { null }

    init {
        isClickable = true
        isFocusable = true
    }

    fun selectMode(mode: NavMode, animate: Boolean = true) {
        if (selectedMode == mode && thumbAnimator == null) return
        selectedMode = mode
        val idx = modes.indexOf(mode)
        val newTargetX = calculateCenterX(idx)

        if (!animate || width == 0) {
            curThumbX = newTargetX
            targetThumbX = newTargetX
            invalidate()
            return
        }

        thumbAnimator?.cancel()
        targetThumbX = newTargetX
        val startX = curThumbX

        thumbAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            // Spring-like overshoot curve
            interpolator = android.view.animation.OvershootInterpolator(1.15f)
            addUpdateListener {
                val t = it.animatedValue as Float
                curThumbX = startX + (targetThumbX - startX) * t
                invalidate()
            }
            start()
        }
        playTick()
    }

    private fun calculateCenterX(index: Int): Float {
        val totalWidth = width.toFloat()
        val count = modes.size
        val startX = (totalWidth - (count - 1) * itemSpacing) / 2f
        return startX + index * itemSpacing
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalW = (modes.size * itemSpacing + 28f * density).toInt()
        val totalH = pillHeight.toInt()
        setMeasuredDimension(totalW, totalH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val idx = modes.indexOf(selectedMode)
        curThumbX = calculateCenterX(idx)
        targetThumbX = curThumbX
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val tx = event.x
            var bestIdx = -1
            var bestDist = Float.MAX_VALUE

            for (i in modes.indices) {
                val cx = calculateCenterX(i)
                val dist = abs(tx - cx)
                if (dist < bestDist && dist < itemSpacing * 0.65f) {
                    bestDist = dist
                    bestIdx = i
                }
            }

            if (bestIdx != -1) {
                val mode = modes[bestIdx]
                if (mode != selectedMode) {
                    selectMode(mode, animate = true)
                    onModeSelected?.invoke(mode)
                }
                return true
            }
        }
        return true
    }

    private fun playTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            }
        } catch (_: Exception) {}
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2f
        val corner = h / 2f

        // 1. Sleek dark capsule frame matching reference image
        canvas.drawRoundRect(0f, 0f, w, h, corner, corner, framePaint)
        canvas.drawRoundRect(0.5f * density, 0.5f * density, w - 0.5f * density, h - 0.5f * density, corner, corner, frameRimPaint)

        // 2. Solid Nothing Yellow Circle Indicator (slides smoothly with spring physics)
        canvas.drawCircle(curThumbX, cy, circleRadius, activeThumbPaint)

        // 3. Draw the 4 Mode Icons
        for (i in modes.indices) {
            val cx = calculateCenterX(i)
            val distFromThumb = abs(cx - curThumbX)
            val isSelected = (distFromThumb < circleRadius * 0.8f)

            // Dynamic color blending: deep black when over yellow circle, clean silver outline otherwise
            val activeProgress = (1f - (distFromThumb / circleRadius).coerceIn(0f, 1f))
            val strokeColor = blendColors(
                Color.rgb(166, 166, 172), // Unselected silver/gray
                Color.rgb(18, 18, 22),     // Selected contrast black
                activeProgress
            )

            iconStrokePaint.color = strokeColor
            iconFillPaint.color = strokeColor

            canvas.save()
            canvas.translate(cx, cy)
            drawModeIcon(canvas, modes[i], isSelected, activeProgress)
            canvas.restore()
        }
    }

    private fun drawModeIcon(canvas: Canvas, mode: NavMode, isSelected: Boolean, activeP: Float) {
        val s = 15f * density
        val half = s / 2f
        val d = density

        when (mode) {
            NavMode.TEXT -> {
                // Clean typography T icon
                val barW = 10f * d
                val stemH = 11f * d
                iconStrokePaint.strokeWidth = 2.0f * d
                // Top horizontal crossbar
                canvas.drawLine(-barW / 2f, -stemH / 2f, barW / 2f, -stemH / 2f, iconStrokePaint)
                // Vertical stem
                canvas.drawLine(0f, -stemH / 2f, 0f, stemH / 2f, iconStrokePaint)
            }

            NavMode.NUMBERS -> {
                // Keypad / Hash # icon
                iconStrokePaint.strokeWidth = 1.7f * d
                val hw = 5f * d
                val hh = 5f * d
                // Vertical lines
                canvas.drawLine(-hw * 0.45f, -hh, -hw * 0.45f, hh, iconStrokePaint)
                canvas.drawLine(hw * 0.45f, -hh, hw * 0.45f, hh, iconStrokePaint)
                // Horizontal lines
                canvas.drawLine(-hw, -hh * 0.45f, hw, -hh * 0.45f, iconStrokePaint)
                canvas.drawLine(-hw, hh * 0.45f, hw, hh * 0.45f, iconStrokePaint)
            }

            NavMode.LINKS -> {
                // Interlocking link chain icon
                iconStrokePaint.strokeWidth = 1.8f * d
                val ovalW = 5f * d
                val ovalH = 3.5f * d
                canvas.save()
                canvas.rotate(-35f)
                canvas.drawRoundRect(RectF(-ovalW * 1.1f, -ovalH, 0.4f * d, ovalH), ovalH, ovalH, iconStrokePaint)
                canvas.drawRoundRect(RectF(-0.4f * d, -ovalH, ovalW * 1.1f, ovalH), ovalH, ovalH, iconStrokePaint)
                canvas.restore()
            }

            NavMode.IMAGES -> {
                // Frame / Crop icon
                iconStrokePaint.strokeWidth = 1.8f * d
                val b = 6f * d
                val c = 2.5f * d
                // Rounded crop rectangle with corner extension marks
                canvas.drawRoundRect(RectF(-b, -b, b, b), 2f * d, 2f * d, iconStrokePaint)
                canvas.drawCircle(0f, 0f, 1.4f * d, iconFillPaint)
            }
        }
    }

    private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        val inverseRatio = 1f - ratio
        val a = (Color.alpha(color1) * inverseRatio + Color.alpha(color2) * ratio).toInt()
        val r = (Color.red(color1) * inverseRatio + Color.red(color2) * ratio).toInt()
        val g = (Color.green(color1) * inverseRatio + Color.green(color2) * ratio).toInt()
        val b = (Color.blue(color1) * inverseRatio + Color.blue(color2) * ratio).toInt()
        return Color.argb(a, r, g, b)
    }
}

