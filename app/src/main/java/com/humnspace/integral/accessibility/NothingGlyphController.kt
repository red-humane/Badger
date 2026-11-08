package com.humnspace.integral.accessibility

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphManager

/**
 * Controller for Nothing Phone (3a) / (3a) Pro Glyph Interface (DEVICE_24111, model A059).
 * Hardware Channel Layout (36 channels):
 * - C1 - C20: Indices 0 - 19  (C light progress ring)
 * - A1 - A11: Indices 20 - 30 (A_1 is top, A_11 is bottom -> 11-band audio-reactive spectrum)
 * - B1 - B5:  Indices 31 - 35 (B diagonal accent strip)
 *
 * Glyph Status Language:
 * - Get-Content scanning: One directional sweep
 * - Content extracted: Two short pulses
 * - Voice recording: Audio-reactive pulse
 * - Recording saved: One confirmation flash
 * - Action failed: Three short warning pulses
 * - Ringer changed: Brief confirmation
 */
object NothingGlyphController {
    private const val TAG = "NothingGlyph"
    private const val DEVICE_3A = "A059"
    private const val CHANNELS_COUNT = 36
    private const val MAX_BRIGHTNESS = 4000

    private var glyphManager: GlyphManager? = null
    private var isConnected = false
    private var isSessionOpen = false
    private var appContext: Context? = null

    private val frameColors = IntArray(CHANNELS_COUNT)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Voice Meter State (Strip A: channels 20..30)
    private var isVoiceRecording = false
    @Volatile private var targetVoiceLevel = 0f
    private var currentMeterLevel = 0f

    private val spectrumRunnable = object : Runnable {
        override fun run() {
            if (!isVoiceRecording) return
            val target = targetVoiceLevel
            currentMeterLevel += (target - currentMeterLevel) * 0.45f
            val level = currentMeterLevel.coerceIn(0f, 1f)
            val litCount = (level * 11f).toInt().coerceIn(0, 11)
            val brightness = (1200 + 2600 * level).toInt().coerceIn(1200, MAX_BRIGHTNESS)

            for (i in 20..30) {
                frameColors[i] = if (i - 20 < litCount) brightness else 0
            }
            applyFrame()
            mainHandler.postDelayed(this, 25L)
        }
    }

