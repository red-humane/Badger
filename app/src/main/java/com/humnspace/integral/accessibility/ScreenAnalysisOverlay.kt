package com.humnspace.integral.accessibility

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.View
import android.view.WindowManager
import android.net.Uri
import android.content.Intent
import android.widget.FrameLayout
import android.widget.*
import android.provider.MediaStore
import android.provider.ContactsContract
import android.provider.CalendarContract
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import android.graphics.Typeface
import android.content.ClipboardManager
import com.humnspace.integral.R

/** A temporary, touchable results sheet with a touch-through perimeter animation. */
// Gesture ripple and comet tail nodes for ScreenAnalysis
data class GestureTrailNode(val x: Float, val y: Float, val time: Long)
object ScreenAnalysisOverlay {
    private var windowManager: WindowManager? = null
    private var glowView: View? = null
    private var panelView: View? = null
    private var resultsPanel: View? = null
    private var visualView: View? = null
    private var imagePreviewView: View? = null
    private var activeImageCrop: Bitmap? = null
    private var collapseExpanded: (() -> Boolean)? = null
    private var scanRoot: View? = null
    private var activeScreenshot: Bitmap? = null
    private var overlayGeneration = 0
    private var scanAtTop = false
    private var scanResults: (() -> View)? = null
    private var pendingScreenshot: Bitmap? = null
    private var activeRingerOverlay: RingerExpansionOverlayView? = null
    private var activeVoiceOverlay: VoiceSearchOverlayView? = null
    private var activeVoiceRecordingOverlay: VoiceRecordingOverlayView? = null
    private var activePressIndicator: ButtonPressIndicatorOverlayView? = null

    val isRingerOverlayShowing: Boolean get() = activeRingerOverlay?.isShowing == true
    fun isRingerOverlayActive(): Boolean = activeRingerOverlay?.isShowing == true
    fun getCurrentRingerMode(): String? {
        val ringer = activeRingerOverlay ?: return null
        return if (ringer.isShowing) ringer.currentMode else null
    }
    val isVoiceOverlayShowing: Boolean get() = activeVoiceOverlay?.isShowing == true
    val isVoiceRecordingOverlayShowing: Boolean get() = activeVoiceRecordingOverlay?.isShowing == true
    fun isAnyOverlayShowing(): Boolean {
        return panelView != null || resultsPanel != null || isRingerOverlayShowing || isVoiceOverlayShowing || isVoiceRecordingOverlayShowing || scanRoot != null
    }
    fun dismissRingerOverlay() { activeRingerOverlay?.dismissWithCollapse() }
    fun dismissVoiceSearchOverlay() {
        dismissButtonPress()
        activeVoiceOverlay?.dismissWithCollapse()
    }
    fun dismissVoiceRecordingOverlay() { activeVoiceRecordingOverlay?.dismissWithCollapse() }

