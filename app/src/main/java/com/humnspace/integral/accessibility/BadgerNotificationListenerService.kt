package com.humnspace.integral.accessibility

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * High-performance NotificationListenerService for Badger.
 *
 * Captures real-time app download and update progress directly from Google Play Store
 * (com.android.vending), Google Play Services, Chrome, and DownloadManager, dynamically
 * reflecting download progress on Nothing Phone (3a) C Glyph lights (channels 0..19).
 */
class BadgerNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "BadgerNotification"
        private val PERCENT_PATTERN = Pattern.compile("(\\d{1,3})\\s*%")
        private val SIZE_RATIO_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(?:MB|GB|KB|B)?\\s*(?:/|of)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:MB|GB|KB|B)", Pattern.CASE_INSENSITIVE)
        private val activeDownloads = ConcurrentHashMap<String, Float>()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = Runnable {
        activeDownloads.clear()
        NothingGlyphController.clearDownloadProgress()
    }

    override fun onCreate() {
        super.onCreate()
        NothingGlyphController.init(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NothingGlyphController.init(this)
        Log.d(TAG, "Badger Notification Listener connected. Scanning active notifications...")
        try {
            activeNotifications?.forEach { sbn ->
                onNotificationPosted(sbn)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking active notifications on connect: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        val isTargetPkg = pkg == "com.android.vending" ||
                pkg == "com.google.android.gms" ||
                pkg == "com.android.chrome" ||
                pkg == "com.android.providers.downloads" ||
                pkg.contains("vending", ignoreCase = true)

        if (!isTargetPkg) return

        NothingGlyphController.init(this)

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Robust number extraction handling Int, Long, Float, or Double
        val max = when (val m = extras.get(Notification.EXTRA_PROGRESS_MAX)) {
            is Number -> m.toInt()
            is String -> m.toIntOrNull() ?: 0
            else -> 0
        }
        val progress = when (val p = extras.get(Notification.EXTRA_PROGRESS)) {
            is Number -> p.toInt()
            is String -> p.toIntOrNull() ?: 0
            else -> 0
        }
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty()
        val ticker = notification.tickerText?.toString().orEmpty()
        val combinedText = "$title $text $subText $bigText $infoText $summaryText $ticker"

        val key = "${pkg}_${sbn.id}_${sbn.tag.orEmpty()}"

        // Check if download/install has fully completed
        val isCompleted = (max > 0 && progress >= max && !indeterminate) ||
                (combinedText.contains("Installed", ignoreCase = true) && !combinedText.contains("Installing", ignoreCase = true)) ||
                combinedText.contains("Download complete", ignoreCase = true) ||
                combinedText.contains("Update complete", ignoreCase = true)

        if (isCompleted) {
            activeDownloads.remove(key)
            mainHandler.removeCallbacks(watchdogRunnable)
            NothingGlyphController.completeDownloadProgress()
            return
        }

        // 1. Try extracting standard notification progress integer
        if (max > 0) {
            val frac = (progress.toFloat() / max.toFloat()).coerceIn(0f, 1f)
            if (frac > 0f) {
                activeDownloads[key] = frac
                updateAggregateProgress()
                return
            }
        }

        // 2. Fallback: extract percentage via regex (e.g. "45% of 28 MB" or "Updating (45%)")
        val percentMatcher = PERCENT_PATTERN.matcher(combinedText)
        if (percentMatcher.find()) {
            val percentVal = percentMatcher.group(1)?.toIntOrNull()
            if (percentVal != null) {
                val frac = (percentVal.toFloat() / 100f).coerceIn(0f, 1f)
                activeDownloads[key] = frac
                updateAggregateProgress()
                return
            }
        }

        // 3. Fallback: extract download size ratio (e.g. "12.5 MB / 25.0 MB" or "12 MB of 25 MB")
        val sizeMatcher = SIZE_RATIO_PATTERN.matcher(combinedText)
        if (sizeMatcher.find()) {
            val cur = sizeMatcher.group(1)?.toFloatOrNull()
            val total = sizeMatcher.group(2)?.toFloatOrNull()
            if (cur != null && total != null && total > 0f) {
                val frac = (cur / total).coerceIn(0f, 1f)
                activeDownloads[key] = frac
                updateAggregateProgress()
                return
            }
        }

        // 4. Indeterminate starting state when download/update is in progress
        val isStarting = indeterminate ||
                combinedText.contains("Downloading", ignoreCase = true) ||
                combinedText.contains("Installing", ignoreCase = true) ||
                combinedText.contains("Updating", ignoreCase = true) ||
                combinedText.contains("Pending", ignoreCase = true)

        if (isStarting) {
            activeDownloads[key] = 0.05f
            mainHandler.removeCallbacks(watchdogRunnable)
            NothingGlyphController.setIndeterminateDownloadProgress()
            mainHandler.postDelayed(watchdogRunnable, 10000L)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        val isTargetPkg = pkg == "com.android.vending" ||
                pkg == "com.google.android.gms" ||
                pkg == "com.android.chrome" ||
                pkg == "com.android.providers.downloads" ||
                pkg.contains("vending", ignoreCase = true)

        if (isTargetPkg) {
            val key = "${pkg}_${sbn.id}_${sbn.tag.orEmpty()}"
            activeDownloads.remove(key)
            if (activeDownloads.isEmpty()) {
                mainHandler.removeCallbacks(watchdogRunnable)
                NothingGlyphController.clearDownloadProgress()
            } else {
                updateAggregateProgress()
            }
        }
    }

    private fun updateAggregateProgress() {
        if (activeDownloads.isEmpty()) {
            NothingGlyphController.clearDownloadProgress()
            return
        }

        // Display highest active progress when multiple apps download simultaneously
        val maxFrac = activeDownloads.values.maxOrNull() ?: 0f
        mainHandler.removeCallbacks(watchdogRunnable)

        if (maxFrac >= 1f) {
            NothingGlyphController.completeDownloadProgress()
        } else {
            NothingGlyphController.setDownloadProgress(maxFrac)
            // Safety timeout after 12 seconds of no updates
            mainHandler.postDelayed(watchdogRunnable, 12000L)
        }
    }
}
