package com.humnspace.integral.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.NotificationManager
import android.Manifest
import android.content.Context
import android.content.ClipData
import android.content.ClipDescription
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.content.pm.PackageManager
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityNodeInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.Display
import android.graphics.Bitmap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class EssentialKeyService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotWorker: ExecutorService = Executors.newSingleThreadExecutor()
    private var contentScanInFlight = false
    private var selectedKeyDown = false
    private var longPressFired = false
    private var tapCount = 0
    private var pendingMultiTap: Runnable? = null
    private var pendingRingerPreview: String? = null
    private val longPressAction = Runnable {
        if (selectedKeyDown && !longPressFired) {
            longPressFired = true
            ScreenAnalysisOverlay.showButtonPress(this@EssentialKeyService, ButtonPressIndicatorOverlayView.PressType.LONG_HOLD)
            pendingMultiTap?.let(mainHandler::removeCallbacks)
            pendingMultiTap = null
            tapCount = 0
            cancelRingerPreview()
            runMappedGesture(ACTION_LONG)
        }
    }
    // Fast in-memory cached preferences (zero disk I/O on key and accessibility events)
    @Volatile private var cachedMappingEnabled = true
    @Volatile private var cachedDiagnosticEnabled = false
    @Volatile private var cachedTargetKeySignature: String? = null
    @Volatile private var cachedUseGlyphs = true
    @Volatile private var cachedGlyphsProgress = true
    @Volatile private var cachedGlyphsAppFlash = true
    @Volatile private var cachedGlyphsVoiceReactivity = true

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        when (key) {
            MAPPING_ENABLED -> cachedMappingEnabled = sp.getBoolean(MAPPING_ENABLED, true)
            DETECTION_ENABLED -> cachedDiagnosticEnabled = sp.getBoolean(DETECTION_ENABLED, false)
            TARGET_KEY_SIGNATURE -> cachedTargetKeySignature = sp.getString(TARGET_KEY_SIGNATURE, null)
            USE_GLYPHS -> cachedUseGlyphs = sp.getBoolean(USE_GLYPHS, true)
            GLYPHS_PROGRESS -> cachedGlyphsProgress = sp.getBoolean(GLYPHS_PROGRESS, true)
            GLYPHS_APP_FLASH -> cachedGlyphsAppFlash = sp.getBoolean(GLYPHS_APP_FLASH, true)
            GLYPHS_VOICE_REACTIVITY -> cachedGlyphsVoiceReactivity = sp.getBoolean(GLYPHS_VOICE_REACTIVITY, true)
        }
    }

    private fun loadCachedPreferences() {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        cachedMappingEnabled = sp.getBoolean(MAPPING_ENABLED, true)
        cachedDiagnosticEnabled = sp.getBoolean(DETECTION_ENABLED, false)
        cachedTargetKeySignature = sp.getString(TARGET_KEY_SIGNATURE, null)
        cachedUseGlyphs = sp.getBoolean(USE_GLYPHS, true)
        cachedGlyphsProgress = sp.getBoolean(GLYPHS_PROGRESS, true)
        cachedGlyphsAppFlash = sp.getBoolean(GLYPHS_APP_FLASH, true)
        cachedGlyphsVoiceReactivity = sp.getBoolean(GLYPHS_VOICE_REACTIVITY, true)
        sp.registerOnSharedPreferenceChangeListener(prefChangeListener)
    }

    // Battery deep-sleep: pause glyphs and dismiss overlays when screen turns off
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                NothingGlyphController.turnOffAll()
                ScreenAnalysisOverlay.dismiss()
                resetPressState()
                progressWatchdogHandler.removeCallbacks(progressWatchdogRunnable)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        loadCachedPreferences()
        NothingGlyphController.init(this)
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        try {
            registerReceiver(screenStateReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        } catch (_: Exception) {}
    }
    private val launcherPackages = mutableSetOf(
        "com.nothing.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.sec.android.app.launcher"
    )
    private var launcherPackagesQueried = false
    private var previousPackageName: String? = null
    private val progressWatchdogHandler = Handler(Looper.getMainLooper())
    private val progressWatchdogRunnable = Runnable {
        NothingGlyphController.clearDownloadProgress()
    }

    private fun ensureLauncherPackages() {
        if (launcherPackagesQueried) return
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            for (info in resolveInfos) {
                info.activityInfo?.packageName?.let { launcherPackages.add(it) }
            }
            launcherPackagesQueried = true
        } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !cachedUseGlyphs) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (!cachedGlyphsAppFlash) return

                val newPkg = event.packageName?.toString() ?: return
                if (newPkg == previousPackageName || newPkg == "com.android.systemui" || newPkg == packageName) return

                ensureLauncherPackages()

                val wasHome = previousPackageName == null || launcherPackages.contains(previousPackageName)
                val isHome = launcherPackages.contains(newPkg)

                if (wasHome && !isHome) {
                    NothingGlyphController.playAppOpenFlash()
                } else if (!wasHome && isHome) {
                    NothingGlyphController.playAppCloseFlash()
                }

                previousPackageName = newPkg
            }

            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (!cachedGlyphsProgress) return

                // Instant filter: Ignore 99.9% of non-download notifications with zero allocations
                val pkg = event.packageName?.toString() ?: return
                if (pkg != "com.android.chrome" && pkg != "com.android.vending" && pkg != "com.android.providers.downloads") {
                    return
                }

                val notification = event.parcelableData as? android.app.Notification ?: return
                val extras = notification.extras ?: return
                val max = extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX, 0)
                val progress = extras.getInt(android.app.Notification.EXTRA_PROGRESS, 0)
                val indeterminate = extras.getBoolean(android.app.Notification.EXTRA_PROGRESS_INDETERMINATE, false)

                if (max > 0) {
                    progressWatchdogHandler.removeCallbacks(progressWatchdogRunnable)
                    val frac = (progress.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                    if (frac >= 1f || progress >= max) {
                        NothingGlyphController.completeDownloadProgress()
                        progressWatchdogHandler.postDelayed(progressWatchdogRunnable, 1200L)
                    } else {
                        NothingGlyphController.setDownloadProgress(frac)
                        progressWatchdogHandler.postDelayed(progressWatchdogRunnable, 8000L)
                    }
                } else if (indeterminate) {
                    progressWatchdogHandler.removeCallbacks(progressWatchdogRunnable)
                    NothingGlyphController.setIndeterminateDownloadProgress()
                    progressWatchdogHandler.postDelayed(progressWatchdogRunnable, 8000L)
                }
            }
        }
    }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (ScreenAnalysisOverlay.isAnyOverlayShowing()) {
                if (event.action == KeyEvent.ACTION_UP) {
                    ScreenAnalysisOverlay.handleBack()
                }
                return true
            }
        }
        if (!cachedMappingEnabled && !cachedDiagnosticEnabled) return false

        if (cachedDiagnosticEnabled) DetectionState.add(event)
        if (isSelectedKey(event, cachedTargetKeySignature)) {
            if (!cachedMappingEnabled) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(DETECTION_ENABLED, false).apply()
                }
            } else if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onSelectedKeyDown()
            } else if (event.action == KeyEvent.ACTION_UP) {
                onSelectedKeyUp()
            }
        }
        return false
    }

    override fun onInterrupt() {
        resetPressState()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(DETECTION_ENABLED, false).apply()
        // Do not dismiss the user's active results panel on system feedback interrupts
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
        try { getSharedPreferences(PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefChangeListener) } catch (_: Exception) {}
        screenshotWorker.shutdownNow()
        ScreenAnalysisOverlay.dismiss()
        NothingGlyphController.turnOffAll()
        super.onDestroy()
    }

    private fun onSelectedKeyDown() {
        if (selectedKeyDown) return
        selectedKeyDown = true
        longPressFired = false
        pendingMultiTap?.let(mainHandler::removeCallbacks)
        pendingMultiTap = null
        // Organic black mountain deformation grows attached to the bezel with distinct intensity per tap
        ScreenAnalysisOverlay.showButtonPress(
            this,
            ButtonPressIndicatorOverlayView.PressType.DOWN,
            tapIndex = tapCount
        )
        performPressHaptic()
        mainHandler.removeCallbacks(longPressAction)
        mainHandler.postDelayed(longPressAction, LONG_PRESS_TIMEOUT_MS)
    }

    private fun onSelectedKeyUp() {
        if (!selectedKeyDown) return
        selectedKeyDown = false
        mainHandler.removeCallbacks(longPressAction)

        if (longPressFired) {
            longPressFired = false
            tapCount = 0
            ScreenAnalysisOverlay.showButtonPress(this, ButtonPressIndicatorOverlayView.PressType.LONG_RELEASE)
            ScreenAnalysisOverlay.onLongPressKeyUp()
            return
        }

        // As soon as button is released, smoothly retract and dismiss button press indicator
        ScreenAnalysisOverlay.showButtonPress(this, ButtonPressIndicatorOverlayView.PressType.UP)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val singleAction = prefs.getString(ACTION_SINGLE, prefs.getString(MAPPED_ACTION, ACTION_RINGER)) ?: ACTION_RINGER
        // When ringer overlay is already active, every subsequent tap toggles the ringer immediately with zero latency
        if (singleAction == ACTION_RINGER && ScreenAnalysisOverlay.isRingerOverlayActive()) {
            tapCount = 0
            pendingMultiTap?.let(mainHandler::removeCallbacks)
            pendingMultiTap = null
            executeMapping(ACTION_RINGER)
            return
        }

        tapCount++
        if (tapCount >= 3) {
            tapCount = 0
            cancelRingerPreview()
            ScreenAnalysisOverlay.showButtonPress(
                this,
                ButtonPressIndicatorOverlayView.PressType.TRIPLE
            )
            runMappedGesture(ACTION_TRIPLE)
            return
        }

        val multiTap = Runnable {
            val count = tapCount
            tapCount = 0
            pendingMultiTap = null
            when (count) {
                1 -> runMappedGesture(ACTION_SINGLE)
                2 -> {
                    ScreenAnalysisOverlay.showButtonPress(
                        this@EssentialKeyService,
                        ButtonPressIndicatorOverlayView.PressType.DOUBLE
                    )
                    runMappedGesture(ACTION_DOUBLE)
                }
            }
        }
        pendingMultiTap = multiTap
        mainHandler.postDelayed(multiTap, MULTI_TAP_TIMEOUT_MS)
    }

    private fun resetPressState() {
        selectedKeyDown = false
        longPressFired = false
        tapCount = 0
        pendingMultiTap?.let(mainHandler::removeCallbacks)
        pendingMultiTap = null
        mainHandler.removeCallbacks(longPressAction)
        cancelRingerPreview()
        ScreenAnalysisOverlay.dismissButtonPress()
    }

    /** Begin the visual response on key-down; actual mode changes still wait for gesture classification. */
    private fun startRingerPreview() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val singleAction = prefs.getString(ACTION_SINGLE, prefs.getString(MAPPED_ACTION, ACTION_RINGER))
        if (singleAction != ACTION_RINGER) return
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val target = if (audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE) "ring" else "vibrate"
        pendingRingerPreview = target
        ScreenAnalysisOverlay.animateAction(this, target)
    }

    private fun cancelRingerPreview() {
        if (pendingRingerPreview != null) ScreenAnalysisOverlay.dismissRingerOverlay()
        pendingRingerPreview = null
    }

    private fun runMappedGesture(gesture: String) {
        val preview = pendingRingerPreview
        pendingRingerPreview = null
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val fallback = when (gesture) {
            ACTION_SINGLE -> prefs.getString(MAPPED_ACTION, ACTION_RINGER) ?: ACTION_RINGER
            ACTION_DOUBLE -> ACTION_CONTENTS
            ACTION_TRIPLE -> ACTION_BUBBLE_HUB
            ACTION_LONG -> ACTION_VOICE_SEARCH
            else -> ACTION_NONE
        }
        val action = prefs.getString(gesture, fallback) ?: fallback
        // The ringer is a system audio setting and can be switched while the keyguard is showing.
        // Other actions need the foreground app/window and remain unavailable until unlocked.
        if ((getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked && action != ACTION_RINGER) {
            if (preview != null) ScreenAnalysisOverlay.dismissRingerOverlay()
            android.widget.Toast.makeText(this, "Unlock your phone to use this action", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val isDouble = (gesture == ACTION_DOUBLE)
        if (action == ACTION_RINGER && gesture == ACTION_SINGLE) executeMapping(action, preview, false)
        else {
            if (preview != null) ScreenAnalysisOverlay.dismissRingerOverlay()
            if (action != ACTION_NONE) executeMapping(action, isDouble = isDouble)
            else ScreenAnalysisOverlay.dismissButtonPress()
        }
    }

    private fun performPressHaptic() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val singleAction = prefs.getString(ACTION_SINGLE, prefs.getString(MAPPED_ACTION, ACTION_RINGER))
        if (singleAction == ACTION_RINGER) return // Zero haptics for ring profile
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                else @Suppress("DEPRECATION") vibrator.vibrate(18)
            }
        } catch (_: SecurityException) { }
    }

    private fun executeMapping(action: String, preview: String? = null, isDouble: Boolean = false) {
        when (action) {
            ACTION_RINGER -> {
                val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                val currentDisplayMode = ScreenAnalysisOverlay.getCurrentRingerMode()
                val targetMode = if (currentDisplayMode != null) {
                    if (currentDisplayMode == "vibrate") "ring" else "vibrate"
                } else {
                    if (audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE) "ring" else "vibrate"
                }
                val nextSystemMode = if (targetMode == "vibrate") AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
                try {
                    audio.ringerMode = nextSystemMode
                    // Immediate synchronous overlay display at the exact same instant audio mode changes
                    ScreenAnalysisOverlay.showRingerOverlay(this, targetMode)
                } catch (_: SecurityException) {
                    showMessage("Ringer mode wasn’t changed", "Android blocked this change under the current Do Not Disturb policy.")
                }
            }
            ACTION_VOICE_SEARCH -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    showMessage("Microphone permission needed", "Open Badger and allow Microphone access to use Say it.")
                    return
                }
                ScreenAnalysisOverlay.showVoiceSearchOverlay(this)
            }
            ACTION_VOICE_RECORDING -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    showMessage("Microphone permission needed", "Open Badger and allow Microphone access to use Voice Recording.")
                    return
                }
                ScreenAnalysisOverlay.showVoiceRecordingOverlay(this)
            }
            ACTION_BUBBLE_HUB -> ScreenAnalysisOverlay.showFeatureBubbles(
                context = this,
                onSelectRinger = { executeMapping(ACTION_RINGER) },
                onSelectGetContent = { scanScreenContent(false) },
                onSelectVoice = { executeMapping(ACTION_VOICE_SEARCH) },
                onSelectVoiceRecording = { executeMapping(ACTION_VOICE_RECORDING) }
            )
            ACTION_QR_SCAN -> scanQrLens()
            ACTION_SUMMARIZE -> ScreenAnalysisOverlay.showScreenSummarizer(this, rootInActiveWindow)
            ACTION_NONE -> Unit
            else -> scanScreenContent(isDouble)
        }
    }

    private fun scanQrLens() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    screenshotWorker,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                screenshotResult.hardwareBuffer,
                                screenshotResult.colorSpace
                            )?.copy(Bitmap.Config.ARGB_8888, false)
                            screenshotResult.hardwareBuffer.close()
                            mainHandler.post {
                                ScreenAnalysisOverlay.showQrLens(this@EssentialKeyService, bitmap)
                            }
                        }
                        override fun onFailure(errorCode: Int) {
                            mainHandler.post {
                                ScreenAnalysisOverlay.showQrLens(this@EssentialKeyService, null)
                            }
                        }
                    }
                )
            } catch (_: Exception) {
                ScreenAnalysisOverlay.showQrLens(this, null)
            }
        } else {
            ScreenAnalysisOverlay.showQrLens(this, null)
        }
    }

    fun rescanContent() = scanScreenContent(false)

    private fun scanScreenContent(isDouble: Boolean = false) {
        if (contentScanInFlight) return
        contentScanInFlight = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            contentScanInFlight = false
            ScreenAnalysisOverlay.showCaptureBlocked(this, "Screen scanning needs Android 11 or newer. This version does not expose a safe screenshot API.")
            return
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, screenshotWorker, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally { screenshot.hardwareBuffer.close() }
                    if (bitmap == null) {
                        mainHandler.post { contentScanInFlight = false; ScreenAnalysisOverlay.showCaptureBlocked(this@EssentialKeyService, "Android did not provide a readable screenshot for this screen.") }
                        return
                    }
                    mainHandler.post {
                        val token = ScreenAnalysisOverlay.showInstantContent(this@EssentialKeyService, bitmap)
                        val rootNode = rootInActiveWindow
                        screenshotWorker.execute {
                            val nodeDetails = ScreenContentAnalyzer.scanAccessibilityNodes(rootNode, resources.displayMetrics.density)
                            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                                .addOnSuccessListener(screenshotWorker) { result ->
                                    val topGuard = (56f * resources.displayMetrics.density).toInt()
                                    val bottomGuard = heightPixels() - (36f * resources.displayMetrics.density).toInt()
                                    // 1. Extract classified items (Links and Phone numbers) from lines & blocks
                                    val extractedDetails = mutableListOf<ScreenDetail>()
                                    for (block in result.textBlocks) {
                                        for (line in block.lines) {
                                            val lineText = line.text.trim()
                                            val lineRect = line.boundingBox ?: block.boundingBox ?: android.graphics.Rect()
                                            val lineClassified = ScreenContentAnalyzer.classifyRecognizedText(lineText)
                                            for (item in lineClassified) {
                                                val matchingElements = line.elements.filter { elem ->
                                                    elem.text.isNotEmpty() && item.value.contains(elem.text, ignoreCase = true)
                                                }
                                                val targetBounds = if (matchingElements.isNotEmpty()) {
                                                    val union = android.graphics.Rect(matchingElements.first().boundingBox ?: lineRect)
                                                    for (elem in matchingElements.drop(1)) {
                                                        elem.boundingBox?.let { union.union(it) }
                                                    }
                                                    union
                                                } else {
                                                    android.graphics.Rect(lineRect)
                                                }
                                                extractedDetails.add(item.copy(bounds = targetBounds))
                                            }
                                        }
                                    }
                                    val finalDetails = ScreenContentAnalyzer.rankAndDeduplicate(extractedDetails + nodeDetails)

                                    // 2. Extract granular word-level items for precision word selection and manual resize
                                    val regions = result.textBlocks.flatMap { block ->
                                        block.lines.flatMap { line ->
                                            line.elements.mapNotNull { element ->
                                                val rect = element.boundingBox
                                                val text = element.text.trim()
                                                if (rect != null && text.isNotEmpty() && rect.width() > 3 && rect.height() > 3 &&
                                                    ScreenContentAnalyzer.isAcceptableOcrBlock(text, rect, topGuard, bottomGuard)) {
                                                    OcrTextItem(text, android.graphics.Rect(rect), element.confidence)
                                                } else null
                                            }
                                        }
                                    }.sortedWith(compareBy<OcrTextItem> { it.bounds.top }.thenBy { it.bounds.left })
                                        .distinctBy { it.text.lowercase() to it.bounds.toShortString() }
                                    mainHandler.post {
                                        contentScanInFlight = false
                                        ScreenAnalysisOverlay.updateContentDetails(
                                            token,
                                            this@EssentialKeyService,
                                            finalDetails,
                                            regions
                                        )
                                    }
                                }
                                .addOnFailureListener(screenshotWorker) {
                                    mainHandler.post {
                                        contentScanInFlight = false
                                        ScreenAnalysisOverlay.updateContentDetails(
                                            token,
                                            this@EssentialKeyService,
                                            emptyList(),
                                            emptyList()
                                        )
                                    }
                                }
                                .addOnCompleteListener(screenshotWorker) { recognizer.close() }
                        }
                    }
                }
                override fun onFailure(errorCode: Int) {
                    val message = if (Build.VERSION.SDK_INT >= 34 && errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                        "This app marks the screen as secure, so Android blocked the screenshot. Badger cannot scan secure content."
                    } else "Android could not capture this screen. Close secure playback or try again from another app."
                    mainHandler.post { contentScanInFlight = false; ScreenAnalysisOverlay.showCaptureBlocked(this@EssentialKeyService, message) }
                }
            })
        } catch (_: RuntimeException) {
            contentScanInFlight = false
            ScreenAnalysisOverlay.showCaptureBlocked(this, "Android could not capture this screen. Try again from a different app.")
        }
    }

    private fun heightPixels(): Int = resources.displayMetrics.heightPixels

    private fun showMessage(title: String, message: String) = android.widget.Toast.makeText(this, "$title · $message", android.widget.Toast.LENGTH_LONG).show()

    private fun isSelectedKey(event: KeyEvent, stored: String?): Boolean {
        val descriptor = event.device?.descriptor.orEmpty()
        val signature = "$descriptor|${event.keyCode}:${event.scanCode}"
        val nothingDefault = Build.MANUFACTURER.equals("Nothing", ignoreCase = true) &&
            event.keyCode == KeyEvent.KEYCODE_UNKNOWN && event.scanCode == ESSENTIAL_KEY_SCAN_CODE
        return nothingDefault || (stored != null && stored == signature)
    }

    companion object {
        const val PREFS = "integral_detection"
const val USE_GLYPHS = "use_glyphs"
const val GLYPHS_PROGRESS = "glyphs_progress"
const val GLYPHS_APP_FLASH = "glyphs_app_flash"
const val GLYPHS_VOICE_REACTIVITY = "glyphs_voice_reactivity"
        const val ONBOARDING_COMPLETED = "onboarding_completed"
        const val DETECTION_ENABLED = "detection_enabled"
        const val MAPPING_ENABLED = "mapping_enabled"
        const val TARGET_KEY_SIGNATURE = "target_key_signature"
        const val MAPPED_ACTION = "mapped_action"
        const val ACTION_SINGLE = "mapped_single_action"
        const val ACTION_DOUBLE = "mapped_double_action"
        const val ACTION_TRIPLE = "mapped_triple_action"
        const val ACTION_LONG = "mapped_long_action"
        const val TORCH_ON = "torch_on"
        const val ANIMATION_ORIGIN_X = "animation_origin_x"
        const val ANIMATION_ORIGIN_Y = "animation_origin_y"
        const val DEFAULT_ORIGIN_X = 0.98f
        const val DEFAULT_ORIGIN_Y = 0.5614879f
        const val ACTION_RINGER = "ringer"
        const val ACTION_CONTENTS = "contents"
        const val ACTION_VOICE_SEARCH = "voice_search"
        const val ACTION_VOICE_RECORDING = "voice_recording"
        const val ACTION_BUBBLE_HUB = "bubble_hub"
        const val ACTION_TRANSLATE_SCREEN = "translate_screen"
        const val ACTION_MEMO = "memo"
        const val ACTION_QR_SCAN = "qr_scan"
        const val ACTION_SUMMARIZE = "summarize"
        const val ACTION_NONE = "none"
        private const val LONG_PRESS_TIMEOUT_MS = 480L
        private const val MULTI_TAP_TIMEOUT_MS = 250L
        const val ESSENTIAL_KEY_SCAN_CODE = 250
    }
}
