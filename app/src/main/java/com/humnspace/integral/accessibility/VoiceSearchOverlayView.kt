package com.humnspace.integral.accessibility

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.humnspace.integral.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Premium Nothing OS-inspired Voice Search Overlay.
 *
 * Requirements:
 * 1. Clean Bezel Indicator Handling:
 *    - Releasing the hardware key immediately fades out and dismisses the yellow line indication.
 * 2. Live Text Streaming:
 *    - Words stream onto the layout layer in real time with crisp high-contrast typography animation.
 *    - Each recognized word scales up subtly (0.95 to 1.0) and transitions from translucent gray
 *      (#66FFFFFF / #80B0B0C0) to opaque solid white (#FFFFFF) as speech-to-text dictation locks it in.
 * 3. Continuous Fluid Gesture (The "Out" & Transition Animation):
 *    - Structural Expansion: The compact Voice Pill instantly accelerates downward and outward.
 *      Its border-radius corners flatten out (24dp/27dp down to 0dp) into a full-bleed viewport.
 *    - The Relay Handshake: Captured query is passed directly into Chrome deep-link URL protocol scheme
 *      (googlechrome://navigate?url=https://www.google.com/search?q=...).
 *    - The Content Reveal: Browser assets (Omnibox, navigation tabs, and rendered SERP cards)
 *      fade in simultaneously with micro-staggered layout (staggerChildren: 0.03).
 */
class VoiceSearchOverlayView(
    context: Context,
    private val onDismissComplete: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE)

    // Fonts
    private val satoshiBold: Typeface = ResourcesCompat.getFont(context, R.font.satoshi_bold) ?: Typeface.DEFAULT_BOLD
    private val satoshiMedium: Typeface = ResourcesCompat.getFont(context, R.font.satoshi_medium) ?: Typeface.DEFAULT_BOLD
    private val satoshiRegular: Typeface = ResourcesCompat.getFont(context, R.font.satoshi_regular) ?: Typeface.DEFAULT

    // Colors
    private val badgerYellow = Color.rgb(255, 179, 0) // #F7D915 Nothing / Badger Yellow
    private val charcoalGlassA = 220                    // ~75% opacity (255 * 0.75 = 191..220)
    private val charcoalGlassR = 14
    private val charcoalGlassG = 14
    private val charcoalGlassB = 18

    // State Machine
    enum class State {
        IDLE,
        PRESS_COMPRESSION,
        EXPANDING,
        RECORDING,
        TRANSCRIBING,
        TRANSFORMING_TO_REMINDER,
        EXPANDING_TO_BROWSER,
        COLLAPSING,
        DISMISSED
    }

    private var state = State.IDLE
    val isShowing: Boolean get() = state != State.IDLE && state != State.DISMISSED
    val isRecording: Boolean get() = state == State.RECORDING || state == State.EXPANDING

    // Coordinates
    private val buttonBounds = RectF()
    private val compressedButtonBounds = RectF()
    private val targetOverlayRect = RectF()
    private var initialCornerRadius = 9f * density
    private var targetCornerRadius = 27f * density
    private var buttonCenter = PointF()

    // Dynamic capsule sizing
    private var currentCapsuleWidth = 180f * density
    private var targetCapsuleWidth = 180f * density
    private var reminderCardRect = RectF()
    private var reminderTransformProgress = 0f
    private var savedReminderTitle = ""
    private var savedReminderTime = ""

    // Progress values
    private var compressionProgress = 0f
    private var expansionProgress = 0f
    private var collapseProgress = 1f
    private var browserExpandProgress = 0f
    private var audioLevelRms = 0f
    private var smoothedAudioLevel = 0f
    private var textScrollX = 0f
    private var cursorAnimProgress = 0f

    // Live Text Streaming Structure (Continuous Text-Reveal)
    data class StreamingWord(
        var text: String,
        var isLocked: Boolean = false,
        var currentScale: Float = 0.92f, // Fades in from 0.92 scale
        var currentAlphaProgress: Float = 0f, // 0f = translucent gray, 1f = solid white
        var oldText: String? = null,
        var oldAlphaProgress: Float = 0f
    )

    private val streamingWords = mutableListOf<StreamingWord>()
    private var wordAnimator: ValueAnimator? = null

    // Voice recognition
    private var speechRecognizer: SpeechRecognizer? = null
    private var currentTranscript = ""
    private var statusText = "Say it..."
    private var hasSubmitted = false
    private var hasRelayHandshaked = false

    // Animators
    private var activeAnimator: ValueAnimator? = null
    private val safetyTimeoutRunnable = Runnable {
        if (!hasSubmitted && (state == State.RECORDING || state == State.TRANSCRIBING)) {
            finishAndSearch(currentTranscript)
        }
    }

    // Paints
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = satoshiBold
        isSubpixelText = true
    }
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = satoshiBold
        isSubpixelText = true
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }

    private val morphPath = Path()
    private val tempRect = RectF()

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
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

        // Target expanded overlay pill: 180dp width by 54dp height with 27dp corner radius
        val targetWidth = 180f * density
        val targetHeight = 54f * density
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
        targetCornerRadius = targetHeight / 2f
    }

    /**
     * Start the voice search sequence: tactile press, outward spring push, and audio recording.
     */
    fun start() {
        if (state != State.IDLE) return
        performHaptic(VibrationEffect.EFFECT_CLICK)

        state = State.PRESS_COMPRESSION
        activeAnimator?.cancel()

        activeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 85L
            interpolator = LinearInterpolator()
            addUpdateListener {
                compressionProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    startExpansionPhase()
                }
            })
            start()
        }
    }

    private fun startExpansionPhase() {
        state = State.EXPANDING
        activeAnimator?.cancel()
        ScreenAnalysisOverlay.dismissButtonPress()

        IntegralAcoustics.playVoiceStart(context)
        startSpeechRecording()

        activeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = android.view.animation.OvershootInterpolator(1.35f)
            addUpdateListener {
                expansionProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (state == State.EXPANDING) {
                        state = State.RECORDING
                    }
                    postInvalidateOnAnimation()
                }
            })
            start()
        }
    }

    private fun startSpeechRecording() {
        NothingGlyphController.startVoiceRecordingSpectrum()
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                statusText = "Voice input unavailable"
                postInvalidateOnAnimation()
                return
            }

            // Direct audio spectrum without volume indication glyph
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        statusText = "Say it: Listening..."
                        postInvalidateOnAnimation()
                    }

                    override fun onBeginningOfSpeech() {
                        statusText = "Say it: Listening..."
                        postInvalidateOnAnimation()
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        val norm = ((rmsdB + 1f) / 9f).coerceIn(0f, 1f)
                        val boosted = kotlin.math.sqrt(norm.toDouble()).toFloat()
                        audioLevelRms = boosted
                        NothingGlyphController.setVoiceSpectrumLevel(boosted)
                        postInvalidateOnAnimation()
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        statusText = "Processing..."
                        NothingGlyphController.setVoiceSpectrumLevel(0f)
                        postInvalidateOnAnimation()
                    }

                    override fun onError(error: Int) {
                        ScreenAnalysisOverlay.dismissButtonPress()
                        if (currentTranscript.isNotBlank()) {
                            finishAndSearch(currentTranscript)
                        } else {
                            val msg = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                                else -> "Could not hear audio"
                            }
                            statusText = msg
                            postInvalidateOnAnimation()
                            handler.postDelayed({ dismissWithCollapse() }, 900L)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim().orEmpty()
                        val finalQuery = if (text.isNotBlank()) text else currentTranscript
                        updateStreamingWords(finalQuery, isFinal = true)
                        finishAndSearch(finalQuery)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim()
                        if (!text.isNullOrBlank()) {
                            currentTranscript = text
                            statusText = text
                            updateStreamingWords(text, isFinal = false)
                            postInvalidateOnAnimation()
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            statusText = "Speech input error"
            postInvalidateOnAnimation()
        }
    }

    /**
     * Updates streaming words and triggers crisp typography animation:
     * - Continuous text-reveal mechanism: previous words remain stationary.
     * - New words fade in from 0.92 scale.
     * - Corrected words crossfade rather than abruptly replacing text.
     * - Smoothly adjusts capsule target width based on spoken text length.
     */
    private fun updateStreamingWords(text: String, isFinal: Boolean) {
        val rawTokens = text.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (rawTokens.isEmpty()) return

        for (i in rawTokens.indices) {
            val token = rawTokens[i]
            val wordLocked = isFinal || (i < rawTokens.size - 1)
            if (i < streamingWords.size) {
                val existing = streamingWords[i]
                if (existing.text != token) {
                    // Speech correction: smooth crossfade
                    existing.oldText = existing.text
                    existing.oldAlphaProgress = existing.currentAlphaProgress
                    existing.text = token
                    existing.isLocked = wordLocked
                    existing.currentScale = 0.92f
                    existing.currentAlphaProgress = 0f
                } else {
                    if (wordLocked && !existing.isLocked) {
                        existing.isLocked = true
                    }
                }
            } else {
                // New word: starts at 0.92 scale, fades in smoothly
                streamingWords.add(StreamingWord(
                    text = token,
                    isLocked = wordLocked,
                    currentScale = 0.92f,
                    currentAlphaProgress = 0f
                ))
            }
        }

        // Dynamically compute target capsule width
        var totalTextW = 0f
        val spaceW = wordPaint.measureText(" ")
        for (w in streamingWords) {
            totalTextW += wordPaint.measureText(w.text) + spaceW
        }
        val neededW = 22f * density + 18f * density + 16f * density + 4 * (2.4f + 3.2f) * density + 16f * density + totalTextW + 24f * density
        val maxCapsuleW = (width - 24f * density).coerceAtLeast(180f * density)
        targetCapsuleWidth = neededW.coerceIn(180f * density, maxCapsuleW)

        // Animate words towards locked targets
        if (wordAnimator?.isRunning != true) {
            wordAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240L
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener {
                    var needsAnotherFrame = false

                    // Smooth capsule width tracking
                    if (abs(targetCapsuleWidth - currentCapsuleWidth) > 1f) {
                        currentCapsuleWidth += (targetCapsuleWidth - currentCapsuleWidth) * 0.22f
                        needsAnotherFrame = true
                    }

                    for (word in streamingWords) {
                        // Old text fade out
                        if (word.oldText != null) {
                            word.oldAlphaProgress = (word.oldAlphaProgress - 0.12f).coerceAtLeast(0f)
                            if (word.oldAlphaProgress <= 0.01f) {
                                word.oldText = null
                            } else {
                                needsAnotherFrame = true
                            }
                        }

                        if (word.isLocked) {
                            if (word.currentScale < 1.0f) {
                                word.currentScale = (word.currentScale + 0.025f).coerceAtMost(1.0f)
                                needsAnotherFrame = true
                            }
                            if (word.currentAlphaProgress < 1.0f) {
                                word.currentAlphaProgress = (word.currentAlphaProgress + 0.08f).coerceAtMost(1.0f)
                                needsAnotherFrame = true
                            }
                        } else {
                            if (word.currentScale < 0.98f) {
                                word.currentScale = (word.currentScale + 0.018f).coerceAtMost(0.98f)
                            }
                            if (word.currentAlphaProgress < 0.55f) {
                                word.currentAlphaProgress = (word.currentAlphaProgress + 0.06f).coerceAtMost(0.55f)
                            }
                        }
                    }
                    cursorAnimProgress = (cursorAnimProgress + 0.05f) % 1.0f
                    postInvalidateOnAnimation()
                }
                start()
            }
        }
    }

    /**
     * Called when the physical button is released.
     */
    fun onButtonReleased() {
        NothingGlyphController.stopVoiceRecordingSpectrum()
        // Immediately dismiss press indicator along bezel
        ScreenAnalysisOverlay.dismissButtonPress()

        if (state == State.COLLAPSING || state == State.DISMISSED || state == State.EXPANDING_TO_BROWSER || hasSubmitted) return
        performHaptic(VibrationEffect.EFFECT_TICK)
        state = State.TRANSCRIBING
        statusText = if (currentTranscript.isNotBlank()) "Searching..." else "Processing..."
        postInvalidateOnAnimation()

        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}

        handler.postDelayed(safetyTimeoutRunnable, 1200L)
    }

    private fun finishAndSearch(query: String) {
        if (hasSubmitted) return
        hasSubmitted = true
        handler.removeCallbacks(safetyTimeoutRunnable)
        cleanupSpeech()

        val finalQuery = query.trim()
        if (finalQuery.isNotBlank()) {
            val lower = finalQuery.lowercase()
            val isReminder = lower.startsWith("remind me") || lower.startsWith("reminder") ||
                    lower.startsWith("set a reminder") || lower.contains("remind me to") ||
                    lower.startsWith("remind ")

            if (isReminder) {
                performHaptic(VibrationEffect.EFFECT_CLICK)
                IntegralAcoustics.playVoiceSuccess(context)
                NothingGlyphController.onRecordingSaved()
                val reminder = ReminderManager.createReminder(context, finalQuery)
                savedReminderTitle = reminder.title
                val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                val isToday = android.text.format.DateUtils.isToday(reminder.triggerTimestamp)
                val dateLabel = if (isToday) "Today" else java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(reminder.triggerTimestamp))
                savedReminderTime = "$dateLabel · " + sdf.format(java.util.Date(reminder.triggerTimestamp))
                startReminderTransformation()
                return
            }

            if (tryLaunchApp(context, finalQuery)) {
                return
            }

            performHaptic(VibrationEffect.EFFECT_CLICK)
            IntegralAcoustics.playVoiceSuccess(context)
            startBrowserExpansion(finalQuery)
        } else {
            Toast.makeText(context, "No speech detected", Toast.LENGTH_SHORT).show()
            dismissWithCollapse()
        }
    }

    private fun tryLaunchApp(context: Context, query: String): Boolean {
        val raw = query.trim()
        if (raw.isBlank()) return false
        val lower = raw.lowercase(java.util.Locale.ROOT)

        // Ignore web questions or explicit search phrases
        if (lower.startsWith("search for ") || lower.startsWith("google ") ||
            lower.startsWith("what is ") || lower.startsWith("who is ") ||
            lower.startsWith("how to ") || lower.startsWith("where is ")
        ) {
            return false
        }

        // Clean command prefixes: "open ", "launch ", "start ", "run ", "go to "
        val appQuery = lower
            .replace(Regex("""^(?:please\s+)?(?:can\s+you\s+)?(?:open|launch|start|run|go\s+to)\s+"""), "")
            .trim()
            .trim('"', '\'', '.', ',', '!', '?')
        if (appQuery.isBlank()) return false

        val pm = context.packageManager

        // 1. Direct high-confidence aliases
        val aliases = mapOf(
            "gallery" to listOf("com.nothing.gallery", "com.google.android.apps.photos", "com.android.gallery3d"),
            "photos" to listOf("com.nothing.gallery", "com.google.android.apps.photos"),
            "pictures" to listOf("com.nothing.gallery", "com.google.android.apps.photos"),
            "pinterest" to listOf("com.pinterest"),
            "camera" to listOf("com.nothing.camera", "com.google.android.GoogleCamera", "com.android.camera"),
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "instagram" to listOf("com.instagram.android"),
            "spotify" to listOf("com.spotify.music"),
            "settings" to listOf("com.android.settings"),
            "calculator" to listOf("com.google.android.calculator", "com.nothing.calculator", "com.android.calculator2"),
            "clock" to listOf("com.google.android.deskclock", "com.nothing.clock", "com.android.deskclock"),
            "alarm" to listOf("com.google.android.deskclock", "com.nothing.clock"),
            "files" to listOf("com.google.android.apps.nbu.files", "com.nothing.files", "com.android.documentsui"),
            "notes" to listOf("com.nothing.notes", "com.google.android.keep"),
            "maps" to listOf("com.google.android.apps.maps"),
            "chrome" to listOf("com.android.chrome"),
            "gmail" to listOf("com.google.android.gm"),
            "mail" to listOf("com.google.android.gm"),
            "play store" to listOf("com.android.vending"),
            "store" to listOf("com.android.vending"),
            "messages" to listOf("com.google.android.apps.messaging", "com.android.mms"),
            "phone" to listOf("com.google.android.dialer", "com.android.dialer")
        )

        val targetPackages = aliases[appQuery]
        if (targetPackages != null) {
            for (pkg in targetPackages) {
                try {
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        context.startActivity(launchIntent)
                        val appName = try {
                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        } catch (_: Exception) {
                            appQuery.replaceFirstChar { it.uppercase() }
                        }
                        Toast.makeText(context, "Opening $appName", Toast.LENGTH_SHORT).show()
                        IntegralAcoustics.playVoiceSuccess(context)
                        ScreenAnalysisOverlay.dismissButtonPress()
                        dismissWithCollapse()
                        return true
                    }
                } catch (_: Exception) {}
            }
        }

        // Special system settings
        if (appQuery == "settings") {
            try {
                val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                }
                context.startActivity(intent)
                Toast.makeText(context, "Opening Settings", Toast.LENGTH_SHORT).show()
                IntegralAcoustics.playVoiceSuccess(context)
                ScreenAnalysisOverlay.dismissButtonPress()
                dismissWithCollapse()
                return true
            } catch (_: Exception) {}
        }

        // 2. Query all installed launcher activities with package visibility
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val activities = pm.queryIntentActivities(mainIntent, 0)
        if (activities.isNotEmpty()) {
            // Priority 1: Exact match with app label
            var matched = activities.firstOrNull {
                val label = it.loadLabel(pm).toString().trim().lowercase(java.util.Locale.ROOT)
                label == appQuery || label.replace(" ", "") == appQuery.replace(" ", "")
            }

            // Priority 2: Label starts with, ends with or contains token
            if (matched == null && appQuery.length >= 3) {
                matched = activities.firstOrNull {
                    val label = it.loadLabel(pm).toString().trim().lowercase(java.util.Locale.ROOT)
                    label.startsWith(appQuery) || label.endsWith(appQuery) ||
                    label.split(Regex("""[\s\-_]+""")).any { part -> part == appQuery }
                }
            }

            // Priority 3: Match package name token
            if (matched == null && appQuery.length >= 4) {
                matched = activities.firstOrNull {
                    val pkg = it.activityInfo.packageName.lowercase(java.util.Locale.ROOT)
                    pkg.endsWith(".$appQuery") || pkg.contains(".$appQuery.") || pkg.contains(appQuery)
                }
            }

            if (matched != null) {
                try {
                    val launchIntent = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        context.startActivity(launchIntent)
                        val appName = matched.loadLabel(pm).toString()
                        Toast.makeText(context, "Opening $appName", Toast.LENGTH_SHORT).show()
                        IntegralAcoustics.playVoiceSuccess(context)
                        ScreenAnalysisOverlay.dismissButtonPress()
                        dismissWithCollapse()
                        return true
                    }
                } catch (_: Exception) {}
            }
        }
        return false
    }

    /**
     * Continuous Fluid Gesture Transition:
     * 1. Structural Expansion: Pill accelerates downward and outward; corner radius 27dp -> 0dp.
     * 2. Relay Handshake: Passes text string to Chrome deep-link URL protocol scheme.
     * 3. Content Reveal: Browser assets fade in with micro-staggered layout (staggerChildren: 0.03).
     */
    private fun startBrowserExpansion(query: String) {
        state = State.EXPANDING_TO_BROWSER
        activeAnimator?.cancel()
        hasRelayHandshaked = false

        // Instant bezel press indicator cleanup
        ScreenAnalysisOverlay.dismissButtonPress()

        activeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            interpolator = AccelerateInterpolator(1.6f) // Instantly accelerates downward and outward
            addUpdateListener {
                val p = it.animatedValue as Float
                browserExpandProgress = p

                // Relay Handshake executed right as outward expansion takes off
                if (!hasRelayHandshaked && p >= 0.15f) {
                    hasRelayHandshaked = true
                    executeRelayHandshake(query)
                }
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    handler.postDelayed({
                        state = State.DISMISSED
                        ScreenAnalysisOverlay.dismissButtonPress()
                        onDismissComplete()
                    }, 50L)
                }
            })
            start()
        }
    }

    /**
     * In-Place Voice Intent Transformation:
     * - Transforms the same Living Capsule into a polished Nothing-style Reminder Card.
     * - No dismissal followed by another entrance; one living object morphing across states.
     */
    private fun startReminderTransformation() {
        state = State.TRANSFORMING_TO_REMINDER
        activeAnimator?.cancel()
        ScreenAnalysisOverlay.dismissButtonPress()

        activeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L
            interpolator = android.view.animation.OvershootInterpolator(1.25f)
            addUpdateListener {
                reminderTransformProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Hold reminder card for 1.4s, then magnetically retract towards button
                    handler.postDelayed({
                        dismissWithCollapse()
                    }, 1400L)
                }
            })
            start()
        }
    }

    /**
     * The Relay Handshake: Passes captured text directly into Chrome deep-link protocol scheme.
     */
    /**
     * Destination Memory & Natural Command Routing:
     * - Dispatches voice queries directly to Web Search, YouTube, or YouTube Music without intermediate screens.
     * - Understands natural commands like "Search for new songs in YouTube" or "Play a song".
     */
    private fun executeRelayHandshake(query: String) {
        val rawQuery = query.trim()
        if (rawQuery.isBlank()) return
        val lower = rawQuery.lowercase(java.util.Locale.ROOT)
        val words = lower.split(Regex("""\s+""")).filter { it.isNotBlank() }

        // Clean out any legacy youtube_music memory
        prefs.edit().remove("voice_destination_memory").apply()

        val isSingleWord = (words.size == 1)

        val isReminder = !isSingleWord && (
            lower.startsWith("remind me") || lower.startsWith("reminder") ||
            lower.startsWith("set a reminder") || lower.contains("remind me to") ||
            lower.startsWith("remind ")
        )
        if (isReminder) {
            val reminder = ReminderManager.createReminder(context, rawQuery)
            Toast.makeText(context, "Reminder set: ${reminder.title}", Toast.LENGTH_SHORT).show()
            NothingGlyphController.onRecordingSaved()
            return
        }

        if (tryLaunchApp(context, rawQuery)) {
            return
        }

        val isPlayCommand = lower.startsWith("play ") || lower.startsWith("listen to ") || lower.startsWith("stream ")

        // Explicit video intent:
        val isExplicitYouTube = !isSingleWord && (
            lower.contains("youtube") || lower.contains("on yt") || lower.contains("in yt") ||
            lower.startsWith("watch ")
        )

        when {
            isExplicitYouTube -> {
                var ytQuery = rawQuery
                    .replace(Regex("""(?i)\b(search\s+for|search)?\s*(.+?)\s*(in\s+youtube|on\s+youtube|youtube|on\s+yt)\b"""), "$2")
                    .replace(Regex("""(?i)\b(in\s+youtube|on\s+youtube|youtube|watch|search|play\s+a\s+song|play\s+song|play|listen\s+to|stream)\b"""), "")
                    .trim()
                    .trim('"', '\'', ' ')
                if (ytQuery.isBlank()) ytQuery = rawQuery.replace(Regex("""(?i)\b(play|watch|stream)\b"""), "").trim().trim('"', '\'', ' ')
                if (ytQuery.isBlank()) ytQuery = rawQuery
                launchYouTube(ytQuery, autoPlay = isPlayCommand || lower.startsWith("watch "))
            }
            else -> {
                launchWebSearch(rawQuery)
            }
        }
    }

    private fun launchYouTube(searchQuery: String, autoPlay: Boolean = false) {
        val cleanQuery = searchQuery.trim('"', '\'', ' ')
        val encoded = Uri.encode(cleanQuery)

        if (autoPlay) {
            // 1. Media Play From Search directly into YouTube (searches and immediately plays top result)
            try {
                val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra(android.app.SearchManager.QUERY, cleanQuery)
                    putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra("android.intent.extra.focus", "vnd.android.cursor.item/*")
                    putExtra("autolaunch", true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }

        // 2. Try YouTube App Search Intent
        try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", cleanQuery)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
            return
        } catch (_: Exception) {}

        // 3. Fallback to vnd.youtube deep-link
        try {
            val uri = Uri.parse("vnd.youtube://results?search_query=$encoded")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
            return
        } catch (_: Exception) {}

        // 4. Web fallback
        launchWebSearch("https://www.youtube.com/results?search_query=$encoded", isDirectUrl = true)
    }

    private fun launchWebSearch(query: String, isDirectUrl: Boolean = false) {
        val searchUrl = if (isDirectUrl) query else "https://www.google.com/search?q=${Uri.encode(query)}"

        // 1. Chrome URL protocol scheme deep-link
        val chromeProtocolUri = Uri.parse("googlechrome://navigate?url=$searchUrl")
        val protocolIntent = Intent(Intent.ACTION_VIEW, chromeProtocolUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            context.startActivity(protocolIntent)
            return
        } catch (_: Exception) {}

        // 2. Chrome package intent fallback
        try {
            val chromePkgIntent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                setPackage("com.android.chrome")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(chromePkgIntent)
            return
        } catch (_: Exception) {}

        // 3. Default browser intent fallback
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(browserIntent)
        } catch (_: Exception) {}
    }

    fun dismissWithCollapse() {
        if (state == State.COLLAPSING || state == State.DISMISSED || state == State.EXPANDING_TO_BROWSER) return
        state = State.COLLAPSING
        IntegralAcoustics.playOverlayDismiss(context)
        activeAnimator?.cancel()
        cleanupSpeech()
        ScreenAnalysisOverlay.dismissButtonPress()

        activeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 200L
            interpolator = AccelerateInterpolator(1.4f)
            addUpdateListener {
                collapseProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    state = State.DISMISSED
                    ScreenAnalysisOverlay.dismissButtonPress()
                    onDismissComplete()
                }
            })
            start()
        }
    }

    private fun cleanupSpeech() {
        NothingGlyphController.stopVoiceRecordingSpectrum()
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            if (state == State.RECORDING || state == State.EXPANDING) {
                onButtonReleased()
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || state == State.IDLE || state == State.DISMISSED) return

        if (buttonBounds.isEmpty) {
            calculateCoordinates(width.toFloat(), height.toFloat())
        }


        when (state) {
            State.PRESS_COMPRESSION -> drawPressCompression(canvas)
            State.EXPANDING, State.RECORDING, State.TRANSCRIBING -> drawMorphingVoiceOverlay(canvas)
            State.TRANSFORMING_TO_REMINDER -> drawReminderCard(canvas)
            State.EXPANDING_TO_BROWSER -> drawBrowserExpansion(canvas)
            State.COLLAPSING -> drawCollapsingVoiceOverlay(canvas)
            else -> Unit
        }
    }

    private fun drawPressCompression(canvas: Canvas) {
        val t = compressionProgress.coerceIn(0f, 1f)
        val currentScale = 1f - 0.07f * t

        val halfW = (buttonBounds.width() * currentScale) / 2f
        val halfH = (buttonBounds.height() * currentScale) / 2f
        tempRect.set(buttonCenter.x - halfW, buttonCenter.y - halfH, buttonCenter.x + halfW, buttonCenter.y + halfH)
        val corner = initialCornerRadius * currentScale

        fillPaint.color = Color.argb((220 * t).toInt(), 18, 18, 20)
        canvas.drawRoundRect(tempRect, corner, corner, fillPaint)

        borderPaint.color = Color.argb((150 * t).toInt(), 255, 179, 0)
        borderPaint.strokeWidth = 1.2f * density
        canvas.drawRoundRect(tempRect, corner, corner, borderPaint)

        val insetX = 3f * density * currentScale
        val insetY = 4f * density * currentScale
        val innerRect = RectF(tempRect.left + insetX, tempRect.top + insetY, tempRect.right - insetX, tempRect.bottom - insetY)
        val innerCorner = innerRect.width() / 2f
        fillPaint.color = Color.argb((255f * t).toInt(), 255, 179, 0)
        canvas.drawRoundRect(innerRect, innerCorner, innerCorner, fillPaint)
    }

    private fun drawMorphingVoiceOverlay(canvas: Canvas) {
        val e = expansionProgress
        val clampE = e.coerceIn(0f, 1f)
        val compCorner = minOf(compressedButtonBounds.width(), compressedButtonBounds.height()) / 2f
        val fromRight = buttonCenter.x >= width / 2f

        // Dynamic capsule width adjustment based on recognized speech
        val dynamicTargetW = currentCapsuleWidth
        val margin = 14f * density
        val dynamicTargetCenterX = if (fromRight) {
            (width - margin - dynamicTargetW / 2f).coerceAtLeast(dynamicTargetW / 2f + margin)
        } else {
            (margin + dynamicTargetW / 2f).coerceAtMost(width - dynamicTargetW / 2f - margin)
        }
        targetOverlayRect.set(
            dynamicTargetCenterX - dynamicTargetW / 2f,
            buttonCenter.y - 27f * density,
            dynamicTargetCenterX + dynamicTargetW / 2f,
            buttonCenter.y + 27f * density
        )

        val leadT = sin(clampE * Math.PI).toFloat()
        val stretch = 5f * density * leadT

        val baseLeft = compressedButtonBounds.left + (targetOverlayRect.left - compressedButtonBounds.left) * e
        val baseTop = compressedButtonBounds.top + (targetOverlayRect.top - compressedButtonBounds.top) * e
        val baseRight = compressedButtonBounds.right + (targetOverlayRect.right - compressedButtonBounds.right) * e
        val baseBottom = compressedButtonBounds.bottom + (targetOverlayRect.bottom - compressedButtonBounds.bottom) * e

        val curLeft = if (fromRight) baseLeft - stretch else baseLeft
        val curRight = if (!fromRight) baseRight + stretch else baseRight
        val curTop = baseTop + stretch * 0.18f
        val curBottom = baseBottom - stretch * 0.18f

        tempRect.set(curLeft, curTop, curRight, curBottom)
        val curCorner = compCorner + (targetCornerRadius - compCorner) * clampE

        morphPath.reset()
        morphPath.addRoundRect(tempRect, curCorner, curCorner, Path.Direction.CW)

        // Background morph: Yellow -> Obsidian Translucent Glass (75% opacity)
        val colorT = (clampE / 0.38f).coerceIn(0f, 1f)
        val smoothColorT = colorT * colorT * (3f - 2f * colorT)

        val bgA = (255f + (charcoalGlassA - 255f) * smoothColorT).toInt()
        val bgR = (247f + (charcoalGlassR - 247f) * smoothColorT).toInt()
        val bgG = (217f + (charcoalGlassG - 217f) * smoothColorT).toInt()
        val bgB = (21f + (charcoalGlassB - 21f) * smoothColorT).toInt()

        fillPaint.color = Color.argb(bgA, bgR, bgG, bgB)
        canvas.drawPath(morphPath, fillPaint)

        // Subtle specular sheen
        if (smoothColorT > 0.15f) {
            val sheenA = (20f * clampE).toInt()
            sheenPaint.shader = LinearGradient(
                0f, tempRect.top, 0f, tempRect.top + 24f * density,
                intArrayOf(Color.argb(sheenA, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(morphPath, sheenPaint)
            sheenPaint.shader = null
        }

        // Contour border: Badger Yellow #F7D915
        val strokeAlpha = if (clampE < 0.35f) 255 else (255f - 75f * ((clampE - 0.35f) / 0.65f)).toInt()
        borderPaint.strokeWidth = (1.8f - 0.5f * clampE) * density
        borderPaint.color = Color.argb(strokeAlpha, 255, 179, 0)
        canvas.drawPath(morphPath, borderPaint)

        if (clampE > 0.35f) {
            drawVoiceContent(canvas, tempRect, clampE)
        }
    }

    /**
     * Draws Voice Pill content with Live Text Streaming and crisp typography animation.
     */
    private fun drawVoiceContent(canvas: Canvas, bounds: RectF, expandProgress: Float) {
        val cy = bounds.centerY()
        val alpha = ((expandProgress - 0.35f) / 0.65f).coerceIn(0f, 1f)
        val alphaInt = (255f * alpha).toInt()

        smoothedAudioLevel += (audioLevelRms - smoothedAudioLevel) * 0.28f

        // Microphone Icon
        val micX = bounds.left + 22f * density
        drawMicrophoneIcon(canvas, micX, cy, 18f * density, alphaInt)

        // Pulsing voice visualizer bars
        val waveStartX = micX + 16f * density
        val numBars = 4
        val barWidth = 2.4f * density
        val barGap = 3.2f * density

        for (i in 0 until numBars) {
            val phase = (i * 0.8f + System.currentTimeMillis() * 0.008f).toFloat()
            val waveHeightBase = 4f * density
            val dynamicBoost = 18f * density * smoothedAudioLevel * (0.6f + 0.4f * sin(phase))
            val barH = (waveHeightBase + dynamicBoost).coerceIn(3f * density, 24f * density)

            val bx = waveStartX + i * (barWidth + barGap)
            val barRect = RectF(bx, cy - barH / 2f, bx + barWidth, cy + barH / 2f)

            wavePaint.color = Color.argb((alphaInt * (0.65f + 0.35f * smoothedAudioLevel)).toInt().coerceIn(0, 255), 255, 179, 0)
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, wavePaint)
        }

        // Live Text Streaming Area
        val textStartX = waveStartX + numBars * (barWidth + barGap) + 8f * density
        val maxTextWidth = bounds.right - textStartX - 12f * density

        wordPaint.textSize = 13.5f * density
        wordPaint.letterSpacing = 0.02f
        val fontMetrics = wordPaint.fontMetrics
        val textY = cy - (fontMetrics.descent + fontMetrics.ascent) / 2f

        if (streamingWords.isEmpty()) {
            textPaint.textSize = 13f * density
            textPaint.typeface = satoshiMedium
            textPaint.color = Color.argb((alphaInt * 0.85f).toInt(), 255, 179, 0) // Badger Yellow
            val statusElided = android.text.TextUtils.ellipsize(statusText, android.text.TextPaint(textPaint), maxTextWidth.coerceAtLeast(10f), android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(statusElided, textStartX, textY, textPaint)
        } else {
            // Measure total words length to support auto-scrolling
            val spaceW = wordPaint.measureText(" ")
            var totalW = 0f
            val wordWidths = FloatArray(streamingWords.size)
            for (i in streamingWords.indices) {
                val w = wordPaint.measureText(streamingWords[i].text) * streamingWords[i].currentScale
                wordWidths[i] = w
                totalW += w + (if (i < streamingWords.size - 1) spaceW else 0f)
            }

            val targetScroll = (totalW - maxTextWidth).coerceAtLeast(0f)
            textScrollX += (targetScroll - textScrollX) * 0.25f

            canvas.save()
            canvas.clipRect(textStartX, bounds.top, bounds.right - 10f * density, bounds.bottom)

            var curX = textStartX - textScrollX
            for (i in streamingWords.indices) {
                val word = streamingWords[i]
                // Draw crossfading previous word if undergoing speech correction
                if (word.oldText != null && word.oldAlphaProgress > 0f) {
                    val oldA = (alphaInt * word.oldAlphaProgress * 0.7f).toInt().coerceIn(0, 255)
                    wordPaint.color = Color.argb(oldA, 175, 175, 185)
                    canvas.save()
                    canvas.translate(curX, textY)
                    canvas.drawText(word.oldText!!, 0f, 0f, wordPaint)
                    canvas.restore()
                }

                val t = word.currentAlphaProgress // 0f = translucent gray, 1f = solid white
                val scale = word.currentScale      // 0.92 -> 1.0

                // Color transition: translucent gray (#66FFFFFF / #80B0B0C0) -> solid white (#FFFFFF)
                val grayA = 0.55f
                val effectiveA = (alphaInt * (grayA + (1f - grayA) * t)).toInt().coerceIn(0, 255)
                val r = (175 + (255 - 175) * t).toInt()
                val g = (175 + (255 - 175) * t).toInt()
                val b = (185 + (255 - 185) * t).toInt()

                wordPaint.color = Color.argb(effectiveA, r, g, b)

                canvas.save()
                canvas.translate(curX, textY)
                canvas.scale(scale, scale)
                canvas.drawText(word.text, 0f, 0f, wordPaint)
                canvas.restore()

                curX += wordWidths[i] + spaceW
            }

            // Smooth moving amber cursor indicator following progressive speech
            val cursorX = (curX + 2f * density).coerceAtMost(bounds.right - 12f * density)
            val cursorH = 13f * density
            val cursorTop = cy - cursorH / 2f
            val cursorAlpha = ((sin(cursorAnimProgress * Math.PI * 2) * 0.45f + 0.55f) * 255 * alpha).toInt().coerceIn(0, 255)
            borderPaint.color = Color.argb(cursorAlpha, 255, 179, 0) // Amber
            borderPaint.strokeWidth = 2f * density
            canvas.drawLine(cursorX, cursorTop, cursorX, cursorTop + cursorH, borderPaint)

            canvas.restore()
        }
    }

    /**
     * Structural Expansion & Content Reveal (Continuous Fluid Gesture):
     * - Compact voice pill accelerates downward and outward into full-bleed viewport.
     * - Corners flatten from 27dp/24dp down to 0dp.
     * - Browser UI assets (Omnibox, navigation tabs, instant results, and SERP cards)
     *   fade in simultaneously with micro-staggered layout (staggerChildren: 0.03).
     */
    private fun drawBrowserExpansion(canvas: Canvas) {
        val p = browserExpandProgress.coerceIn(0f, 1f)
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Structural Expansion: Accelerate downward and outward, flattening corners
        val curLeft = targetOverlayRect.left * (1f - p)
        val curTop = targetOverlayRect.top * (1f - p)
        val curRight = targetOverlayRect.right + (w - targetOverlayRect.right) * p
        val curBottom = targetOverlayRect.bottom + (h - targetOverlayRect.bottom) * p
        val curCorner = targetCornerRadius * (1f - p)

        tempRect.set(curLeft, curTop, curRight, curBottom)
        morphPath.reset()
        morphPath.addRoundRect(tempRect, curCorner, curCorner, Path.Direction.CW)

        // Deep Obsidian / Chrome Dark viewport surface (#121214)
        val bgAlpha = (charcoalGlassA + (255 - charcoalGlassA) * p).toInt().coerceIn(0, 255)
        fillPaint.color = Color.argb(bgAlpha, 18, 18, 20)
        canvas.drawPath(morphPath, fillPaint)

        // Contour border fades away smoothly
        val borderAlpha = ((1f - p / 0.45f).coerceIn(0f, 1f) * 220).toInt()
        if (borderAlpha > 0) {
            borderPaint.strokeWidth = 1.4f * density
            borderPaint.color = Color.argb(borderAlpha, 255, 179, 0) // #F7D915
            canvas.drawPath(morphPath, borderPaint)
        }

        // 2. The Content Reveal (Micro-staggered layout: staggerChildren: 0.03)
        if (p > 0.20f) {
            canvas.save()
            canvas.clipPath(morphPath)
            drawBrowserUIContent(canvas, p, w, h)
            canvas.restore()
        }
    }

    /**
     * Micro-staggered Browser UI Reveal (staggerChildren: 0.03).
     */
    private fun drawBrowserUIContent(canvas: Canvas, p: Float, w: Float, h: Float) {
        val staggerStep = 0.03f
        val displayQuery = if (currentTranscript.isNotBlank()) currentTranscript else statusText

        // Child 0: Top Chrome Omnibox Search Field
        val p0 = ((p - 0.20f - 0 * staggerStep) / 0.35f).coerceIn(0f, 1f)
        if (p0 > 0f) {
            val a0 = (255 * p0).toInt()
            val slide0 = (1f - p0) * 16f * density
            val omniTop = 48f * density + slide0
            val omniH = 46f * density
            val omniLeft = 14f * density
            val omniRight = w - 14f * density
            val omniCorner = omniH / 2f

            // Omnibox background
            fillPaint.color = Color.argb(a0, 42, 42, 46)
            canvas.drawRoundRect(omniLeft, omniTop, omniRight, omniTop + omniH, omniCorner, omniCorner, fillPaint)

            // Google "G" / Lock icon on left
            val lockCx = omniLeft + 22f * density
            val lockCy = omniTop + omniH / 2f
            drawGoogleIcon(canvas, lockCx, lockCy, 16f * density, a0)

            // Query text
            textPaint.typeface = satoshiBold
            textPaint.textSize = 14f * density
            textPaint.color = Color.argb(a0, 255, 255, 255)
            val queryStartX = omniLeft + 42f * density
            val maxOmniTextW = omniRight - queryStartX - 44f * density
            val queryElided = android.text.TextUtils.ellipsize(displayQuery, android.text.TextPaint(textPaint), maxOmniTextW.coerceAtLeast(10f), android.text.TextUtils.TruncateAt.END).toString()
            val queryY = lockCy - (textPaint.fontMetrics.descent + textPaint.fontMetrics.ascent) / 2f
            canvas.drawText(queryElided, queryStartX, queryY, textPaint)

            // Right microphone icon in Badger Yellow
            val rightMicX = omniRight - 22f * density
            drawMicrophoneIcon(canvas, rightMicX, lockCy, 16f * density, a0)
        }

        // Child 1: Navigation & Search Filter Tabs
        val p1 = ((p - 0.20f - 1 * staggerStep) / 0.35f).coerceIn(0f, 1f)
        if (p1 > 0f) {
            val a1 = (255 * p1).toInt()
            val slide1 = (1f - p1) * 16f * density
            val tabsTop = 106f * density + slide1

            textPaint.typeface = satoshiMedium
            textPaint.textSize = 13f * density

            val tabs = listOf("All", "Images", "Videos", "News", "Maps")
            var tabX = 18f * density
            for (idx in tabs.indices) {
                val tab = tabs[idx]
                val isSelected = idx == 0
                textPaint.color = if (isSelected) Color.argb(a1, 255, 255, 255) else Color.argb((a1 * 0.65f).toInt(), 180, 180, 185)
                canvas.drawText(tab, tabX, tabsTop, textPaint)

                if (isSelected) {
                    val tabW = textPaint.measureText(tab)
                    borderPaint.strokeWidth = 2.5f * density
                    borderPaint.color = Color.argb(a1, 255, 179, 0) // Badger Yellow underline
                    val lineY = tabsTop + 8f * density
                    canvas.drawLine(tabX, lineY, tabX + tabW, lineY, borderPaint)
                }
                tabX += textPaint.measureText(tab) + 24f * density
            }

            // Divider hairline
            fillPaint.color = Color.argb((a1 * 0.4f).toInt(), 45, 45, 50)
            canvas.drawRect(0f, tabsTop + 10f * density, w, tabsTop + 11f * density, fillPaint)
        }

        // Child 2: Instant Overview / Search Answer Card
        val p2 = ((p - 0.20f - 2 * staggerStep) / 0.35f).coerceIn(0f, 1f)
        if (p2 > 0f) {
            val a2 = (255 * p2).toInt()
            val slide2 = (1f - p2) * 16f * density
            val cardTop = 138f * density + slide2
            val cardH = 82f * density
            val cardL = 14f * density
            val cardR = w - 14f * density

            fillPaint.color = Color.argb(a2, 32, 33, 36)
            canvas.drawRoundRect(cardL, cardTop, cardR, cardTop + cardH, 14f * density, 14f * density, fillPaint)

            // Header tag
            textPaint.typeface = satoshiMedium
            textPaint.textSize = 11f * density
            textPaint.color = Color.argb((a2 * 0.7f).toInt(), 160, 160, 165)
            canvas.drawText("Google Search · Instant Answers", cardL + 14f * density, cardTop + 22f * density, textPaint)

            // Query Title
            textPaint.typeface = satoshiBold
            textPaint.textSize = 16f * density
            textPaint.color = Color.argb(a2, 255, 255, 255)
            val titleElided = android.text.TextUtils.ellipsize(displayQuery, android.text.TextPaint(textPaint), (cardR - cardL - 28f * density).coerceAtLeast(10f), android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(titleElided, cardL + 14f * density, cardTop + 44f * density, textPaint)

            // Subtitle
            textPaint.typeface = satoshiRegular
            textPaint.textSize = 12f * density
            textPaint.color = Color.argb((a2 * 0.75f).toInt(), 189, 193, 198)
            canvas.drawText("Showing live web results and answers for \"$displayQuery\"", cardL + 14f * density, cardTop + 64f * density, textPaint)
        }

        // Child 3: Primary SERP Result Card
        val p3 = ((p - 0.20f - 3 * staggerStep) / 0.35f).coerceIn(0f, 1f)
        if (p3 > 0f) {
            val a3 = (255 * p3).toInt()
            val slide3 = (1f - p3) * 16f * density
            val cardTop = 232f * density + slide3
            val cardH = 108f * density
            val cardL = 14f * density
            val cardR = w - 14f * density

            fillPaint.color = Color.argb(a3, 32, 33, 36)
            canvas.drawRoundRect(cardL, cardTop, cardR, cardTop + cardH, 14f * density, 14f * density, fillPaint)

            // Breadcrumb URL
            textPaint.typeface = satoshiRegular
            textPaint.textSize = 11.5f * density
            textPaint.color = Color.argb((a3 * 0.7f).toInt(), 170, 170, 175)
            canvas.drawText("https://www.google.com › search", cardL + 14f * density, cardTop + 22f * density, textPaint)

            // Headline link in Google Search Blue (#8AB4F8)
            textPaint.typeface = satoshiBold
            textPaint.textSize = 15f * density
            textPaint.color = Color.argb(a3, 138, 180, 248)
            val linkTitle = "$displayQuery - Google Search"
            val linkElided = android.text.TextUtils.ellipsize(linkTitle, android.text.TextPaint(textPaint), (cardR - cardL - 28f * density).coerceAtLeast(10f), android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(linkElided, cardL + 14f * density, cardTop + 45f * density, textPaint)

            // Snippet
            textPaint.typeface = satoshiRegular
            textPaint.textSize = 12f * density
            textPaint.color = Color.argb((a3 * 0.8f).toInt(), 189, 193, 198)
            canvas.drawText("Explore top news, fast definitions, interactive cards, and", cardL + 14f * density, cardTop + 68f * density, textPaint)
            canvas.drawText("comprehensive knowledge graph queries from Google.", cardL + 14f * density, cardTop + 86f * density, textPaint)
        }

        // Child 4: Secondary SERP Result Card
        val p4 = ((p - 0.20f - 4 * staggerStep) / 0.35f).coerceIn(0f, 1f)
        if (p4 > 0f) {
            val a4 = (255 * p4).toInt()
            val slide4 = (1f - p4) * 16f * density
            val cardTop = 352f * density + slide4
            val cardH = 96f * density
            val cardL = 14f * density
            val cardR = w - 14f * density

            fillPaint.color = Color.argb(a4, 32, 33, 36)
            canvas.drawRoundRect(cardL, cardTop, cardR, cardTop + cardH, 14f * density, 14f * density, fillPaint)

            textPaint.typeface = satoshiRegular
            textPaint.textSize = 11.5f * density
            textPaint.color = Color.argb((a4 * 0.7f).toInt(), 170, 170, 175)
            canvas.drawText("https://en.wikipedia.org › wiki", cardL + 14f * density, cardTop + 22f * density, textPaint)

            textPaint.typeface = satoshiBold
            textPaint.textSize = 15f * density
            textPaint.color = Color.argb(a4, 138, 180, 248)
            canvas.drawText("$displayQuery - Wikipedia", cardL + 14f * density, cardTop + 45f * density, textPaint)

            textPaint.typeface = satoshiRegular
            textPaint.textSize = 12f * density
            textPaint.color = Color.argb((a4 * 0.8f).toInt(), 189, 193, 198)
            canvas.drawText("Comprehensive reference encyclopedia overview and details.", cardL + 14f * density, cardTop + 68f * density, textPaint)
        }
    }

    private fun drawGoogleIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, alpha: Int) {
        val r = size / 2f
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = 2.2f * density
        glyphPaint.color = Color.argb(alpha, 138, 180, 248)
        val arcRect = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(arcRect, 45f, 270f, false, glyphPaint)
        canvas.drawLine(cx, cy, cx + r, cy, glyphPaint)
    }

    private fun drawMicrophoneIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, alpha: Int) {
        if (alpha <= 0) return

        glyphPaint.color = Color.argb(alpha, 255, 179, 0) // Badger Yellow #F7D915
        glyphFillPaint.color = glyphPaint.color

        val micW = size * 0.34f
        val micH = size * 0.58f
        val micRect = RectF(cx - micW / 2f, cy - micH / 2f - size * 0.08f, cx + micW / 2f, cy + micH / 2f - size * 0.08f)
        canvas.drawRoundRect(micRect, micW / 2f, micW / 2f, glyphFillPaint)

        val cradleW = size * 0.56f
        val cradleH = size * 0.52f
        val cradleRect = RectF(cx - cradleW / 2f, cy - cradleH / 2f - size * 0.04f, cx + cradleW / 2f, cy + cradleH / 2f - size * 0.04f)
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = size * 0.085f
        canvas.drawArc(cradleRect, 0f, 180f, false, glyphPaint)

        val stemTop = cradleRect.centerY() + cradleH / 2f
        val stemBottom = stemTop + size * 0.18f
        canvas.drawLine(cx, stemTop, cx, stemBottom, glyphPaint)

        val baseW = size * 0.44f
        canvas.drawLine(cx - baseW / 2f, stemBottom, cx + baseW / 2f, stemBottom, glyphPaint)
    }

    private fun drawCollapsingVoiceOverlay(canvas: Canvas) {
        val c = collapseProgress.coerceIn(0f, 1f)
        if (c <= 0f) return

        // Magnetic Retraction: accelerates towards buttonCenter
        val t = 1f - c
        val accelT = (t * t * 1.35f).coerceIn(0f, 1f)

        val curCenterX = targetOverlayRect.centerX() + (buttonCenter.x - targetOverlayRect.centerX()) * accelT
        val curCenterY = targetOverlayRect.centerY() + (buttonCenter.y - targetOverlayRect.centerY()) * accelT

        val curW = (targetOverlayRect.width() + (buttonBounds.width() - targetOverlayRect.width()) * accelT).coerceAtLeast(buttonBounds.width())
        val curH = (targetOverlayRect.height() + (buttonBounds.height() - targetOverlayRect.height()) * accelT).coerceAtLeast(buttonBounds.height())
        val curCorner = targetCornerRadius + (initialCornerRadius - targetCornerRadius) * accelT

        tempRect.set(curCenterX - curW / 2f, curCenterY - curH / 2f, curCenterX + curW / 2f, curCenterY + curH / 2f)
        morphPath.reset()
        morphPath.addRoundRect(tempRect, curCorner, curCorner, Path.Direction.CW)

        val exitAlpha = (c * 255).toInt().coerceIn(0, 255)
        fillPaint.color = Color.argb((exitAlpha * 0.85f).toInt(), charcoalGlassR, charcoalGlassG, charcoalGlassB)
        canvas.drawPath(morphPath, fillPaint)

        borderPaint.strokeWidth = (1.4f * (1f - accelT * 0.4f)).coerceAtLeast(1f) * density
        borderPaint.color = Color.argb(exitAlpha, 255, 179, 0)
        canvas.drawPath(morphPath, borderPaint)
    }

    /**
     * In-Place Reminder Card Transformation:
     * - Seamless morph of the voice capsule into an obsidian & amber reminder card.
     */
    private fun drawReminderCard(canvas: Canvas) {
        val p = reminderTransformProgress.coerceIn(0f, 1f)
        val fromRight = buttonCenter.x >= width / 2f
        val w = width.toFloat()

        val startW = targetOverlayRect.width()
        val targetCardW = (w - 32f * density).coerceAtMost(300f * density)
        val currentW = startW + (targetCardW - startW) * p

        val startH = 54f * density
        val targetCardH = 78f * density
        val currentH = startH + (targetCardH - startH) * p

        val startCorner = 27f * density
        val targetCorner = 18f * density
        val currentCorner = startCorner + (targetCorner - startCorner) * p

        val margin = 14f * density
        val cx = if (fromRight) {
            (w - margin - currentW / 2f)
        } else {
            (margin + currentW / 2f)
        }
        val cy = buttonCenter.y

        tempRect.set(cx - currentW / 2f, cy - currentH / 2f, cx + currentW / 2f, cy + currentH / 2f)
        morphPath.reset()
        morphPath.addRoundRect(tempRect, currentCorner, currentCorner, Path.Direction.CW)

        // Card glass background
        fillPaint.color = Color.argb(235, 16, 16, 20)
        canvas.drawPath(morphPath, fillPaint)

        // Subtle specular sheen
        sheenPaint.shader = LinearGradient(
            0f, tempRect.top, 0f, tempRect.top + 28f * density,
            intArrayOf(Color.argb(25, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(morphPath, sheenPaint)
        sheenPaint.shader = null

        // Amber border contour
        borderPaint.strokeWidth = 1.4f * density
        borderPaint.color = Color.argb(240, 255, 179, 0)
        canvas.drawPath(morphPath, borderPaint)

        // Content reveal inside card
        if (p > 0.25f) {
            val contentAlpha = ((p - 0.25f) / 0.75f).coerceIn(0f, 1f)
            val aInt = (contentAlpha * 255).toInt()

            canvas.save()
            canvas.clipPath(morphPath)

            // Top row: Amber badge "REMINDER SET"
            val badgeX = tempRect.left + 16f * density
            val badgeY = tempRect.top + 16f * density
            val bellSize = 13f * density
            drawBellIcon(canvas, badgeX + bellSize / 2f, badgeY + bellSize / 2f, bellSize, aInt)

            textPaint.typeface = satoshiBold
            textPaint.textSize = 10f * density
            textPaint.color = Color.argb(aInt, 255, 179, 0) // Amber
            canvas.drawText("REMINDER SCHEDULED", badgeX + bellSize + 8f * density, badgeY + bellSize * 0.85f, textPaint)

            // Middle row: Title
            textPaint.typeface = satoshiBold
            textPaint.textSize = 14f * density
            textPaint.color = Color.argb(aInt, 255, 255, 255) // White
            val maxTitleW = currentW - 32f * density
            val titleElided = android.text.TextUtils.ellipsize(savedReminderTitle, android.text.TextPaint(textPaint), maxTitleW, android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(titleElided, badgeX, tempRect.top + 46f * density, textPaint)

            // Bottom row: Scheduled time
            textPaint.typeface = satoshiMedium
            textPaint.textSize = 11f * density
            textPaint.color = Color.argb((aInt * 0.65f).toInt(), 200, 200, 210)
            canvas.drawText(savedReminderTime, badgeX, tempRect.top + 64f * density, textPaint)

            canvas.restore()
        }
    }

    private fun drawBellIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, alpha: Int) {
        if (alpha <= 0) return
        glyphPaint.color = Color.argb(alpha, 255, 179, 0)
        glyphFillPaint.color = glyphPaint.color
        val bellW = size * 0.52f
        val bellH = size * 0.54f
        val bellTop = cy - bellH * 0.55f

        val bellPath = Path().apply {
            moveTo(cx, bellTop)
            cubicTo(cx + bellW * 0.45f, bellTop, cx + bellW * 0.55f, bellTop + bellH * 0.7f, cx + bellW * 0.65f, bellTop + bellH)
            lineTo(cx - bellW * 0.65f, bellTop + bellH)
            cubicTo(cx - bellW * 0.55f, bellTop + bellH * 0.7f, cx - bellW * 0.45f, bellTop, cx, bellTop)
            close()
        }
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = 1.6f * density
        canvas.drawPath(bellPath, glyphPaint)
        canvas.drawCircle(cx, bellTop + bellH + 2f * density, 1.5f * density, glyphFillPaint)
    }

    private fun performHaptic(effectId: Int) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(effectId))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(20)
                }
            }
        } catch (_: Exception) {}
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(safetyTimeoutRunnable)
        cleanupSpeech()
        activeAnimator?.cancel()
        activeAnimator = null
        wordAnimator?.cancel()
        wordAnimator = null
        ScreenAnalysisOverlay.dismissButtonPress()
        super.onDetachedFromWindow()
    }
}
