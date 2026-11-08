package com.humnspace.integral.accessibility

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.humnspace.integral.R
import kotlin.math.*

/**
 * Android implementation of the React Bits <GooeyNav /> component.
 *
 * Provides a fluid metaball gooey tab navigation bar:
 * - Frosted glass pill frame with subtle rim highlight.
 * - Active pill that breaks into fluid droplets when a new tab is selected.
 * - Droplets fly along bezier swing paths with velocity elongation tails.
 * - Droplets melt into the destination pill with spring wobble physics.
 * - High-contrast text switching between active and muted ink.
 */
class GooeyNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    // Tab items
    data class TabItem(val id: String, val label: String)

    var tabs: List<TabItem> = listOf(
        TabItem("text", "Text"),
        TabItem("numbers", "Numbers"),
        TabItem("links", "Links"),
        TabItem("images", "Images")
    )
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var selectedIndex: Int = 0
        private set

    var onTabSelected: ((Int, TabItem) -> Unit)? = null

    // Fluid physics settings matching React Bits <GooeyNav />
    private val particleCount = 14
    private val spread = 18f * density
    private val animationDurationMs = 380L
    private val wobble = 0.5f
    private val gooeyness = 0.5f

    // Droplet simulation data structure
    private data class GooeyDrop(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val mass: Float,
        val size: Float,
        var sourceIndex: Int,
        var targetIndex: Int,
        val leaveTime: Long,
        var startTime: Long = 0L,
        var durationMs: Long = 320L,
        var emerged: Float = 0f,
        var absorbed: Float = 0f,
        var state: String = "waiting", // "waiting", "flying", "absorbing"
        var path: FloatArray = FloatArray(8)
    )

    private val activeDrops = mutableListOf<GooeyDrop>()

    // Pill spring state per tab index
    private data class PillState(
        var mass: Float = 0f,
        var scale: Float = 0f,
        var velocity: Float = 0f,
        var outgoing: Float = 0f
    )

    private val pillStates = mutableMapOf<Int, PillState>()
    private val tabRects = mutableListOf<RectF>()

    // Paints
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(225, 20, 20, 24)
    }

    private val frameRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(38, 255, 255, 255)
    }

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 179, 0) // Nothing signature yellow
    }

    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 179, 0)
    }

    private val dropTailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(255, 179, 0)
    }

    private val typefaceSatoshi = ResourcesCompat.getFont(context, R.font.satoshi_bold) ?: Typeface.DEFAULT_BOLD

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = typefaceSatoshi
        textSize = 12f * density
        letterSpacing = 0.04f
        textAlign = Paint.Align.CENTER
    }

    private val textActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = typefaceSatoshi
        textSize = 12f * density
        letterSpacing = 0.04f
        textAlign = Paint.Align.CENTER
        color = Color.rgb(18, 18, 22) // High contrast dark ink
    }

    private val textMutedColor = Color.rgb(200, 200, 205)

    private var lastTimeMs: Long = 0L

    // Haptics
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
        pillStates[0] = PillState(mass = 1f, scale = 1f)
    }

    fun setTab(index: Int, animate: Boolean = true) {
        if (index !in tabs.indices || index == selectedIndex) return
        val fromIndex = selectedIndex
        selectedIndex = index

        if (!animate) {
            pillStates.clear()
            pillStates[index] = PillState(mass = 1f, scale = 1f)
            activeDrops.clear()
            invalidate()
            return
        }

        emitDroplets(fromIndex, index)
        playTick()
        postInvalidateOnAnimation()
    }

    private fun emitDroplets(fromIndex: Int, toIndex: Int) {
        if (tabRects.size != tabs.size) return
        val fromRect = tabRects[fromIndex]
        val toRect = tabRects[toIndex]

        val now = System.currentTimeMillis()
        val sourceState = pillStates.getOrPut(fromIndex) { PillState(mass = 1f, scale = 1f) }
        sourceState.outgoing = sourceState.mass

        val targetState = pillStates.getOrPut(toIndex) { PillState(mass = 0f, scale = 0f) }

        val windowMs = (animationDurationMs * 0.16f).toLong()

        for (i in 0 until particleCount) {
            val mass = 1f / particleCount
            val startX = fromRect.centerX() + (Math.random().toFloat() - 0.5f) * fromRect.width() * 0.6f
            val startY = fromRect.centerY() + (Math.random().toFloat() - 0.5f) * fromRect.height() * 0.3f
            val endX = toRect.centerX() + (Math.random().toFloat() - 0.5f) * toRect.width() * 0.5f
            val endY = toRect.centerY() + (Math.random().toFloat() - 0.5f) * toRect.height() * 0.3f

            val swing = spread * (0.6f + Math.random().toFloat() * 0.6f)
            val sideSign = if (i % 2 == 0) 1f else -1f
            val ctrl1X = startX + (endX - startX) * 0.3f
            val ctrl1Y = startY + sideSign * swing
            val ctrl2X = startX + (endX - startX) * 0.7f
            val ctrl2Y = endY - sideSign * swing

            val drop = GooeyDrop(
                x = startX,
                y = startY,
                vx = 0f,
                vy = 0f,
                mass = mass,
                size = 0.85f + Math.random().toFloat() * 0.35f,
                sourceIndex = fromIndex,
                targetIndex = toIndex,
                leaveTime = now + (Math.random() * windowMs).toLong(),
                startTime = now,
                durationMs = (animationDurationMs * (0.75f + Math.random().toFloat() * 0.35f)).toLong(),
                path = floatArrayOf(startX, startY, ctrl1X, ctrl1Y, ctrl2X, ctrl2Y, endX, endY)
            )
            activeDrops.add(drop)
        }
    }

    private fun playTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            }
        } catch (_: Exception) {}
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val padH = 6f * density
        val padV = 5f * density
        val itemPadH = 14f * density
        val itemH = 34f * density

        var totalW = padH * 2
        for (tab in tabs) {
            val tw = textPaint.measureText(tab.label)
            totalW += tw + itemPadH * 2 + 4f * density
        }

        val totalH = itemH + padV * 2
        setMeasuredDimension(totalW.toInt(), totalH.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        measureTabRects()
    }

    private fun measureTabRects() {
        tabRects.clear()
        if (tabs.isEmpty() || width == 0) return

        val padH = 6f * density
        val padV = 5f * density
        val itemPadH = 14f * density
        val itemH = height - padV * 2

        var curX = padH
        for (tab in tabs) {
            val tw = textPaint.measureText(tab.label)
            val itemW = tw + itemPadH * 2
            val rect = RectF(curX, padV, curX + itemW, padV + itemH)
            tabRects.add(rect)
            curX += itemW + 4f * density
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val tx = event.x
            val ty = event.y
            for ((idx, rect) in tabRects.withIndex()) {
                if (rect.contains(tx, ty)) {
                    if (idx != selectedIndex) {
                        setTab(idx, animate = true)
                        onTabSelected?.invoke(idx, tabs[idx])
                    }
                    return true
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tabRects.size != tabs.size) {
            measureTabRects()
        }

        val now = System.currentTimeMillis()
        val dt = if (lastTimeMs > 0L) min(0.05f, (now - lastTimeMs) / 1000f) else 1f / 60f
        lastTimeMs = now

        // 1. Draw Frosted Nav Frame with Rim
        val frameR = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), frameR, frameR, framePaint)
        canvas.drawRoundRect(0.5f * density, 0.5f * density, width.toFloat() - 0.5f * density, height.toFloat() - 0.5f * density, frameR, frameR, frameRimPaint)

        // 2. Advance Droplets Simulation
        val emergeTime = 0.08f
        val absorbTime = 0.12f

        val itDrop = activeDrops.iterator()
        while (itDrop.hasNext()) {
            val drop = itDrop.next()
            if (drop.state == "waiting") {
                if (now >= drop.leaveTime) {
                    drop.state = "flying"
                    drop.startTime = now
                }
            }
            if (drop.state == "flying") {
                drop.emerged = min(1f, drop.emerged + dt / emergeTime)
                val srcState = pillStates[drop.sourceIndex]
                if (srcState != null && srcState.mass > 0f) {
                    val step = min(drop.mass * (dt / emergeTime), srcState.mass)
                    srcState.mass = max(0f, srcState.mass - step)
                }

                val t = ((now - drop.startTime).toFloat() / drop.durationMs).coerceIn(0f, 1f)
                val eased = travelEase(t)
                val p = drop.path
                val curX = cubicBezier(p[0], p[2], p[4], p[6], eased)
                val curY = cubicBezier(p[1], p[3], p[5], p[7], eased)

                if (dt > 0) {
                    drop.vx = (curX - drop.x) / dt
                    drop.vy = (curY - drop.y) / dt
                }
                drop.x = curX
                drop.y = curY

                if (t >= 1f) {
                    drop.state = "absorbing"
                }
            }
            if (drop.state == "absorbing") {
                val step = min(1f - drop.absorbed, dt / absorbTime)
                drop.absorbed += step
                drop.vx *= 0.8f
                drop.vy *= 0.8f
                val targetState = pillStates.getOrPut(drop.targetIndex) { PillState() }
                targetState.mass = min(1f, targetState.mass + drop.mass * step)
                if (drop.absorbed >= 1f) {
                    itDrop.remove()
                }
            }
        }

        // 3. Advance Pill Spring Oscillations (React Bits spring physics)
        val zeta = 1f - 0.72f * wobble.coerceIn(0f, 1f) // 0.64
        val omega = 26f
        val h = dt / 2f
        for (i in 0..1) {
            for ((idx, state) in pillStates) {
                state.velocity += (omega * omega * (state.mass - state.scale) - 2f * zeta * omega * state.velocity) * h
                state.scale += state.velocity * h
            }
        }

        // 4. Draw Active & Source Pills
        for ((idx, state) in pillStates) {
            if (idx !in tabRects.indices) continue
            val rect = tabRects[idx]
            val scale = state.scale.coerceIn(0f, 1.25f)
            if (scale > 0.005f) {
                val pillW = rect.width() * scale
                val pillH = rect.height() * (0.45f + 0.55f * sqrt(scale))
                val px = rect.centerX() - pillW / 2f
                val py = rect.centerY() - pillH / 2f
                val pr = pillH / 2f
                canvas.drawRoundRect(px, py, px + pillW, py + pillH, pr, pr, pillPaint)
            }
        }

        // 5. Draw Liquid Flying Droplets with Velocity Tails
        for (drop in activeDrops) {
            if (drop.state == "waiting") continue
            val radius = 4.5f * density * drop.size * min(drop.emerged, 1f - drop.absorbed)
            if (radius <= 0.5f) continue

            val speed = hypot(drop.vx, drop.vy)
            if (speed > 12f * density) {
                val tailLen = min(radius * 1.5f, speed * 0.016f)
                val tx = (drop.vx / speed) * tailLen
                val ty = (drop.vy / speed) * tailLen
                dropTailPaint.strokeWidth = radius * 1.8f
                canvas.drawLine(drop.x, drop.y, drop.x - tx, drop.y - ty, dropTailPaint)
            }
            canvas.drawCircle(drop.x, drop.y, radius, dropPaint)
        }

        // 6. Draw Tab Text Labels with Color Transitions
        for ((idx, tab) in tabs.withIndex()) {
            if (idx !in tabRects.indices) continue
            val rect = tabRects[idx]
            val textX = rect.centerX()
            val textY = rect.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2f)

            val pState = pillStates[idx]
            val activeP = (pState?.scale ?: 0f).coerceIn(0f, 1f)

            if (activeP > 0.05f) {
                // High contrast dark text inside active pill
                textActivePaint.alpha = (activeP * 255).toInt().coerceIn(0, 255)
                canvas.drawText(tab.label, textX, textY, textActivePaint)
            }

            if (activeP < 0.95f) {
                // Muted gray text outside pill
                textPaint.color = textMutedColor
                textPaint.alpha = ((1f - activeP) * 255).toInt().coerceIn(0, 255)
                canvas.drawText(tab.label, textX, textY, textPaint)
            }
        }

        // Check if animation is still settling
        val needInvalidate = activeDrops.isNotEmpty() || pillStates.values.any {
            abs(it.mass - it.scale) > 0.005f || abs(it.velocity) > 0.01f
        }
        if (needInvalidate) {
            postInvalidateOnAnimation()
        }
    }

    private fun travelEase(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    }

    private fun cubicBezier(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val u = 1f - t
        return u * u * u * p0 + 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t * p3
    }
}

