package com.humnspace.integral.accessibility

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Signature acoustic feedback generator for Badger & Nothing OS.
 *
 * Implements the Teenage Engineering / Nothing OS audio design philosophy:
 * - Pure micro-synthesizers with mathematical envelopes (exponential decay, fast attack).
 * - Tactile retro-modern mechanical clicks, digital radar sweeps, and crystalline chimes.
 * - Zero latency: all PCM audio waveforms are pre-computed in memory.
 * - Zero asset files: 100% offline and lightweight (< 40 KB total RAM).
 * - Respects system ringer/audio modes via USAGE_ASSISTANCE_SONIFICATION.
 */
object IntegralAcoustics {
    private val executor = Executors.newSingleThreadExecutor()
    private const val SAMPLE_RATE = 44100

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val audioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()

    // Pre-computed PCM audio waveforms for instant 0ms playback:
    private val badgePressPcm: ShortArray by lazy { generateClick(1800f, 18, 0.40f) }
    private val overlayOpenPcm: ShortArray by lazy { generateSweep(480f, 840f, 38, 0.45f) }
    private val overlayDismissPcm: ShortArray by lazy { generateSweep(750f, 320f, 32, 0.38f) }

    private val ringerRingPcm: ShortArray by lazy { generateHarmonicChime(1046f, 2093f, 75, 0.50f) }
    private val ringerVibratePcm: ShortArray by lazy { generateThud(180f, 65f, 42, 0.55f) }
    private val ringerSilentPcm: ShortArray by lazy { generateSweep(440f, 280f, 25, 0.35f) }

    private val scanRadarPcm: ShortArray by lazy { generateRadarSweep(380f, 920f, 60, 0.42f) }
    private val contentDetectedPcm: ShortArray by lazy { generateDoublePip(1400f, 1760f, 22, 25, 18, 0.48f) }
    private val itemSelectPcm: ShortArray by lazy { generateClick(1350f, 18, 0.36f) }
    private val cropExtractPcm: ShortArray by lazy { generateCropSnap(650f, 1450f, 40, 0.46f) }

    private val voiceStartPcm: ShortArray by lazy { generateSweep(520f, 880f, 50, 0.45f) }
    private val voiceSuccessPcm: ShortArray by lazy { generateDoublePip(820f, 1240f, 32, 45, 12, 0.48f) }

    private val recordStartPcm: ShortArray by lazy { generateSweep(400f, 660f, 45, 0.44f) }
    private val recordPausePcm: ShortArray by lazy { generateSweep(500f, 320f, 28, 0.36f) }
    private val recordResumePcm: ShortArray by lazy { generateSweep(320f, 580f, 30, 0.40f) }
    private val recordSavedPcm: ShortArray by lazy { generateTriChime(587f, 880f, 1175f, 25, 25, 35, 0.48f) }

    const val PREF_ENABLE_SFX = "enable_overlay_sfx"

