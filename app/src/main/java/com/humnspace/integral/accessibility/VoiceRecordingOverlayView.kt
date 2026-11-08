package com.humnspace.integral.accessibility

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sin

/**
 * Premium Nothing OS-inspired Voice Recording Overlay.
 *
 * Expands from the hardware button press coordinates with spring physics,
 * records voice notes directly to high-quality audio files (.m4a), displays
 * live duration, pulsing REC indicator, dynamic waveform, and a dedicated STOP button.
 */
class VoiceRecordingOverlayView(
    context: Context,
    private val onDismissComplete: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE)

    // Design Tokens
    private val yellowAccent = Color.rgb(255, 179, 0) // Nothing signature yellow #F7D915
    private val charcoalGlassA = 215
    private val charcoalGlassR = 14
    private val charcoalGlassG = 14
    private val charcoalGlassB = 18

    enum class State {
        IDLE,
        EXPANDING,
        RECORDING,
        COLLAPSING,
        DISMISSED
    }

    private var state = State.IDLE

    // Geometry
    private val buttonBounds = RectF()
    private val compressedButtonBounds = RectF()
    private val targetOverlayRect = RectF()
    private var initialCornerRadius = 9f * density
    private var targetCornerRadius = 24f * density
    private val buttonCenter = PointF()

    // Action buttons bounds within target overlay
    private val stopButtonRect = RectF()
    private val pauseButtonRect = RectF()
    private val discardButtonRect = RectF()

    // Progress & Living Waveform
    private var expansionProgress = 0f
    private var collapseProgress = 1f
    private var audioLevelNorm = 0f
    private var smoothedAudioLevel = 0f

    private var isSaveSequenceActive = false
    private var saveContractProgress = 1f
    private var saveCheckmarkAlpha = 0f
    private var savedTimestampText: String? = null

    private val barValues = FloatArray(12) { 0.05f }
    private val barWeights = floatArrayOf(
        0.35f, 0.55f, 0.85f, 1.0f, 0.90f, 0.70f,
        0.65f, 0.92f, 1.05f, 0.80f, 0.60f, 0.38f
    )

    // Recording internals
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var recordingStartTime = 0L
    private var lastResumeTime = 0L
    private var accumulatedDurationMs = 0L
    private var recordingDurationMs = 0L
    private var isRecording = false
    private var isPaused = false

    // Active Animators
    private var activeAnimator: ValueAnimator? = null

    // Paints
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }
    private val stopBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pauseBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val morphPath = Path()
    private val tempRect = RectF()

    val isShowing: Boolean get() = state != State.DISMISSED && state != State.IDLE
    val isCollapsing: Boolean get() = state == State.COLLAPSING

    init {
        isClickable = true
        isFocusable = true
    }

    private fun calculateCoordinates(widthPx: Float, heightPx: Float) {
        val originX = prefs.getFloat(EssentialKeyService.ANIMATION_ORIGIN_X, EssentialKeyService.DEFAULT_ORIGIN_X).coerceIn(0.02f, 0.98f)
        val originY = prefs.getFloat(EssentialKeyService.ANIMATION_ORIGIN_Y, EssentialKeyService.DEFAULT_ORIGIN_Y).coerceIn(0.02f, 0.98f)
        val fromRight = originX >= 0.5f

        val btnW = 18f * density
        val btnH = 50f * density
        val cy = heightPx * originY
        val outerRight = if (fromRight) widthPx - 2f * density else 2f * density + btnW
        val outerLeft = if (fromRight) outerRight - btnW else 2f * density
        buttonBounds.set(outerLeft, cy - btnH / 2f, outerRight, cy + btnH / 2f)
        initialCornerRadius = btnW / 2f
        buttonCenter.set(buttonBounds.centerX(), buttonBounds.centerY())

        val compScale = 0.93f
        val compHalfW = (buttonBounds.width() * compScale) / 2f
        val compHalfH = (buttonBounds.height() * compScale) / 2f
        compressedButtonBounds.set(
            buttonCenter.x - compHalfW,
            buttonCenter.y - compHalfH,
            buttonCenter.x + compHalfW,
            buttonCenter.y + compHalfH
        )

        // Expanded overlay card: physical recording device layout (275dp width by 66dp height)
        val targetWidth = 275f * density
        val targetHeight = 66f * density
        val margin = 14f * density

        val targetCenterX = if (fromRight) {
            (widthPx - margin - targetWidth / 2f).coerceAtLeast(targetWidth / 2f + margin)
        } else {
            (margin + targetWidth / 2f).coerceAtMost(widthPx - targetWidth / 2f - margin)
        }
        val targetCenterY = buttonCenter.y.coerceIn(targetHeight / 2f + margin, heightPx - targetHeight / 2f - margin)

        targetOverlayRect.set(
            targetCenterX - targetWidth / 2f,
            targetCenterY - targetHeight / 2f,
            targetCenterX + targetWidth / 2f,
            targetCenterY + targetHeight / 2f
        )

        // Position Discard/Close button on the far left
        val discardDiameter = 32f * density
        val discardLeft = targetOverlayRect.left + 10f * density
        discardButtonRect.set(
            discardLeft,
            targetOverlayRect.centerY() - discardDiameter / 2f,
            discardLeft + discardDiameter,
            targetOverlayRect.centerY() + discardDiameter / 2f
        )

        // Position Stop button inside the right side of the card
        val stopDiameter = 40f * density
        val stopRight = targetOverlayRect.right - 10f * density
        stopButtonRect.set(
            stopRight - stopDiameter,
            targetOverlayRect.centerY() - stopDiameter / 2f,
            stopRight,
            targetOverlayRect.centerY() + stopDiameter / 2f
        )

        // Position Pause/Resume button between waveform and stop button
        val pauseDiameter = 34f * density
        val pauseRight = stopButtonRect.left - 8f * density
        pauseButtonRect.set(
            pauseRight - pauseDiameter,
            targetOverlayRect.centerY() - pauseDiameter / 2f,
            pauseRight,
            targetOverlayRect.centerY() + pauseDiameter / 2f
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) calculateCoordinates(w.toFloat(), h.toFloat())
    }

    fun startRecordingFlow() {
        if (state != State.IDLE) return
        state = State.EXPANDING
        IntegralAcoustics.playOverlayOpen(context)

        post {
            calculateCoordinates(width.toFloat(), height.toFloat())
            animateExpansion {
                state = State.RECORDING
                startMediaRecording()
            }
        }
    }

    private fun startMediaRecording() {
        try {
            val dir = VoiceRecordingManager.getRecordingsDir(context)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "REC_$timeStamp.m4a")
            outputFile = file

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            isPaused = false
            recordingStartTime = System.currentTimeMillis()
            lastResumeTime = recordingStartTime
            accumulatedDurationMs = 0L
            performHapticClick()
            IntegralAcoustics.playRecordStart(context)

            val prefs = context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(EssentialKeyService.USE_GLYPHS, true) && prefs.getBoolean(EssentialKeyService.GLYPHS_VOICE_REACTIVITY, true)) {
                NothingGlyphController.startVoiceRecordingSpectrum()
            }
            scheduleMeterTick()
        } catch (e: Exception) {
            Toast.makeText(context, "Could not start audio recorder", Toast.LENGTH_SHORT).show()
            dismissWithCollapse()
        }
    }

    private val meterRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            if (!isPaused) {
                try {
                    val amp = mediaRecorder?.maxAmplitude ?: 0
                    val norm = (amp / 22000f).coerceIn(0f, 1f)
                    val boosted = kotlin.math.sqrt(norm.toDouble()).toFloat()
                    audioLevelNorm = boosted
                    NothingGlyphController.setVoiceSpectrumLevel(boosted)
                } catch (_: Exception) {}

                recordingDurationMs = accumulatedDurationMs + (System.currentTimeMillis() - lastResumeTime)
            }
            invalidate()
            handler.postDelayed(this, 35L) // ~30fps audio meter
        }
    }

    fun togglePauseResume() {
        if (!isRecording) return
        if (isPaused) {
            // Resume recording
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mediaRecorder?.resume()
                }
            } catch (_: Exception) {}
            isPaused = false
            lastResumeTime = System.currentTimeMillis()
            NothingGlyphController.startVoiceRecordingSpectrum()
            performHapticClick()
            IntegralAcoustics.playRecordResume(context)
            scheduleMeterTick()
        } else {
            // Pause recording
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mediaRecorder?.pause()
                }
            } catch (_: Exception) {}
            isPaused = true
            accumulatedDurationMs += (System.currentTimeMillis() - lastResumeTime)
            audioLevelNorm = 0.05f
            smoothedAudioLevel = 0.05f
            NothingGlyphController.stopVoiceRecordingSpectrum()
            performHapticClick()
            IntegralAcoustics.playRecordPause(context)
        }
        invalidate()
    }

    private fun scheduleMeterTick() {
        handler.removeCallbacks(meterRunnable)
        handler.post(meterRunnable)
    }

    fun stopAndSave() {
        if (state == State.COLLAPSING || state == State.DISMISSED || isSaveSequenceActive) return
        isSaveSequenceActive = true
        isRecording = false
        handler.removeCallbacks(meterRunnable)

        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null

        if (isPaused) {
            recordingDurationMs = accumulatedDurationMs
        } else {
            recordingDurationMs = accumulatedDurationMs + (System.currentTimeMillis() - lastResumeTime)
        }

        val durSeconds = (recordingDurationMs / 1000).coerceAtLeast(1)
        val mins = durSeconds / 60
        val secs = durSeconds % 60
        val durText = String.format(Locale.US, "%02d:%02d", mins, secs)
        savedTimestampText = "SAVED $durText"

        NothingGlyphController.stopVoiceRecordingSpectrum()
        NothingGlyphController.onRecordingSaved()
        BadgerMotionEngine.Haptics.performGestureConfirmed(context)
        IntegralAcoustics.playRecordSaved(context)

        // 5-Step Tactile Save Sequence:
        // 1. Stop button contracts (80ms)
        // 2. Waveform freezes and amber confirmation checkmark blooms (100ms)
        // 3. Duration transforms to saved timestamp
        // 4. Brief hold so user perceives the confirmation
        // 5. Magnetic retraction into bezel (190ms)
        val contractAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 80L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                saveContractProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
        }

        val checkAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 100L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                saveCheckmarkAlpha = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
        }

        val seq = AnimatorSet()
        seq.playSequentially(contractAnim, checkAnim)
        seq.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                handler.postDelayed({
                    dismissWithCollapse()
                }, 280L)
            }
        })
        seq.start()
    }

    fun discardRecording() {
        if (state == State.COLLAPSING || state == State.DISMISSED) return
        isRecording = false
        handler.removeCallbacks(meterRunnable)

        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null

        outputFile?.delete()
        outputFile = null

        NothingGlyphController.stopVoiceRecordingSpectrum()
        performHapticClick()
        Toast.makeText(context, "Recording discarded", Toast.LENGTH_SHORT).show()

        dismissWithCollapse()
    }

    private fun animateExpansion(onEnd: () -> Unit) {
        activeAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = android.view.animation.OvershootInterpolator(1.35f)
            addUpdateListener { va ->
                expansionProgress = va.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    expansionProgress = 1f
                    onEnd()
                }
            })
        }
        activeAnimator = animator
        animator.start()
    }

    fun dismissWithCollapse() {
        if (state == State.COLLAPSING || state == State.DISMISSED) return
        state = State.COLLAPSING
        IntegralAcoustics.playOverlayDismiss(context)
        isRecording = false
        handler.removeCallbacks(meterRunnable)

        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        NothingGlyphController.stopVoiceRecordingSpectrum()

        activeAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = BadgerMotionEngine.Timings.CHASSIS_RETRACTION
            interpolator = BadgerMotionEngine.Physics.MagneticRetract
            addUpdateListener { va ->
                collapseProgress = va.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    state = State.DISMISSED
                    onDismissComplete()
                }
            })
        }
        activeAnimator = animator
        animator.start()
    }

    private fun performHapticClick() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            }
        } catch (_: Exception) {}
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (state != State.RECORDING) return true

        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y

            // Check if tapped STOP button
            if (stopButtonRect.contains(x, y)) {
                stopAndSave()
                return true
            }

            // Check if tapped Pause/Resume button
            if (pauseButtonRect.contains(x, y)) {
                togglePauseResume()
                return true
            }

            // Check if tapped Discard button
            if (discardButtonRect.contains(x, y)) {
                discardRecording()
                return true
            }

            // Tapping outside card closes and saves
            if (!targetOverlayRect.contains(x, y)) {
                stopAndSave()
                return true
            }
        }
        return true
    }

    /**
     * Exit animation matching media_1793869566953_9e52b4dd.png:
     * Morphs and shrinks the voice recording overlay into a sleek vertical yellow capsule
     * on the right screen edge aligned with the physical Essential Key, draws the bezel edge line,
     * and slides right off-screen into the button.
     */
    private fun drawCollapsingRecordingOverlay(canvas: Canvas) {
        val c = collapseProgress.coerceIn(0f, 1f)
        if (c <= 0f) return

        val t = 1f - c // 0f at settled state, 1f when fully exited

        // Proportional capsule geometry tailored for voice recording overlay
        val capW = 34f * density
        val capH = 62f * density
        val capCorner = 14f * density
        val capStroke = 2.4f * density
        val cy = buttonCenter.y

        val restingCapRight = width - 14f * density
        val restingCapLeft = restingCapRight - capW
        val restingCapTop = cy - capH / 2f
        val restingCapBottom = cy + capH / 2f

        // Phase 1 (0f -> 0.52f): Morph from current resting bounds into capsule
        // Phase 2 (0.52f -> 1.0f): Slide right into the hardware button off-screen
        val morphProgress = (t / 0.52f).coerceIn(0f, 1f)
        val easedMorph = (Math.sin(morphProgress * Math.PI / 2)).toFloat()

        val slideProgress = ((t - 0.52f) / 0.48f).coerceIn(0f, 1f)
        val slideX = slideProgress * slideProgress * (85f * density)
        val exitAlpha = (1f - slideProgress * slideProgress).coerceIn(0f, 1f)

        val curLeft = (targetOverlayRect.left + (restingCapLeft - targetOverlayRect.left) * easedMorph) + slideX
        val curTop = targetOverlayRect.top + (restingCapTop - targetOverlayRect.top) * easedMorph
        val curRight = (targetOverlayRect.right + (restingCapRight - targetOverlayRect.right) * easedMorph) + slideX
        val curBottom = targetOverlayRect.bottom + (restingCapBottom - targetOverlayRect.bottom) * easedMorph
        val curCorner = targetCornerRadius + (capCorner - targetCornerRadius) * easedMorph

        tempRect.set(curLeft, curTop, curRight, curBottom)
        morphPath.reset()
        morphPath.addRoundRect(tempRect, curCorner, curCorner, Path.Direction.CW)

        // Smooth fill transition: from card charcoal glass (215 alpha) to 45% black opacity (115 alpha)
        val baseFillA = charcoalGlassA.toFloat()
        val targetFillA = 115f
        val curFillA = ((baseFillA + (targetFillA - baseFillA) * easedMorph) * exitAlpha).toInt().coerceIn(0, 255)
        val curFillR = (charcoalGlassR * (1f - easedMorph)).toInt()
        val curFillG = (charcoalGlassG * (1f - easedMorph)).toInt()
        val curFillB = (charcoalGlassB * (1f - easedMorph)).toInt()
        fillPaint.color = Color.argb(curFillA, curFillR, curFillG, curFillB)
        canvas.drawPath(morphPath, fillPaint)

        // Bold yellow capsule border
        val borderAlpha = ((160f + (255f - 160f) * easedMorph) * exitAlpha).toInt().coerceIn(0, 255)
        if (borderAlpha > 0) {
            borderPaint.strokeWidth = (1.2f * density + (capStroke - 1.2f * density) * easedMorph)
            borderPaint.color = Color.argb(borderAlpha, 255, 179, 0)
            canvas.drawPath(morphPath, borderPaint)
        }

        // Hardware key vertical yellow edge line along bezel
        if (easedMorph > 0.05f) {
            val edgeLineA = (255f * easedMorph * exitAlpha).toInt().coerceIn(0, 255)
            if (edgeLineA > 0) {
                borderPaint.strokeWidth = 2.5f * density
                borderPaint.color = Color.argb(edgeLineA, 255, 179, 0)
                val lineH = 140f * density
                val lineRight = width.toFloat() + slideX
                val lineLeft = lineRight - 2.5f * density
                val edgeRect = RectF(lineLeft, cy - lineH / 2f, lineRight, cy + lineH / 2f)
                canvas.drawRoundRect(edgeRect, 1.25f * density, 1.25f * density, borderPaint)
            }
        }

        // Smooth fade out of inner controls
        val contentAlpha = (1f - (t / 0.25f)).coerceIn(0f, 1f) * exitAlpha
        val alphaInt = (255 * contentAlpha).toInt()
        if (alphaInt > 0) {
            drawDiscardButton(canvas, alphaInt)
            drawRecTimer(canvas, alphaInt)
            drawAudioWaveform(canvas, alphaInt)
            drawStopButton(canvas, alphaInt)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (state == State.DISMISSED || state == State.IDLE) return

        if (state == State.COLLAPSING) {
            drawCollapsingRecordingOverlay(canvas)
            return
        }

        val p = expansionProgress
        if (p <= 0.001f) return


        // 2. Morph between buttonBounds and targetOverlayRect
        val curLeft = lerp(compressedButtonBounds.left, targetOverlayRect.left, p)
        val curTop = lerp(compressedButtonBounds.top, targetOverlayRect.top, p)
        val curRight = lerp(compressedButtonBounds.right, targetOverlayRect.right, p)
        val curBottom = lerp(compressedButtonBounds.bottom, targetOverlayRect.bottom, p)
        val curRadius = lerp(initialCornerRadius, targetCornerRadius, p)

        tempRect.set(curLeft, curTop, curRight, curBottom)

        // Draw outer glass surface
        fillPaint.color = Color.argb((charcoalGlassA * p).toInt(), charcoalGlassR, charcoalGlassG, charcoalGlassB)
        canvas.drawRoundRect(tempRect, curRadius, curRadius, fillPaint)

        // Draw yellow accent border
        borderPaint.color = Color.argb((160 * p).toInt(), 255, 179, 0)
        borderPaint.strokeWidth = 1.2f * density
        canvas.drawRoundRect(tempRect, curRadius, curRadius, borderPaint)

        // Draw contents only when expanded
        if (p > 0.65f) {
            val contentAlpha = ((p - 0.65f) / 0.35f).coerceIn(0f, 1f)
            val alphaInt = (255 * contentAlpha).toInt()

            // 1. Discard button
            drawDiscardButton(canvas, alphaInt)

            // 2. Pulsing yellow REC dot & Timer
            drawRecTimer(canvas, alphaInt)

            // 3. Dynamic audio waveform bars
            drawAudioWaveform(canvas, alphaInt)

            // 4. Pause / Resume button
            drawPauseButton(canvas, alphaInt)

            // 5. Studio STOP button (one-tap finish)
            drawStopButton(canvas, alphaInt)
        }
    }

    private fun drawDiscardButton(canvas: Canvas, alpha: Int) {
        val cx = discardButtonRect.centerX()
        val cy = discardButtonRect.centerY()
        val r = discardButtonRect.width() / 2f

        // Soft black glass disc
        fillPaint.color = Color.argb((alpha * 0.45f).toInt(), 20, 20, 24)
        canvas.drawCircle(cx, cy, r, fillPaint)

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = Color.argb((alpha * 0.25f).toInt(), 255, 255, 255)
        }
        canvas.drawCircle(cx, cy, r - 0.5f * density, border)

        // icon in soft white
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = Color.argb((alpha * 0.85f).toInt(), 220, 220, 225)
            strokeCap = Paint.Cap.ROUND
        }
        val arm = 4.5f * density
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, strokePaint)
        canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, strokePaint)
    }

    private fun drawRecTimer(canvas: Canvas, alpha: Int) {
        val leftX = discardButtonRect.right + 10f * density
        val cy = targetOverlayRect.centerY()

        val totalMs = recordingDurationMs.coerceAtLeast(0L)
        val totalSecs = totalMs / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        val tenths = (totalMs % 1000) / 100
        val timeStr = String.format(Locale.US, "%02d:%02d.%d", mins, secs, tenths)

        // Pulsing amber REC dot or solid pause indicator
        val pulse = if (isPaused) 0.5f else (kotlin.math.sin(System.currentTimeMillis() * 0.007) * 0.35f + 0.65f).toFloat()
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (isPaused) Color.argb((alpha * 0.6f).toInt(), 180, 180, 190) else Color.argb((alpha * pulse).toInt(), 255, 179, 0)
        }
        canvas.drawCircle(leftX + 4f * density, cy - 8f * density, 3.5f * density, dotPaint)

        // Large elapsed-time indicator
        textPaint.apply {
            color = Color.argb(alpha, 255, 255, 255)
            textSize = 16.5f * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
            letterSpacing = 0.02f
        }
        canvas.drawText(timeStr, leftX + 13f * density, cy - 3f * density, textPaint)

        // Subtitle status: "REC NOTE" or "PAUSED"
        textPaint.apply {
            color = if (isPaused) Color.argb((alpha * 0.7f).toInt(), 180, 180, 190) else Color.argb((alpha * 0.9f).toInt(), 255, 179, 0)
            textSize = 9f * density
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(if (isPaused) "PAUSED" else "REC NOTE", leftX + 4f * density, cy + 12f * density, textPaint)
    }

    private fun drawAudioWaveform(canvas: Canvas, alpha: Int) {
        val waveStartX = discardButtonRect.right + 82f * density
        val waveEndX = pauseButtonRect.left - 8f * density
        val cy = targetOverlayRect.centerY()
        val availableW = waveEndX - waveStartX

        if (availableW <= 10f || isSaveSequenceActive) return

        val targetAudio = if (isPaused) 0.04f else audioLevelNorm.coerceIn(0.04f, 1.0f)
        val smoothingFactor = if (targetAudio > smoothedAudioLevel) 0.42f else 0.18f
        smoothedAudioLevel += (targetAudio - smoothedAudioLevel) * smoothingFactor

        val numBars = 12
        val barW = 2.0f * density
        val spacing = (availableW - (numBars * barW)) / (numBars + 1)

        for (i in 0 until numBars) {
            val naturalScale = barWeights[i]
            val prevVal = if (i > 0) barValues[i - 1] else smoothedAudioLevel
            val nextVal = if (i < numBars - 1) barValues[i + 1] else smoothedAudioLevel
            val barTarget = (smoothedAudioLevel * 0.70f + (prevVal + nextVal) * 0.15f) * naturalScale

            val barSmoothing = if (barTarget > barValues[i]) 0.45f else 0.16f
            barValues[i] += (barTarget - barValues[i]) * barSmoothing

            val minH = 2.8f * density
            val maxH = 24f * density
            val h = (minH + (barValues[i] * 20f * density)).coerceIn(minH, maxH)

            val bx = waveStartX + spacing + i * (barW + spacing)
            wavePaint.color = if (isPaused) Color.argb((alpha * 0.35f).toInt(), 200, 200, 210) else Color.argb(alpha, 255, 179, 0)
            tempRect.set(bx, cy - h / 2f, bx + barW, cy + h / 2f)
            canvas.drawRoundRect(tempRect, barW / 2f, barW / 2f, wavePaint)
        }
    }

    private fun drawPauseButton(canvas: Canvas, alpha: Int) {
        if (isSaveSequenceActive) return
        val cx = pauseButtonRect.centerX()
        val cy = pauseButtonRect.centerY()
        val r = pauseButtonRect.width() / 2f

        // Soft matte disc
        pauseBtnPaint.style = Paint.Style.FILL
        pauseBtnPaint.color = Color.argb((alpha * 0.55f).toInt(), 24, 24, 28)
        canvas.drawCircle(cx, cy, r, pauseBtnPaint)

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = Color.argb((alpha * 0.35f).toInt(), 255, 255, 255)
        }
        canvas.drawCircle(cx, cy, r - 0.5f * density, border)

        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb((alpha * 0.95f).toInt(), 255, 255, 255)
        }

        if (isPaused) {
            // Draw Play triangle
            val triW = 5f * density
            val triH = 6f * density
            val triPath = Path().apply {
                moveTo(cx - triW / 2f + 1f * density, cy - triH)
                lineTo(cx + triW + 1f * density, cy)
                lineTo(cx - triW / 2f + 1f * density, cy + triH)
                close()
            }
            canvas.drawPath(triPath, iconPaint)
        } else {
            // Draw two vertical pause bars
            val barW = 2.2f * density
            val barH = 10f * density
            val gap = 3f * density
            tempRect.set(cx - gap / 2f - barW, cy - barH / 2f, cx - gap / 2f, cy + barH / 2f)
            canvas.drawRoundRect(tempRect, 1.2f * density, 1.2f * density, iconPaint)
            tempRect.set(cx + gap / 2f, cy - barH / 2f, cx + gap / 2f + barW, cy + barH / 2f)
            canvas.drawRoundRect(tempRect, 1.2f * density, 1.2f * density, iconPaint)
        }
    }

    private fun drawStopButton(canvas: Canvas, alpha: Int) {
        val cx = stopButtonRect.centerX()
        val cy = stopButtonRect.centerY()
        val r = stopButtonRect.width() / 2f

        val scale = if (isSaveSequenceActive) saveContractProgress else 1f
        if (scale > 0.05f) {
            val activeR = r * scale
            // 1. Deep black circular disc
            stopBtnPaint.style = Paint.Style.FILL
            stopBtnPaint.color = Color.argb(alpha, 12, 12, 15)
            canvas.drawCircle(cx, cy, activeR, stopBtnPaint)

            // 2. Yellow accent border
            stopBtnPaint.style = Paint.Style.STROKE
            stopBtnPaint.strokeWidth = 2f * density * scale
            stopBtnPaint.color = Color.argb(alpha, 255, 179, 0)
            canvas.drawCircle(cx, cy, (activeR - 1f * density).coerceAtLeast(1f), stopBtnPaint)

            // 3. Inner solid yellow stop square
            val sqPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(alpha, 255, 179, 0)
            }
            val squareHalf = 6f * density * scale
            tempRect.set(cx - squareHalf, cy - squareHalf, cx + squareHalf, cy + squareHalf)
            canvas.drawRoundRect(tempRect, 2.5f * density * scale, 2.5f * density * scale, sqPaint)
        }

        // 4. Amber Confirmation Checkmark & Saved Timestamp
        if (isSaveSequenceActive && saveCheckmarkAlpha > 0.05f) {
            val aInt = (255 * saveCheckmarkAlpha).toInt().coerceIn(0, 255)
            val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(aInt, 255, 179, 0)
                style = Paint.Style.STROKE
                strokeWidth = 2.4f * density
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val checkPath = Path().apply {
                moveTo(cx - 5f * density, cy)
                lineTo(cx - 1.5f * density, cy + 3.5f * density)
                lineTo(cx + 5.5f * density, cy - 3.5f * density)
            }
            canvas.drawPath(checkPath, checkPaint)

            savedTimestampText?.let { txt ->
                val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(aInt, 255, 255, 255)
                    textSize = 11.5f * density
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.05f
                }
                canvas.drawText(txt, targetOverlayRect.centerX() - 32f * density, targetOverlayRect.centerY() + 4f * density, txtPaint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isRecording = false
        handler.removeCallbacks(meterRunnable)
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        com.humnspace.integral.accessibility.NothingGlyphController.stopVoiceRecordingSpectrum()
        activeAnimator?.cancel()
        activeAnimator = null
    }

    private fun lerp(start: Float, stop: Float, amount: Float): Float =
        start + (stop - start) * amount
}