    fun dismissButtonPress() {
        val indicator = activePressIndicator
        activePressIndicator = null
        indicator?.let { ind ->
            ind.dismissImmediate()
            try {
                val wm = (ind.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: windowManager
                wm?.removeViewImmediate(ind)
            } catch (_: Exception) {
                try {
                    val wm = (ind.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: windowManager
                    wm?.removeView(ind)
                } catch (_: Exception) {}
            }
            try {
                (ind.parent as? ViewGroup)?.removeView(ind)
            } catch (_: Exception) {}
        }
    }

    fun showButtonPress(
        context: Context,
        type: ButtonPressIndicatorOverlayView.PressType,
        tapIndex: Int = 0
    ) {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = manager
        if (activePressIndicator == null) {
            lateinit var indicator: ButtonPressIndicatorOverlayView
            indicator = ButtonPressIndicatorOverlayView(context) {
                try {
                    manager.removeViewImmediate(indicator)
                } catch (_: Exception) {
                    try {
                        manager.removeView(indicator)
                    } catch (_: Exception) {}
                }
                try {
                    (indicator.parent as? ViewGroup)?.removeView(indicator)
                } catch (_: Exception) {}
                if (activePressIndicator === indicator) {
                    activePressIndicator = null
                }
            }
            activePressIndicator = indicator
            val windowType = if (context is AccessibilityService) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            try {
                manager.addView(indicator, params)
            } catch (_: Exception) {
                (context as? android.app.Activity)?.window?.decorView?.let { decor ->
                    (decor as? ViewGroup)?.addView(indicator)
                }
            }
        }
        activePressIndicator?.triggerPress(type, tapIndex)
    }

    fun onLongPressKeyUp() {
        activeVoiceOverlay?.let { voice ->
            if (voice.isRecording) {
                voice.onButtonReleased()
            }
        }
    }

    fun show(service: AccessibilityService, details: List<ScreenDetail>, titleText: String = "Badger found", subtitleText: String = "Screen details · tap an item to copy", screenshot: Bitmap? = null, textRegions: List<OcrTextItem> = emptyList()) {
        val token = beginScan(service)
        completeScan(token, service, details, screenshot, textRegions, titleText, subtitleText)
    }

    fun beginScan(service: AccessibilityService, isDouble: Boolean = false): Int {
        dismiss()
        val generation = ++overlayGeneration
        val manager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = manager
        activeService = service
        scanAtTop = true
        scanResults = null
        NothingGlyphController.startGetContentGlyph()
        IntegralAcoustics.playScanRadar(service)
        return generation
    }

    fun completeScan(token: Int, service: AccessibilityService, details: List<ScreenDetail>, screenshot: Bitmap?, textRegions: List<OcrTextItem>, titleText: String = "Get Content", subtitleText: String = "Choose an action") {
        if (overlayGeneration != token) { screenshot?.takeIf { !it.isRecycled }?.recycle(); return }
        val manager = windowManager ?: (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        windowManager = manager
        activeService = service
        activeScreenshot = screenshot
        pendingScreenshot = null
        val panel = makePanel(service, details, screenshot, textRegions, onRescan = { (service as? EssentialKeyService)?.rescanContent() })
        panelView = panel
        resultsPanel = panel
        NothingGlyphController.onContentExtracted()
        IntegralAcoustics.playContentDetected(service)
        revealPanel(manager, service, panel)
    }

    fun showCaptureBlocked(service: AccessibilityService, message: String) {
        dismiss()
        val token = ++overlayGeneration
        val manager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = manager
        val panel = makePanel(service, listOf(ScreenDetail("Notice", message, false)), null, emptyList(), onRescan = {})
        panelView = panel
        resultsPanel = panel
        revealPanel(manager, service, panel)
    }

    private fun finishScanIfReady(token: Int) {
        // Replaced by single immediate reveal in completeScan
    }

    private var activeService: AccessibilityService? = null

    private fun revealReadyPanel(token: Int, manager: WindowManager, service: AccessibilityService) {
        if (overlayGeneration != token) return
        val panel = scanResults?.invoke() ?: return
        scanResults = null
        panelView = panel
        resultsPanel = panel
        revealPanel(manager, service, panel)
    }

    private fun revealPanel(manager: WindowManager, context: Context, panel: View) {
        if (windowManager !== manager) return
        try {
            val density = context.resources.displayMetrics.density
            var canDismiss = false
            val modalRoot = object : FrameLayout(context) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.action == KeyEvent.ACTION_UP) {
                            dismissWithAnimation()
                        }
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
            }.apply {
                setBackgroundColor(Color.argb(60, 0, 0, 0)) // 49% dim touch-blocking scrim
                postDelayed({ canDismiss = true }, 400L)
                isFocusable = true
                isFocusableInTouchMode = true
            }

            modalRoot.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    modalRoot.requestFocus()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        try {
                            modalRoot.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY
                            ) {
                                dismissWithAnimation()
                            }
                        } catch (_: Throwable) {}
                    }
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })

            val isContextual = (panel.tag == "contextual_overlay")
            if (isContextual) {
                modalRoot.setBackgroundColor(Color.TRANSPARENT)
                modalRoot.addView(panel, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                resultsPanel = panel

                // In-animation: Expanding yellow sheet from the button with drifting matrix dots & 4-side edge waves
                val prefs = context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE)
                val originY = prefs.getFloat(EssentialKeyService.ANIMATION_ORIGIN_Y, EssentialKeyService.DEFAULT_ORIGIN_Y).coerceIn(0.05f, 0.95f)
                val originX = prefs.getFloat(EssentialKeyService.ANIMATION_ORIGIN_X, EssentialKeyService.DEFAULT_ORIGIN_X).coerceIn(0.02f, 0.98f)
                val cy = context.resources.displayMetrics.heightPixels * originY
                val fromRight = originX >= 0.5f

                // Spring Morphing Shared-Element & Staggered Reveal Transition
                (panel as? GetContentRootLayout)?.startSpringMorphIn(cy, fromRight)
            } else {
                val swipeSheet = SwipeableSheetLayout(context) {
                    dismiss()
                }
                swipeSheet.addView(panel, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ))

                val sheetParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
                modalRoot.addView(swipeSheet, sheetParams)
                resultsPanel = swipeSheet

                modalRoot.setOnClickListener {
                    if (canDismiss) {
                        dismissWithAnimation()
                    }
                }
            }

            val windowParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                windowAnimations = 0
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }

            }

            manager.addView(modalRoot, windowParams)
            panelView = modalRoot

            if (isContextual) {
                panel.alpha = 0f
                panel.animate().alpha(1f).setDuration(220).start()
            } else {
                val swipeSheet = resultsPanel
                swipeSheet?.alpha = 0f
                swipeSheet?.translationY = 90f * density
                modalRoot.alpha = 0f
                modalRoot.animate().alpha(1f).setDuration(220).start()
                swipeSheet?.animate()?.alpha(1f)?.translationY(0f)?.setDuration(260)
                    ?.setInterpolator(android.view.animation.DecelerateInterpolator(1.3f))?.start()
            }
        } catch (_: RuntimeException) { dismiss() }
    }

    private var isDismissingOverlay = false

    fun dismissWithAnimation(onEnd: (() -> Unit)? = null) {
        val root = panelView ?: run { dismiss(); onEnd?.invoke(); return }
        if (isDismissingOverlay) return
        isDismissingOverlay = true
        NothingGlyphController.stopGetContentGlyph()
        NothingGlyphController.stopVoiceRecordingSpectrum()
        IntegralAcoustics.playOverlayDismiss(root.context)

        val context = root.context
        val prefs = context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE)
        val originY = prefs.getFloat(EssentialKeyService.ANIMATION_ORIGIN_Y, EssentialKeyService.DEFAULT_ORIGIN_Y).coerceIn(0.05f, 0.95f)
        val originX = prefs.getFloat(EssentialKeyService.ANIMATION_ORIGIN_X, EssentialKeyService.DEFAULT_ORIGIN_X).coerceIn(0.02f, 0.98f)
        val fromRight = originX >= 0.5f
        val dm = context.resources.displayMetrics
        val cy = dm.heightPixels * originY

        val container = root as? ViewGroup
        val isGetContent = (root.tag == "contextual_overlay") || (container?.findViewWithTag<View>("contextual_overlay") != null)

        if (isGetContent && container != null) {
            root.setBackgroundColor(Color.TRANSPARENT)

            // Spring morph reverse collapse back into physical button location
            val getContentRoot = (container as? GetContentRootLayout)
                ?: container.findViewWithTag<GetContentRootLayout>("contextual_overlay")
                ?: (root as? GetContentRootLayout)
            if (getContentRoot != null) {
                getContentRoot.startSpringMorphOut(cy, fromRight) {
                    isDismissingOverlay = false
                    dismiss()
                    onEnd?.invoke()
                }
                return
            }
        }

        val isFeatureBubbles = (root.tag == "feature_bubbles_overlay") || (container?.findViewWithTag<View>("feature_bubbles_container") != null)
        val wheelView = container?.findViewWithTag<View>("feature_bubbles_container")

        if (isFeatureBubbles && wheelView != null) {
            val targetX = if (fromRight) 85f * dm.density else -85f * dm.density
            val targetY = cy - (wheelView.top + wheelView.height / 2f)
            wheelView.animate()
                .translationX(targetX)
                .translationY(targetY)
                .scaleX(0.08f)
                .scaleY(0.08f)
                .alpha(0f)
                .setDuration(190L)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.4f))
                .start()
            root.animate()
                .alpha(0f)
                .setDuration(200L)
                .withEndAction {
                    isDismissingOverlay = false
                    dismiss()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        onEnd?.invoke()
                    }, 60L)
                }
                .start()
            return
        }

        // Fallback for any other overlay
        root.pivotX = if (fromRight) root.width.toFloat() else 0f
        root.pivotY = cy
        val targetTransX = if (fromRight) 45f * dm.density else -45f * dm.density

        root.animate()
            .scaleX(0f)
            .scaleY(0f)
            .translationX(targetTransX)
            .alpha(0f)
            .setDuration(240L)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.3f))
            .withEndAction {
                isDismissingOverlay = false
                dismiss()
                onEnd?.invoke()
            }
            .start()
    }

    /** Premium, physically responsive ringer expansion overlay. */
    fun showRingerOverlay(
        context: Context,
        mode: String,
        buttonBounds: RectF? = null,
        onDismissed: (() -> Unit)? = null
    ) {
        // If already showing (even if collapsing), update mode seamlessly with haptics
        activeRingerOverlay?.let { current ->
            if (current.isShowing) {
                current.updateMode(mode)
                return
            }
        }

        dismiss()

        val density = context.resources.displayMetrics.density
        val view = RingerExpansionOverlayView(
            context = context,
            mode = mode,
            sourceRect = buttonBounds,
            onDismissComplete = { ringerInstance ->
                val manager = windowManager
                try {
                    manager?.removeView(ringerInstance)
                    (ringerInstance.parent as? ViewGroup)?.removeView(ringerInstance)
                } catch (_: RuntimeException) {}
                if (activeRingerOverlay === ringerInstance) {
                    activeRingerOverlay = null
                }
                onDismissed?.invoke()
            }
        )
        activeRingerOverlay = view
        glowView = view

        if (context is AccessibilityService) {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = manager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                windowAnimations = 0
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }

            }
            try {
                manager.addView(view, params)
                view.start()
            } catch (_: RuntimeException) {
                dismiss()
            }
        } else if (context is android.app.Activity) {
            val decor = context.window?.decorView as? ViewGroup
            if (decor != null) {
                decor.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                view.start()
            }
        }
    }

    private fun mountFloatingRoot(context: Context, root: FrameLayout) {
        dismiss()
        val density = context.resources.displayMetrics.density
        if (context is AccessibilityService) {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = manager
            panelView = root
            resultsPanel = root
            val isFeatureBubbles = root.tag == "feature_bubbles_overlay"
            val windowFlags = if (isFeatureBubbles) {
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            }
            val windowParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                windowFlags,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                windowAnimations = 0
                val density = context.resources.displayMetrics.density
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            try {
                manager.addView(root, windowParams)
                if (isFeatureBubbles) {
                    root.requestFocus()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        try {
                            root.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY
                            ) {
                                dismissWithAnimation()
                            }
                        } catch (_: Throwable) {}
                    }
                }
                root.alpha = 0f
                root.animate().alpha(1f).setDuration(220).start()
            } catch (_: RuntimeException) {
                dismiss()
            }
        } else if (context is android.app.Activity) {
            val decor = context.window?.decorView as? ViewGroup
            if (decor != null) {
                panelView = root
                resultsPanel = root
                decor.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                root.alpha = 0f
                root.animate().alpha(1f).setDuration(220).start()
            }
        }
    }

    /**
     * Minimal floating Feature Bubbles (opened via Triple-Press).
     * Displays 4 sleek floating circular bubbles:
     * - Ring Profile
     * - Get Content
     * - Translate Screen
     * - Voice Search
     */
    fun showFeatureBubbles(
        context: Context,
        onSelectRinger: () -> Unit,
        onSelectGetContent: () -> Unit,
        onSelectVoice: () -> Unit,
        onSelectVoiceRecording: (() -> Unit)? = null,
        onSelectTranslate: (() -> Unit)? = null
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismissWithAnimation()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            tag = "feature_bubbles_overlay"
            setBackgroundColor(Color.TRANSPARENT) // Focused localized blur, no full-screen scrim
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { dismissWithAnimation() }
        }

        val prefs = context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE)
        val originX = prefs.getFloat(EssentialKeyService.ANIMATION_ORIGIN_X, EssentialKeyService.DEFAULT_ORIGIN_X).coerceIn(0.02f, 0.98f)
        val originY = prefs.getFloat(EssentialKeyService.ANIMATION_ORIGIN_Y, EssentialKeyService.DEFAULT_ORIGIN_Y).coerceIn(0.02f, 0.98f)
        val fromRight = originX >= 0.5f

        val dm = context.resources.displayMetrics
        val isLandscape = dm.widthPixels > dm.heightPixels


        // React Bits <OptionWheel /> integration for Feature Tray
        val optionWheel = OptionWheelView(context).apply {
            tag = "feature_bubbles_container"
            side = if (fromRight) "right" else "left"
            tiltDeg = if (isLandscape) 14f else 18f
            curve = 1.0f
            smoothingMs = 180f
            insetPx = if (fromRight) dp(18).toFloat() else dp(18).toFloat()
            rowH = if (isLandscape) dp(52).toFloat() else dp(62).toFloat()
            items = listOf(
                OptionWheelItem("content", "GET CONTENT", "content") {
                    dismissWithAnimation { onSelectGetContent() }
                },
                OptionWheelItem("recorder", "VOICE RECORDER", "recorder") {
                    dismissWithAnimation {
                        if (onSelectVoiceRecording != null) {
                            onSelectVoiceRecording()
                        } else {
                            showVoiceRecordingOverlay(context)
                        }
                    }
                },
                OptionWheelItem("voice", "SAY IT", "voice") {
                    dismissWithAnimation { onSelectVoice() }
                },
                OptionWheelItem("ringer", "DYNAMIC RINGER", "ringer") {
                    dismissWithAnimation { onSelectRinger() }
                }
            )

            // Smooth entrance animation curling from the edge with spring overshoot
            val startOffset = if (fromRight) (if (isLandscape) 55f else 85f) * density else (if (isLandscape) -55f else -85f) * density
            alpha = 0f
            translationX = startOffset
            scaleX = 0.9f
            scaleY = 0.9f
            animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(320L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.15f))
                .start()
        }

        val wheelH = if (isLandscape) dp(320) else dp(420)
        val cy = (dm.heightPixels * originY).toInt()
        val wheelTop = (cy - wheelH / 2).coerceIn(dp(30), (dm.heightPixels - wheelH - dp(30)).coerceAtLeast(dp(30)))

        val wheelParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, wheelH).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = wheelTop
        }
        root.addView(optionWheel, wheelParams)
        mountFloatingRoot(context, root)
    }

    private fun createTrayIconView(context: Context, iconType: String, sizePx: Int): View {
        return object : View(context) {
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * resources.displayMetrics.density
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = Color.rgb(255, 179, 0) // yellow accent
            }
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.rgb(255, 179, 0) // yellow accent
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                val cx = w / 2f
                val cy = h / 2f
                val d = resources.displayMetrics.density

                when (iconType) {
                    "content" -> {
                        // Two overlapping rounded rectangles
                        val cardW = w * 0.54f
                        val cardH = h * 0.62f
                        canvas.drawRoundRect(RectF(w * 0.32f, h * 0.12f, w * 0.32f + cardW, h * 0.12f + cardH), 2.5f * d, 2.5f * d, strokePaint)
                        canvas.drawRoundRect(RectF(w * 0.14f, h * 0.26f, w * 0.14f + cardW, h * 0.26f + cardH), 2.5f * d, 2.5f * d, strokePaint)
                    }
                    "recorder" -> {
                        // Studio microphone capsule + stand
                        val micW = w * 0.34f
                        val micH = h * 0.44f
                        canvas.drawRoundRect(RectF(cx - micW/2f, cy - micH/2f - 2f * d, cx + micW/2f, cy + micH/2f - 2f * d), micW/2f, micW/2f, strokePaint)
                        canvas.drawArc(RectF(cx - micW * 0.85f, cy - micH * 0.18f, cx + micW * 0.85f, cy + micH * 0.55f), 0f, 180f, false, strokePaint)
                        val stemY1 = cy + micH * 0.55f
                        val stemY2 = stemY1 + h * 0.14f
                        canvas.drawLine(cx, stemY1, cx, stemY2, strokePaint)
                        canvas.drawLine(cx - w * 0.22f, stemY2, cx + w * 0.22f, stemY2, strokePaint)
                    }
                    "voice" -> {
                        // Voice search microphone with sound arcs
                        val micW = w * 0.30f
                        val micH = h * 0.42f
                        canvas.drawRoundRect(RectF(cx - micW/2f, cy - micH/2f - 2f * d, cx + micW/2f, cy + micH/2f - 2f * d), micW/2f, micW/2f, strokePaint)
                        canvas.drawArc(RectF(cx - w * 0.44f, cy - h * 0.28f, cx + w * 0.44f, cy + h * 0.28f), -50f, 100f, false, strokePaint)
                        canvas.drawArc(RectF(cx - w * 0.44f, cy - h * 0.28f, cx + w * 0.44f, cy + h * 0.28f), 130f, 100f, false, strokePaint)
                    }
                    "ringer" -> {
                        // Speaker cone + radiating waves
                        val speakerPath = Path().apply {
                            moveTo(cx - w * 0.24f, cy - h * 0.14f)
                            lineTo(cx - w * 0.08f, cy - h * 0.14f)
                            lineTo(cx + w * 0.08f, cy - h * 0.28f)
                            lineTo(cx + w * 0.08f, cy + h * 0.28f)
                            lineTo(cx - w * 0.08f, cy + h * 0.14f)
                            lineTo(cx - w * 0.24f, cy + h * 0.14f)
                            close()
                        }
                        canvas.drawPath(speakerPath, fillPaint)
                        canvas.drawArc(RectF(cx - w * 0.08f, cy - h * 0.20f, cx + w * 0.32f, cy + h * 0.20f), -50f, 100f, false, strokePaint)
                        canvas.drawArc(RectF(cx - w * 0.08f, cy - h * 0.32f, cx + w * 0.44f, cy + h * 0.32f), -50f, 100f, false, strokePaint)
                    }
                }
            }
        }
    }

    /**
     * Standalone Screen Translation triggered directly via Gesture or Feature Bubbles.
     */
    fun showDirectTranslation(context: Context, activeScreen: AccessibilityNodeInfo?) {
        Toast.makeText(context, "Translating screen...", Toast.LENGTH_SHORT).show()
        NothingGlyphController.startTranslateFlicker()
        ScreenTranslator.translate(activeScreen) { translated, languagePair ->
            if (languagePair.isNotBlank() && translated.isNotBlank()) {
                val rootLayout = FrameLayout(context).apply {
                    tag = "direct_translation_overlay"
                    setBackgroundColor(Color.argb(140, 0, 0, 0))
                    isClickable = true
                    setOnClickListener { dismissWithAnimation() }
                }

                showScreenTranslationModal(
                    context = context,
                    container = rootLayout,
                    translatedText = translated,
                    languagePair = languagePair,
                    onClose = { dismissWithAnimation() }
                )
                mountFloatingRoot(context, rootLayout)
            } else {
                Toast.makeText(context, "No text found on screen to translate", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Long-press voice search overlay with Nothing OS-inspired push-out animation,
     * live sound waves, and automatic web search on release.
     */
    fun showVoiceSearchOverlay(context: Context, onDismissed: (() -> Unit)? = null) {
        dismiss()
        val view = VoiceSearchOverlayView(
            context = context,
            onDismissComplete = {
                val manager = windowManager
                try {
                    activeVoiceOverlay?.let { v ->
                        manager?.removeView(v)
                        (v.parent as? ViewGroup)?.removeView(v)
                    }
                } catch (_: RuntimeException) {}
                activeVoiceOverlay = null
                dismissButtonPress()
                onDismissed?.invoke()
            }
        )
        activeVoiceOverlay = view
        glowView = view

        if (context is AccessibilityService) {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = manager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                windowAnimations = 0
                val density = context.resources.displayMetrics.density
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            try {
                manager.addView(view, params)
                view.start()
            } catch (_: RuntimeException) {
                dismiss()
            }
        } else if (context is android.app.Activity) {
            val decor = context.window?.decorView as? ViewGroup
            if (decor != null) {
                decor.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                view.start()
            }
        }
    }

    /**
     * Innovative Feature: Quick Memo
     * Rapid thought capture scratchpad & voice notes shelf with persistence.
     */
    fun showVoiceRecordingOverlay(context: Context, onDismissed: (() -> Unit)? = null) {
        dismiss()
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = manager

        val overlay = VoiceRecordingOverlayView(context) {
            try {
                activeVoiceRecordingOverlay?.let { manager.removeView(it) }
            } catch (_: Exception) {}
            activeVoiceRecordingOverlay = null
            onDismissed?.invoke()
        }
        activeVoiceRecordingOverlay = overlay

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val density = context.resources.displayMetrics.density
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = (16 * density).toInt()
            }
        }

        try {
            manager.addView(overlay, params)
            overlay.startRecordingFlow()
        } catch (_: Exception) {
            dismiss()
        }
    }

    fun showQrLens(context: Context, screenshot: Bitmap?) {
        dismiss()
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        if (screenshot != null) {
            val image = InputImage.fromBitmap(screenshot, 0)
            val scanner = BarcodeScanning.getClient()
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    scanner.close()
                    if (barcodes.isNotEmpty()) {
                        renderQrResults(context, screenshot, barcodes)
                    } else {
                        renderNoQrFound(context)
                    }
                }
                .addOnFailureListener {
                    scanner.close()
                    renderNoQrFound(context)
                }
        } else {
            renderNoQrFound(context)
        }
    }

    private fun renderQrResults(context: Context, screenshot: Bitmap, barcodes: List<Barcode>) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = FrameLayout(context).apply {
            tag = "qr_overlay"
            setBackgroundColor(Color.argb(60, 0, 0, 0))
        }

        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val isLandscape = screenW > screenH
        val scaleX = screenW.toFloat() / screenshot.width
        val scaleY = screenH.toFloat() / screenshot.height

        val bgCanvas = object : View(context) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val dst = RectF()
            val box = RectF()
            override fun onDraw(c: Canvas) {
                super.onDraw(c)
                dst.set(0f, 0f, width.toFloat(), height.toFloat())
                c.drawBitmap(screenshot, null, dst, p)
                c.drawColor(Color.argb(80, 0, 0, 0))

                for (barcode in barcodes) {
                    val b = barcode.boundingBox ?: continue
                    box.set(b.left * scaleX, b.top * scaleY, b.right * scaleX, b.bottom * scaleY)
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 2.5f * density
                    p.color = Color.rgb(255, 216, 77)
                    c.drawRoundRect(box, 8f * density, 8f * density, p)
                }
            }
        }
        root.addView(bgCanvas, FrameLayout.LayoutParams(-1, -1))

        // Action card for the first detected QR
        val firstBarcode = barcodes.first()
        val rawValue = firstBarcode.rawValue.orEmpty()
        val isUpi = rawValue.startsWith("upi://pay", ignoreCase = true)
        val isUrl = rawValue.startsWith("http://", true) || rawValue.startsWith("https://", true) || rawValue.startsWith("www.", true)
        val isWifi = rawValue.startsWith("WIFI:", ignoreCase = true)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 18, 22))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(255, 216, 77))
            }
            elevation = 18f * density
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tagLabel = TextView(context).apply {
            text = when {
                isUpi -> "💰 UPI PAYMENT DETECTED"
                isUrl -> "🌐 WEB LINK DETECTED"
                isWifi -> "📶 WI-FI NETWORK"
                else -> "⚡ QR / BARCODE DETECTED"
            }
            textSize = 10.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setTextColor(Color.rgb(255, 216, 77))
        }
        header.addView(tagLabel, LinearLayout.LayoutParams(0, -2, 1f))
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            isClickable = true
            setOnClickListener { dismissWithAnimation() }
        }
        header.addView(closeBtn, LinearLayout.LayoutParams(-2, -2))
        card.addView(header)

        val valueView = TextView(context).apply {
            text = rawValue
            textSize = 13.5f
            setTextColor(Color.WHITE)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, dp(10))
        }
        card.addView(valueView)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        if (isUpi) {
            actions.addView(pillButton(context, "PAY VIA UPI", true) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(rawValue)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    dismissWithAnimation()
                } catch (_: Exception) {
                    Toast.makeText(context, "No UPI app found", Toast.LENGTH_SHORT).show()
                }
            })
        } else if (isUrl) {
            actions.addView(pillButton(context, "OPEN LINK", true) {
                try {
                    val url = if (rawValue.startsWith("http", true)) rawValue else "https://$rawValue"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    dismissWithAnimation()
                } catch (_: Exception) {}
            })
        }

        actions.addView(pillButton(context, "COPY", !isUpi && !isUrl) {
            val clip = ClipData.newPlainText("QR Code", rawValue)
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
            Toast.makeText(context, "QR content copied", Toast.LENGTH_SHORT).show()
            dismissWithAnimation()
        })
        card.addView(actions)

        val modalW = (screenW * 0.90f).toInt().coerceIn(dp(270), dp(360))
        val cardParams = FrameLayout.LayoutParams(modalW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        root.addView(card, cardParams)

        if (context is AccessibilityService) {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = manager
            revealPanel(manager, context, root)
        }
    }

    private fun renderNoQrFound(context: Context) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = FrameLayout(context).apply {
            tag = "no_qr_overlay"
            setBackgroundColor(Color.argb(60, 0, 0, 0))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 18, 22))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(255, 216, 77))
            }
            elevation = 18f * density
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tagLabel = TextView(context).apply {
            text = "QR & LENS SCANNER"
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(Color.rgb(255, 216, 77))
        }
        header.addView(tagLabel, LinearLayout.LayoutParams(0, -2, 1f))
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            isClickable = true
            setOnClickListener { dismissWithAnimation() }
        }
        header.addView(closeBtn, LinearLayout.LayoutParams(-2, -2))
        card.addView(header)

        val desc = TextView(context).apply {
            text = "No QR code or barcode found on the active screen.\n\nOpen camera scanner to point at real-world codes?"
            textSize = 13.5f
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, dp(14))
        }
        card.addView(desc)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actions.addView(pillButton(context, "CANCEL", false) { dismissWithAnimation() })
        actions.addView(pillButton(context, "CAMERA SCANNER", true) {
            try {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(context, "Camera scanner unavailable", Toast.LENGTH_SHORT).show()
            }
            dismissWithAnimation()
        })
        card.addView(actions)

        val screenW = context.resources.displayMetrics.widthPixels
        val modalW = (screenW * 0.90f).toInt().coerceIn(dp(270), dp(360))
        val params = FrameLayout.LayoutParams(modalW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        root.addView(card, params)

        if (context is AccessibilityService) {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = manager
            revealPanel(manager, context, root)
        } else if (context is android.app.Activity) {
            val decor = context.window?.decorView as? ViewGroup
            decor?.addView(root, ViewGroup.LayoutParams(-1, -1))
        }
    }

    /**
     * Innovative Feature: Screen TL;DR
     * Intelligent on-device structured digest of visible screen content.
     */
    fun showScreenSummarizer(context: Context, activeScreen: AccessibilityNodeInfo?) {
        dismiss()
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val screenContent = collectScreenText(activeScreen)
        if (screenContent.isBlank() || screenContent.length < 30) {
            Toast.makeText(context, "Not enough text on screen to summarize.", Toast.LENGTH_SHORT).show()
            return
        }

        val summary = generateDeterministicSummary(screenContent)

        val root = FrameLayout(context).apply {
            tag = "summarizer_overlay"
            setBackgroundColor(Color.argb(60, 0, 0, 0))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 18, 22))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(255, 216, 77))
            }
            elevation = 18f * density
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tagLabel = TextView(context).apply {
            this.text = "SCREEN TL;DR"
            textSize = 11.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(Color.rgb(255, 216, 77))
        }
        header.addView(tagLabel, LinearLayout.LayoutParams(0, -2, 1f))

        val wordsCount = screenContent.split(Regex("\\s+")).size
        val readTimeMin = (wordsCount / 180).coerceAtLeast(1)
        val statsLabel = TextView(context).apply {
            this.text = "~$wordsCount words · ${readTimeMin}m read"
            textSize = 10f
            setTextColor(Color.rgb(160, 160, 165))
            setPadding(0, 0, dp(12), 0)
        }
        header.addView(statsLabel, LinearLayout.LayoutParams(-2, -2))

        val closeBtn = TextView(context).apply {
            this.text = "✕"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            isClickable = true
            setOnClickListener { dismissWithAnimation() }
        }
        header.addView(closeBtn, LinearLayout.LayoutParams(-2, -2))
        card.addView(header)

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(230)).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
        }
        val summaryView = TextView(context).apply {
            this.text = summary
            textSize = 13.5f
            setTextColor(Color.WHITE)
            setLineSpacing(dp(3).toFloat(), 1.15f)
        }
        scroll.addView(summaryView)
        card.addView(scroll)

        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actionsRow.addView(pillButton(context, "SEARCH TOPIC", false) {
            val mainTopic = summary.lines().firstOrNull { it.isNotBlank() }?.removePrefix("•")?.trim().orEmpty()
            val query = if (mainTopic.length > 5) mainTopic.take(60) else screenContent.take(60)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            dismissWithAnimation()
        })
        actionsRow.addView(pillButton(context, "COPY TL;DR", true) {
            val clip = ClipData.newPlainText("Screen TL;DR", summary)
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
            Toast.makeText(context, "Summary copied to clipboard", Toast.LENGTH_SHORT).show()
            dismissWithAnimation()
        })
        card.addView(actionsRow)

        val screenW = context.resources.displayMetrics.widthPixels
        val modalW = (screenW * 0.90f).toInt().coerceIn(dp(270), dp(360))
        val params = FrameLayout.LayoutParams(modalW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        root.addView(card, params)

        if (context is AccessibilityService) {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = manager
            revealPanel(manager, context, root)
        } else if (context is android.app.Activity) {
            val decor = context.window?.decorView as? ViewGroup
            decor?.addView(root, ViewGroup.LayoutParams(-1, -1))
        }
    }

    private fun collectScreenText(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val values = LinkedHashSet<String>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 500 && values.sumOf(String::length) < 4000) {
            val node = pending.removeLast()
            try {
                if (node.isVisibleToUser && !node.isPassword) {
                    node.text?.toString()?.trim()?.takeIf { it.length >= 3 }?.let(values::add)
                    for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
                }
            } catch (_: RuntimeException) {
            } finally {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        }
        return values.joinToString("\n")
    }

    private fun generateDeterministicSummary(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.length >= 10 }
        if (lines.isEmpty()) return text.take(200)

        val lead = lines.firstOrNull { it.length >= 25 } ?: lines.first()
        val otherLines = lines.filter { it != lead }

        val metricLines = otherLines.filter {
            it.contains(Regex("[0-9]|₹|\\$|%|date|time|score|result|update|price", RegexOption.IGNORE_CASE))
        }.distinct().take(2)

        val remainingLines = otherLines.filter { it !in metricLines }.distinct().take(2)
        val bullets = (metricLines + remainingLines).take(3)

        return buildString {
            append("MAIN TAKEAWAY:\n$lead\n\n")
            if (bullets.isNotEmpty()) {
                append("KEY HIGHLIGHTS:\n")
                bullets.forEach { append("• $it\n") }
            }
        }.trim()
    }

    /** Brief, non-interactive feedback animation for actions that should not open a results sheet. */
    fun animateAction(service: AccessibilityService, action: String) {
        if (action == "ring" || action == "vibrate") {
            showRingerOverlay(service, action)
            return
        }
        dismiss()
        val manager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = ActionFeedbackView(service, action).apply { alpha = 0.75f }
        windowManager = manager
        glowView = view
        try {
            manager.addView(view, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START })
            view.start { removeGlow() }
        } catch (_: RuntimeException) {
            dismiss()
        }
    }

    /**
     * Modal dialog showing full on-device screen translation.
     */
    private fun showScreenTranslationModal(
        context: Context,
        container: FrameLayout,
        translatedText: String,
        languagePair: String,
        onClose: () -> Unit
    ) {
        NothingGlyphController.startTranslateFlicker()
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val card = LinearLayout(context).apply {
            tag = "screen_translation_modal"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 18, 22))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(255, 216, 77))
            }
            elevation = 20f * density
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "TRANSLATION ($languagePair)"
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setTextColor(Color.rgb(255, 216, 77))
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                NothingGlyphController.stopTranslateFlicker()
                onClose()
            }
        }
        header.addView(closeBtn, LinearLayout.LayoutParams(-2, -2))
        card.addView(header)

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(220)).apply {
                topMargin = dp(10)
                bottomMargin = dp(12)
            }
        }
        val content = TextView(context).apply {
            text = translatedText
            textSize = 13.5f
            setTextColor(Color.WHITE)
            setLineSpacing(dp(2).toFloat(), 1.15f)
        }
        scroll.addView(content)
        card.addView(scroll)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(pillButton(context, "COPY TRANSLATION", true) {
            val clip = ClipData.newPlainText("Translation", translatedText)
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
            Toast.makeText(context, "Translation copied to clipboard", Toast.LENGTH_SHORT).show()
            NothingGlyphController.stopTranslateFlicker()
            onClose()
        })
        card.addView(btnRow)

        val screenW = context.resources.displayMetrics.widthPixels
        val modalW = (screenW * 0.90f).toInt().coerceIn(dp(260), dp(360))
        val params = FrameLayout.LayoutParams(modalW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        container.addView(card, params)
        card.alpha = 0f
        card.scaleX = 0.88f
        card.scaleY = 0.88f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
    }

    private fun shareText(context: Context, text: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            dismiss()
        } catch (_: RuntimeException) {
            Toast.makeText(context, "No app available to share", Toast.LENGTH_SHORT).show()
        }
    }

    private enum class ContentMode { TEXT, NUMBERS, LINKS, IMAGES }
    private enum class ResizeCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private enum class TextSelectionHandle { NONE, START, END }
    private data class ScreenTargetItem(val value: String, val bounds: android.graphics.Rect, val type: String)
    private data class SemanticResultItem(
        val id: String,
        val category: String,
        val iconEmoji: String,
        val title: String,
        val subtitle: String?,
        val bounds: android.graphics.Rect,
        val primaryActionName: String,
        val onPrimaryAction: () -> Unit,
        val actions: List<ActionBubbleItem>
    )
    private data class ActionBubbleItem(val label: String, val onClick: () -> Unit)

    private class ActionMenuBubbleDrawable(
        private val cornerRadius: Float,
        private val arrowWidth: Float,
        private val arrowHeight: Float,
        private val fillColor: Int = Color.rgb(18, 18, 20),
        private val strokeColor: Int = Color.argb(55, 255, 255, 255),
        private val strokeWidth: Float = 2.5f
    ) : android.graphics.drawable.Drawable() {
        var arrowX: Float = 0f
        var arrowOnBottom: Boolean = true

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = strokeColor
            this.strokeWidth = strokeWidth
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val path = Path()
            val r = cornerRadius
            val halfW = arrowWidth / 2f

            val rectTop = if (arrowOnBottom) b.top.toFloat() else b.top.toFloat() + arrowHeight
            val rectBottom = if (arrowOnBottom) b.bottom.toFloat() - arrowHeight else b.bottom.toFloat()
            val rectLeft = b.left.toFloat()
            val rectRight = b.right.toFloat()

            val clampedArrowX = arrowX.coerceIn(rectLeft + r + halfW, rectRight - r - halfW)

            path.moveTo(rectLeft + r, rectTop)
            if (!arrowOnBottom) {
                path.lineTo(clampedArrowX - halfW, rectTop)
                path.lineTo(clampedArrowX, b.top.toFloat())
                path.lineTo(clampedArrowX + halfW, rectTop)
            }
            path.lineTo(rectRight - r, rectTop)
            path.quadTo(rectRight, rectTop, rectRight, rectTop + r)

            path.lineTo(rectRight, rectBottom - r)
            path.quadTo(rectRight, rectBottom, rectRight - r, rectBottom)

            if (arrowOnBottom) {
                path.lineTo(clampedArrowX + halfW, rectBottom)
                path.lineTo(clampedArrowX, b.bottom.toFloat())
                path.lineTo(clampedArrowX - halfW, rectBottom)
            }
            path.lineTo(rectLeft + r, rectBottom)
            path.quadTo(rectLeft, rectBottom, rectLeft, rectBottom - r)

            path.lineTo(rectLeft, rectTop + r)
            path.quadTo(rectLeft, rectTop, rectLeft + r, rectTop)
            path.close()

            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = (alpha * 55 / 255)
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /**
     * Get Content: Full-screen interactive workspace.
     * - Minimal draggable/movable 4-mode pill: [ Text | Numbers | Links | Images ]
     * - Circular Close ( ✕ ) button at top-right corner.
     * - Text mode: swipe-across-text selection & single-tap with [ Copy | Search | Share ]
     * - Numbers mode: phone number detection with yellow grids & [ Call | Copy | Share ]
     * - Links mode: URL/email detection with yellow grids & [ Open Link | Copy | Share ]
     * - Images mode: Circle-to-crop with interactive corner resize handles & [ Copy Image | Share ]
     * - Context dialogue menu matching native black capsule with arrow (media_1793864384213_30be3207.png)
     */
    /**
     * Ambient perimeter glow view mimicking Google Gemini Assistant / Circle to Search.
     * Radiates an animated, breathing Badger Yellow aura inward along all four screen edges,
     * bordered by a crisp glowing wireframe edge.
     */
    private class GeminiAmbientGlowView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val glowDepth = 36f * density
        private var glowPulse = 0.5f

        private val topPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bottomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val leftPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }

        private var animator: ValueAnimator? = null

        init {
            isClickable = false
            isFocusable = false
            animator = ValueAnimator.ofFloat(0.35f, 0.95f).apply {
                duration = 1600L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener {
                    glowPulse = it.animatedValue as Float
                    updateGlowPaints()
                    invalidate()
                }
            }
            animator?.start()
        }

        override fun onTouchEvent(event: MotionEvent?): Boolean = false

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateGlowPaints()
        }

        private fun updateGlowPaints() {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val baseAlpha = (glowPulse * 160).toInt().coerceIn(0, 255)
            val yellowColor = Color.argb(baseAlpha, 255, 179, 0)
            val transColor = Color.argb(0, 255, 179, 0)

            topPaint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, glowDepth,
                yellowColor, transColor, Shader.TileMode.CLAMP
            )
            bottomPaint.shader = android.graphics.LinearGradient(
                0f, h, 0f, h - glowDepth,
                yellowColor, transColor, Shader.TileMode.CLAMP
            )
            leftPaint.shader = android.graphics.LinearGradient(
                0f, 0f, glowDepth, 0f,
                yellowColor, transColor, Shader.TileMode.CLAMP
            )
            rightPaint.shader = android.graphics.LinearGradient(
                w, 0f, w - glowDepth, 0f,
                yellowColor, transColor, Shader.TileMode.CLAMP
            )

            borderStrokePaint.color = Color.argb((glowPulse * 190).toInt().coerceIn(0, 255), 255, 179, 0)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            canvas.drawRect(0f, 0f, w, glowDepth, topPaint)
            canvas.drawRect(0f, h - glowDepth, w, h, bottomPaint)
            canvas.drawRect(0f, 0f, glowDepth, h, leftPaint)
            canvas.drawRect(w - glowDepth, 0f, w, h, rightPaint)

            val inset = borderStrokePaint.strokeWidth / 2f
            canvas.drawRect(inset, inset, w - inset, h - inset, borderStrokePaint)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            animator?.cancel()
        }
    }

    private fun makePanel(
        context: Context,
        details: List<ScreenDetail>,
        screenshot: Bitmap?,
        textRegions: List<OcrTextItem>,
        onRescan: () -> Unit
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val satoshiRegular = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.satoshi_regular) ?: Typeface.DEFAULT
        val satoshiMedium = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.satoshi_medium) ?: Typeface.DEFAULT_BOLD
        val satoshiBold = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.satoshi_bold) ?: Typeface.DEFAULT_BOLD

        val yellowAccent = Color.rgb(255, 179, 0)
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels

        val rootLayout = GetContentRootLayout(context).apply {
            tag = "contextual_overlay"
        }

        var currentMode = ContentMode.TEXT

        // 1. Detected phone numbers
        val detectedPhoneItems = mutableListOf<ScreenTargetItem>()
        val seenPhones = HashSet<String>()
        fun addPhoneIfValid(numStr: String, bounds: Rect?) {
            if (bounds == null) return
            val cleanDigits = numStr.filter(Char::isDigit)
            if (cleanDigits.length in 7..15) {
                val key = if (cleanDigits.length >= 10) cleanDigits.takeLast(10) else cleanDigits
                if (seenPhones.add(key)) {
                    detectedPhoneItems.add(ScreenTargetItem(numStr.trim(), bounds, "phone"))
                }
            }
        }

        for (d in details) {
            if (d.kind == "Phone number" && d.bounds != null) {
                addPhoneIfValid(d.value, d.bounds)
            }
        }

        for (item in textRegions) {
            val trimmed = item.text.trim()
            ScreenContentAnalyzer.extractPhones(trimmed).forEach { phone ->
                addPhoneIfValid(phone, item.bounds)
            }
        }

        // Reconstruct line clusters from textRegions to catch multi-word phone numbers
        if (textRegions.size >= 2) {
            val sortedRegions = textRegions.sortedWith(compareBy<OcrTextItem> { it.bounds.top }.thenBy { it.bounds.left })
            val lineClusters = mutableListOf<MutableList<OcrTextItem>>()
            for (w in sortedRegions) {
                val cy = w.bounds.exactCenterY()
                val line = lineClusters.firstOrNull { cluster ->
                    val avgY = cluster.map { it.bounds.exactCenterY() }.average()
                    kotlin.math.abs(cy - avgY) <= (14f * density)
                }
                if (line != null) {
                    line.add(w)
                } else {
                    lineClusters.add(mutableListOf(w))
                }
            }
            for (line in lineClusters) {
                line.sortBy { it.bounds.left }
                val lineStr = line.joinToString(" ") { it.text.trim() }
                ScreenContentAnalyzer.extractPhones(lineStr).forEach { phone ->
                    val matching = line.filter { elem ->
                        val digits = elem.text.filter(Char::isDigit)
                        digits.isNotEmpty() && phone.contains(digits)
                    }
                    val targetBounds = if (matching.isNotEmpty()) {
                        val union = Rect(matching.first().bounds)
                        for (elem in matching.drop(1)) union.union(elem.bounds)
                        union
                    } else {
                        val union = Rect(line.first().bounds)
                        for (elem in line.drop(1)) union.union(elem.bounds)
                        union
                    }
                    addPhoneIfValid(phone, targetBounds)
                }
            }
        }

        // 2. Detected links & emails
        val detectedLinkItems = mutableListOf<ScreenTargetItem>()
        val seenLinks = HashSet<String>()
        fun addLinkIfValid(rawUrl: String, bounds: Rect?, isEmail: Boolean = false) {
            if (bounds == null) return
            val clean = ScreenContentAnalyzer.cleanUrl(rawUrl) ?: if (isEmail) rawUrl.trim() else null
            if (clean != null) {
                val norm = clean.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
                if (seenLinks.add(norm)) {
                    detectedLinkItems.add(ScreenTargetItem(clean, bounds, if (isEmail) "email" else "link"))
                }
            }
        }

        for (d in details) {
            if ((d.kind == "Link" || d.kind == "Email") && d.bounds != null) {
                addLinkIfValid(d.value, d.bounds, d.kind == "Email")
            }
        }

        for (item in textRegions) {
            val trimmed = item.text.trim()
            ScreenContentAnalyzer.extractLinks(trimmed).forEach { url ->
                addLinkIfValid(url, item.bounds)
            }
            ScreenContentAnalyzer.extractEmails(trimmed).forEach { email ->
                addLinkIfValid(email, item.bounds, isEmail = true)
            }
        }

        // Reconstruct line clusters from textRegions to catch multi-token URLs
        if (textRegions.size >= 2) {
            val sortedRegions = textRegions.sortedWith(compareBy<OcrTextItem> { it.bounds.top }.thenBy { it.bounds.left })
            val lineClusters = mutableListOf<MutableList<OcrTextItem>>()
            for (w in sortedRegions) {
                val cy = w.bounds.exactCenterY()
                val line = lineClusters.firstOrNull { cluster ->
                    val avgY = cluster.map { it.bounds.exactCenterY() }.average()
                    kotlin.math.abs(cy - avgY) <= (14f * density)
                }
                if (line != null) {
                    line.add(w)
                } else {
                    lineClusters.add(mutableListOf(w))
                }
            }
            for (line in lineClusters) {
                line.sortBy { it.bounds.left }
                val lineStr = line.joinToString(" ") { it.text.trim() }
                ScreenContentAnalyzer.extractLinks(lineStr).forEach { url ->
                    val matching = line.filter { elem ->
                        elem.text.isNotEmpty() && url.contains(elem.text.trim(), ignoreCase = true)
                    }
                    val targetBounds = if (matching.isNotEmpty()) {
                        val union = Rect(matching.first().bounds)
                        for (elem in matching.drop(1)) union.union(elem.bounds)
                        union
                    } else {
                        val union = Rect(line.first().bounds)
                        for (elem in line.drop(1)) union.union(elem.bounds)
                        union
                    }
                    addLinkIfValid(url, targetBounds)
                }
                ScreenContentAnalyzer.extractEmails(lineStr).forEach { email ->
                    val matching = line.filter { elem ->
                        elem.text.isNotEmpty() && email.contains(elem.text.trim(), ignoreCase = true)
                    }
                    val targetBounds = if (matching.isNotEmpty()) {
                        val union = Rect(matching.first().bounds)
                        for (elem in matching.drop(1)) union.union(elem.bounds)
                        union
                    } else {
                        val union = Rect(line.first().bounds)
                        for (elem in line.drop(1)) union.union(elem.bounds)
                        union
                    }
                    addLinkIfValid(email, targetBounds, isEmail = true)
                }
            }
        }

        // 3. Context-Aware Detected Events (e.g. "Call Geetha on Wednesday at 3 PM")
        val detectedEventItems = mutableListOf<DetectedEventItem>()
        val seenEvents = HashSet<String>()
        fun addEventIfValid(item: DetectedEventItem?) {
            if (item == null) return
            val key = "${item.title.lowercase()}_${item.startMillis}"
            if (seenEvents.add(key)) {
                detectedEventItems.add(item)
            }
        }

        for (d in details) {
            if (d.bounds != null) {
                addEventIfValid(CalendarEventManager.parseEventCandidate(d.value, d.bounds))
            }
        }

        if (textRegions.size >= 2) {
            val sortedRegions = textRegions.sortedWith(compareBy<OcrTextItem> { it.bounds.top }.thenBy { it.bounds.left })
            val lineClusters = mutableListOf<MutableList<OcrTextItem>>()
            for (w in sortedRegions) {
                val cy = w.bounds.exactCenterY()
                val line = lineClusters.firstOrNull { cluster ->
                    val avgY = cluster.map { it.bounds.exactCenterY() }.average()
                    kotlin.math.abs(cy - avgY) <= (14f * density)
                }
                if (line != null) {
                    line.add(w)
                } else {
                    lineClusters.add(mutableListOf(w))
                }
            }
            for (line in lineClusters) {
                line.sortBy { it.bounds.left }
                val lineStr = line.joinToString(" ") { it.text.trim() }
                val union = Rect(line.first().bounds)
                for (elem in line.drop(1)) union.union(elem.bounds)
                addEventIfValid(CalendarEventManager.parseEventCandidate(lineStr, union))
            }
        }

        // 4. Detected Addresses
        val detectedAddressItems = mutableListOf<ScreenTargetItem>()
        val seenAddresses = HashSet<String>()
        fun addAddressIfValid(addrStr: String, bounds: Rect?) {
            if (bounds == null) return
            val clean = addrStr.trim()
            if (clean.length >= 6 && seenAddresses.add(clean.lowercase())) {
                detectedAddressItems.add(ScreenTargetItem(clean, bounds, "address"))
            }
        }
        for (d in details) {
            if (d.kind == "Address" && d.bounds != null) {
                addAddressIfValid(d.value, d.bounds)
            }
        }
        for (item in textRegions) {
            ScreenContentAnalyzer.extractAddresses(item.text).forEach { addr ->
                addAddressIfValid(addr, item.bounds)
            }
        }

        // Active selection states
        var activeTextRect: RectF? = null
        var selectedTargetItem: ScreenTargetItem? = null
        var selectedCropRect: RectF? = null
        var croppedBitmap: Bitmap? = null
        var activeResizeCorner: ResizeCorner? = null

        // Action Menu Bubble (capsule matching media_1793864384213_30be3207.png)
        val bubbleDrawable = ActionMenuBubbleDrawable(
            cornerRadius = dp(10).toFloat(),
            arrowWidth = dp(14).toFloat(),
            arrowHeight = dp(7).toFloat(),
            fillColor = Color.rgb(18, 18, 20),
            strokeColor = Color.argb(160, 255, 179, 0),
            strokeWidth = 1.2f * density
        )

        val actionBubbleLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#141418"))
                setStroke(dp(1), Color.argb(120, 255, 179, 0))
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            elevation = 0f
            outlineProvider = null
            visibility = View.GONE
        }

        
        fun saveCroppedImageToGallery(context: Context, bitmap: Bitmap?) {
            if (bitmap == null) return
            IntegralAcoustics.playCropExtract(context)
            SavedCropsManager.saveCroppedBitmap(context, bitmap)
        }

        fun searchImageWithLens(context: Context, bitmap: Bitmap?) {
            if (bitmap == null) return
            try {
                val file = java.io.File(context.cacheDir, "lens_query.png")
                val stream = java.io.FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.flush()
                stream.close()
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.humnspace.integral.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    setPackage("com.google.android.googlequicksearchbox")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                // Fallback to general image share / search
                shareCroppedImage(context, bitmap)
            }
        }

        fun showActionMenu(targetRect: RectF, items: List<ActionBubbleItem>) {
            actionBubbleLayout.removeAllViews()

            val appleAmber = Color.parseColor("#FFB300")
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    val divider = View(context).apply {
                        setBackgroundColor(Color.argb(70, 80, 80, 85))
                    }
                    val divParams = LinearLayout.LayoutParams(dp(1), dp(20)).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        leftMargin = dp(2)
                        rightMargin = dp(2)
                    }
                    actionBubbleLayout.addView(divider, divParams)
                }

                val isPrimary = (index == 0)
                val btn = TextView(context).apply {
                    text = item.label
                    textSize = 12.5f
                    typeface = if (isPrimary) satoshiBold else satoshiMedium
                    setTextColor(if (isPrimary) Color.BLACK else Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    if (isPrimary) {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(9).toFloat()
                            setColor(appleAmber)
                        }
                    } else {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(9).toFloat()
                            setColor(Color.TRANSPARENT)
                        }
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        IntegralAcoustics.playItemSelect(context)
                        item.onClick()
                    }
                }
                actionBubbleLayout.addView(btn)
            }

            actionBubbleLayout.measure(
                View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
            )
            val bubbleW = actionBubbleLayout.measuredWidth.coerceAtLeast(dp(120))
            val bubbleH = actionBubbleLayout.measuredHeight.coerceAtLeast(dp(38))

            // Account for physical 3% lift scale if crop is selected
            val isLifted = (selectedCropRect != null)
            val targetBottom = if (isLifted) targetRect.centerY() + (targetRect.height() / 2f) * 1.03f else targetRect.bottom
            val targetTop = if (isLifted) targetRect.centerY() - (targetRect.height() / 2f) * 1.03f else targetRect.top

            // Floating Contextual Action Pill glides directly underneath or above the lassoed text / image
            val canPlaceBelow = (targetBottom + bubbleH + dp(65)) < screenH
            val placeAbove = !canPlaceBelow
            actionBubbleLayout.setPadding(dp(4), dp(4), dp(4), dp(4))

            val bubbleX = (targetRect.centerX() - bubbleW / 2f).coerceIn(dp(12).toFloat(), (screenW - bubbleW - dp(12)).toFloat())
            val bubbleY = if (placeAbove) targetTop - bubbleH - dp(8) else targetBottom + dp(8)
            actionBubbleLayout.x = bubbleX

            // Fluid spring glide animation into resting position
            val startY = if (placeAbove) bubbleY + dp(22) else bubbleY - dp(22)
            actionBubbleLayout.y = startY
            actionBubbleLayout.alpha = 0f
            actionBubbleLayout.scaleX = 0.90f
            actionBubbleLayout.scaleY = 0.90f
            actionBubbleLayout.visibility = View.VISIBLE

            actionBubbleLayout.animate()
                .alpha(1f)
                .y(bubbleY)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(230L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.35f))
                .start()
        }

        // 1. Fullscreen Interactive Screen Canvas
        val interactiveCanvas = object : View(context) {
            private val scanStartTime = System.currentTimeMillis()
            private val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val numberUnderlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                color = Color.rgb(255, 179, 0)
                strokeWidth = 2.4f * density
            }
            private val linkBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.argb(220, 255, 179, 0)
                strokeWidth = 1.4f * density
            }
            private val sheenUnderlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
            private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }

            // Selection Bounding Box paints for Text & Numbers
            private val textSelectionFillSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(55, 255, 179, 0)
            }
            private val textSelectionStrokeSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.rgb(255, 179, 0)
                strokeWidth = 1.8f * density
            }

            // Crop & Chroma paints for circled image
            private val chromaWashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            private val chromaGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            private val chromaHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            private val imageCropBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * density
                color = yellowAccent
            }
            private val imageCropCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3.5f * density
                strokeCap = Paint.Cap.ROUND
                color = yellowAccent
            }

            // Handle paints
            private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = yellowAccent
            }
            private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.8f * density
                color = Color.rgb(18, 18, 22)
            }
            private val handleCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.rgb(18, 18, 22)
            }

            // Yellow comet tail paints
            private val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val tailAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val tailHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }

            private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            private val destRect = RectF()
            private var scale = 1f
            private var offX = 0f
            private var offY = 0f

            private val touchPoints = mutableListOf<PointF>()
            private var downX = 0f
            private var downY = 0f
            private var isDraggingGesture = false
            private val gestureTrail = mutableListOf<GestureTrailNode>()
            private val gesturePath = Path()

            private var cropChromaAnimator: ValueAnimator? = null
            private var cropRevealAlpha = 0f
            private var activeTextHandle: String? = null

            private var isSnappingLasso = false
            private var lassoSnapProgress = 0f
            private val morphUserPoints = mutableListOf<PointF>()
            private val morphTargetPoints = mutableListOf<PointF>()
            private val targetSnappingBox = RectF()
            private var lassoSnapAnimator: ValueAnimator? = null

            private fun resamplePoints(pts: List<PointF>, n: Int = 64): List<PointF> {
                if (pts.size < 2) return List(n) { pts.firstOrNull() ?: PointF() }
                val closed = pts.toMutableList()
                val first = closed.first()
                val last = closed.last()
                if (kotlin.math.hypot((first.x - last.x).toDouble(), (first.y - last.y).toDouble()) > 1.0) {
                    closed.add(PointF(first.x, first.y))
                }
                val dists = FloatArray(closed.size)
                dists[0] = 0f
                for (i in 1 until closed.size) {
                    val d = kotlin.math.hypot((closed[i].x - closed[i - 1].x).toDouble(), (closed[i].y - closed[i - 1].y).toDouble()).toFloat()
                    dists[i] = dists[i - 1] + d
                }
                val totalLen = dists.last()
                if (totalLen <= 0.01f) return List(n) { PointF(first.x, first.y) }

                val resampled = ArrayList<PointF>(n)
                val step = totalLen / n
                var curIdx = 0
                for (i in 0 until n) {
                    val targetD = i * step
                    while (curIdx < dists.size - 2 && dists[curIdx + 1] < targetD) {
                        curIdx++
                    }
                    val segLen = dists[curIdx + 1] - dists[curIdx]
                    if (segLen <= 0.0001f) {
                        resampled.add(PointF(closed[curIdx].x, closed[curIdx].y))
                    } else {
                        val t = (targetD - dists[curIdx]) / segLen
                        val x = closed[curIdx].x + t * (closed[curIdx + 1].x - closed[curIdx].x)
                        val y = closed[curIdx].y + t * (closed[curIdx + 1].y - closed[curIdx].y)
                        resampled.add(PointF(x, y))
                    }
                }
                return resampled
            }

            private fun sampleRoundedRect(rect: RectF, radius: Float, n: Int = 64): List<PointF> {
                val left = rect.left
                val top = rect.top
                val right = rect.right
                val bottom = rect.bottom
                val w = right - left
                val h = bottom - top
                val r = radius.coerceAtMost(w / 2f).coerceAtMost(h / 2f)

                val seg1Len = w / 2f - r
                val arcLen = (0.5 * Math.PI * r).toFloat()
                val seg2Len = h - 2f * r
                val seg3Len = w - 2f * r
                val seg4Len = h - 2f * r

                val total = 2f * (w - 2f * r) + 2f * (h - 2f * r) + (2.0 * Math.PI * r).toFloat()
                val pts = ArrayList<PointF>(n)

                for (i in 0 until n) {
                    val d = (i.toFloat() / n.toFloat()) * total
                    val pt: PointF = when {
                        d < seg1Len -> {
                            PointF(left + w / 2f + d, top)
                        }
                        d < seg1Len + arcLen -> {
                            val ad = d - seg1Len
                            val ang = -Math.PI / 2.0 + (ad / arcLen) * (Math.PI / 2.0)
                            PointF((right - r) + r * kotlin.math.cos(ang).toFloat(), (top + r) + r * kotlin.math.sin(ang).toFloat())
                        }
                        d < seg1Len + arcLen + seg2Len -> {
                            val sd = d - (seg1Len + arcLen)
                            PointF(right, top + r + sd)
                        }
                        d < seg1Len + 2f * arcLen + seg2Len -> {
                            val ad = d - (seg1Len + arcLen + seg2Len)
                            val ang = 0.0 + (ad / arcLen) * (Math.PI / 2.0)
                            PointF((right - r) + r * kotlin.math.cos(ang).toFloat(), (bottom - r) + r * kotlin.math.sin(ang).toFloat())
                        }
                        d < seg1Len + 2f * arcLen + seg2Len + seg3Len -> {
                            val sd = d - (seg1Len + 2f * arcLen + seg2Len)
                            PointF((right - r) - sd, bottom)
                        }
                        d < seg1Len + 3f * arcLen + seg2Len + seg3Len -> {
                            val ad = d - (seg1Len + 2f * arcLen + seg2Len + seg3Len)
                            val ang = Math.PI / 2.0 + (ad / arcLen) * (Math.PI / 2.0)
                            PointF((left + r) + r * kotlin.math.cos(ang).toFloat(), (bottom - r) + r * kotlin.math.sin(ang).toFloat())
                        }
                        d < seg1Len + 3f * arcLen + seg2Len + seg3Len + seg4Len -> {
                            val sd = d - (seg1Len + 3f * arcLen + seg2Len + seg3Len)
                            PointF(left, (bottom - r) - sd)
                        }
                        else -> {
                            val ad = d - (seg1Len + 3f * arcLen + seg2Len + seg3Len + seg4Len)
                            val ang = Math.PI + (ad / arcLen) * (Math.PI / 2.0)
                            PointF((left + r) + r * kotlin.math.cos(ang).toFloat(), (top + r) + r * kotlin.math.sin(ang).toFloat())
                        }
                    }
                    pts.add(pt)
                }
                return pts
            }

            private fun prepareLassoMorph(rawUserPoints: List<PointF>, box: RectF, cornerRadius: Float) {
                val n = 64
                val userResampled = resamplePoints(rawUserPoints, n).toMutableList()
                val targetSampled = sampleRoundedRect(box, cornerRadius, n)

                // Check winding direction (signed area): if counter-clockwise (area < 0 in screen coords), reverse
                var area = 0.0
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    area += (userResampled[i].x * userResampled[j].y - userResampled[j].x * userResampled[i].y)
                }
                if (area < 0.0) {
                    userResampled.reverse()
                }

                // Phase align: find user point closest to top-center of target box (targetSampled[0])
                val topCenter = targetSampled[0]
                var bestIdx = 0
                var minD = Float.MAX_VALUE
                for (i in 0 until n) {
                    val d = kotlin.math.hypot((userResampled[i].x - topCenter.x).toDouble(), (userResampled[i].y - topCenter.y).toDouble()).toFloat()
                    if (d < minD) {
                        minD = d
                        bestIdx = i
                    }
                }
                // Rotate user points so bestIdx is at index 0
                val alignedUser = ArrayList<PointF>(n)
                for (i in 0 until n) {
                    alignedUser.add(userResampled[(bestIdx + i) % n])
                }

                morphUserPoints.clear()
                morphUserPoints.addAll(alignedUser)
                morphTargetPoints.clear()
                morphTargetPoints.addAll(targetSampled)
            }
            private val cropShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.TRANSPARENT
            }
            private val grayscaleColorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

            init {
                setLayerType(LAYER_TYPE_HARDWARE, null)
            }

            private fun drawPreciseSelectionBox(canvas: Canvas, rect: RectF) {
                val padH = 3.5f * density
                val padV = 1.5f * density
                val fitted = RectF(rect.left - padH, rect.top - padV, rect.right + padH, rect.bottom + padV)
                canvas.drawRoundRect(fitted, 4f * density, 4f * density, textSelectionFillSelected)
                canvas.drawRoundRect(fitted, 4f * density, 4f * density, textSelectionStrokeSelected)
            }

            private fun drawSubtleYellowLightReflection(canvas: Canvas, rect: RectF, now: Long) {
                val baselineY = rect.bottom + 1.2f * density
                sheenUnderlinePaint.color = Color.argb(40, 255, 179, 0)
                sheenUnderlinePaint.strokeWidth = 1.2f * density
                canvas.drawLine(rect.left + 2f * density, baselineY, rect.right - 2f * density, baselineY, sheenUnderlinePaint)

                val cycle = ((now % 2600L).toFloat() / 2600f)
                val sheenCenter = rect.left + (rect.width() + 60f * density) * cycle - 30f * density
                val sheenW = 28f * density
                sheenPaint.shader = android.graphics.LinearGradient(
                    sheenCenter - sheenW, rect.top,
                    sheenCenter + sheenW, rect.bottom,
                    intArrayOf(0, Color.argb(28, 255, 179, 0), 0),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(rect, 3f * density, 3f * density, sheenPaint)
            }

            private fun drawTwoEndsTextSelectionHandles(canvas: Canvas, rect: RectF) {
                val handleRadius = 8f * density
                val pinStrokeW = 2.4f * density
                val pinDrop = 10f * density

                // Start Handle (Left end pin)
                canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom + pinDrop, handleStrokePaint.apply {
                    strokeWidth = pinStrokeW
                    color = yellowAccent
                })
                canvas.drawCircle(rect.left, rect.bottom + pinDrop, handleRadius, handleFillPaint)
                canvas.drawCircle(rect.left, rect.bottom + pinDrop, handleRadius, handleStrokePaint.apply {
                    strokeWidth = 1.8f * density
                    color = Color.rgb(18, 18, 22)
                })
                canvas.drawCircle(rect.left, rect.bottom + pinDrop, 2.5f * density, handleCenterPaint)

                // End Handle (Right end pin)
                canvas.drawLine(rect.right, rect.top, rect.right, rect.bottom + pinDrop, handleStrokePaint.apply {
                    strokeWidth = pinStrokeW
                    color = yellowAccent
                })
                canvas.drawCircle(rect.right, rect.bottom + pinDrop, handleRadius, handleFillPaint)
                canvas.drawCircle(rect.right, rect.bottom + pinDrop, handleRadius, handleStrokePaint.apply {
                    strokeWidth = 1.8f * density
                    color = Color.rgb(18, 18, 22)
                })
                canvas.drawCircle(rect.right, rect.bottom + pinDrop, 2.5f * density, handleCenterPaint)
            }

            private fun drawImageCropSelection(canvas: Canvas, rect: RectF) {
                canvas.drawRoundRect(rect, 6f * density, 6f * density, imageCropBorderPaint)
                val clen = 16f * density
                canvas.drawLine(rect.left, rect.top, rect.left + clen, rect.top, imageCropCornerPaint)
                canvas.drawLine(rect.left, rect.top, rect.left, rect.top + clen, imageCropCornerPaint)
                canvas.drawLine(rect.right - clen, rect.top, rect.right, rect.top, imageCropCornerPaint)
                canvas.drawLine(rect.right, rect.top, rect.right, rect.top + clen, imageCropCornerPaint)
                canvas.drawLine(rect.left, rect.bottom, rect.left + clen, rect.bottom, imageCropCornerPaint)
                canvas.drawLine(rect.left, rect.bottom - clen, rect.left, rect.bottom, imageCropCornerPaint)
                canvas.drawLine(rect.right - clen, rect.bottom, rect.right, rect.bottom, imageCropCornerPaint)
                canvas.drawLine(rect.right, rect.bottom - clen, rect.right, rect.bottom, imageCropCornerPaint)

                // Draggable tactile handles on 4 corners
                val handleR = 5.5f * density
                val corners = listOf(
                    PointF(rect.left, rect.top),
                    PointF(rect.right, rect.top),
                    PointF(rect.left, rect.bottom),
                    PointF(rect.right, rect.bottom)
                )
                for (c in corners) {
                    canvas.drawCircle(c.x, c.y, handleR, handleFillPaint)
                    canvas.drawCircle(c.x, c.y, handleR, handleStrokePaint.apply {
                        color = Color.rgb(18, 18, 22)
                        strokeWidth = 1.6f * density
                    })
                }
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val now = System.currentTimeMillis()

                if (screenshot != null && !screenshot.isRecycled) {
                    scale = minOf(width.toFloat() / screenshot.width, height.toFloat() / screenshot.height)
                    val drawW = screenshot.width * scale
                    val drawH = screenshot.height * scale
                    offX = (width - drawW) / 2f
                    offY = (height - drawH) / 2f
                    destRect.set(offX, offY, offX + drawW, offY + drawH)

                    // Draw base screenshot: monochrome grayscale if an image is squared, else full color
                    val hasSquaredImage = (selectedCropRect != null || isSnappingLasso)
                    if (hasSquaredImage) {
                        paint.colorFilter = grayscaleColorFilter
                        canvas.drawBitmap(screenshot, null, destRect, paint)
                        paint.colorFilter = null
                        canvas.drawColor(Color.argb(30, 10, 10, 14))
                    } else {
                        paint.colorFilter = null
                        canvas.drawBitmap(screenshot, null, destRect, paint)
                    }

                    // White subtle grid with 45% max opacity spreading across like a grid and fading out
                    val scanElapsed = now - scanStartTime
                    if (scanElapsed in 0..1300L) {
                        val t = scanElapsed / 1300f
                        val loc = DeviceOrientationHelper.getButtonScreenLocation(context, width.toFloat(), height.toFloat())
                        val ox = loc.x
                        val oy = loc.y
                        val maxDist = kotlin.math.hypot(
                            maxOf(ox, width.toFloat() - ox).toDouble(),
                            maxOf(oy, height.toFloat() - oy).toDouble()
                        ).toFloat().coerceAtLeast(100f)

                        // Spreads across the screen in first ~55% of duration, then smoothly fades out
                        val spreadProgress = (t / 0.55f).coerceIn(0f, 1f)
                        val waveRadius = (maxDist * spreadProgress).coerceAtLeast(20f)
                        val fadeProgress = if (t < 0.55f) 1f else (1f - (t - 0.55f) / 0.45f).coerceIn(0f, 1f)
                        // Opacity 45% max (115/255 = 0.451)
                        val maxAlpha = (115 * fadeProgress).toInt().coerceIn(0, 115)

                        if (maxAlpha > 2) {
                            val gridStep = 32f * density
                            val gridStroke = 0.85f * density

                            // Radial gradient shader for grid wave propagation from the Badge button
                            val leadAlpha = maxAlpha
                            val trailAlpha = (maxAlpha * 0.75f).toInt()
                            val colors = intArrayOf(
                                Color.argb(trailAlpha, 255, 255, 255),
                                Color.argb(leadAlpha, 255, 255, 255),
                                Color.argb((leadAlpha * 0.35f).toInt(), 255, 255, 255),
                                0
                            )
                            val stops = floatArrayOf(0f, 0.85f, 0.98f, 1f)
                            tempPaint.shader = android.graphics.RadialGradient(
                                ox, oy, waveRadius,
                                colors, stops,
                                Shader.TileMode.CLAMP
                            )
                            tempPaint.style = Paint.Style.STROKE
                            tempPaint.strokeWidth = gridStroke

                            // Vertical grid lines
                            var gx = 0f
                            while (gx <= width.toFloat()) {
                                canvas.drawLine(gx, 0f, gx, height.toFloat(), tempPaint)
                                gx += gridStep
                            }

                            // Horizontal grid lines
                            var gy = 0f
                            while (gy <= height.toFloat()) {
                                canvas.drawLine(0f, gy, width.toFloat(), gy, tempPaint)
                                gy += gridStep
                            }

                            // Subtle intersection dots within active wave
                            tempPaint.shader = null
                            tempPaint.style = Paint.Style.FILL
                            val dotAlpha = (maxAlpha * 0.85f).toInt()
                            tempPaint.color = Color.argb(dotAlpha, 255, 255, 255)
                            val dotR = 1.2f * density

                            gx = 0f
                            while (gx <= width.toFloat()) {
                                gy = 0f
                                while (gy <= height.toFloat()) {
                                    val d = kotlin.math.hypot((gx - ox).toDouble(), (gy - oy).toDouble()).toFloat()
                                    if (d <= waveRadius) {
                                        canvas.drawCircle(gx, gy, dotR, tempPaint)
                                    }
                                    gy += gridStep
                                }
                                gx += gridStep
                            }
                        }
                    }

                    // Perfect Geometric Square Snapping: cleanly animates the circled area as a square with 3% lift & ambient shadow
                    if (isSnappingLasso) {
                        val p = lassoSnapProgress.coerceIn(0f, 1.25f)
                        val cx = targetSnappingBox.centerX()
                        val cy = targetSnappingBox.centerY()
                        val liftScale = 0.90f + 0.13f * p.coerceAtMost(1f) // Smooth physical spring expansion from 0.90 to 1.03
                        val cornerR = 14f * density

                        // 1. Soft physical ambient drop shadow blossoming under the lifted image
                        if (p > 0.05f) {
                            val shadowAlpha = (165 * p.coerceAtMost(1f)).toInt().coerceIn(0, 255)
                            val shadowOffset = 7f * density * p.coerceAtMost(1f)
                            val shadowW = targetSnappingBox.width() * liftScale
                            val shadowH = targetSnappingBox.height() * liftScale
                            val shadowRect = RectF(
                                cx - shadowW / 2f,
                                cy - shadowH / 2f + shadowOffset,
                                cx + shadowW / 2f,
                                cy + shadowH / 2f + shadowOffset
                            )
                            cropShadowPaint.setShadowLayer(
                                16f * density * p.coerceAtMost(1f),
                                0f,
                                shadowOffset * 0.75f,
                                Color.argb(shadowAlpha, 0, 0, 0)
                            )
                            canvas.drawRoundRect(shadowRect, cornerR, cornerR, cropShadowPaint)
                            cropShadowPaint.clearShadowLayer()
                        }

                        // 2. Full color vibrant screenshot revealed inside the clean rounded square
                        if (screenshot != null && !screenshot.isRecycled) {
                            canvas.save()
                            canvas.scale(liftScale, liftScale, cx, cy)
                            val clipPath = Path().apply {
                                addRoundRect(targetSnappingBox, cornerR, cornerR, Path.Direction.CW)
                            }
                            canvas.clipPath(clipPath)
                            paint.colorFilter = null
                            paint.alpha = (p.coerceAtMost(1f) * 255).toInt().coerceIn(0, 255)
                            canvas.drawBitmap(screenshot, null, destRect, paint)
                            paint.alpha = 255

                            // Amber radiant wash
                            val washR = maxOf(targetSnappingBox.width(), targetSnappingBox.height()) * 1.15f
                            chromaWashPaint.shader = android.graphics.RadialGradient(
                                cx, targetSnappingBox.bottom, washR,
                                intArrayOf(Color.argb((90 * p.coerceAtMost(1f)).toInt(), 255, 179, 0), Color.argb((25 * p.coerceAtMost(1f)).toInt(), 255, 179, 0), 0),
                                floatArrayOf(0f, 0.5f, 1f),
                                Shader.TileMode.CLAMP
                            )
                            canvas.drawRect(targetSnappingBox, chromaWashPaint)
                            canvas.restore()
                        }

                        // 3. Crisp Amber perimeter outline with soft radiant aura
                        canvas.save()
                        canvas.scale(liftScale, liftScale, cx, cy)
                        chromaHaloPaint.color = Color.argb((85 * p.coerceAtMost(1f)).toInt(), 255, 179, 0)
                        chromaHaloPaint.strokeWidth = 5f * density
                        canvas.drawRoundRect(targetSnappingBox, cornerR, cornerR, chromaHaloPaint)

                        chromaGlowPaint.strokeWidth = 2.4f * density
                        chromaGlowPaint.color = Color.argb((240 * p.coerceAtMost(1f)).toInt(), 255, 179, 0)
                        canvas.drawRoundRect(targetSnappingBox, cornerR, cornerR, chromaGlowPaint)

                        drawImageCropSelection(canvas, targetSnappingBox)
                        canvas.restore()
                    }

                    // 1. If an image is circled/cropped, physically scale up by 3% and cast soft drop shadow
                    selectedCropRect?.let { crop ->
                        val chromaPower = cropRevealAlpha.coerceIn(0f, 1f)
                        val liftScale = 1f + 0.03f * chromaPower // Physically scales up by 3%
                        val cx = crop.centerX()
                        val cy = crop.centerY()
                        val cornerR = 14f * density

                        // Soft physical drop shadow cast on wallpaper stack
                        if (chromaPower > 0.05f) {
                            val shadowW = crop.width() * liftScale
                            val shadowH = crop.height() * liftScale
                            val shadowOffset = 7f * density * chromaPower
                            val shadowRect = RectF(
                                cx - shadowW / 2f,
                                cy - shadowH / 2f + shadowOffset,
                                cx + shadowW / 2f,
                                cy + shadowH / 2f + shadowOffset
                            )
                            cropShadowPaint.setShadowLayer(
                                16f * density * chromaPower,
                                0f,
                                shadowOffset * 0.75f,
                                Color.argb((165 * chromaPower).toInt(), 0, 0, 0)
                            )
                            canvas.drawRoundRect(shadowRect, cornerR, cornerR, cropShadowPaint)
                            cropShadowPaint.clearShadowLayer()
                        }

                        // Physically lifted image area: scaled up 3%
                        canvas.save()
                        canvas.scale(liftScale, liftScale, cx, cy)
                        val clipPath = Path().apply {
                            addRoundRect(crop, cornerR, cornerR, Path.Direction.CW)
                        }
                        canvas.clipPath(clipPath)
                        paint.colorFilter = null
                        paint.alpha = (chromaPower * 255).toInt().coerceIn(0, 255)
                        canvas.drawBitmap(screenshot, null, destRect, paint)
                        paint.alpha = 255

                        val washR = maxOf(crop.width(), crop.height()) * 1.15f
                        chromaWashPaint.shader = RadialGradient(
                            crop.centerX(), crop.bottom, washR,
                            intArrayOf(Color.argb((115 * chromaPower).toInt(), 255, 179, 0), Color.argb((40 * chromaPower).toInt(), 255, 179, 0), 0),
                            floatArrayOf(0f, 0.45f, 1f),
                            Shader.TileMode.CLAMP
                        )
                        canvas.drawRect(crop, chromaWashPaint)
                        canvas.restore()

                        // Crop perimeter & corner drag handles at lifted scale
                        canvas.save()
                        canvas.scale(liftScale, liftScale, cx, cy)
                        chromaGlowPaint.strokeWidth = 2.4f * density
                        chromaGlowPaint.color = Color.argb((235 * chromaPower).toInt(), 255, 179, 0)
                        canvas.drawRoundRect(crop, cornerR, cornerR, chromaGlowPaint)

                        chromaHaloPaint.strokeWidth = 4.8f * density
                        chromaHaloPaint.color = Color.argb((70 * chromaPower).toInt(), 255, 179, 0)
                        canvas.drawRoundRect(crop, cornerR, cornerR, chromaHaloPaint)

                        drawImageCropSelection(canvas, crop)
                        canvas.restore()
                    }

                    // 2. Draw Phone Numbers with Light-up, Lift (scale 1.04) and gentle pulsating glow
                    for (phone in detectedPhoneItems) {
                        val r = phone.bounds
                        val bx = RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
                        if (phone == selectedTargetItem) {
                            canvas.save()
                            val pulse = 0.5f + 0.5f * kotlin.math.sin(now / 170.0).toFloat()
                            canvas.scale(1.04f, 1.04f, bx.centerX(), bx.centerY())
                            // Gentle yellow pulsating glow
                            chromaHaloPaint.color = Color.argb((85 + (45 * pulse)).toInt(), 255, 179, 0)
                            chromaHaloPaint.strokeWidth = 3.2f * density
                            canvas.drawRoundRect(bx, 6f * density, 6f * density, chromaHaloPaint)
                            // Elegant subtle fill
                            tempPaint.style = Paint.Style.FILL
                            tempPaint.color = Color.argb((45 + (20 * pulse)).toInt(), 255, 179, 0)
                            canvas.drawRoundRect(bx, 6f * density, 6f * density, tempPaint)
                            canvas.restore()
                        } else {
                            val baselineY = bx.bottom + 2.5f * density
                            canvas.drawLine(bx.left, baselineY, bx.right, baselineY, numberUnderlinePaint)
                        }
                    }

                    // 3. Draw Links with animated underline and floating favicon
                    for (link in detectedLinkItems) {
                        val r = link.bounds
                        val bx = RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
                        if (link == selectedTargetItem) {
                            val pulse = 0.5f + 0.5f * kotlin.math.sin(now / 180.0).toFloat()
                            // Sleek animated underline
                            chromaGlowPaint.color = Color.argb((200 + (55 * pulse)).toInt(), 255, 179, 0)
                            chromaGlowPaint.strokeWidth = 2.4f * density
                            val lineY = bx.bottom + 2.5f * density
                            canvas.drawLine(bx.left, lineY, bx.right, lineY, chromaGlowPaint)
                            // Subtle ambient tint
                            tempPaint.style = Paint.Style.FILL
                            tempPaint.color = Color.argb((35 + (20 * pulse)).toInt(), 255, 179, 0)
                            canvas.drawRoundRect(bx, 4f * density, 4f * density, tempPaint)
                            // Floating favicon
                            tempPaint.textSize = 12f * density
                            val favY = bx.top - 4f * density - (3f * density * pulse)
                            val iconEmoji = if (link.type == "email") "✉️" else "🔗"
                            canvas.drawText(iconEmoji, (bx.right + 2f * density).coerceAtMost(width - 16f * density), favY + 10f * density, tempPaint)
                        } else {
                            canvas.drawRoundRect(bx, 4f * density, 4f * density, linkBoxPaint)
                        }
                    }

                    // Source Position Anchor: Clearly highlights source position on screen
                    if (selectedTargetItem != null) {
                        val r = selectedTargetItem!!.bounds
                        val bx = RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
                        val padH = 4f * density
                        val padV = 3f * density
                        val anchorRect = RectF(bx.left - padH, bx.top - padV, bx.right + padH, bx.bottom + padV)

                        // Soft amber tint band (rgba(255, 179, 0, 0.15))
                        tempPaint.style = Paint.Style.FILL
                        tempPaint.color = Color.argb(38, 255, 179, 0)
                        canvas.drawRoundRect(anchorRect, 4f * density, 4f * density, tempPaint)

                        // Razor-thin amber outline
                        tempPaint.style = Paint.Style.STROKE
                        tempPaint.strokeWidth = 1f * density
                        tempPaint.color = Color.argb(200, 255, 179, 0)
                        canvas.drawRoundRect(anchorRect, 4f * density, 4f * density, tempPaint)

                        // 4 corner bracket anchor marks
                        val arm = 7f * density
                        tempPaint.strokeWidth = 2.2f * density
                        tempPaint.color = Color.rgb(255, 179, 0)
                        tempPaint.strokeCap = Paint.Cap.ROUND
                        canvas.drawLine(anchorRect.left - 1f, anchorRect.top + arm, anchorRect.left - 1f, anchorRect.top - 1f, tempPaint)
                        canvas.drawLine(anchorRect.left - 1f, anchorRect.top - 1f, anchorRect.left + arm, anchorRect.top - 1f, tempPaint)
                        canvas.drawLine(anchorRect.right + 1f - arm, anchorRect.top - 1f, anchorRect.right + 1f, anchorRect.top - 1f, tempPaint)
                        canvas.drawLine(anchorRect.right + 1f, anchorRect.top - 1f, anchorRect.right + 1f, anchorRect.top + arm, tempPaint)
                        canvas.drawLine(anchorRect.left - 1f, anchorRect.bottom - arm, anchorRect.left - 1f, anchorRect.bottom + 1f, tempPaint)
                        canvas.drawLine(anchorRect.left - 1f, anchorRect.bottom + 1f, anchorRect.left + arm, anchorRect.bottom + 1f, tempPaint)
                        canvas.drawLine(anchorRect.right + 1f - arm, anchorRect.bottom + 1f, anchorRect.right + 1f, anchorRect.bottom + 1f, tempPaint)
                        canvas.drawLine(anchorRect.right + 1f, anchorRect.bottom + 1f, anchorRect.right + 1f, anchorRect.bottom - arm, tempPaint)
                    }

                    // 4. Draw Texts with Apple Intelligence-style yellow radiant glowing tint
                    if (activeTextRect != null) {
                        val tr = activeTextRect!!
                        // Clean solid background highlight band in soft amber tint (rgba(255, 179, 0, 0.15))
                        tempPaint.style = Paint.Style.FILL
                        tempPaint.color = Color.argb(38, 255, 179, 0) // rgba(255, 179, 0, 0.15)
                        val padH = 4f * density
                        val padV = 2.5f * density
                        val bandRect = RectF(tr.left - padH, tr.top - padV, tr.right + padH, tr.bottom + padV)
                        canvas.drawRoundRect(bandRect, 5f * density, 5f * density, tempPaint)
                    } else {
                        for (item in textRegions.take(30)) {
                            val r = item.bounds
                            val bx = RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
                            val isPhone = detectedPhoneItems.any { Rect.intersects(it.bounds, r) }
                            val isLink = detectedLinkItems.any { Rect.intersects(it.bounds, r) }
                            if (!isPhone && !isLink) {
                                drawSubtleYellowLightReflection(canvas, bx, now)
                            }
                        }
                    }

                    postInvalidateOnAnimation()
                } else {
                    canvas.drawColor(Color.argb(230, 12, 12, 16))
                }

                // Yellow Comet Tail for gestures
                if (gestureTrail.size >= 2) {
                    val cutoff = now - 380L
                    gestureTrail.removeAll { it.time < cutoff }
                    val count = gestureTrail.size
                    if (count >= 2) {
                        for (i in 0 until count - 1) {
                            val p1 = gestureTrail[i]
                            val p2 = gestureTrail[i + 1]
                            val segP = i.toFloat() / (count - 1)
                            val segAlpha = (segP * 255).toInt().coerceIn(0, 255)
                            val segW = (1.6f * density + (5f * density * segP))

                            tailAuraPaint.strokeWidth = segW * 2.2f
                            tailAuraPaint.color = Color.argb((segAlpha * 0.35f).toInt(), 255, 179, 0)
                            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, tailAuraPaint)

                            tailPaint.strokeWidth = segW
                            tailPaint.color = Color.argb(segAlpha, 255, 179, 0)
                            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, tailPaint)
                        }
                        val head = gestureTrail.last()
                        tailHeadPaint.color = Color.rgb(255, 179, 0)
                        canvas.drawCircle(head.x, head.y, 4.5f * density, tailHeadPaint)
                        tailHeadPaint.color = yellowAccent
                        canvas.drawCircle(head.x, head.y, 2f * density, tailHeadPaint)
                        postInvalidateOnAnimation()
                    }
                }
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y

                        // 1. Check if touching crop resize corners
                        if (selectedCropRect != null) {
                            val crop = selectedCropRect!!
                            val hitRadius = 32f * density
                            val dTL = kotlin.math.hypot((downX - crop.left).toDouble(), (downY - crop.top).toDouble())
                            val dTR = kotlin.math.hypot((downX - crop.right).toDouble(), (downY - crop.top).toDouble())
                            val dBL = kotlin.math.hypot((downX - crop.left).toDouble(), (downY - crop.bottom).toDouble())
                            val dBR = kotlin.math.hypot((downX - crop.right).toDouble(), (downY - crop.bottom).toDouble())

                            when {
                                dTL < hitRadius -> {
                                    activeResizeCorner = ResizeCorner.TOP_LEFT
                                    actionBubbleLayout.visibility = View.GONE
                                    return true
                                }
                                dTR < hitRadius -> {
                                    activeResizeCorner = ResizeCorner.TOP_RIGHT
                                    actionBubbleLayout.visibility = View.GONE
                                    return true
                                }
                                dBL < hitRadius -> {
                                    activeResizeCorner = ResizeCorner.BOTTOM_LEFT
                                    actionBubbleLayout.visibility = View.GONE
                                    return true
                                }
                                dBR < hitRadius -> {
                                    activeResizeCorner = ResizeCorner.BOTTOM_RIGHT
                                    actionBubbleLayout.visibility = View.GONE
                                    return true
                                }
                            }
                        }

                        // 2. Check if touching text dragger handles
                        if (activeTextRect != null) {
                            val rect = activeTextRect!!
                            val handleR = 24f * density
                            val leftHandleDist = kotlin.math.hypot((downX - rect.left).toDouble(), (downY - (rect.bottom + 10f * density)).toDouble())
                            val rightHandleDist = kotlin.math.hypot((downX - rect.right).toDouble(), (downY - (rect.bottom + 10f * density)).toDouble())
                            if (leftHandleDist < handleR) {
                                activeTextHandle = "START"
                                actionBubbleLayout.visibility = View.GONE
                                return true
                            } else if (rightHandleDist < handleR) {
                                activeTextHandle = "END"
                                actionBubbleLayout.visibility = View.GONE
                                return true
                            }
                        }

                        activeTextHandle = null
                        activeResizeCorner = null
                        touchPoints.clear()
                        touchPoints.add(PointF(downX, downY))
                        gesturePath.reset()
                        gesturePath.moveTo(downX, downY)
                        isDraggingGesture = false

                        val now = System.currentTimeMillis()
                        gestureTrail.clear()
                        gestureTrail.add(GestureTrailNode(downX, downY, now))
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val cx = event.x
                        val cy = event.y

                        // Drag resize corners of image crop
                        if (activeResizeCorner != null && selectedCropRect != null) {
                            val crop = selectedCropRect!!
                            val minDim = 32f * density
                            when (activeResizeCorner) {
                                ResizeCorner.TOP_LEFT -> {
                                    crop.left = cx.coerceAtMost(crop.right - minDim)
                                    crop.top = cy.coerceAtMost(crop.bottom - minDim)
                                }
                                ResizeCorner.TOP_RIGHT -> {
                                    crop.right = cx.coerceAtLeast(crop.left + minDim)
                                    crop.top = cy.coerceAtMost(crop.bottom - minDim)
                                }
                                ResizeCorner.BOTTOM_LEFT -> {
                                    crop.left = cx.coerceAtMost(crop.right - minDim)
                                    crop.bottom = cy.coerceAtLeast(crop.top + minDim)
                                }
                                ResizeCorner.BOTTOM_RIGHT -> {
                                    crop.right = cx.coerceAtLeast(crop.left + minDim)
                                    crop.bottom = cy.coerceAtLeast(crop.top + minDim)
                                }
                                null -> {}
                            }
                            invalidate()
                            return true
                        }

                        if (activeTextHandle != null && activeTextRect != null) {
                            if (activeTextHandle == "START") {
                                activeTextRect!!.left = cx.coerceAtMost(activeTextRect!!.right - 10f * density)
                            } else {
                                activeTextRect!!.right = cx.coerceAtLeast(activeTextRect!!.left + 10f * density)
                            }
                            invalidate()
                            return true
                        }

                        touchPoints.add(PointF(cx, cy))
                        gesturePath.lineTo(cx, cy)
                        gestureTrail.add(GestureTrailNode(cx, cy, System.currentTimeMillis()))

                        val dist = kotlin.math.hypot((cx - downX).toDouble(), (cy - downY).toDouble()).toFloat()
                        if (dist > 14f * density) {
                            isDraggingGesture = true
                            actionBubbleLayout.visibility = View.GONE
                        }
                        invalidate()
                    }

                    MotionEvent.ACTION_UP -> {
                        if (activeResizeCorner != null && selectedCropRect != null) {
                            activeResizeCorner = null
                            val finalCropRect = selectedCropRect!!
                            if (screenshot != null && !screenshot.isRecycled) {
                                val leftBm = ((finalCropRect.left - offX) / scale).toInt().coerceIn(0, screenshot.width - 1)
                                val topBm = ((finalCropRect.top - offY) / scale).toInt().coerceIn(0, screenshot.height - 1)
                                val rightBm = ((finalCropRect.right - offX) / scale).toInt().coerceIn(leftBm + 1, screenshot.width)
                                val bottomBm = ((finalCropRect.bottom - offY) / scale).toInt().coerceIn(topBm + 1, screenshot.height)
                                val cropW = (rightBm - leftBm).coerceAtLeast(1)
                                val cropH = (bottomBm - topBm).coerceAtLeast(1)
                                try {
                                    croppedBitmap = Bitmap.createBitmap(screenshot, leftBm, topBm, cropW, cropH)
                                } catch (_: Exception) {}
                            }
                            showActionMenu(finalCropRect, listOf(
                                ActionBubbleItem("Search with Lens") {
                                    searchImageWithLens(context, croppedBitmap)
                                    NothingGlyphController.stopGetContentGlyph()
                                    dismissWithAnimation()
                                },
                                ActionBubbleItem("Copy Image") {
                                    copyCroppedImageToClipboard(context, croppedBitmap)
                                    NothingGlyphController.stopGetContentGlyph()
                                    dismissWithAnimation()
                                },
                                ActionBubbleItem("Share") {
                                    shareCroppedImage(context, croppedBitmap)
                                    NothingGlyphController.stopGetContentGlyph()
                                    dismissWithAnimation()
                                },
                                ActionBubbleItem("Save") {
                                    saveCroppedImageToGallery(context, croppedBitmap)
                                    NothingGlyphController.stopGetContentGlyph()
                                    dismissWithAnimation()
                                }
                            ))
                            IntegralAcoustics.playCropExtract(context)
                            invalidate()
                            return true
                        }

                        if (activeTextHandle != null && activeTextRect != null) {
                            activeTextHandle = null
                            val selectedItems = textRegions.filter { item ->
                                val b = item.bounds
                                val left = offX + b.left * scale
                                val right = offX + b.right * scale
                                val top = offY + b.top * scale
                                val bottom = offY + b.bottom * scale
                                RectF.intersects(activeTextRect!!, RectF(left, top, right, bottom))
                            }
                            val combined = if (selectedItems.isNotEmpty()) selectedItems.joinToString(" ") { it.text } else "Selected text"
                            showActionMenu(activeTextRect!!, listOf(
                                ActionBubbleItem("Copy") {
                                    val clip = ClipData.newPlainText("Screen Text", combined)
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                                    Toast.makeText(context, "Copied: " + combined.take(30) + "...", Toast.LENGTH_SHORT).show()
                                    NothingGlyphController.stopGetContentGlyph()
                                    dismissWithAnimation()
                                },
                                ActionBubbleItem("Search") {
                                    val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                                        putExtra(android.app.SearchManager.QUERY, combined)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    try { context.startActivity(intent) } catch (_: Exception) {}
                                    NothingGlyphController.stopGetContentGlyph()
                                    dismissWithAnimation()
                                },
                                ActionBubbleItem("Share") {
                                    shareText(context, combined)
                                    NothingGlyphController.stopGetContentGlyph()
                                    dismissWithAnimation()
                                }
                            ))
                            return true
                        }

                        if (isDraggingGesture && touchPoints.size > 5) {
                            var minX = Float.MAX_VALUE
                            var minY = Float.MAX_VALUE
                            var maxX = Float.MIN_VALUE
                            var maxY = Float.MIN_VALUE
                            for (pt in touchPoints) {
                                minX = minOf(minX, pt.x)
                                minY = minOf(minY, pt.y)
                                maxX = maxOf(maxX, pt.x)
                                maxY = maxOf(maxY, pt.y)
                            }
                            val w = maxX - minX
                            val h = maxY - minY

                            // User circled an area -> IDENTIFY AS IMAGE!
                            if (w > 32f * density && h > 32f * density) {
                                activeTextRect = null
                                selectedTargetItem = null

                                val finalCropRect = RectF(minX, minY, maxX, maxY)
                                val targetBox = finalCropRect
                                targetSnappingBox.set(targetBox)
                                isSnappingLasso = true
                                lassoSnapProgress = 0f

                                // Spring snap animation: circled area cleanly expands into perfect rounded square
                                lassoSnapAnimator?.cancel()
                                lassoSnapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                                    duration = 280L
                                    interpolator = android.view.animation.OvershootInterpolator(1.15f)
                                    addUpdateListener {
                                        lassoSnapProgress = it.animatedValue as Float
                                        invalidate()
                                    }
                                    addListener(object : AnimatorListenerAdapter() {
                                        override fun onAnimationEnd(animation: Animator) {
                                            isSnappingLasso = false
                                            selectedCropRect = targetBox
                                            cropRevealAlpha = 1.0f
                                            invalidate()
                                        }
                                    })
                                    start()
                                }

                                if (screenshot != null && !screenshot.isRecycled) {
                                    val leftBm = ((finalCropRect.left - offX) / scale).toInt().coerceIn(0, screenshot.width - 1)
                                    val topBm = ((finalCropRect.top - offY) / scale).toInt().coerceIn(0, screenshot.height - 1)
                                    val rightBm = ((finalCropRect.right - offX) / scale).toInt().coerceIn(leftBm + 1, screenshot.width)
                                    val bottomBm = ((finalCropRect.bottom - offY) / scale).toInt().coerceIn(topBm + 1, screenshot.height)
                                    val cropW = (rightBm - leftBm).coerceAtLeast(1)
                                    val cropH = (bottomBm - topBm).coerceAtLeast(1)
                                    try {
                                        croppedBitmap = Bitmap.createBitmap(screenshot, leftBm, topBm, cropW, cropH)
                                    } catch (_: Exception) {}
                                }

                                showActionMenu(finalCropRect, listOf(
                                    ActionBubbleItem("Search Image") {
                                        searchImageWithLens(context, croppedBitmap)
                                        NothingGlyphController.stopGetContentGlyph()
                                        dismissWithAnimation()
                                    },
                                    ActionBubbleItem("Copy Image") {
                                        copyCroppedImageToClipboard(context, croppedBitmap)
                                        NothingGlyphController.stopGetContentGlyph()
                                        dismissWithAnimation()
                                    },
                                    ActionBubbleItem("Share") {
                                        shareCroppedImage(context, croppedBitmap)
                                        NothingGlyphController.stopGetContentGlyph()
                                        dismissWithAnimation()
                                    },
                                    ActionBubbleItem("Save") {
                                        saveCroppedImageToGallery(context, croppedBitmap)
                                        NothingGlyphController.stopGetContentGlyph()
                                        dismissWithAnimation()
                                    }
                                ))
                            } else if (w > 26f * density && h <= 32f * density) {
                                // Horizontal swipe across text
                                selectedCropRect = null
                                selectedTargetItem = null
                                val swipeRect = RectF(minX, minY, maxX, maxY)
                                val hitWords = textRegions.filter { item ->
                                    val b = item.bounds
                                    val left = offX + b.left * scale
                                    val right = offX + b.right * scale
                                    val top = offY + b.top * scale
                                    val bottom = offY + b.bottom * scale
                                    RectF.intersects(swipeRect, RectF(left, top, right, bottom))
                                }
                                if (hitWords.isNotEmpty()) {
                                    val unionLeft = hitWords.minOf { offX + it.bounds.left * scale }
                                    val unionTop = hitWords.minOf { offY + it.bounds.top * scale }
                                    val unionRight = hitWords.maxOf { offX + it.bounds.right * scale }
                                    val unionBottom = hitWords.maxOf { offY + it.bounds.bottom * scale }
                                    val combinedRect = RectF(unionLeft, unionTop, unionRight, unionBottom)
                                    activeTextRect = combinedRect
                                    val combinedText = hitWords.joinToString(" ") { it.text }
                                    showActionMenu(combinedRect, listOf(
                                        ActionBubbleItem("Copy") {
                                            val clip = ClipData.newPlainText("Screen Text", combinedText)
                                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                            NothingGlyphController.stopGetContentGlyph()
                                            dismissWithAnimation()
                                        },
                                        ActionBubbleItem("Search") {
                                            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                                                putExtra(android.app.SearchManager.QUERY, combinedText)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            try { context.startActivity(intent) } catch (_: Exception) {}
                                            NothingGlyphController.stopGetContentGlyph()
                                            dismissWithAnimation()
                                        },
                                        ActionBubbleItem("Share") {
                                            shareText(context, combinedText)
                                            NothingGlyphController.stopGetContentGlyph()
                                            dismissWithAnimation()
                                        }
                                    ))
                                }
                            }
                            isDraggingGesture = false
                            gestureTrail.clear()
                            gesturePath.reset()
                            invalidate()
                        } else {
                            // Single Tap -> Adaptive Automatic Target Identification
                            val tx = event.x
                            val ty = event.y

                            // 1. Check Phone Numbers
                            val clickedPhone = detectedPhoneItems.firstOrNull { item ->
                                val b = item.bounds
                                val left = offX + b.left * scale
                                val top = offY + b.top * scale
                                val right = offX + b.right * scale
                                val bottom = offY + b.bottom * scale
                                tx in (left - 8f * density)..(right + 8f * density) && ty in (top - 8f * density)..(bottom + 8f * density)
                            }

                            // 2. Check Links
                            val clickedLink = detectedLinkItems.firstOrNull { item ->
                                val b = item.bounds
                                val left = offX + b.left * scale
                                val top = offY + b.top * scale
                                val right = offX + b.right * scale
                                val bottom = offY + b.bottom * scale
                                tx in (left - 8f * density)..(right + 8f * density) && ty in (top - 8f * density)..(bottom + 8f * density)
                            }

                            // 3. Check Text words
                            val clickedWord = textRegions.filter { item ->
                                val b = item.bounds
                                val left = offX + b.left * scale
                                val top = offY + b.top * scale
                                val right = offX + b.right * scale
                                val bottom = offY + b.bottom * scale
                                tx in (left - 6f * density)..(right + 6f * density) && ty in (top - 6f * density)..(bottom + 6f * density)
                            }.minByOrNull { it.bounds.width().toLong() * it.bounds.height().toLong() }

                            when {
                                clickedPhone != null -> {
                                    IntegralAcoustics.playItemSelect(context)
                                    activeTextRect = null
                                    selectedCropRect = null
                                    selectedTargetItem = clickedPhone
                                    val r = clickedPhone.bounds
                                    val bx = RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
                                    val num = clickedPhone.value.trim()
                                    showActionMenu(bx, listOf(
                                        ActionBubbleItem("Call") {
                                            try {
                                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num))).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(intent)
                                            } catch (_: Exception) {}
                                            NothingGlyphController.stopGetContentGlyph()
                                            dismissWithAnimation()
                                        },
                                        ActionBubbleItem("Message") {
                                            try {
                                                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num))).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(intent)
                                            } catch (_: Exception) {}
                                            NothingGlyphController.stopGetContentGlyph()
                                            dismissWithAnimation()
                                        },
                                        ActionBubbleItem("Copy") {
                                            val clip = ClipData.newPlainText("Phone Number", num)
                                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                                            Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
                                            NothingGlyphController.stopGetContentGlyph()
                                            dismissWithAnimation()
                                        },
                                        ActionBubbleItem("Add to Contact") {
                                            try {
                                                val intent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                                                    type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                                                    putExtra(ContactsContract.Intents.Insert.PHONE, num)
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(intent)
                                            } catch (_: Exception) {
                                                try {
                                                    val fallback = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
                                                        putExtra(ContactsContract.Intents.Insert.PHONE, num)
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(fallback)
                                                } catch (_: Exception) {}
                                            }
                                            NothingGlyphController.stopGetContentGlyph()
                                            dismissWithAnimation()
                                        }
                                    ))
                                }

                                clickedLink != null -> {
                                    IntegralAcoustics.playItemSelect(context)
                                    activeTextRect = null
                                    selectedCropRect = null
                                    selectedTargetItem = clickedLink
                                    val r = clickedLink.bounds
                                    val bx = RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
                                    val raw = clickedLink.value.trim()
                                    val isEmail = clickedLink.type == "email" || raw.contains("@")
                                    val openActionTitle = if (isEmail) "Send Email" else "Open Link"
                                    showActionMenu(bx, listOf(
                                        ActionBubbleItem(openActionTitle) {
                                            try {
                                                val uri = if (isEmail) Uri.parse("mailto:$raw")
                                                else if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) Uri.parse(raw)
                                                else Uri.parse("https://$raw")
                                                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(intent)
                                            } catch (_: Exception) {}
                                            NothingGlyphController.stopGetContentGlyph()
                                            dismissWithAnimation()
                                        },
                                        ActionBubbleItem("Copy") {
                                            val clip = ClipData.newPlainText(if (isEmail) "Email" else "Link", raw)
                                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                                            Toast.makeText(context, if (isEmail) "Email copied" else "Link copied", Toast.LENGTH_SHORT).show()
                                            NothingGlyphController.stopGetContentGlyph()
                                            dismissWithAnimation()
                                        },
                                        ActionBubbleItem("Share") {
                                            shareText(context, raw)
                                            NothingGlyphController.stopGetContentGlyph()
                                            dismissWithAnimation()
                                        }
                                    ))
                                }

                                clickedWord != null -> {
                                    IntegralAcoustics.playItemSelect(context)
                                    selectedTargetItem = null
                                    selectedCropRect = null
                                    val r = clickedWord.bounds
                                    val bx = RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
                                    activeTextRect = bx
                                    val wordText = clickedWord.text.trim()
                                    showActionMenu(bx, listOf(
                                        ActionBubbleItem("Copy") {
                                            val clip = ClipData.newPlainText("Word", wordText)
                                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                                            Toast.makeText(context, "Copied: " + wordText, Toast.LENGTH_SHORT).show()
                                            NothingGlyphController.stopGetContentGlyph()
                                            dismissWithAnimation()
                                        },
                                        ActionBubbleItem("Search") {
                                            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                                                putExtra(android.app.SearchManager.QUERY, wordText)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            try { context.startActivity(intent) } catch (_: Exception) {}
                                            NothingGlyphController.stopGetContentGlyph()
                                            dismissWithAnimation()
                                        },
                                        ActionBubbleItem("Share") {
                                            shareText(context, wordText)
                                            NothingGlyphController.stopGetContentGlyph()
                                            dismissWithAnimation()
                                        }
                                    ))
                                }

                                else -> {
                                    activeTextRect = null
                                    selectedTargetItem = null
                                    selectedCropRect = null
                                    actionBubbleLayout.visibility = View.GONE
                                }
                            }
                            invalidate()
                        }
                    }
                }
                return true
            }
        }

                // Ambient glow eliminated per user request (strict obsidian black & yellow theme)

        // 2. Badger Theme Top Header (Clean obsidian glass with Badger Yellow accent)
        val topBar = FrameLayout(context).apply {
            val topInset = dp(38)
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.TOP
                topMargin = topInset
                leftMargin = dp(16)
                rightMargin = dp(16)
            }

            val amberYellow = Color.parseColor("#FFB300")
            val darkBase = Color.parseColor("#121214")

            // Centered: Solid matte obsidian black base (#121214) with 1px amber-yellow (#FFB300) outline
            val titlePill = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(darkBase)
                    setStroke(dp(1), Color.argb(180, 255, 179, 0))
                }
                setPadding(dp(18), dp(7), dp(18), dp(7))
                elevation = 0f
                outlineProvider = null

                val titleText = TextView(context).apply {
                    text = "Get Content"
                    textSize = 13.5f
                    setTextColor(amberYellow)
                    typeface = satoshiBold
                    letterSpacing = 0.04f
                }
                addView(titleText)
            }
            addView(titlePill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

            // Right: Matching close button
            val closeBtn = FrameLayout(context).apply {
                val s = dp(36)
                layoutParams = FrameLayout.LayoutParams(s, s, Gravity.END or Gravity.CENTER_VERTICAL)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(darkBase)
                    setStroke(dp(1), Color.argb(180, 255, 179, 0))
                }
                elevation = 0f
                outlineProvider = null
                isClickable = true
                isFocusable = true
                addView(TextView(context).apply {
                    text = "✕"
                    textSize = 13f
                    typeface = satoshiBold
                    setTextColor(amberYellow)
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(-1, -1))

                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    NothingGlyphController.stopGetContentGlyph()
                    dismissWithAnimation()
                }
            }
            addView(closeBtn)
        }

        // 3. Horizontally Scrollable Bottom Mini Rectangle Cards for Numbers & Links
        val semanticResultItems = mutableListOf<SemanticResultItem>()

        // 1. Events
        detectedEventItems.forEachIndexed { i, ev ->
            semanticResultItems.add(
                SemanticResultItem(
                    id = "event_$i",
                    category = "Events",
                    iconEmoji = "📅",
                    title = ev.title,
                    subtitle = ev.dateSummary,
                    bounds = ev.bounds,
                    primaryActionName = "Create Event",
                    onPrimaryAction = {
                        val success = CalendarEventManager.createCalendarEvent(context, ev)
                        if (success) {
                            Toast.makeText(context, "✓ Event created: ${ev.title}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Opening calendar: ${ev.title}", Toast.LENGTH_SHORT).show()
                        }
                        NothingGlyphController.stopGetContentGlyph()
                        dismissWithAnimation()
                    },
                    actions = listOf(
                        ActionBubbleItem("Create Event") {
                            CalendarEventManager.createCalendarEvent(context, ev)
                            NothingGlyphController.stopGetContentGlyph()
                            dismissWithAnimation()
                        },
                        ActionBubbleItem("Copy") {
                            val clip = ClipData.newPlainText("Event", "${ev.title} on ${ev.dateSummary}")
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            NothingGlyphController.stopGetContentGlyph()
                            dismissWithAnimation()
                        }
                    )
                )
            )
        }

        // 2. Phone Numbers
        detectedPhoneItems.forEachIndexed { i, phoneItem ->
            val numStr = phoneItem.value.trim()
            semanticResultItems.add(
                SemanticResultItem(
                    id = "phone_$i",
                    category = "Numbers",
                    iconEmoji = "📞",
                    title = numStr,
                    subtitle = null,
                    bounds = phoneItem.bounds,
                    primaryActionName = "Call",
                    onPrimaryAction = {
                        try {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(numStr))).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                        NothingGlyphController.stopGetContentGlyph()
                        dismissWithAnimation()
                    },
                    actions = listOf(
                        ActionBubbleItem("Call") {
                            try {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(numStr))).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                            NothingGlyphController.stopGetContentGlyph()
                            dismissWithAnimation()
                        },
                        ActionBubbleItem("Message") {
                            try {
                                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(numStr))).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                            NothingGlyphController.stopGetContentGlyph()
                            dismissWithAnimation()
                        },
                        ActionBubbleItem("Add to Contact") {
                            try {
                                val intent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                                    type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                                    putExtra(ContactsContract.Intents.Insert.PHONE, numStr)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                try {
                                    val fallback = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
                                        putExtra(ContactsContract.Intents.Insert.PHONE, numStr)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(fallback)
                                } catch (_: Exception) {}
                            }
                            NothingGlyphController.stopGetContentGlyph()
                            dismissWithAnimation()
                        },
                        ActionBubbleItem("Copy") {
                            val clip = ClipData.newPlainText("Phone Number", numStr)
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                            Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
                            NothingGlyphController.stopGetContentGlyph()
                            dismissWithAnimation()
                        }
                    )
                )
            )
        }

        // 3. Links & Emails
        detectedLinkItems.forEachIndexed { i, linkItem ->
            val linkStr = linkItem.value.trim()
            val isEmail = linkItem.type == "email"
            if (isEmail) {
                semanticResultItems.add(
                    SemanticResultItem(
                        id = "email_$i",
                        category = "Emails",
                        iconEmoji = "✉️",
                        title = linkStr,
                        subtitle = null,
                        bounds = linkItem.bounds,
                        primaryActionName = "Send Email",
                        onPrimaryAction = {
                            try {
                                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(linkStr))).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                            NothingGlyphController.stopGetContentGlyph()
                            dismissWithAnimation()
                        },
                        actions = listOf(
                            ActionBubbleItem("Send Email") {
                                try {
                                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(linkStr))).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                                NothingGlyphController.stopGetContentGlyph()
                                dismissWithAnimation()
                            },
                            ActionBubbleItem("Copy") {
                                val clip = ClipData.newPlainText("Email", linkStr)
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                                Toast.makeText(context, "Email copied", Toast.LENGTH_SHORT).show()
                                NothingGlyphController.stopGetContentGlyph()
                                dismissWithAnimation()
                            },
                            ActionBubbleItem("Share") {
                                shareText(context, linkStr)
                                NothingGlyphController.stopGetContentGlyph()
                                dismissWithAnimation()
                            }
                        )
                    )
                )
            } else {
                semanticResultItems.add(
                    SemanticResultItem(
                        id = "link_$i",
                        category = "Links",
                        iconEmoji = "🔗",
                        title = linkStr.removePrefix("https://").removePrefix("http://").take(32),
                        subtitle = null,
                        bounds = linkItem.bounds,
                        primaryActionName = "Open",
                        onPrimaryAction = {
                            try {
                                val uri = if (!linkStr.startsWith("http://") && !linkStr.startsWith("https://")) Uri.parse("https://$linkStr") else Uri.parse(linkStr)
                                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                            NothingGlyphController.stopGetContentGlyph()
                            dismissWithAnimation()
                        },
                        actions = listOf(
                            ActionBubbleItem("Open") {
                                try {
                                    val uri = if (!linkStr.startsWith("http://") && !linkStr.startsWith("https://")) Uri.parse("https://$linkStr") else Uri.parse(linkStr)
                                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                                NothingGlyphController.stopGetContentGlyph()
                                dismissWithAnimation()
                            },
                            ActionBubbleItem("Copy") {
                                val clip = ClipData.newPlainText("Link", linkStr)
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                                NothingGlyphController.stopGetContentGlyph()
                                dismissWithAnimation()
                            },
                            ActionBubbleItem("Share") {
                                shareText(context, linkStr)
                                NothingGlyphController.stopGetContentGlyph()
                                dismissWithAnimation()
                            }
                        )
                    )
                )
            }
        }

        // 4. Addresses
        detectedAddressItems.forEachIndexed { i, addrItem ->
            val addrStr = addrItem.value.trim()
            semanticResultItems.add(
                SemanticResultItem(
                    id = "address_$i",
                    category = "Addresses",
                    iconEmoji = "📍",
                    title = addrStr.take(32),
                    subtitle = null,
                    bounds = addrItem.bounds,
                    primaryActionName = "Navigate",
                    onPrimaryAction = {
                        try {
                            val mapUri = Uri.parse("geo:0,0?q=" + Uri.encode(addrStr))
                            val intent = Intent(Intent.ACTION_VIEW, mapUri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                        NothingGlyphController.stopGetContentGlyph()
                        dismissWithAnimation()
                    },
                    actions = listOf(
                        ActionBubbleItem("Navigate") {
                            try {
                                val mapUri = Uri.parse("geo:0,0?q=" + Uri.encode(addrStr))
                                val intent = Intent(Intent.ACTION_VIEW, mapUri).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                            NothingGlyphController.stopGetContentGlyph()
                            dismissWithAnimation()
                        },
                        ActionBubbleItem("Copy") {
                            val clip = ClipData.newPlainText("Address", addrStr)
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                            Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                            NothingGlyphController.stopGetContentGlyph()
                            dismissWithAnimation()
                        },
                        ActionBubbleItem("Share") {
                            shareText(context, addrStr)
                            NothingGlyphController.stopGetContentGlyph()
                            dismissWithAnimation()
                        }
                    )
                )
            )
        }

        // Intelligent Adaptive Bottom Tray with Category Filtering (Modern M3 Expressive Style)
        val miniCardsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val lp = FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = dp(28)
                leftMargin = dp(14)
                rightMargin = dp(14)
            }
            layoutParams = lp
        }

        // Two-row bottom intelligence tray:
        // Row 1: Phone Numbers
        // Row 2: Links & Events
        val phoneItems = semanticResultItems.filter { it.category == "Numbers" }
        val linkAndEventItems = semanticResultItems.filter { it.category in listOf("Events", "Links", "Emails", "Address") }

        fun buildResultCard(item: SemanticResultItem, index: Int, staggerOffset: Long): View {
            val amberYellow = Color.parseColor("#FFB300")
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(28).toFloat() // Modern M3 Expressive tactile capsule
                    setColor(Color.parseColor("#16161A")) // Rich obsidian matte base
                    setStroke(dp(1), Color.argb(130, 255, 179, 0)) // Glowing Badger yellow accent rim
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                elevation = 0f
                outlineProvider = null
                isClickable = true
                isFocusable = true

                // 1. Leading M3E Expressive Squircle Avatar
                val avatarSize = dp(40)
                val iconContainer = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(14).toFloat()
                        setColor(Color.argb(45, 255, 179, 0))
                        setStroke(dp(1), Color.argb(90, 255, 179, 0))
                    }
                    val iconTv = TextView(context).apply {
                        text = item.iconEmoji
                        textSize = 17f
                        gravity = Gravity.CENTER
                    }
                    addView(iconTv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                }
                addView(iconContainer)

                // 2. Middle Content Stack (Meta category tag + bold value/title)
                val catLabel = when (item.category) {
                    "Events" -> "EVENT"
                    "Numbers" -> "PHONE NUMBER"
                    "Links" -> "LINK"
                    "Emails" -> "EMAIL"
                    "Address" -> "LOCATION"
                    else -> "CLIPBOARD"
                }

                val textColumn = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), 0, dp(14), 0)

                    val metaTv = TextView(context).apply {
                        text = catLabel
                        textSize = 9.5f
                        typeface = satoshiBold
                        setTextColor(amberYellow)
                        letterSpacing = 0.08f
                    }
                    val titleTv = TextView(context).apply {
                        text = item.title
                        textSize = 13.5f
                        typeface = satoshiBold
                        setTextColor(Color.WHITE)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    addView(metaTv)
                    addView(titleTv)
                }
                addView(textColumn, LinearLayout.LayoutParams(0, -2).apply {
                    weight = 1f
                })

                // 3. Trailing M3E Action Button (Prominent tactile capsule in Badger yellow)
                val actionBtn = TextView(context).apply {
                    text = item.primaryActionName
                    textSize = 12.5f
                    typeface = satoshiBold
                    setTextColor(Color.parseColor("#121214"))
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(18).toFloat()
                        setColor(amberYellow)
                    }
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        IntegralAcoustics.playItemSelect(context)
                        item.onPrimaryAction()
                    }
                }
                addView(actionBtn)

                // Staggered entrance animation:
                val delay = (staggerOffset + index * 45L).coerceAtMost(360L)
                alpha = 0f
                translationY = 16f * density
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(260L)
                    .setStartDelay(delay)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.15f))
                    .start()

                // Tapping card body identifies source position on screen and glides action menu
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    IntegralAcoustics.playItemSelect(context)
                    activeTextRect = null
                    selectedCropRect = null
                    selectedTargetItem = ScreenTargetItem(item.title, item.bounds, item.category)
                    interactiveCanvas.invalidate()

                    val r = item.bounds
                    val bx = RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
                    showActionMenu(bx, item.actions)
                }
            }
        }

        if (phoneItems.isNotEmpty() || linkAndEventItems.isNotEmpty()) {
            // Row 1: Phone Numbers
            if (phoneItems.isNotEmpty()) {
                val phoneScroll = HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                }
                val phoneRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                phoneItems.forEachIndexed { idx, item ->
                    phoneRow.addView(buildResultCard(item, idx, 0L), LinearLayout.LayoutParams(-2, -2).apply {
                        rightMargin = dp(10)
                    })
                }
                phoneScroll.addView(phoneRow)
                miniCardsContainer.addView(phoneScroll, LinearLayout.LayoutParams(-1, -2).apply {
                    if (linkAndEventItems.isNotEmpty()) bottomMargin = dp(10)
                })
            }

            // Row 2: Links and Events
            if (linkAndEventItems.isNotEmpty()) {
                val linkEventScroll = HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                }
                val linkEventRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val staggerBase = if (phoneItems.isNotEmpty()) 80L else 0L
                linkAndEventItems.forEachIndexed { idx, item ->
                    linkEventRow.addView(buildResultCard(item, idx, staggerBase), LinearLayout.LayoutParams(-2, -2).apply {
                        rightMargin = dp(10)
                    })
                }
                linkEventScroll.addView(linkEventRow)
                miniCardsContainer.addView(linkEventScroll, LinearLayout.LayoutParams(-1, -2))
            }
        } else {
            miniCardsContainer.visibility = View.GONE
        }

        rootLayout.addView(interactiveCanvas, FrameLayout.LayoutParams(-1, -1))

        // Inline contextual badges for high-intent Events
        detectedEventItems.forEach { ev ->
            val eventBadge = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.argb(245, 20, 20, 24))
                    setStroke(dp(1), Color.rgb(255, 179, 0))
                }
                setPadding(dp(9), dp(4), dp(10), dp(4))
                elevation = 0f
                outlineProvider = null
                isClickable = true
                isFocusable = true

                val icon = TextView(context).apply {
                    text = "📅 "
                    textSize = 10.5f
                }
                val label = TextView(context).apply {
                    text = "Create Event"
                    textSize = 11.5f
                    typeface = satoshiBold
                    setTextColor(Color.rgb(255, 179, 0))
                }
                addView(icon)
                addView(label)

                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    val success = CalendarEventManager.createCalendarEvent(context, ev)
                    if (success) {
                        Toast.makeText(context, "✓ Event created: ${ev.title}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Opening calendar: ${ev.title}", Toast.LENGTH_SHORT).show()
                    }
                    NothingGlyphController.stopGetContentGlyph()
                    dismissWithAnimation()
                }

                scaleX = 0f
                scaleY = 0f
                alpha = 0f
                animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                    .setDuration(340L)
                    .setStartDelay(200L)
                    .start()
            }

            val badgeLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            val badgeWEstimate = dp(120)
            if (ev.bounds.right + badgeWEstimate + dp(12) <= screenW) {
                badgeLp.leftMargin = ev.bounds.right + dp(6)
                badgeLp.topMargin = (ev.bounds.centerY() - dp(13)).coerceIn(dp(64), screenH - dp(120))
            } else {
                badgeLp.leftMargin = ev.bounds.left.coerceIn(dp(12), screenW - badgeWEstimate - dp(12))
                badgeLp.topMargin = (ev.bounds.top - dp(32)).coerceIn(dp(64), screenH - dp(120))
            }
            rootLayout.addView(eventBadge, badgeLp)
        }

        // Inline contextual badges for high-intent Addresses
        detectedAddressItems.forEach { addrItem ->
            val addrBadge = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.argb(245, 20, 20, 24))
                    setStroke(dp(1), Color.rgb(255, 179, 0))
                }
                setPadding(dp(9), dp(4), dp(10), dp(4))
                elevation = 0f
                outlineProvider = null
                isClickable = true
                isFocusable = true

                val icon = TextView(context).apply {
                    text = "📍 "
                    textSize = 10.5f
                }
                val label = TextView(context).apply {
                    text = "Navigate"
                    textSize = 11.5f
                    typeface = satoshiBold
                    setTextColor(Color.rgb(255, 179, 0))
                }
                addView(icon)
                addView(label)

                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    try {
                        val mapUri = Uri.parse("geo:0,0?q=" + Uri.encode(addrItem.value))
                        val intent = Intent(Intent.ACTION_VIEW, mapUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                    NothingGlyphController.stopGetContentGlyph()
                    dismissWithAnimation()
                }

                scaleX = 0f
                scaleY = 0f
                alpha = 0f
                animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                    .setDuration(340L)
                    .setStartDelay(200L)
                    .start()
            }

            val badgeLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            val badgeWEstimate = dp(105)
            if (addrItem.bounds.right + badgeWEstimate + dp(12) <= screenW) {
                badgeLp.leftMargin = addrItem.bounds.right + dp(6)
                badgeLp.topMargin = (addrItem.bounds.centerY() - dp(13)).coerceIn(dp(64), screenH - dp(120))
            } else {
                badgeLp.leftMargin = addrItem.bounds.left.coerceIn(dp(12), screenW - badgeWEstimate - dp(12))
                badgeLp.topMargin = (addrItem.bounds.top - dp(32)).coerceIn(dp(64), screenH - dp(120))
            }
            rootLayout.addView(addrBadge, badgeLp)
        }

        rootLayout.addView(actionBubbleLayout, FrameLayout.LayoutParams(-2, -2))
        rootLayout.addView(topBar)
        rootLayout.addView(miniCardsContainer)

        rootLayout.setupViews(interactiveCanvas, actionBubbleLayout, topBar, miniCardsContainer)

        return rootLayout
    }

    private fun copyCroppedImageToClipboard(context: Context, bitmap: Bitmap?) {
        if (bitmap == null) return
        try {
            val cacheDir = java.io.File(context.cacheDir, "images").apply { mkdirs() }
            val file = java.io.File(cacheDir, "cropped_image.png")
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.humnspace.integral.fileprovider", file)
            val clip = ClipData.newUri(context.contentResolver, "Cropped Image", uri)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Image copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Could not copy image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareCroppedImage(context: Context, bitmap: Bitmap?) {
        if (bitmap == null) return
        try {
            val cacheDir = java.io.File(context.cacheDir, "images").apply { mkdirs() }
            val file = java.io.File(cacheDir, "cropped_image.png")
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.humnspace.integral.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share Cropped Image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(context, "Could not share image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun wrapSwipeDismiss(content: View, onDismiss: () -> Unit): View {
        val swipeLayout = SwipeableSheetLayout(content.context, onDismiss)
        swipeLayout.addView(content, FrameLayout.LayoutParams(-1, -2))
        return swipeLayout
    }

    private fun performAction(context: Context, detail: ScreenDetail, action: String) {
        when (action) {
            "call" -> {
                try {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(detail.value.trim())}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    copy(context, detail)
                }
            }
            "open" -> {
                try {
                    val raw = detail.value.trim()
                    val uri = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
                        Uri.parse(raw)
                    } else if (raw.contains("@")) {
                        Uri.parse("mailto:$raw")
                    } else {
                        Uri.parse("https://$raw")
                    }
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    copy(context, detail)
                }
            }
            "search" -> {
                try {
                    val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                        putExtra(android.app.SearchManager.QUERY, detail.value)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(detail.value)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                }
            }
            else -> copy(context, detail)
        }
        NothingGlyphController.stopGetContentGlyph()
        dismissWithAnimation()
    }

    private fun pillButton(context: Context, label: String, isPrimary: Boolean, action: () -> Unit): TextView = TextView(context).apply {
        val density = context.resources.displayMetrics.density
        val satoshiBold = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.satoshi_bold) ?: Typeface.DEFAULT_BOLD
        text = label
        textSize = 11f
        typeface = satoshiBold
        letterSpacing = 0.08f
        setPadding((12 * density).toInt(), (7 * density).toInt(), (12 * density).toInt(), (7 * density).toInt())
        val accentYellow = Color.rgb(255, 216, 77)
        setTextColor(if (isPrimary) Color.rgb(18, 18, 20) else Color.WHITE)
        background = GradientDrawable().apply {
            setColor(if (isPrimary) accentYellow else Color.rgb(34, 34, 38))
            cornerRadius = 14f * density
            setStroke((1 * density).toInt(), if (isPrimary) accentYellow else Color.rgb(56, 56, 62))
        }
        isClickable = true
        isFocusable = true
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
    }

    private fun copy(context: Context, detail: ScreenDetail) {
        val description = ClipDescription(detail.kind, arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN))
        if (Build.VERSION.SDK_INT >= 33) {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        val clip = ClipData(description, ClipData.Item(detail.value))
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
        Toast.makeText(context, "${detail.kind} copied", Toast.LENGTH_SHORT).show()
    }

    private fun removeGlow() {
        val manager = windowManager ?: return
        val root = scanRoot
        val view = glowView
        try { if (root != null) manager.removeView(root) else view?.let(manager::removeView) } catch (_: RuntimeException) { }
        scanRoot = null
        glowView = null
    }

    fun dismiss() {
        isDismissingOverlay = false
        NothingGlyphController.stopGetContentGlyph()
        NothingGlyphController.stopVoiceRecordingSpectrum()
        overlayGeneration++
        val manager = windowManager
        try {
            activeRingerOverlay?.let { ringer ->
                manager?.removeView(ringer)
                (ringer.parent as? ViewGroup)?.removeView(ringer)
            }
        } catch (_: RuntimeException) { }
        activeRingerOverlay = null
        try {
            activeVoiceOverlay?.let { voice ->
                manager?.removeView(voice)
                (voice.parent as? ViewGroup)?.removeView(voice)
            }
        } catch (_: RuntimeException) { }
        activeVoiceOverlay = null
        try {
            activeVoiceRecordingOverlay?.let { rec ->
                manager?.removeView(rec)
                (rec.parent as? ViewGroup)?.removeView(rec)
            }
        } catch (_: RuntimeException) { }
        activeVoiceRecordingOverlay = null
        try { scanRoot?.let { manager?.removeView(it) } ?: glowView?.let { manager?.removeView(it) } } catch (_: RuntimeException) { }
        try { panelView?.let { manager?.removeView(it) } } catch (_: RuntimeException) { }
        try { if (resultsPanel !== panelView) resultsPanel?.let { manager?.removeView(it) } } catch (_: RuntimeException) { }
        try { visualView?.let { manager?.removeView(it) } } catch (_: RuntimeException) { }
        try { imagePreviewView?.let { manager?.removeView(it) } } catch (_: RuntimeException) { }
        glowView = null
        panelView = null
        resultsPanel = null
        visualView = null
        imagePreviewView = null
        scanRoot = null
        windowManager = null
        activeService = null
        scanResults = null
        scanAtTop = false
        activeScreenshot?.takeIf { !it.isRecycled }?.recycle()
        if (pendingScreenshot !== activeScreenshot) pendingScreenshot?.takeIf { !it.isRecycled }?.recycle()
        activeScreenshot = null
        activeImageCrop?.takeIf { !it.isRecycled }?.recycle()
        activeImageCrop = null
        pendingScreenshot = null
        collapseExpanded = null
    }

    fun handleBack(): Boolean {
        activeVoiceRecordingOverlay?.let { rec ->
            try {
                windowManager?.removeView(rec)
                (rec.parent as? ViewGroup)?.removeView(rec)
            } catch (_: RuntimeException) { }
            activeVoiceRecordingOverlay = null
            return true
        }
        activeVoiceOverlay?.let { voice ->
            if (voice.isShowing) {
                voice.dismissWithCollapse()
                return true
            }
        }
        activeRingerOverlay?.let { ringer ->
            if (ringer.isShowing && !ringer.isCollapsing) {
                ringer.dismissWithCollapse()
                return true
            }
        }
        val manager = windowManager
        if (visualView != null) {
            try { visualView?.let { manager?.removeView(it) } } catch (_: RuntimeException) { }
            visualView = null
            resultsPanel?.let { revealPanel(manager ?: return true, activeService ?: return true, it); panelView = it }
            return true
        }
        if (imagePreviewView != null) {
            try { imagePreviewView?.let { manager?.removeView(it) } } catch (_: RuntimeException) { }
            imagePreviewView = null
            activeImageCrop?.takeIf { !it.isRecycled }?.recycle(); activeImageCrop = null
            resultsPanel?.let { revealPanel(manager ?: return true, activeService ?: return true, it); panelView = it }
            return true
        }
        if (collapseExpanded?.invoke() == true) return true
        if (panelView != null) {
            dismissWithAnimation()
            return true
        }
        if (scanRoot != null) { dismiss(); return true }
        return false
    }

    private class SwipeableSheetLayout(
        context: Context,
        private val onDismiss: () -> Unit
    ) : FrameLayout(context) {
        private val density = resources.displayMetrics.density
        private var downY = 0f
        private var isDragging = false
        private var velocityTracker: android.view.VelocityTracker? = null

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    isDragging = false
                    velocityTracker?.recycle()
                    velocityTracker = android.view.VelocityTracker.obtain().apply { addMovement(ev) }
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(ev)
                    val dy = ev.rawY - downY
                    val scroll = findScrollView(this)
                    val atTop = scroll == null || scroll.scrollY <= 0
                    if (dy > 10f * density && atTop) {
                        isDragging = true
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                    isDragging = false
                }
            }
            return isDragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            velocityTracker?.addMovement(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (ev.rawY - downY).coerceAtLeast(0f)
                    translationY = delta
                    (parent as? View)?.alpha = (1f - (delta / (height.coerceAtLeast(1) * 0.85f))).coerceIn(0.15f, 1f)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vy = velocityTracker?.yVelocity ?: 0f
                    val currentY = translationY
                    velocityTracker?.recycle()
                    velocityTracker = null

                    if (currentY > 75f * density || vy > 650f * density) {
                        animate()
                            .translationY(height.toFloat() + 80f * density)
                            .alpha(0f)
                            .setDuration(220)
                            .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
                            .withEndAction { onDismiss() }
                            .start()
                        (parent as? View)?.animate()?.alpha(0f)?.setDuration(200)?.start()
                    } else {
                        animate()
                            .translationY(0f)
                            .setDuration(200)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(1.3f))
                            .start()
                        (parent as? View)?.animate()?.alpha(1f)?.setDuration(180)?.start()
                    }
                    isDragging = false
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }

        private fun findScrollView(v: View): ScrollView? {
            if (v is ScrollView) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val found = findScrollView(v.getChildAt(i))
                    if (found != null) return found
                }
            }
            return null
        }
    }

    private class ButtonSmokeScanView(context: Context, private val isDoublePress: Boolean = false) : View(context) {
        private val density = resources.displayMetrics.density
        private val prefs = context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE)
        private val originX = prefs.getFloat(EssentialKeyService.ANIMATION_ORIGIN_X, EssentialKeyService.DEFAULT_ORIGIN_X).coerceIn(0.02f, 0.98f)
        private val originY = prefs.getFloat(EssentialKeyService.ANIMATION_ORIGIN_Y, EssentialKeyService.DEFAULT_ORIGIN_Y).coerceIn(0.02f, 0.98f)

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private var progress = 0f
        private var scanFade = 1f
        private var animator: ValueAnimator? = null
        private var firstHapticPlayed = false
        private var secondHapticPlayed = false

        private data class SmokeEmber(val angle: Float, val speed: Float, val size: Float, val orbitOffset: Float)
        private val embers = List(16) { i ->
            val angle = (i * (Math.PI * 2.0 / 16.0)).toFloat() + 0.18f * (i % 3 - 1)
            val speed = 0.82f + (i % 4) * 0.07f
            val size = 1.3f + (i % 3) * 0.5f
            val orbit = (i % 5) * 8f - 16f
            SmokeEmber(angle, speed, size, orbit)
        }

        fun startScan(onScanPassed: () -> Unit) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 840L
                interpolator = android.view.animation.DecelerateInterpolator(1.35f)
                var panelRevealed = false
                addUpdateListener {
                    progress = it.animatedValue as Float
                    if (isDoublePress) {
                        if (progress > 0.08f && !firstHapticPlayed) {
                            firstHapticPlayed = true
                            performHaptic()
                        }
                        if (progress > 0.28f && !secondHapticPlayed) {
                            secondHapticPlayed = true
                            performHaptic()
                        }
                    }
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (!panelRevealed) {
                            panelRevealed = true
                            onScanPassed()
                        }
                    }
                })
                start()
            }
        }

        private fun performHaptic() {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(android.os.Vibrator::class.java)
                }
                if (vibrator?.hasVibrator() == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(18)
                    }
                }
            } catch (_: Exception) {}
        }

        fun fadeOut(onFinished: () -> Unit) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 180L
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { scanFade = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = onFinished()
                })
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return

            val cx = width * originX
            val cy = height * originY
            val fromRight = originX >= 0.5f
            val opacity = scanFade
            val p = progress.coerceIn(0f, 1f)

            // Max radius from button center to the furthest screen corner
            val maxRadius = kotlin.math.hypot(
                maxOf(cx, width - cx).toDouble(),
                maxOf(cy, height - cy).toDouble()
            ).toFloat() * 1.10f

            // 1. Edge Button Indicator matching the provided image (starts at edge, compresses tactually, shows label)
            if (p < 0.54f) {
                val btnT = (p / 0.54f).coerceIn(0f, 1f)
                val pushT: Float
                val flashAlphaMul: Float
                if (isDoublePress) {
                    // Two rapid rhythmic clicks:
                    if (btnT < 0.46f) {
                        val t1 = (btnT / 0.46f).coerceIn(0f, 1f)
                        pushT = kotlin.math.sin(t1 * Math.PI).toFloat()
                        flashAlphaMul = 0.82f + 0.18f * pushT
                    } else if (btnT in 0.46f..0.52f) {
                        pushT = 0f
                        flashAlphaMul = 0.65f
                    } else {
                        val t2 = ((btnT - 0.52f) / 0.48f).coerceIn(0f, 1f)
                        pushT = kotlin.math.sin(t2 * Math.PI).toFloat()
                        flashAlphaMul = 0.82f + 0.18f * pushT
                    }
                } else {
                    pushT = kotlin.math.sin(btnT * Math.PI).toFloat()
                    flashAlphaMul = 1f
                }

                val btnW = 18f * density
                val btnH = 50f * density
                val depth = 4f * density * pushT

                val outerRight = if (fromRight) width.toFloat() - 2f * density else 2f * density + btnW + depth
                val outerLeft = if (fromRight) outerRight - btnW - depth else 2f * density
                val outerTop = cy - btnH / 2f
                val outerBottom = cy + btnH / 2f
                val outerCorner = btnW / 2f
                val outerRect = RectF(outerLeft, outerTop, outerRight, outerBottom)

                val btnAlpha = (255f * (1f - btnT * 0.65f) * opacity).toInt().coerceIn(0, 255)

                // Dark outer capsule container with amber perimeter glow
                buttonPaint.style = Paint.Style.FILL
                buttonPaint.color = Color.argb((btnAlpha * 0.85f).toInt(), 18, 18, 20)
                canvas.drawRoundRect(outerRect, outerCorner, outerCorner, buttonPaint)

                borderPaint.color = Color.argb((btnAlpha * 0.55f).toInt(), 255, 194, 0)
                borderPaint.strokeWidth = 1.2f * density
                canvas.drawRoundRect(outerRect, outerCorner, outerCorner, borderPaint)

                // Inner bright glowing yellow pill (as in the provided photo)
                val innerInsetX = 3f * density
                val innerInsetY = 4f * density
                val innerRect = RectF(
                    outerRect.left + innerInsetX,
                    outerRect.top + innerInsetY,
                    outerRect.right - innerInsetX,
                    outerRect.bottom - innerInsetY
                )
                val innerCorner = innerRect.width() / 2f
                buttonPaint.color = Color.argb((btnAlpha * flashAlphaMul).toInt().coerceIn(0, 255), 255, 194, 0) // #FFC200
                canvas.drawRoundRect(innerRect, innerCorner, innerCorner, buttonPaint)

                // Two-line yellow label right beside the button (as in the photo)
                val textAlpha = (btnAlpha * (1f - btnT)).toInt().coerceIn(0, 255)
                if (textAlpha > 0) {
                    textPaint.color = Color.argb(textAlpha, 255, 194, 0)
                    textPaint.textSize = 13.5f * density
                    textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textPaint.letterSpacing = 0.04f

                    val labelX = if (fromRight) {
                        textPaint.textAlign = Paint.Align.RIGHT
                        outerRect.left - 10f * density
                    } else {
                        textPaint.textAlign = Paint.Align.LEFT
                        outerRect.right + 10f * density
                    }

                    if (isDoublePress) {
                        canvas.drawText("Double-Click", labelX, cy - 2.5f * density, textPaint)
                        textPaint.textSize = 12f * density
                        textPaint.typeface = android.graphics.Typeface.DEFAULT
                        canvas.drawText("to Continue", labelX, cy + 13f * density, textPaint)
                    } else {
                        canvas.drawText("Get Content", labelX, cy - 2.5f * density, textPaint)
                        textPaint.textSize = 12f * density
                        textPaint.typeface = android.graphics.Typeface.DEFAULT
                        canvas.drawText("Scanning...", labelX, cy + 13f * density, textPaint)
                    }
                    // Sub-label below indicator matching Nothing OS Essential Key hardware prompt
                    textPaint.textSize = 9.5f * density
                    textPaint.letterSpacing = 0.14f
                    textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textPaint.color = Color.argb((textAlpha * 0.72f).toInt(), 200, 200, 205)
                    canvas.drawText("QUICK ACCESS", labelX, cy + 28f * density, textPaint)
                }
            }

            // 2. Faded smoke-style yellow spreading all over the screen from that button
            val currentRadius = maxRadius * p
            if (currentRadius > 4f) {
                // Layer A: Soft warm ambient wash behind the smoke front (9% max opacity)
                val ambientAlpha = (255f * 0.09f * opacity * minOf(1f, p * 1.6f)).toInt()
                if (ambientAlpha > 0) {
                    paint.style = Paint.Style.FILL
                    paint.shader = null
                    paint.color = Color.argb(ambientAlpha, 255, 216, 77)
                    canvas.drawCircle(cx, cy, currentRadius, paint)
                }

                // Layer A2: Signature Nothing OS minimal white dots grid pattern in the yellow smoke
                val dotAlpha = (255f * 0.28f * opacity * minOf(1f, p * 1.5f) * (1f - p * 0.22f)).toInt()
                if (dotAlpha > 4) {
                    paint.style = Paint.Style.FILL
                    paint.shader = null
                    paint.color = Color.argb(dotAlpha, 255, 255, 255)
                    val spacing = 18f * density
                    val dotRadius = 1.15f * density
                    val startX = (cx % spacing)
                    val startY = (cy % spacing)
                    val rSq = currentRadius * currentRadius
                    var x = startX
                    while (x < width) {
                        var y = startY
                        while (y < height) {
                            val dx = x - cx
                            val dy = y - cy
                            if (dx * dx + dy * dy <= rSq) {
                                canvas.drawCircle(x, y, dotRadius, paint)
                            }
                            y += spacing
                        }
                        x += spacing
                    }
                }

                // Layer B: Billowing smoke wavefront band (soft, atmospheric faded yellow diffusion)
                val smokeThickness = (160f + 80f * p) * density
                val smokeCoreRadius = currentRadius
                val outerRadius = smokeCoreRadius + smokeThickness * 0.5f
                val innerRadius = (smokeCoreRadius - smokeThickness * 0.65f).coerceAtLeast(0f)

                if (outerRadius > innerRadius) {
                    val peakSmokeAlpha = (255f * 0.32f * opacity * (1f - p * 0.35f)).toInt().coerceIn(0, 255)
                    val midSmokeAlpha = (peakSmokeAlpha * 0.62f).toInt()

                    paint.style = Paint.Style.FILL
                    paint.shader = RadialGradient(
                        cx, cy, outerRadius,
                        intArrayOf(
                            Color.TRANSPARENT,
                            Color.argb(midSmokeAlpha, 255, 216, 77),
                            Color.argb(peakSmokeAlpha, 255, 216, 77),
                            Color.argb(midSmokeAlpha, 255, 216, 77),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(
                            0f,
                            (innerRadius / outerRadius).coerceIn(0f, 0.45f),
                            (smokeCoreRadius / outerRadius).coerceIn(0.46f, 0.85f),
                            ((smokeCoreRadius + smokeThickness * 0.22f) / outerRadius).coerceIn(0.86f, 0.96f),
                            1f
                        ),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(cx, cy, outerRadius, paint)
                    paint.shader = null
                }

                // Layer C: Secondary billowing harmonic ripple lagging behind for realistic fluid smoke depth
                val secRadius = currentRadius * 0.72f
                val secThickness = 95f * density
                if (secRadius > secThickness) {
                    val secAlpha = (255f * 0.14f * opacity * (1f - p * 0.38f)).toInt().coerceIn(0, 255)
                    paint.shader = RadialGradient(
                        cx, cy, secRadius + secThickness * 0.45f,
                        intArrayOf(
                            Color.TRANSPARENT,
                            Color.argb(secAlpha, 255, 216, 77),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(
                            ((secRadius - secThickness * 0.5f) / (secRadius + secThickness * 0.45f)).coerceIn(0f, 0.6f),
                            (secRadius / (secRadius + secThickness * 0.45f)).coerceIn(0.61f, 0.88f),
                            1f
                        ),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(cx, cy, secRadius + secThickness * 0.45f, paint)
                    paint.shader = null
                }

                // Layer D: Tertiary harmonic wisp
                val tertRadius = currentRadius * 0.46f
                val tertThickness = 65f * density
                if (tertRadius > tertThickness) {
                    val tertAlpha = (255f * 0.08f * opacity * (1f - p * 0.4f)).toInt().coerceIn(0, 255)
                    paint.shader = RadialGradient(
                        cx, cy, tertRadius + tertThickness * 0.4f,
                        intArrayOf(Color.TRANSPARENT, Color.argb(tertAlpha, 255, 216, 77), Color.TRANSPARENT),
                        floatArrayOf(0.2f, 0.75f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(cx, cy, tertRadius + tertThickness * 0.4f, paint)
                    paint.shader = null
                }

                // Layer E: Amber smoke ember particles drifting along the wavefront
                val emberFade = (1f - p * 0.4f) * opacity
                if (emberFade > 0.05f) {
                    paint.shader = null
                    for (mote in embers) {
                        val r = currentRadius * mote.speed + mote.orbitOffset * density
                        if (r > 4f) {
                            val px = cx + kotlin.math.cos(mote.angle.toDouble()).toFloat() * r
                            val py = cy + kotlin.math.sin(mote.angle.toDouble()).toFloat() * r
                            val moteAlpha = (255f * 0.34f * emberFade * kotlin.math.sin(p * Math.PI).toFloat()).toInt().coerceIn(0, 255)
                            if (moteAlpha > 0) {
                                paint.color = Color.argb(moteAlpha, 255, 216, 77)
                                // canvas.drawCircle(px, py, mote.size * density, paint)
                            }
                        }
                    }
                }
            }
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }
    }

    private class ActionFeedbackView(context: Context, private val action: String) : View(context) {
        private val density = resources.displayMetrics.density
        private val prefs = context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE)
        private val originX = prefs.getFloat(EssentialKeyService.ANIMATION_ORIGIN_X, EssentialKeyService.DEFAULT_ORIGIN_X).coerceIn(0.02f, 0.98f)
        private val originY = prefs.getFloat(EssentialKeyService.ANIMATION_ORIGIN_Y, EssentialKeyService.DEFAULT_ORIGIN_Y).coerceIn(0.02f, 0.98f)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var progress = 0f
        private var animator: ValueAnimator? = null

        fun start(onFinished: () -> Unit) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (action == "vibrate" || action == "ring") 1_250L else 940L
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = onFinished()
                })
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (action == "vibrate" || action == "ring") {
                drawRingerFocus(canvas)
                return
            }
            val originXpx = width * originX
            val originYpx = height * originY
            val fade = (1f - progress).coerceIn(0f, 1f)
            val spread = (18f + maxOf(width, height) * 0.58f * progress)
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(originXpx, originYpx, spread,
                intArrayOf(Color.argb((120 * fade).toInt(), 255, 196, 0), Color.argb((48 * fade).toInt(), 255, 196, 0), Color.TRANSPARENT),
                floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
            // canvas.drawCircle removed
            paint.shader = null

            val directionFromEdge = if (originX > 0.55f) -1f else 1f
            val startX = originXpx + directionFromEdge * 31f * density
            val x = startX + width * 0.76f * progress
            val y = originYpx
            val iconAlpha = (255f * (1f - progress * 0.92f)).toInt().coerceIn(0, 255)
            when {
                action == "vibrate" || action == "ring" -> drawRinger(canvas, x, y, iconAlpha, action == "vibrate")
                action.startsWith("torch") -> drawTorchCard(canvas, x, y, iconAlpha)
                action.startsWith("dnd") -> drawDndCard(canvas, x, y, iconAlpha, action == "dnd_on")
                action.startsWith("rotate") -> drawRotateCard(canvas, x, y, iconAlpha, action == "rotate_on")
                else -> drawTranslateCard(canvas, x, y, iconAlpha)
            }
        }

        /** Dark edge bezel pushes in from the configured physical key position, pauses, then retracts. */
        private fun drawRingerFocus(canvas: Canvas) {
            val p = progress.coerceIn(0f, 1f)
            val fromRight = originX >= 0.5f
            val maxDepth = width * 0.20f
            val push = overshoot((p / 0.14f).coerceIn(0f, 1f))
            val retract = 1f - smooth(((p - 0.72f) / 0.28f).coerceIn(0f, 1f))
            val hold = if (p in 0.14f..0.72f) kotlin.math.sin(((p - 0.14f) / 0.58f * Math.PI * 2.0)).toFloat() else 0f
            val amount = (minOf(push, 1.08f) * retract * (1f + hold * 0.018f)).coerceIn(0f, 1f)
            if (amount <= 0f) return

            val depth = maxDepth * amount
            val bulgeHeight = minOf(height * 0.14f, 112f * density) * (0.40f + 0.60f * smooth(amount))
            val centerY = (height * originY).coerceIn(bulgeHeight * 0.58f, height - bulgeHeight * 0.58f)
            val bounds = if (fromRight) {
                RectF(width - depth, centerY - bulgeHeight / 2f, width + 3f * density, centerY + bulgeHeight / 2f)
            } else {
                RectF(-3f * density, centerY - bulgeHeight / 2f, depth, centerY + bulgeHeight / 2f)
            }
            val corner = 30f * density
            val radii = if (fromRight) floatArrayOf(corner, corner, 0f, 0f, 0f, 0f, corner, corner)
                else floatArrayOf(0f, 0f, corner, corner, corner, corner, 0f, 0f)
            val bezel = Path().apply { addRoundRect(bounds, radii, Path.Direction.CW) }

            // Soft low-opacity perimeter strokes create a frosted fade instead of a solid panel.
            paint.style = Paint.Style.STROKE
            paint.color = Color.argb((8f * amount).toInt(), 255, 255, 255)
            paint.strokeWidth = 16f * density
            canvas.drawPath(bezel, paint)
            paint.color = Color.argb((14f * amount).toInt(), 255, 255, 255)
            paint.strokeWidth = 8f * density
            canvas.drawPath(bezel, paint)

            // Draw the black edge card; the overlay view itself is composited at 75% opacity.
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = Color.argb((255f * amount).toInt(), 0, 0, 0)
            canvas.drawPath(bezel, paint)

            // A faint inner-edge highlight finishes the translucent glass rim.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            paint.color = Color.argb((110f * amount).toInt(), 255, 196, 0)
            val lipX = if (fromRight) bounds.left + 1f * density else bounds.right - 1f * density
            canvas.drawLine(lipX, centerY - bulgeHeight * 0.28f, lipX, centerY + bulgeHeight * 0.28f, paint)
            paint.style = Paint.Style.FILL

            // Small orbiting gold particles give the edge press a restrained black-hole shimmer.
            val orbit = (18f + 40f * smooth(p)).toFloat() * density
            for (i in 0 until 12) {
                val angle = (i * (Math.PI * 2.0 / 12.0)) + p * 4.2
                val px = width * originX + kotlin.math.cos(angle).toFloat() * orbit
                val py = centerY + kotlin.math.sin(angle).toFloat() * orbit * 1.35f
                paint.color = Color.argb((105f * amount * (1f - (i % 3) * 0.18f)).toInt(), 255, 196, 0)
                // canvas.drawCircle removed
            }

            // The mode symbol emerges from the button location and travels inward with the bezel.
            val iconMove = smooth((p / 0.14f).coerceIn(0f, 1f))
            val originPx = width * originX
            val finalIconX = if (fromRight) width - depth * 0.55f else depth * 0.55f
            val iconX = originPx + (finalIconX - originPx) * iconMove
            val iconY = centerY - 5f * density
            val iconAlpha = (255f * minOf(amount, iconMove)).toInt().coerceIn(0, 255)
            canvas.save()
            if (fromRight && action == "ring") canvas.scale(-1f, 1f, iconX, iconY)
            drawRinger(canvas, iconX, iconY, iconAlpha, action == "vibrate")
            canvas.restore()
        }

        private fun smooth(value: Float): Float = value * value * (3f - 2f * value)
        private fun overshoot(value: Float): Float {
            val t = value - 1f
            return 1f + 2.70158f * t * t * t + 1.70158f * t * t
        }

        private fun drawRinger(canvas: Canvas, x: Float, y: Float, alpha: Int, vibrating: Boolean) {
            if (alpha <= 0) return
            paint.color = Color.argb(alpha, 255, 196, 0)
            paint.strokeCap = Paint.Cap.ROUND
            if (vibrating) {
                canvas.save()
                canvas.translate(x, y)
                paint.style = Paint.Style.FILL
                val s = 28f * density
                val phoneW = s * 0.354f
                val phoneH = s * 0.635f
                val phoneR = phoneW * 0.31f
                canvas.drawRoundRect(-phoneW / 2f, -phoneH / 2f, phoneW / 2f, phoneH / 2f, phoneR, phoneR, paint)
                val barW = s * 0.055f
                val barGap = s * 0.031f
                val innerH = s * 0.286f
                val innerR = barW / 2f
                val outerH = s * 0.168f
                val outerR = barW / 2f
                val liRight = -phoneW / 2f - barGap
                val liLeft = liRight - barW
                canvas.drawRoundRect(liLeft, -innerH / 2f, liRight, innerH / 2f, innerR, innerR, paint)
                val riLeft = phoneW / 2f + barGap
                val riRight = riLeft + barW
                canvas.drawRoundRect(riLeft, -innerH / 2f, riRight, innerH / 2f, innerR, innerR, paint)
                val loRight = liLeft - barGap
                val loLeft = loRight - barW
                canvas.drawRoundRect(loLeft, -outerH / 2f, loRight, outerH / 2f, outerR, outerR, paint)
                val roLeft = riRight + barGap
                val roRight = roLeft + barW
                canvas.drawRoundRect(roLeft, -outerH / 2f, roRight, outerH / 2f, outerR, outerR, paint)
                canvas.restore()
            } else {
                // Material Symbols Outlined volume_up, aimed inward from the selected edge.
                canvas.save()
                canvas.translate(x - 24f * density, y - 24f * density)
                canvas.scale(2f * density, 2f * density)
                paint.style = Paint.Style.FILL
                val speaker = Path().apply {
                    moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f)
                    lineTo(7f, 15f); lineTo(3f, 15f); close()
                }
                canvas.drawPath(speaker, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.7f
                canvas.drawArc(RectF(11f, 7f, 19f, 17f), -55f, 110f, false, paint)
                canvas.drawArc(RectF(10f, 3f, 23f, 21f), -55f, 110f, false, paint)
                canvas.restore()
            }
            paint.style = Paint.Style.FILL
        }

        private fun drawTorchCard(canvas: Canvas, x: Float, y: Float, alpha: Int) {
            val t = progress.coerceIn(0f, 1f)
            val cardW = 66f * density * (1f - 0.18f * t)
            val cardH = 52f * density * (1f - 0.18f * t)
            for (trail in 2 downTo 1) {
                val shift = trail * 12f * density
                paint.style = Paint.Style.FILL
                paint.color = Color.argb((alpha * (0.12f / trail)).toInt(), 255, 196, 0)
                canvas.drawRoundRect(RectF(x - cardW / 2f - shift, y - cardH / 2f, x + cardW / 2f - shift, y + cardH / 2f), 13f * density, 13f * density, paint)
            }
            paint.color = Color.argb(alpha, 12, 12, 13)
            canvas.drawRoundRect(RectF(x - cardW / 2f, y - cardH / 2f, x + cardW / 2f, y + cardH / 2f), 13f * density, 13f * density, paint)
            paint.color = Color.argb(alpha, 255, 196, 0)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            canvas.drawRoundRect(RectF(x - cardW / 2f, y - cardH / 2f, x + cardW / 2f, y + cardH / 2f), 13f * density, 13f * density, paint)
            paint.style = Paint.Style.FILL
            val torch = Path().apply {
                moveTo(x - 5f * density, y - 14f * density); lineTo(x + 5f * density, y - 14f * density)
                lineTo(x + 3f * density, y - 3f * density); lineTo(x + 3f * density, y + 12f * density)
                lineTo(x - 3f * density, y + 12f * density); lineTo(x - 3f * density, y - 3f * density); close()
            }
            paint.color = Color.argb(alpha, 255, 255, 255)
            canvas.drawPath(torch, paint)
            paint.color = Color.argb(alpha, 255, 196, 0)
            canvas.drawRoundRect(RectF(x - 8f * density, y - 17f * density, x + 8f * density, y - 11f * density), 2f * density, 2f * density, paint)
        }

        private fun drawTranslateCard(canvas: Canvas, x: Float, y: Float, alpha: Int) {
            val rect = RectF(x - 28f * density, y - 23f * density, x + 28f * density, y + 23f * density)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(alpha, 12, 12, 13)
            canvas.drawRoundRect(rect, 12f * density, 12f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            paint.color = Color.argb(alpha, 255, 196, 0)
            canvas.drawRoundRect(rect, 12f * density, 12f * density, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(alpha, 255, 255, 255)
            paint.textSize = 21f * density
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.drawText("A 文", x - 17f * density, y + 7f * density, paint)
        }

        private fun drawDndCard(canvas: Canvas, x: Float, y: Float, alpha: Int, dndOn: Boolean) {
            val rect = RectF(x - 28f * density, y - 23f * density, x + 28f * density, y + 23f * density)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(alpha, 12, 12, 13)
            canvas.drawRoundRect(rect, 12f * density, 12f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            paint.color = Color.argb(alpha, 255, 196, 0)
            canvas.drawRoundRect(rect, 12f * density, 12f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.2f * density
            paint.color = if (dndOn) Color.argb(alpha, 255, 196, 0) else Color.argb(alpha, 220, 220, 225)
            canvas.drawCircle(x, y, 10f * density, paint)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(RectF(x - 6f * density, y - 1.5f * density, x + 6f * density, y + 1.5f * density), 1.5f * density, 1.5f * density, paint)
        }

        private fun drawRotateCard(canvas: Canvas, x: Float, y: Float, alpha: Int, rotateAuto: Boolean) {
            val rect = RectF(x - 28f * density, y - 23f * density, x + 28f * density, y + 23f * density)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(alpha, 12, 12, 13)
            canvas.drawRoundRect(rect, 12f * density, 12f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            paint.color = Color.argb(alpha, 255, 196, 0)
            canvas.drawRoundRect(rect, 12f * density, 12f * density, paint)
            paint.color = if (rotateAuto) Color.argb(alpha, 255, 196, 0) else Color.argb(alpha, 220, 220, 225)
            canvas.drawRoundRect(RectF(x - 7f * density, y - 11f * density, x + 7f * density, y + 11f * density), 2f * density, 2f * density, paint)
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }
    }

}


/**
 * Luminous Nothing yellow sheet absorb animation view for Get Content.
 * Replaces the dark screenshot with a vibrant translucent yellow sheet that
 * smoothly contracts and shrinks directly into the hardware Essential Key location.
 */
class YellowSheetAbsorbView(
    context: Context,
    private val buttonCenterY: Float,
    private val fromRight: Boolean,
    private val onAnimationComplete: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val yellowColor = Color.rgb(255, 179, 0) // Nothing signature yellow #F7D915

    private val sheetFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val sheetBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val edgeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = yellowColor
        strokeCap = Paint.Cap.ROUND
    }
    private val tempRect = RectF()
    private val path = Path()

    private var progress = 0f
    private var animator: ValueAnimator? = null

    init {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 270L
            interpolator = android.view.animation.AccelerateInterpolator(1.3f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onAnimationComplete()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = progress.coerceIn(0f, 1f)
        if (t >= 1f) return

        val screenW = width.toFloat()
        val screenH = height.toFloat()
        if (screenW <= 0f || screenH <= 0f) return

        // Target button capsule at the screen bezel
        val capW = 28f * density
        val capH = 56f * density
        val capCorner = 14f * density
        val restingRight = if (fromRight) screenW - 6f * density else 6f * density + capW
        val restingLeft = if (fromRight) restingRight - capW else 6f * density
        val restingTop = buttonCenterY - capH / 2f
        val restingBottom = buttonCenterY + capH / 2f

        // Initial full sheet bounds covering the display
        val startLeft = 0f
        val startTop = 0f
        val startRight = screenW
        val startBottom = screenH
        val startCorner = 12f * density

        // Phase 1 (0f -> 0.78f): Shrink from full-screen sheet directly towards the button capsule
        // Phase 2 (0.78f -> 1.0f): Slide into the hardware button off-screen
        val shrinkProgress = (t / 0.78f).coerceIn(0f, 1f)
        val easedShrink = (Math.sin(shrinkProgress * Math.PI / 2)).toFloat()

        val slideProgress = ((t - 0.78f) / 0.22f).coerceIn(0f, 1f)
        val slideX = slideProgress * slideProgress * (if (fromRight) 50f * density else -50f * density)
        val exitAlpha = (1f - slideProgress * slideProgress).coerceIn(0f, 1f)

        val curLeft = (startLeft + (restingLeft - startLeft) * easedShrink) + slideX
        val curTop = startTop + (restingTop - startTop) * easedShrink
        val curRight = (startRight + (restingRight - startRight) * easedShrink) + slideX
        val curBottom = startBottom + (restingBottom - startBottom) * easedShrink
        val curCorner = startCorner + (capCorner - startCorner) * easedShrink

        tempRect.set(curLeft, curTop, curRight, curBottom)
        path.reset()
        path.addRoundRect(tempRect, curCorner, curCorner, Path.Direction.CW)

        // Luminous Nothing signature yellow translucent sheet fill
        // Starts at ~120 alpha (~47% opacity) and remains vibrant yellow throughout the entire shrink
        val fillAlpha = ((120f + 25f * easedShrink) * exitAlpha).toInt().coerceIn(0, 255)
        sheetFillPaint.color = Color.argb(fillAlpha, 255, 179, 0)
        canvas.drawPath(path, sheetFillPaint)

        // Vibrant Nothing yellow border around the shrinking sheet
        val borderAlpha = ((180f + 75f * easedShrink) * exitAlpha).toInt().coerceIn(0, 255)
        if (borderAlpha > 0) {
            sheetBorderPaint.strokeWidth = (2.2f * density + 1f * density * easedShrink)
            sheetBorderPaint.color = Color.argb(borderAlpha, 255, 179, 0)
            canvas.drawPath(path, sheetBorderPaint)
        }

        // Hardware key vertical yellow edge line along the bezel
        if (easedShrink > 0.15f) {
            val edgeLineA = (255f * easedShrink * exitAlpha).toInt().coerceIn(0, 255)
            if (edgeLineA > 0) {
                edgeLinePaint.color = Color.argb(edgeLineA, 255, 179, 0)
                val lineH = 140f * density
                val edgeX = if (fromRight) screenW - 1.25f * density + slideX else 1.25f * density + slideX
                canvas.drawLine(edgeX, buttonCenterY - lineH / 2f, edgeX, buttonCenterY + lineH / 2f, edgeLinePaint)
            }
        }
    }
}

class DraggablePillLayout(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val touchSlop = 6f * density
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var isDragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                startX = x
                startY = y
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!isDragging && kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return isDragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!isDragging && kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                    isDragging = true
                }
                if (isDragging) {
                    val parentW = (parent as? View)?.width ?: resources.displayMetrics.widthPixels
                    val parentH = (parent as? View)?.height ?: resources.displayMetrics.heightPixels
                    x = (startX + dx).coerceIn(10f * density, (parentW - width - 10f * density).toFloat())
                    y = (startY + dy).coerceIn(36f * density, (parentH - height - 16f * density).toFloat())
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    return true
                }
            }
        }
        return super.onTouchEvent(ev)
    }
}

/**
 * Luminous Nothing yellow sheet in-animation view for Get Content.
 * Expands from the hardware Essential Key location into a full-screen yellow sheet
 * while matrix dots dynamically drift and sweep across the screen analyzing it.
 */
class YellowSheetExpandInView(
    context: Context,
    private val buttonCenterY: Float,
    private val fromRight: Boolean,
    private val onAnimationComplete: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val yellowColor = Color.rgb(255, 179, 0) // Badger signature yellow #F7D915

    private val sheetFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val sheetBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val tempRect = RectF()
    private val path = Path()

    private var progress = 0f
    private var animator: ValueAnimator? = null

    init {
        // Fast, smooth spring-based expand (220ms)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = android.view.animation.DecelerateInterpolator(1.9f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onAnimationComplete()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = progress.coerceIn(0f, 1f)

        val screenW = width.toFloat()
        val screenH = height.toFloat()
        if (screenW <= 0f || screenH <= 0f) return

        // Button capsule origin
        val capW = 28f * density
        val capH = 56f * density
        val capCorner = 14f * density
        val restingRight = if (fromRight) screenW - 6f * density else 6f * density + capW
        val restingLeft = if (fromRight) restingRight - capW else 6f * density
        val restingTop = buttonCenterY - capH / 2f
        val restingBottom = buttonCenterY + capH / 2f

        // Expand progress from capsule outward to full screen
        val curLeft = restingLeft + (0f - restingLeft) * t
        val curTop = restingTop + (0f - restingTop) * t
        val curRight = restingRight + (screenW - restingRight) * t
        val curBottom = restingBottom + (screenH - restingBottom) * t
        val curCorner = capCorner + (0f - capCorner) * t

        tempRect.set(curLeft, curTop, curRight, curBottom)
        path.reset()
        path.addRoundRect(tempRect, curCorner, curCorner, Path.Direction.CW)

        // Yellow translucent sheet fill
        val fillAlpha = ((125f * (1f - t * 0.45f))).toInt().coerceIn(0, 255)
        sheetFillPaint.color = Color.argb(fillAlpha, 255, 179, 0)
        canvas.drawPath(path, sheetFillPaint)

        // Crisp Nothing yellow border
        val borderAlpha = ((255f * (1f - t * 0.35f))).toInt().coerceIn(0, 255)
        if (borderAlpha > 0) {
            sheetBorderPaint.strokeWidth = (2.4f * density * (1f - t * 0.4f)).coerceAtLeast(1.2f * density)
            sheetBorderPaint.color = Color.argb(borderAlpha, 255, 179, 0)
            canvas.drawPath(path, sheetBorderPaint)
        }

        // Drifting matrix dot grids that move here and there to analyse the screen
        val step = 26f * density
        val dotR = 1.6f * density
        val dotAlpha = ((160f * kotlin.math.sin(t * Math.PI))).toInt().coerceIn(0, 255)
        if (dotAlpha > 0) {
            dotPaint.color = Color.argb(dotAlpha, 255, 179, 0)
            var gx = curLeft + step * 0.8f
            var col = 0
            while (gx <= curRight - step * 0.5f) {
                var gy = curTop + step * 0.8f
                var row = 0
                while (gy <= curBottom - step * 0.5f) {
                    val driftX = (kotlin.math.sin(t * 8f + row * 0.45f) * 6f * density).toFloat()
                    val driftY = (kotlin.math.cos(t * 8f + col * 0.45f) * 6f * density).toFloat()
                    canvas.drawCircle(gx + driftX, gy + driftY, dotR, dotPaint)
                    gy += step
                    row++
                }
                gx += step
                col++
            }
        }

    }
}

/**
 * Fast radiant light ray in-animation expanding outward from the physical button position.
 * Sweeps across the display with Badger Yellow aura (#F7D915) and radiant rays.
 */
class LightRayExpandInView(
    context: Context,
    private val buttonCenterY: Float,
    private val fromRight: Boolean,
    private val onAnimationComplete: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var progress = 0f
    private var animator: ValueAnimator? = null

    init {
        // Fast explosive expand: 200ms
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onAnimationComplete()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = progress.coerceIn(0f, 1f)
        val screenW = width.toFloat()
        val screenH = height.toFloat()
        if (screenW <= 0f || screenH <= 0f) return

        val originX = if (fromRight) screenW else 0f
        val originY = buttonCenterY
        val maxR = kotlin.math.hypot(screenW.toDouble(), screenH.toDouble()).toFloat() * 1.05f
        val curR = maxR * t

        // 1. Radiant Radial Aura
        val alphaFade = (1f - t * 0.45f).coerceIn(0f, 1f)
        auraPaint.shader = RadialGradient(
            originX, originY, curR.coerceAtLeast(10f),
            intArrayOf(
                Color.argb((210 * alphaFade).toInt(), 255, 179, 0),
                Color.argb((85 * alphaFade).toInt(), 255, 179, 0),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(originX, originY, curR, auraPaint)

        // 2. Energetic Light Rays spreading outward
        val rayCount = 12
        val baseAngle = if (fromRight) Math.PI else 0.0
        val angleSpread = Math.PI
        beamPaint.color = Color.argb((230 * alphaFade).toInt(), 255, 179, 0)

        for (i in 0 until rayCount) {
            val frac = (i.toFloat() / (rayCount - 1))
            val angle = baseAngle - (Math.PI / 2.0) + frac * angleSpread
            val rayLength = curR * (0.75f + 0.25f * kotlin.math.sin(frac * Math.PI * 3.0 + t * 4.0)).toFloat()
            val targetX = originX + kotlin.math.cos(angle).toFloat() * rayLength
            val targetY = originY + kotlin.math.sin(angle).toFloat() * rayLength
            beamPaint.strokeWidth = (3.5f * density * (1f - t * 0.35f)).coerceAtLeast(1.2f * density)
            canvas.drawLine(originX, originY, targetX, targetY, beamPaint)
        }
    }
}

/**
 * Rapid light ray out-animation retracting from screen back into the physical button position.
 */
class LightRayAbsorbOutView(
    context: Context,
    private val buttonCenterY: Float,
    private val fromRight: Boolean,
    private val onAnimationComplete: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var progress = 1f
    private var animator: ValueAnimator? = null

    init {
        // Fast funneled collapse: 160ms
        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 160L
            interpolator = android.view.animation.AccelerateInterpolator(1.5f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onAnimationComplete()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = progress.coerceIn(0f, 1f)
        val screenW = width.toFloat()
        val screenH = height.toFloat()
        if (screenW <= 0f || screenH <= 0f || t <= 0.01f) return

        val originX = if (fromRight) screenW else 0f
        val originY = buttonCenterY
        val maxR = kotlin.math.hypot(screenW.toDouble(), screenH.toDouble()).toFloat() * 1.05f
        val curR = maxR * t

        auraPaint.shader = RadialGradient(
            originX, originY, curR.coerceAtLeast(10f),
            intArrayOf(
                Color.argb((220 * t).toInt(), 255, 179, 0),
                Color.argb((85 * t).toInt(), 255, 179, 0),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(originX, originY, curR, auraPaint)

        val rayCount = 10
        val baseAngle = if (fromRight) Math.PI else 0.0
        val angleSpread = Math.PI
        beamPaint.color = Color.argb((240 * t).toInt(), 255, 179, 0)

        for (i in 0 until rayCount) {
            val frac = (i.toFloat() / (rayCount - 1))
            val angle = baseAngle - (Math.PI / 2.0) + frac * angleSpread
            val rayLength = curR * 0.9f
            val targetX = originX + kotlin.math.cos(angle).toFloat() * rayLength
            val targetY = originY + kotlin.math.sin(angle).toFloat() * rayLength
            beamPaint.strokeWidth = 2.5f * density * t
            canvas.drawLine(originX, originY, targetX, targetY, beamPaint)
        }
    }
}

/**
 * Shared-element morphing root container for Badger Get-Content.
 *
 * Implements:
 * 1. Viewport Squeeze ("In" Animation): Screen content smoothly scales down to 0.96 with spring motion,
 *    moving away from the physical button side as if the button physically pushes screen inward.
 * 2. Magnetic Pull Sucked-Back Collapse ("Out" Animation):
 *    - 180ms clipped duration.
 *    - Aggressive Ease-In curve: PathInterpolator(0.6f, -0.28f, 0.735f, 0.045f).
 *    - Structural Collapse: Action Pill, cards, bars, and canvas collapse together into the physical button coordinate.
 *    - Re-absorption & Haptic Snap: Unified point vanishes at the physical button with VibrationEffect.EFFECT_CLICK.
 */
class GetContentRootLayout(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val yellowAccent = Color.rgb(255, 179, 0) // Badger Yellow #F7D915

    private val morphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val morphRect = RectF()
    private val morphPath = Path()

    private var morphProgress = 0f
    private var isMorphing = false
    private var buttonY = 0f
    private var isFromRight = true

    private var interactiveCanvasView: View? = null
    private var actionBubbleLayoutView: View? = null
    private var topBarView: View? = null
    private var miniCardsView: View? = null
    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun setupViews(
        canvas: View,
        actionBubble: View,
        topBar: View,
        miniCards: View
    ) {
        this.interactiveCanvasView = canvas
        this.actionBubbleLayoutView = actionBubble
        this.topBarView = topBar
        this.miniCardsView = miniCards

        // Initially hide children for spring staggered reveal
        topBar.alpha = 0f
        topBar.translationY = -35f * density
        topBar.scaleX = 0.85f
        topBar.scaleY = 0.85f

        miniCards.alpha = 0f
        miniCards.translationY = 45f * density
        miniCards.scaleX = 0.90f
        miniCards.scaleY = 0.90f
    }

    fun startSpringMorphIn(buttonCenterY: Float, fromRight: Boolean) {
        this.buttonY = buttonCenterY
        this.isFromRight = fromRight
        this.isMorphing = true

        val pushOffset = if (isFromRight) -14f * density else 14f * density

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            // Spring damping & stiffness curve for bouncy organic pop
            interpolator = android.view.animation.OvershootInterpolator(1.25f)
            addUpdateListener {
                val p = it.animatedValue as Float
                morphProgress = p
                // 1:1 crisp screen layout — no scale down per user request
                interactiveCanvasView?.let { ic ->
                    ic.scaleX = 1f
                    ic.scaleY = 1f
                    ic.translationX = 0f
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isMorphing = false
                    invalidate()
                }
            })
        }
        animator.start()

        // Staggered Bubble / Menu Reveal (sequential reveals spaced 35ms apart)
        topBarView?.let { tb ->
            tb.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(50L)
                .setDuration(220L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                .start()
        }

        miniCardsView?.let { mc ->
            mc.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(90L)
                .setDuration(230L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.35f))
                .start()
        }


    }

    fun startSpringMorphOut(buttonCenterY: Float, fromRight: Boolean, onEnd: () -> Unit) {
        this.buttonY = buttonCenterY
        this.isFromRight = fromRight
        this.isMorphing = true

        val w = width.toFloat().coerceAtLeast(1f)
        val targetX = if (fromRight) w else 0f
        val pushOffset = if (isFromRight) -14f * density else 14f * density

        val elements = listOfNotNull(actionBubbleLayoutView, miniCardsView, topBarView)
        val startLocs = elements.map { v ->
            val cx = v.x + v.width / 2f
            val cy = v.y + v.height / 2f
            Triple(v, cx, cy)
        }

        // Aggressively accelerated Ease-In curve: cubic-bezier(0.6, -0.28, 0.735, 0.045)
        val magneticPullInterpolator = android.view.animation.PathInterpolator(0.6f, -0.28f, 0.735f, 0.045f)

        val animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 180L
            interpolator = magneticPullInterpolator
            addUpdateListener {
                val p = it.animatedValue as Float
                morphProgress = p

                // 1. Structural Collapse: Floating Action Pill, crop box, lasso, cards collapse together into button
                startLocs.forEach { (view, startX, startY) ->
                    val pull = 1f - p
                    view.translationX = (targetX - startX) * pull
                    view.translationY = (buttonY - startY) * pull
                    view.scaleX = p
                    view.scaleY = p
                    view.alpha = (p * 1.25f).coerceIn(0f, 1f)
                }

                // 1:1 crisp screen layout — no scale down per user request
                interactiveCanvasView?.let { ic ->
                    ic.scaleX = 1f
                    ic.scaleY = 1f
                    ic.translationX = 0f
                }

                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Re-absorption: crisp mechanical click haptic snap
                    try {
                        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
                        } else @Suppress("DEPRECATION") context.getSystemService(android.os.Vibrator::class.java)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            vibrator?.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
                        } else @Suppress("DEPRECATION") {
                            vibrator?.vibrate(18)
                        }
                    } catch (_: Exception) {}

                    onEnd()
                }
            })
        }
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = morphProgress.coerceIn(0f, 1f)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Backdrop Dimming (Fades in simultaneously)
        scrimPaint.color = Color.argb((175 * p).toInt(), 10, 10, 14)
        canvas.drawRect(0f, 0f, w, h, scrimPaint)

        // 2. Morphing Button-to-Panel Shared Element Container
        if (isMorphing) {
            val capW = 28f * density
            val capH = 56f * density
            val capCorner = 14f * density

            val startRight = if (isFromRight) w else capW
            val startLeft = if (isFromRight) w - capW else 0f
            val startTop = (buttonY - capH / 2f).coerceIn(0f, h - capH)
            val startBottom = startTop + capH

            val curLeft = startLeft + (0f - startLeft) * p
            val curTop = startTop + (0f - startTop) * p
            val curRight = startRight + (w - startRight) * p
            val curBottom = startBottom + (h - startBottom) * p
            val curCorner = capCorner * (1f - p)

            morphRect.set(curLeft, curTop, curRight, curBottom)
            morphPath.reset()
            morphPath.addRoundRect(morphRect, curCorner, curCorner, Path.Direction.CW)

            // Deep obsidian morphing body
            morphPaint.color = Color.argb((230 * p).toInt(), 14, 14, 18)
            canvas.drawPath(morphPath, morphPaint)

            // Dynamic Badger yellow morphing contour
            val borderAlpha = ((255 * (1f - p * 0.4f))).toInt().coerceIn(0, 255)
            borderPaint.strokeWidth = 2.2f * density * (1f - p * 0.3f)
            borderPaint.color = Color.argb(borderAlpha, 255, 179, 0)
            canvas.drawPath(morphPath, borderPaint)
        }
    }
}