    fun isEnabled(context: Context?): Boolean {
        if (context == null) return true
        val prefs = context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_ENABLE_SFX, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_ENABLE_SFX, enabled).apply()
    }

    /**
     * Tactile mechanical click on hardware button bezel press.
     */
    fun playBadgePress(context: Context? = null) {
        if (!isEnabled(context)) return
        playPcm(badgePressPcm)
    }

    /**
     * Capsule emerges from chassis/bezel.
     */
    fun playOverlayOpen(context: Context? = null) {
        if (!isEnabled(context)) return
        playPcm(overlayOpenPcm)
    }

    /**
     * Capsule retracts back into chassis/bezel.
     */
    fun playOverlayDismiss(context: Context? = null) {
        if (!isEnabled(context)) return
        playPcm(overlayDismissPcm)
    }

    /**
     * Dynamic Ringer switched to Ring mode (crystal harmonic bell).
     */
    fun playRingerRing(context: Context? = null) {
        if (!isEnabled(context)) return
        playPcm(ringerRingPcm)
    }

    /**
     * Dynamic Ringer switched to Vibrate mode (low motorized thud).
     */
    fun playRingerVibrate(context: Context? = null) {
        if (!isEnabled(context)) return
        playPcm(ringerVibratePcm)
    }

    /**
     * Dynamic Ringer switched to Silent mode.
     */
    fun playRingerSilent(context: Context? = null) {
        if (!isEnabled(context)) return
        playPcm(ringerSilentPcm)
    }

    /**
     * Get-Content scanning grid sweep start.
     */
    fun playScanRadar(context: Context? = null) {
        if (!isEnabled(context)) return
        playPcm(scanRadarPcm)
    }

    /**
     * Get-Content OCR items extracted (Nothing Glyph double-pulse cue).
     */
    fun playContentDetected(context: Context? = null) {
        if (!isEnabled(context)) return
        playPcm(contentDetectedPcm)
    }

    /**
     * Tapping a detected phone, link, or address pill.
     */
    fun playItemSelect(context: Context? = null) {
        if (!isEnabled(context)) return
        playPcm(itemSelectPcm)
    }

    /**
     * Circle-to-crop extracted or saved.
     */
    fun playCropExtract(context: Context? = null) {
        if (!isEnabled(context)) return
        playPcm(cropExtractPcm)
    }

    /**
     * Voice search ("Say it") start listening.
     */
    fun playVoiceStart(context: Context? = null) {
        if (!isEnabled(context)) return
        playPcm(voiceStartPcm)
    }

    /**
     * Voice search recognized or reminder scheduled.
     */
    fun playVoiceSuccess(context: Context? = null) {
        if (!isEnabled(context)) return
        playPcm(voiceSuccessPcm)
    }

    /**
     * Voice recorder starts capturing.
     */
    fun playRecordStart(context: Context? = null) {
        if (!isEnabled(context)) return
        playPcm(recordStartPcm)
    }

    /**
     * Voice recorder paused.
     */
    fun playRecordPause(context: Context? = null) {
        if (!isEnabled(context)) return
        playPcm(recordPausePcm)
    }

    /**
     * Voice recorder resumed.
     */
    fun playRecordResume(context: Context? = null) {
        if (!isEnabled(context)) return
        playPcm(recordResumePcm)
    }

    /**
     * Voice recorder saved to local library.
     */
    fun playRecordSaved(context: Context? = null) {
        if (!isEnabled(context)) return
        playPcm(recordSavedPcm)
    }

    // ==========================================
    // Low-Latency Audio Playback Pipeline
    // ==========================================

    private fun playPcm(samples: ShortArray) {
        if (samples.isEmpty()) return
        executor.execute {
            try {
                val bufferSize = samples.size * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(samples, 0, samples.size)
                track.play()
                val durationMs = ((samples.size.toFloat() / SAMPLE_RATE) * 1000).toLong()
                Thread.sleep(durationMs + 15)
                track.stop()
                track.release()
            } catch (_: Exception) {}
        }
    }

    // ==========================================
    // Teenage Engineering / Nothing OS Waveform Synthesizers
    // ==========================================

    private fun generateSweep(startFreq: Float, endFreq: Float, durationMs: Int, volume: Float): ShortArray {
        val numSamples = (SAMPLE_RATE * (durationMs / 1000f)).toInt().coerceAtLeast(1)
        val samples = ShortArray(numSamples)
        var phase = 0.0

        for (i in 0 until numSamples) {
            val t = i.toFloat() / numSamples
            val freq = startFreq + (endFreq - startFreq) * t
            phase += 2.0 * PI * freq / SAMPLE_RATE
            if (phase > 2.0 * PI) phase -= 2.0 * PI

            // Fast 6% attack, tight exponential decay
            val env = when {
                t < 0.06f -> t / 0.06f
                else -> exp(-4.2 * (t - 0.06f)).toFloat()
            }
            val v = (sin(phase) * env * volume * Short.MAX_VALUE).toInt()
            samples[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    private fun generateClick(freq: Float, durationMs: Int, volume: Float): ShortArray {
        val numSamples = (SAMPLE_RATE * (durationMs / 1000f)).toInt().coerceAtLeast(1)
        val samples = ShortArray(numSamples)
        var phase = 0.0

        for (i in 0 until numSamples) {
            val t = i.toFloat() / numSamples
            phase += 2.0 * PI * freq / SAMPLE_RATE
            if (phase > 2.0 * PI) phase -= 2.0 * PI

            // Ultra-snappy mechanical click envelope
            val env = when {
                t < 0.08f -> t / 0.08f
                else -> exp(-9.0 * (t - 0.08f)).toFloat()
            }
            val v = (sin(phase) * env * volume * Short.MAX_VALUE).toInt()
            samples[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    private fun generateHarmonicChime(f1: Float, f2: Float, durationMs: Int, volume: Float): ShortArray {
        val numSamples = (SAMPLE_RATE * (durationMs / 1000f)).toInt().coerceAtLeast(1)
        val samples = ShortArray(numSamples)
        var phase1 = 0.0
        var phase2 = 0.0

        for (i in 0 until numSamples) {
            val t = i.toFloat() / numSamples
            phase1 += 2.0 * PI * f1 / SAMPLE_RATE
            phase2 += 2.0 * PI * f2 / SAMPLE_RATE
            if (phase1 > 2.0 * PI) phase1 -= 2.0 * PI
            if (phase2 > 2.0 * PI) phase2 -= 2.0 * PI

            val env1 = exp(-3.2 * t).toFloat()
            val env2 = exp(-5.0 * t).toFloat()

            val tone = (sin(phase1) * env1 * 0.70f) + (sin(phase2) * env2 * 0.30f)
            val v = (tone * volume * Short.MAX_VALUE).toInt()
            samples[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    private fun generateThud(startFreq: Float, endFreq: Float, durationMs: Int, volume: Float): ShortArray {
        val numSamples = (SAMPLE_RATE * (durationMs / 1000f)).toInt().coerceAtLeast(1)
        val samples = ShortArray(numSamples)
        var phase = 0.0

        for (i in 0 until numSamples) {
            val t = i.toFloat() / numSamples
            // Rapid pitch drop for motorized physical feeling
            val freq = startFreq + (endFreq - startFreq) * (t * t)
            phase += 2.0 * PI * freq / SAMPLE_RATE
            if (phase > 2.0 * PI) phase -= 2.0 * PI

            val env = when {
                t < 0.05f -> t / 0.05f
                else -> exp(-4.8 * (t - 0.05f)).toFloat()
            }
            val v = (sin(phase) * env * volume * Short.MAX_VALUE).toInt()
            samples[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    private fun generateRadarSweep(startFreq: Float, endFreq: Float, durationMs: Int, volume: Float): ShortArray {
        val numSamples = (SAMPLE_RATE * (durationMs / 1000f)).toInt().coerceAtLeast(1)
        val samples = ShortArray(numSamples)
        var phase = 0.0

        for (i in 0 until numSamples) {
            val t = i.toFloat() / numSamples
            val freq = startFreq + (endFreq - startFreq) * kotlin.math.sqrt(t)
            phase += 2.0 * PI * freq / SAMPLE_RATE
            if (phase > 2.0 * PI) phase -= 2.0 * PI

            val env = when {
                t < 0.10f -> t / 0.10f
                else -> exp(-3.6 * (t - 0.10f)).toFloat()
            }
            val v = (sin(phase) * env * volume * Short.MAX_VALUE).toInt()
            samples[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    private fun generateDoublePip(f1: Float, f2: Float, d1Ms: Int, d2Ms: Int, gapMs: Int, volume: Float): ShortArray {
        val s1 = generateSweep(f1, f1 * 1.08f, d1Ms, volume)
        val gap = ShortArray((SAMPLE_RATE * (gapMs / 1000f)).toInt())
        val s2 = generateSweep(f2 * 0.94f, f2, d2Ms, volume * 0.92f)

        val total = ShortArray(s1.size + gap.size + s2.size)
        System.arraycopy(s1, 0, total, 0, s1.size)
        System.arraycopy(gap, 0, total, s1.size, gap.size)
        System.arraycopy(s2, 0, total, s1.size + gap.size, s2.size)
        return total
    }

    private fun generateCropSnap(f1: Float, f2: Float, durationMs: Int, volume: Float): ShortArray {
        val numSamples = (SAMPLE_RATE * (durationMs / 1000f)).toInt().coerceAtLeast(1)
        val samples = ShortArray(numSamples)
        var phase = 0.0

        for (i in 0 until numSamples) {
            val t = i.toFloat() / numSamples
            val freq = f1 + (f2 - f1) * (t * t * t)
            phase += 2.0 * PI * freq / SAMPLE_RATE
            if (phase > 2.0 * PI) phase -= 2.0 * PI

            val env = when {
                t < 0.15f -> t / 0.15f
                else -> exp(-6.5 * (t - 0.15f)).toFloat()
            }
            val v = (sin(phase) * env * volume * Short.MAX_VALUE).toInt()
            samples[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    private fun generateTriChime(f1: Float, f2: Float, f3: Float, d1Ms: Int, d2Ms: Int, d3Ms: Int, volume: Float): ShortArray {
        val s1 = generateSweep(f1, f1 * 1.05f, d1Ms, volume * 0.85f)
        val s2 = generateSweep(f2, f2 * 1.05f, d2Ms, volume * 0.92f)
        val s3 = generateSweep(f3, f3 * 1.05f, d3Ms, volume)

        val total = ShortArray(s1.size + s2.size + s3.size)
        System.arraycopy(s1, 0, total, 0, s1.size)
        System.arraycopy(s2, 0, total, s1.size, s2.size)
        System.arraycopy(s3, 0, total, s1.size + s2.size, s3.size)
        return total
    }
}