    fun init(context: Context) {
        val appCtx = context.applicationContext
        this.appContext = appCtx
        if (glyphManager != null) {
            if (isConnected && !isSessionOpen) {
                try {
                    glyphManager?.openSession()
                    isSessionOpen = true
                } catch (_: Exception) {}
            }
            return
        }
        try {
            val gm = GlyphManager.getInstance(appContext)
            glyphManager = gm
            gm.init(object : GlyphManager.Callback {
                override fun onServiceConnected(componentName: ComponentName?) {
                    isConnected = true
                    try {
                        val is3a = Common.is24111() || Build.MODEL.contains(DEVICE_3A, ignoreCase = true)
                        if (is3a) {
                            gm.register(Glyph.DEVICE_24111)
                        } else {
                            if (Common.is20111()) gm.register(Glyph.DEVICE_20111)
                            else if (Common.is22111()) gm.register(Glyph.DEVICE_22111)
                            else if (Common.is23111()) gm.register(Glyph.DEVICE_23111)
                            else if (Common.is23113()) gm.register(Glyph.DEVICE_23113)
                            else gm.register(Glyph.DEVICE_24111)
                        }
                        gm.openSession()
                        isSessionOpen = true
                        for (i in frameColors.indices) frameColors[i] = 0
                        applyFrame()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to register Glyph session: ${e.message}")
                    }
                }

                override fun onServiceDisconnected(componentName: ComponentName?) {
                    isConnected = false
                    isSessionOpen = false
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "GlyphManager init failed: ${e.message}")
        }
    }

    private fun applyFrame() {
        val gm = glyphManager ?: return
        if (!isConnected || !isSessionOpen) return
        try {
            gm.setFrameColors(frameColors)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting Glyph frame colors: ${e.message}")
        }
    }

    // --- GLYPH STATUS LANGUAGE ---

    /**
     * 1. Get-Content Scanning: One directional sweep across Strip B (31 -> 35).
     */
    fun startGetContentGlyph() {
        mainHandler.removeCallbacksAndMessages(null)
        val sweepSteps = listOf(31, 32, 33, 34, 35)
        sweepSteps.forEachIndexed { idx, ch ->
            mainHandler.postDelayed({
                for (c in 31..35) frameColors[c] = 0
                frameColors[ch] = 3200
                applyFrame()
            }, idx * 35L)
        }
        mainHandler.postDelayed({
            for (c in 31..35) frameColors[c] = 0
            applyFrame()
        }, sweepSteps.size * 35L + 50L)
    }

    fun stopGetContentGlyph() {
        for (c in 31..35) frameColors[c] = 0
        applyFrame()
    }

    /**
     * 2. Content Extracted: Two short, clean pulses.
     */
    fun onContentExtracted() {
        mainHandler.post {
            // Pulse 1
            for (c in 31..35) frameColors[c] = 2800
            applyFrame()
            mainHandler.postDelayed({
                for (c in 31..35) frameColors[c] = 0
                applyFrame()
                mainHandler.postDelayed({
                    // Pulse 2
                    for (c in 31..35) frameColors[c] = 2800
                    applyFrame()
                    mainHandler.postDelayed({
                        for (c in 31..35) frameColors[c] = 0
                        applyFrame()
                    }, 80L)
                }, 90L)
            }, 80L)
        }
    }

    /**
     * 3. Voice Recording: Audio-reactive pulse on Strip A.
     */
    fun startVoiceRecordingSpectrum() {
        isVoiceRecording = true
        targetVoiceLevel = 0f
        currentMeterLevel = 0f
        for (i in frameColors.indices) frameColors[i] = 0
        applyFrame()
        mainHandler.removeCallbacks(spectrumRunnable)
        mainHandler.post(spectrumRunnable)
    }

    fun setVoiceSpectrumLevel(normalizedLevel: Float) {
        if (!isVoiceRecording) return
        targetVoiceLevel = normalizedLevel.coerceIn(0f, 1f)
    }

    fun stopVoiceRecordingSpectrum() {
        isVoiceRecording = false
        mainHandler.removeCallbacks(spectrumRunnable)
        for (i in frameColors.indices) frameColors[i] = 0
        applyFrame()
    }

    /**
     * 4. Recording Saved: One solid confirmation flash.
     */
    fun onRecordingSaved() {
        mainHandler.post {
            for (i in 20..35) frameColors[i] = 3400
            applyFrame()
            mainHandler.postDelayed({
                for (i in 20..35) frameColors[i] = 0
                applyFrame()
            }, 180L)
        }
    }

    /**
     * 5. Action Failed: Three short warning pulses.
     */
    fun onActionFailed() {
        val pulseDuration = 70L
        val pauseDuration = 60L
        mainHandler.post {
            var pulseCount = 0
            fun pulse() {
                if (pulseCount >= 3) {
                    for (i in frameColors.indices) frameColors[i] = 0
                    applyFrame()
                    return
                }
                for (c in 31..35) frameColors[c] = 3000
                applyFrame()
                mainHandler.postDelayed({
                    for (c in 31..35) frameColors[c] = 0
                    applyFrame()
                    pulseCount++
                    mainHandler.postDelayed({ pulse() }, pauseDuration)
                }, pulseDuration)
            }
            pulse()
        }
    }

    /**
     * 6. Ringer Changed: Brief confirmation (single gentle pulse on B strip).
     */
    fun onRingerChanged() {
        mainHandler.post {
            for (c in 31..35) frameColors[c] = 2500
            applyFrame()
            mainHandler.postDelayed({
                for (c in 31..35) frameColors[c] = 0
                applyFrame()
            }, 120L)
        }
    }

    // --- Compatibility & Mirroring Methods ---
    fun setIndeterminateDownloadProgress() {
        for (i in 0 until 5) frameColors[i] = 2200
        for (i in 5 until 20) frameColors[i] = 0
        applyFrame()
    }

    fun setDownloadProgress(progressFraction: Float) {
        val count = (progressFraction.coerceIn(0f, 1f) * 20).toInt().coerceIn(0, 20)
        for (i in 0 until 20) frameColors[i] = if (i < count) 3000 else 0
        applyFrame()
    }

    fun setDownloadProgress(progressPercent: Int) {
        setDownloadProgress(progressPercent / 100f)
    }

    fun clearDownloadProgress() {
        for (i in 0 until 20) frameColors[i] = 0
        applyFrame()
    }

    fun completeDownloadProgress() {
        mainHandler.post {
            for (i in 0 until 20) frameColors[i] = 3600
            applyFrame()
            mainHandler.postDelayed({
                for (i in 0 until 20) frameColors[i] = 0
                applyFrame()
            }, 250L)
        }
    }

    fun playAppOpenFlash() {
        mainHandler.post {
            for (c in 31..35) frameColors[c] = 2500
            applyFrame()
            mainHandler.postDelayed({
                for (c in 31..35) frameColors[c] = 0
                applyFrame()
            }, 100L)
        }
    }

    fun playAppCloseFlash() {
        mainHandler.post {
            for (c in 31..35) frameColors[c] = 2000
            applyFrame()
            mainHandler.postDelayed({
                for (c in 31..35) frameColors[c] = 0
                applyFrame()
            }, 80L)
        }
    }

    fun startTranslateFlicker() {
        startGetContentGlyph()
    }

    fun stopTranslateFlicker() {
        stopGetContentGlyph()
    }

    fun turnOffAll() {
        isVoiceRecording = false
        mainHandler.removeCallbacksAndMessages(null)
        for (i in frameColors.indices) frameColors[i] = 0
        try {
            glyphManager?.turnOff()
        } catch (_: Exception) {}
    }
}
