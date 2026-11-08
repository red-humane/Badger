package com.humnspace.integral.accessibility

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.humnspace.integral.R
import kotlin.math.*

/**
 * OptionWheel item model.
 */
data class OptionWheelItem(
    val id: String,
    val label: String,
    val iconType: String,
    val onSelect: () -> Unit
)

/**
 * Android implementation of the Orbital Menu / OptionWheel component.
 *
 * Adheres to the Badger Motion Philosophy:
 * 1. Central amber point emerges from the hardware button edge (45ms).
 * 2. Expands into an obsidian central hub (90ms).
 * 3. Primary options sequentially orbit into position with 45ms stagger.
 * 4. Directional selection: dragging from the hardware button selects options with boundary detents.
 * 5. Selection pulls the chosen bubble towards the center while remaining bubbles contract and disappear.
 */
class OptionWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    // Configurable parameters
    var items: List<OptionWheelItem> = emptyList()
        set(value) {
            field = value
            if (selectedIndex !in value.indices) {
                selectedIndex = (value.size / 2).coerceIn(0, max(0, value.size - 1))
            }
            targetPos = selectedIndex.toFloat()
            curPos = targetPos
            entranceStartTime = System.currentTimeMillis()
            invalidate()
        }

    var side: String = "right" // "left" or "right"
    var tiltDeg: Float = 18f // Angle in degrees between neighboring options
    var curve: Float = 1.0f // Depth of the circular curve
    var fade: Float = 0.28f // Opacity lost per step away from the middle
    var minOpacity: Float = 0.08f // Opacity floor for furthest options
    var smoothingMs: Float = 180f // Easing time constant in ms
    var insetPx: Float = 28f * density // Distance from anchored edge
    var rowH: Float = 58f * density // Vertical spacing between options

    private var selectedIndex: Int = 0
    private var curPos: Float = 0f
    private var targetPos: Float = 0f
    private var lastTimeMs: Long = 0L

    // Orbital Menu Timeline
    private var entranceStartTime = System.currentTimeMillis()
    private var isSelecting = false
    private var selectedItemIndex = -1
    private var selectionProgress = 0f
    private var selectionAnimator: ValueAnimator? = null

    // Touch & dragging state
    private var isDragging = false
    private var downX = 0f
    private var downY = 0f
    private var lastTouchY = 0f
    private var dragMoved = false
    private var velocityTracker: VelocityTracker? = null

    // Haptics
    private var lastTickIndex = -1
    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) { null }

    // Paints
    private val typefaceSatoshi = ResourcesCompat.getFont(context, R.font.satoshi_bold) ?: Typeface.DEFAULT_BOLD

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = typefaceSatoshi
        textSize = 13f * density
        letterSpacing = 0.08f
    }

    private val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val cardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Colors
    private val colorActiveText = Color.rgb(255, 255, 255)
    private val colorRestingText = Color.rgb(166, 166, 172)
    private val colorYellow = Color.rgb(255, 179, 0) // Nothing signature yellow

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        entranceStartTime = System.currentTimeMillis()
        postInvalidateOnAnimation()
    }

    fun selectIndex(index: Int, smooth: Boolean = true) {
        if (index !in items.indices) return
        selectedIndex = index
        targetPos = index.toFloat()
        if (!smooth) {
            curPos = targetPos
        }
        postInvalidateOnAnimation()
    }

    /**
     * Executes the Badger Orbital selection sequence:
     * - Pulls chosen bubble towards central hub
     * - Contracts and dismisses remaining bubbles
     * - Delivers click haptic and executes selection callback
     */
    private fun triggerItemSelection(index: Int) {
        if (index !in items.indices || isSelecting) return
        isSelecting = true
        selectedItemIndex = index
        selectIndex(index, smooth = true)
        playClick()

        selectionAnimator?.cancel()
        selectionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 140L
            interpolator = android.view.animation.DecelerateInterpolator(1.4f)
            addUpdateListener {
                selectionProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    items[index].onSelect()
                }
            })
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (items.isEmpty() || isSelecting) return super.onTouchEvent(event)

        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastTouchY = event.y
                dragMoved = false
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastTouchY
                lastTouchY = event.y
                if (!dragMoved && (abs(event.y - downY) > 5f * density || abs(event.x - downX) > 5f * density)) {
                    dragMoved = true
                }
                if (dragMoved) {
                    // Update target position based on drag delta
                    val step = dy / rowH
                    targetPos = (targetPos - step).coerceIn(-0.4f, (items.size - 1) + 0.4f)
                    checkHapticTick(targetPos)
                    postInvalidateOnAnimation()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)

                if (dragMoved) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vy = velocityTracker?.yVelocity ?: 0f
                    val flingSteps = -(vy / 1800f)
                    var finalTarget = targetPos + flingSteps
                    finalTarget = round(finalTarget).coerceIn(0f, (items.size - 1).toFloat())
                    targetPos = finalTarget
                    val newIdx = targetPos.toInt().coerceIn(0, items.size - 1)
                    triggerItemSelection(newIdx)
                } else {
                    // Tap detection: calculate which item was clicked
                    val clickedIndex = findItemAt(event.x, event.y)
                    if (clickedIndex != null && clickedIndex in items.indices) {
                        triggerItemSelection(clickedIndex)
                    }
                }

                velocityTracker?.recycle()
                velocityTracker = null
                postInvalidateOnAnimation()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findItemAt(touchX: Float, touchY: Float): Int? {
        val centerY = height / 2f
        val mirror = if (side == "right") -1f else 1f
        val tiltRad = (tiltDeg * Math.PI.toFloat()) / 180f
        val R = if (tiltRad > 0.0005f) rowH / tiltRad else 0f
        val anchorX = if (side == "right") width.toFloat() - insetPx else insetPx

        var closestIdx: Int? = null
        var minDistance = Float.MAX_VALUE

        for (i in items.indices) {
            val d = i - curPos
            if (abs(d) > 2.8f) continue
            val ang = (d * tiltRad).coerceIn(-Math.PI.toFloat() / 2f, Math.PI.toFloat() / 2f)
            val y = centerY + R * sin(ang)
            val x = anchorX - mirror * R * (1f - cos(ang)) * curve

            // Check if touch is within bounding box of the item pill
            val pillW = 180f * density
            val pillH = 46f * density
            val left = if (side == "right") x - pillW else x
            val right = if (side == "right") x else x + pillW
            val top = y - pillH / 2f
            val bottom = y + pillH / 2f

            if (touchX in (left - 15f * density)..(right + 15f * density) && touchY in (top - 8f * density)..(bottom + 8f * density)) {
                val dist = hypot(touchX - (left + right) / 2f, touchY - y)
                if (dist < minDistance) {
                    minDistance = dist
                    closestIdx = i
                }
            }
        }
        return closestIdx
    }

    private fun checkHapticTick(pos: Float) {
        val nearest = round(pos).toInt().coerceIn(0, items.size - 1)
        if (nearest != lastTickIndex && abs(pos - nearest) < 0.18f) {
            lastTickIndex = nearest
            playTick()
        }
    }

    private fun playTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            }
        } catch (_: Exception) {}
    }

    private fun playClick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            }
        } catch (_: Exception) {}
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        val now = System.currentTimeMillis()
        val dt = if (lastTimeMs > 0L) (now - lastTimeMs) / 1000f else 1f / 60f
        lastTimeMs = now

        val elapsed = (now - entranceStartTime).coerceAtLeast(0L)
        if (elapsed < 500L || isSelecting) {
            postInvalidateOnAnimation()
        }

        // Frame-rate independent exponential smoothing (React Bits tau = smoothing / 1000)
        val tau = max(smoothingMs, 1f) / 1000f
        val k = 1f - exp(-dt / tau)
        curPos += (targetPos - curPos) * k
        if (abs(targetPos - curPos) < 0.001f) {
            curPos = targetPos
        } else {
            postInvalidateOnAnimation()
        }

        val centerY = height / 2f
        val mirror = if (side == "right") -1f else 1f
        val tiltRad = (tiltDeg * Math.PI.toFloat()) / 180f
        val R = if (tiltRad > 0.0005f) rowH / tiltRad else 0f
        val anchorX = if (side == "right") width.toFloat() - insetPx else insetPx

        // Sequential Orbital Items Entrance (45ms stagger per item)
        val n = items.size
        for (i in 0 until n) {
            val staggerDelay = 90L + i * 45L
            val itemElapsed = (elapsed - staggerDelay).coerceAtLeast(0L)
            val pOrbitRaw = (itemElapsed / 160f).coerceIn(0f, 1f)
            val pOrbit = if (pOrbitRaw >= 1f) 1f else BadgerMotionEngine.Physics.SpringOvershoot.getInterpolation(pOrbitRaw)

            val d = i - curPos
            val dist = abs(d)
            if (dist > 3.2f) continue // Skip items curled far out of view

            val ang = (d * tiltRad).coerceIn(-Math.PI.toFloat() / 2f, Math.PI.toFloat() / 2f)
            val destY = centerY + R * sin(ang)
            val destX = anchorX - mirror * R * (1f - cos(ang)) * curve
            val rotDeg = mirror * (ang * 180f / Math.PI.toFloat())

            val opacity = (max(minOpacity, 1f - dist * fade).coerceIn(0f, 1f)) * pOrbitRaw
            val activeP = max(0f, 1f - min(dist, 1f)) // 1 at center, 0 one step away

            var curX = anchorX + (destX - anchorX) * pOrbit
            var curY = centerY + (destY - centerY) * pOrbit
            var itemScale = 0.25f + 0.75f * pOrbit
            var itemAlpha = opacity

            if (isSelecting) {
                if (i == selectedItemIndex) {
                    // Selected bubble pulls towards central hub
                    val pullFactor = selectionProgress * 0.65f
                    curX += (anchorX - curX) * pullFactor
                    curY += (centerY - curY) * pullFactor
                    itemScale = 1.0f + 0.08f * sin(selectionProgress * Math.PI.toFloat())
                    itemAlpha = 1.0f
                } else {
                    // Remaining bubbles contract and disappear
                    itemScale = (1.0f - selectionProgress).coerceIn(0f, 1f)
                    itemAlpha = (opacity * (1.0f - selectionProgress * 1.5f)).coerceIn(0f, 1f)
                }
            }

            if (itemAlpha <= 0f || itemScale <= 0f) continue

            canvas.save()
            canvas.translate(curX, curY)
            canvas.scale(itemScale, itemScale)
            canvas.rotate(rotDeg)

            drawOptionCard(canvas, items[i], itemAlpha, activeP)

            canvas.restore()
        }
    }

    private fun drawOptionCard(canvas: Canvas, item: OptionWheelItem, opacity: Float, activeP: Float) {
        val pillH = (44f + 6f * activeP) * density
        val circleSize = (38f + 4f * activeP) * density
        val iconSize = (20f + 2f * activeP) * density
        val isRight = (side == "right")

        val alphaInt = (opacity * 255).toInt().coerceIn(0, 255)
        textPaint.color = blendColors(colorRestingText, colorActiveText, activeP)
        textPaint.alpha = alphaInt
        textPaint.textSize = (11.5f + 2f * activeP) * density

        val labelText = item.label
        val textWidth = textPaint.measureText(labelText)
        val pillW = circleSize + textWidth + 32f * density

        val pillLeft = if (isRight) -pillW else 0f
        val pillRight = if (isRight) 0f else pillW
        val pillTop = -pillH / 2f
        val pillBottom = pillH / 2f
        val pillRect = RectF(pillLeft, pillTop, pillRight, pillBottom)

        // Active halo / yellow glow when near center
        if (activeP > 0.15f) {
            val glowAlpha = (110 * activeP * opacity).toInt().coerceIn(0, 255)
            glowPaint.color = Color.argb(glowAlpha, 255, 179, 0)
            val glowRect = RectF(pillLeft - 4f * density, pillTop - 3f * density, pillRight + 4f * density, pillBottom + 3f * density)
            canvas.drawRoundRect(glowRect, (pillH / 2f) + 4f * density, (pillH / 2f) + 4f * density, glowPaint)
        }

        // Card glass background
        val bgAlpha = ((180 + 65 * activeP) * opacity).toInt().coerceIn(0, 255)
        cardFillPaint.color = Color.argb(bgAlpha, 18, 18, 22)
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, cardFillPaint)

        // Card border (crisp Nothing yellow when active, subtle translucent gray when resting)
        val strokeColor = if (activeP > 0.45f) {
            val yellowA = (255 * activeP * opacity).toInt().coerceIn(0, 255)
            Color.argb(yellowA, 255, 179, 0)
        } else {
            val borderA = (48 * opacity).toInt().coerceIn(0, 255)
            Color.argb(borderA, 255, 255, 255)
        }
        cardStrokePaint.strokeWidth = (1f + 1.2f * activeP) * density
        cardStrokePaint.color = strokeColor
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, cardStrokePaint)

        // Layout: Circle icon on the anchored side, text on the opposite side
        val circleCx = if (isRight) pillRight - circleSize / 2f - 4f * density else pillLeft + circleSize / 2f + 4f * density
        val circleCy = 0f
        val circleRadius = circleSize / 2f - 2f * density

        // Circle glass fill & stroke
        val circleFillA = ((210 + 45 * activeP) * opacity).toInt().coerceIn(0, 255)
        cardFillPaint.color = Color.argb(circleFillA, 24, 24, 30)
        canvas.drawCircle(circleCx, circleCy, circleRadius, cardFillPaint)
        cardStrokePaint.strokeWidth = 1f * density
        cardStrokePaint.color = if (activeP > 0.45f) Color.argb((200 * opacity).toInt(), 255, 179, 0) else Color.argb((45 * opacity).toInt(), 255, 255, 255)
        canvas.drawCircle(circleCx, circleCy, circleRadius, cardStrokePaint)

        // Draw icon inside circle
        canvas.save()
        canvas.translate(circleCx, circleCy)
        drawTrayIcon(canvas, item.iconType, iconSize, opacity, activeP)
        canvas.restore()

        // Draw text label
        val textX = if (isRight) {
            circleCx - circleRadius - 12f * density - textWidth
        } else {
            circleCx + circleRadius + 12f * density
        }
        val textY = -((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(labelText, textX, textY, textPaint)
    }

    private fun drawTrayIcon(canvas: Canvas, iconType: String, sizePx: Float, opacity: Float, activeP: Float) {
        val yellowA = (255 * opacity).toInt().coerceIn(0, 255)
        iconStrokePaint.color = Color.argb(yellowA, 255, 179, 0)
        iconFillPaint.color = Color.argb(yellowA, 255, 179, 0)

        val w = sizePx
        val h = sizePx
        val halfW = w / 2f
        val halfH = h / 2f
        val d = density

        when (iconType) {
            "content" -> {
                val cardW = w * 0.54f
                val cardH = h * 0.62f
                canvas.drawRoundRect(RectF(-halfW * 0.4f, -halfH * 0.75f, -halfW * 0.4f + cardW, -halfH * 0.75f + cardH), 2.5f * d, 2.5f * d, iconStrokePaint)
                canvas.drawRoundRect(RectF(-halfW * 0.85f, -halfH * 0.35f, -halfW * 0.85f + cardW, -halfH * 0.35f + cardH), 2.5f * d, 2.5f * d, iconStrokePaint)
            }
            "recorder" -> {
                val micW = w * 0.36f
                val micH = h * 0.46f
                canvas.drawRoundRect(RectF(-micW / 2f, -micH / 2f - 2f * d, micW / 2f, micH / 2f - 2f * d), micW / 2f, micW / 2f, iconStrokePaint)
                canvas.drawArc(RectF(-micW * 0.85f, -micH * 0.16f, micW * 0.85f, micH * 0.55f), 0f, 180f, false, iconStrokePaint)
                val stemY1 = micH * 0.55f
                val stemY2 = stemY1 + h * 0.14f
                canvas.drawLine(0f, stemY1, 0f, stemY2, iconStrokePaint)
                canvas.drawLine(-w * 0.22f, stemY2, w * 0.22f, stemY2, iconStrokePaint)
            }
            "voice" -> {
                val barW = 2.2f * d
                val count = 4
                val spacing = w * 0.20f
                val startX = -((count - 1) * spacing) / 2f
                val heights = floatArrayOf(0.35f, 0.85f, 0.60f, 0.40f)
                for (j in 0 until count) {
                    val bh = h * heights[j]
                    val bx = startX + j * spacing
                    canvas.drawRoundRect(RectF(bx - barW / 2f, -bh / 2f, bx + barW / 2f, bh / 2f), barW / 2f, barW / 2f, iconFillPaint)
                }
            }
            "ringer" -> {
                val bellW = w * 0.52f
                val bellH = h * 0.54f
                val bellTop = -halfH * 0.55f
                val bellPath = Path().apply {
                    moveTo(0f, bellTop)
                    cubicTo(bellW * 0.45f, bellTop, bellW * 0.55f, bellTop + bellH * 0.7f, bellW * 0.65f, bellTop + bellH)
                    lineTo(-bellW * 0.65f, bellTop + bellH)
                    cubicTo(-bellW * 0.55f, bellTop + bellH * 0.7f, -bellW * 0.45f, bellTop, 0f, bellTop)
                    close()
                }
                canvas.drawPath(bellPath, iconStrokePaint)
                canvas.drawCircle(0f, bellTop + bellH + 2.5f * d, 1.8f * d, iconFillPaint)
            }
            else -> {
                canvas.drawCircle(0f, 0f, 4f * d, iconFillPaint)
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

    override fun onDetachedFromWindow() {
        selectionAnimator?.cancel()
        selectionAnimator = null
        super.onDetachedFromWindow()
    }
}

