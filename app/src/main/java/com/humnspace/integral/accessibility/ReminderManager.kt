package com.humnspace.integral.accessibility

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.humnspace.integral.MainActivity
import com.humnspace.integral.R
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

enum class ReminderStatus {
    PENDING,
    SCHEDULED,
    TRIGGERED,
    COMPLETED,
    CANCELLED
}

data class ParsedReminder(
    val title: String,
    val scheduledAtIso: String,
    val timezone: String,
    val recurrence: String?,
    val triggerTimestamp: Long
)

data class BadgerReminder(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val triggerTimestamp: Long,
    val status: ReminderStatus = ReminderStatus.SCHEDULED,
    val scheduledAtIso: String = "",
    val timezone: String = TimeZone.getDefault().id,
    val recurrence: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isCompleted: Boolean get() = (status == ReminderStatus.COMPLETED)
}

object ReminderManager {
    const val CHANNEL_ID = "badger_reminders"
    const val PREFS_NAME = "badger_reminders_store"
    const val KEY_REMINDERS = "reminders_json"

    const val ACTION_SHOW_REMINDER = "com.humnspace.integral.ACTION_SHOW_REMINDER"
    const val ACTION_REMINDER_DONE = "com.humnspace.integral.ACTION_REMINDER_DONE"
    const val EXTRA_REMINDER_ID = "extra_reminder_id"
    const val EXTRA_REMINDER_TITLE = "extra_reminder_title"

    fun initNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Badger Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders created via Say it"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Parses natural language reminder queries into a structured object:
     * - Title / description (stripped of all scheduling expressions and artifacts)
     * - ISO 8601 scheduled time
     * - Device timezone
     * - Optional recurrence
     *
     * Supports formats:
     * - "Remind me to wake up at 12.30pm" -> Title: "Wake up", Time: 12:30 PM
     * - "Remind me to call John at 5pm" -> Title: "Call John", Time: 5:00 PM
     * - "Remind me to drink water at 8:30 AM" -> Title: "Drink water", Time: 8:30 AM
     * - "Remind me to study at 7 tonight" -> Title: "Study", Time: 7:00 PM
     * - "Remind me to exercise tomorrow at 6am" -> Title: "Exercise", Time: Tomorrow, 6:00 AM
     * - "Remind me to check emails at 12:30" -> Title: "Check emails", Time: 12:30 PM
     * - "Remind me to wake up at 12:30 p.m." -> Title: "Wake up", Time: 12:30 PM
     * - "Remind me to take a break in 30 minutes" -> Title: "Take a break", Time: Now + 30 min
     * - "Remind me to call Mom next Monday at 10am" -> Title: "Call Mom", Time: Next Monday 10:00 AM
     */
    fun parseReminderQuery(rawQuery: String): ParsedReminder {
        val lower = rawQuery.lowercase(Locale.ROOT)
        var working = rawQuery
            .replace(Regex("""(?i)\bp\.m\.?"""), "pm")
            .replace(Regex("""(?i)\ba\.m\.?"""), "am")
            .replace(Regex("""(?i)\b(\d{1,2})[\.:](\d{2})\s*(pm|am)\b"""), "$1:$2 $3")

        // 1. Strip leading trigger prefixes
        val prefixRegex = Regex("""(?i)^\s*(?:please\s+)?(?:can\s+you\s+)?(?:set\s+(?:a\s+)?reminder\s+(?:to|for|about)?|remind\s+me\s+(?:to|for|about)?|reminder\s+(?:to|for|about)?)\s*""")
        working = working.replaceFirst(prefixRegex, "").trim()

        val cal = Calendar.getInstance()
        val now = Calendar.getInstance()
        var dateSpecified = false
        var timeSpecified = false
        var recurrence: String? = null

        // 2. Parse Recurrence
        val recurrencePatterns = listOf(
            Regex("""(?i)\b(?:every\s+day|daily)\b""") to "DAILY",
            Regex("""(?i)\b(?:every\s+week|weekly)\b""") to "WEEKLY",
            Regex("""(?i)\b(?:every\s+month|monthly)\b""") to "MONTHLY",
            Regex("""(?i)\b(?:every\s+weekday|on\s+weekdays)\b""") to "WEEKDAYS"
        )
        for ((p, rec) in recurrencePatterns) {
            if (p.containsMatchIn(working)) {
                recurrence = rec
                working = working.replace(p, " ")
                break
            }
        }

        // 3. Relative Offset: "in X minutes / hours / days"
        val relativeRegex = Regex("""(?i)\bin\s+(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)\b""")
        val relMatch = relativeRegex.find(working)
        if (relMatch != null) {
            val amount = relMatch.groupValues[1].toIntOrNull() ?: 1
            val unit = relMatch.groupValues[2].lowercase(Locale.ROOT)
            when {
                unit.startsWith("sec") -> cal.add(Calendar.SECOND, amount)
                unit.startsWith("m") -> cal.add(Calendar.MINUTE, amount)
                unit.startsWith("h") -> cal.add(Calendar.HOUR_OF_DAY, amount)
                unit.startsWith("d") -> cal.add(Calendar.DAY_OF_YEAR, amount)
            }
            working = working.replace(relMatch.value, " ")
            timeSpecified = true
            dateSpecified = true
        }

        // 4. Date expressions: tomorrow, tonight, next [day of week]
        if (!dateSpecified) {
            val tomorrowRegex = Regex("""(?i)\b(?:tomorrow\s+morning)\b""")
            val tomMornMatch = tomorrowRegex.find(working)
            if (tomMornMatch != null) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 9)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                working = working.replace(tomMornMatch.value, " ")
                dateSpecified = true
                timeSpecified = true
            }
        }

        if (!dateSpecified) {
            val tomorrowEveningRegex = Regex("""(?i)\b(?:tomorrow\s+(?:evening|night))\b""")
            val tomEveMatch = tomorrowEveningRegex.find(working)
            if (tomEveMatch != null) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 20)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                working = working.replace(tomEveMatch.value, " ")
                dateSpecified = true
                timeSpecified = true
            }
        }

        if (!dateSpecified) {
            val tomorrowRegex = Regex("""(?i)\btomorrow\b""")
            val tomMatch = tomorrowRegex.find(working)
            if (tomMatch != null) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                working = working.replace(tomMatch.value, " ")
                dateSpecified = true
            }
        }

        if (!dateSpecified) {
            val tonightRegex = Regex("""(?i)\btonight\b""")
            val tonMatch = tonightRegex.find(working)
            if (tonMatch != null) {
                if (!timeSpecified) {
                    cal.set(Calendar.HOUR_OF_DAY, 20)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    timeSpecified = true
                }
                working = working.replace(tonMatch.value, " ")
                dateSpecified = true
            }
        }

        // Days of week: "next monday", "this friday", "on wednesday"
        val dayOfWeekRegex = Regex("""(?i)\b(?:next|this|on)?\s*(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""")
        val dowMatch = dayOfWeekRegex.find(working)
        if (dowMatch != null) {
            val dayName = dowMatch.groupValues[1].lowercase(Locale.ROOT)
            val targetDow = when (dayName) {
                "sunday" -> Calendar.SUNDAY
                "monday" -> Calendar.MONDAY
                "tuesday" -> Calendar.TUESDAY
                "wednesday" -> Calendar.WEDNESDAY
                "thursday" -> Calendar.THURSDAY
                "friday" -> Calendar.FRIDAY
                "saturday" -> Calendar.SATURDAY
                else -> -1
            }
            if (targetDow != -1) {
                val currentDow = cal.get(Calendar.DAY_OF_WEEK)
                var diff = targetDow - currentDow
                if (diff <= 0) diff += 7
                cal.add(Calendar.DAY_OF_YEAR, diff)
                working = working.replace(dowMatch.value, " ")
                dateSpecified = true
            }
        }

        // 5. Clock Time with minutes and AM/PM:
        // Examples: "at 12.30pm", "at 12:30pm", "at 12:30 pm", "12.30 p.m.", "5pm", "8:30 AM", "at 7 tonight"
        val clockRegex = Regex("""(?i)\b(?:at|by|for)?\s*(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|o'?clock)?\b""")
        val clockMatches = clockRegex.findAll(working).toList()
        for (m in clockMatches) {
            val hourStr = m.groupValues[1]
            val minStr = m.groupValues[2]
            val ampmStr = m.groupValues[3].lowercase(Locale.ROOT).replace(".", "")

            if (hourStr.isNotBlank() && (minStr.isNotBlank() || ampmStr.isNotBlank() || m.value.lowercase(Locale.ROOT).contains("at"))) {
                var hour = hourStr.toIntOrNull() ?: continue
                if (hour > 24) continue
                val minute = if (minStr.isNotBlank()) minStr.toIntOrNull() ?: 0 else 0

                val isPm = ampmStr.startsWith("p") || working.lowercase(Locale.ROOT).contains("tonight") || working.lowercase(Locale.ROOT).contains("evening")
                val isAm = ampmStr.startsWith("a") || working.lowercase(Locale.ROOT).contains("morning")

                if (isPm && hour < 12) hour += 12
                if (isAm && hour == 12) hour = 0

                // If no AM/PM was specified, check if afternoon/evening or tonight
                if (ampmStr.isBlank()) {
                    val currentHour24 = now.get(Calendar.HOUR_OF_DAY)
                    if (hour in 1..11 && currentHour24 >= 12) {
                        val testCal = cal.clone() as Calendar
                        testCal.set(Calendar.HOUR_OF_DAY, hour + 12)
                        testCal.set(Calendar.MINUTE, minute)
                        testCal.set(Calendar.SECOND, 0)
                        testCal.set(Calendar.MILLISECOND, 0)
                        if (testCal.after(now) || testCal.timeInMillis > (now.timeInMillis - 3 * 60_000L)) {
                            hour += 12
                        }
                    } else if (hour in 1..7 && working.lowercase(Locale.ROOT).contains("tonight")) {
                        hour += 12
                    }
                }

                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, minute)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                timeSpecified = true

                working = working.replace(m.value, " ")
                break
            }
        }

        // 6. Natural time anchors: "noon" / "midnight"
        if (!timeSpecified) {
            if (Regex("""(?i)\bnoon\b""").containsMatchIn(working)) {
                cal.set(Calendar.HOUR_OF_DAY, 12)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                timeSpecified = true
                working = working.replace(Regex("""(?i)\bnoon\b"""), " ")
            } else if (Regex("""(?i)\bmidnight\b""").containsMatchIn(working)) {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                timeSpecified = true
                working = working.replace(Regex("""(?i)\bmidnight\b"""), " ")
            }
        }

        // If time was specified but no date, and the specified time is significantly earlier today, push to tomorrow
        if (timeSpecified && !dateSpecified) {
            if (cal.timeInMillis < (now.timeInMillis - 3 * 60_000L)) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        } else if (!timeSpecified && !dateSpecified) {
            // Default: 1 hour in future if no time given
            cal.add(Calendar.HOUR_OF_DAY, 1)
        }

        // 7. Strip leftover scheduling keywords, prepositions, and punctuation
        val leftoverKeywords = Regex("""(?i)\b(today|tomorrow|tonight|morning|afternoon|evening|night|am|pm|a\.m\.|p\.m\.|at|by|on|for|in)\b""")
        working = working.replace(leftoverKeywords, " ")
        // Strip leading/trailing prepositions "to", "for", "about"
        working = working
            .replace(Regex("""^(?i)\s*(?:to|for|about)\s+"""), "")
            .replace(Regex("""(?i)\s+(?:to|for|about)\s*$"""), "")
        working = working.replace(Regex("""^[,\s\-:]+"""), "").replace(Regex("""[,\s\-:]+$"""), "")
        working = working.replace(Regex("""\s+"""), " ").trim()

        // Clean title
        var title = working.ifBlank { "Reminder" }
        title = title.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        val scheduledIso = isoFormatter.format(Date(cal.timeInMillis))
        val tzId = TimeZone.getDefault().id

        return ParsedReminder(
            title = title,
            scheduledAtIso = scheduledIso,
            timezone = tzId,
            recurrence = recurrence,
            triggerTimestamp = cal.timeInMillis
        )
    }

    /**
     * Operation 1: createReminder()
     * Validates and saves the reminder into persistent storage and schedules its future trigger.
     * CRITICAL: DOES NOT invoke the notification display function!
     */
    fun createReminder(context: Context, rawQuery: String): BadgerReminder {
        val parsed = parseReminderQuery(rawQuery)
        val reminder = BadgerReminder(
            title = parsed.title,
            triggerTimestamp = parsed.triggerTimestamp,
            status = ReminderStatus.SCHEDULED,
            scheduledAtIso = parsed.scheduledAtIso,
            timezone = parsed.timezone,
            recurrence = parsed.recurrence
        )

        saveReminder(context, reminder)
        scheduleReminder(context, reminder)
        return reminder
    }

    /**
     * Operation 2: scheduleReminder()
     * Registers future execution with Android AlarmManager.
     * Uses setExactAndAllowWhileIdle for exact alarms with graceful fallback.
     */
    fun scheduleReminder(context: Context, reminder: BadgerReminder) {
        val now = System.currentTimeMillis()
        if (reminder.triggerTimestamp <= now) {
            showReminderNotification(context, reminder)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            action = ACTION_SHOW_REMINDER
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            putExtra(EXTRA_REMINDER_TITLE, reminder.title)
        }
        val notifId = (reminder.id.hashCode() and 0x7FFFFFFF)
        val pi = PendingIntent.getBroadcast(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerTimestamp, pi)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerTimestamp, pi)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerTimestamp, pi)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, reminder.triggerTimestamp, pi)
            }
        } catch (_: Exception) {
            try {
                alarmManager.set(AlarmManager.RTC_WAKEUP, reminder.triggerTimestamp, pi)
            } catch (_: Exception) {}
        }
    }

    /**
     * Operation 3: showReminderNotification()
     * Invoked ONLY when the scheduled trigger actually fires in ReminderBroadcastReceiver.
     * Displays high-priority system notification with "Done" action and Glyph / sound alert.
     */
    fun showReminderNotification(context: Context, reminder: BadgerReminder) {
        initNotificationChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = (reminder.id.hashCode() and 0x7FFFFFFF)

        // Mark status as TRIGGERED
        updateReminderStatus(context, reminder.id, ReminderStatus.TRIGGERED)

        // Done Action PendingIntent
        val doneIntent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            action = ACTION_REMINDER_DONE
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            putExtra(EXTRA_REMINDER_TITLE, reminder.title)
        }
        val donePendingIntent = PendingIntent.getBroadcast(
            context,
            notifId,
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Tap notification to open Badger app
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_page", "reminders")
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            notifId + 1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(reminder.triggerTimestamp))
        val subtext = "Due · $timeStr"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reminder")
            .setContentText(reminder.title)
            .setSubText(subtext)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(reminder.title)
                    .setSummaryText(subtext)
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Done", donePendingIntent)
            .build()

        nm.notify(notifId, notification)
        IntegralAcoustics.playContentDetected(context)
        NothingGlyphController.onRecordingSaved()
    }

    /**
     * Reschedules all pending/scheduled reminders upon device reboot or package update.
     */
    fun rescheduleAllPendingReminders(context: Context) {
        val now = System.currentTimeMillis()
        val all = getReminders(context)
        for (r in all) {
            if (r.status == ReminderStatus.SCHEDULED || r.status == ReminderStatus.PENDING) {
                if (r.triggerTimestamp > now) {
                    scheduleReminder(context, r)
                } else {
                    // Gracefully handle reminders that matured while device was powered off
                    showReminderNotification(context, r)
                }
            }
        }
    }

    fun syncActiveNotifications(context: Context) {
        rescheduleAllPendingReminders(context)
    }

    fun cancelReminder(context: Context, id: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            action = ACTION_SHOW_REMINDER
            putExtra(EXTRA_REMINDER_ID, id)
        }
        val notifId = (id.hashCode() and 0x7FFFFFFF)
        val pi = PendingIntent.getBroadcast(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null && alarmManager != null) {
            alarmManager.cancel(pi)
            pi.cancel()
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notifId)

        updateReminderStatus(context, id, ReminderStatus.CANCELLED)
    }

    fun markCompleted(context: Context, id: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(id.hashCode() and 0x7FFFFFFF)
        updateReminderStatus(context, id, ReminderStatus.COMPLETED)
    }

    fun deleteReminder(context: Context, id: String) {
        cancelReminder(context, id)
        val reminders = getReminders(context).filterNot { it.id == id }
        saveAll(context, reminders)
    }

    fun saveReminder(context: Context, reminder: BadgerReminder) {
        val list = getReminders(context).toMutableList()
        list.removeAll { it.id == reminder.id }
        list.add(reminder)
        saveAll(context, list)
    }

    fun updateReminderStatus(context: Context, id: String, newStatus: ReminderStatus) {
        val list = getReminders(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx != -1) {
            val curr = list[idx]
            list[idx] = curr.copy(status = newStatus)
            saveAll(context, list)
        }
    }

    fun getReminders(context: Context): List<BadgerReminder> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_REMINDERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<BadgerReminder>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val statusStr = obj.optString("status", if (obj.optBoolean("done", false)) "COMPLETED" else "SCHEDULED")
                val status = try {
                    ReminderStatus.valueOf(statusStr)
                } catch (_: Exception) {
                    ReminderStatus.SCHEDULED
                }
                list.add(
                    BadgerReminder(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        triggerTimestamp = obj.getLong("time"),
                        status = status,
                        scheduledAtIso = obj.optString("iso", ""),
                        timezone = obj.optString("tz", TimeZone.getDefault().id),
                        recurrence = obj.optString("recurrence").takeIf { !it.isNullOrBlank() && it != "null" },
                        createdAt = obj.optLong("created", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAll(context: Context, list: List<BadgerReminder>) {
        val arr = JSONArray()
        for (r in list) {
            val obj = JSONObject().apply {
                put("id", r.id)
                put("title", r.title)
                put("time", r.triggerTimestamp)
                put("status", r.status.name)
                put("done", r.status == ReminderStatus.COMPLETED)
                put("iso", r.scheduledAtIso)
                put("tz", r.timezone)
                put("recurrence", r.recurrence)
                put("created", r.createdAt)
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REMINDERS, arr.toString())
            .apply()
    }

    /**
     * Backward compatibility entrypoint: delegates to createReminder without immediate notification.
     */
    fun parseAndSchedule(context: Context, rawQuery: String): BadgerReminder {
        return createReminder(context, rawQuery)
    }

    fun showNotification(context: Context, reminder: BadgerReminder, isAlarmFiring: Boolean = false) {
        if (isAlarmFiring) {
            showReminderNotification(context, reminder)
        }
    }
}

class ReminderBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ReminderManager.rescheduleAllPendingReminders(context)
            }
            ReminderManager.ACTION_SHOW_REMINDER -> {
                val id = intent.getStringExtra(ReminderManager.EXTRA_REMINDER_ID) ?: return
                val title = intent.getStringExtra(ReminderManager.EXTRA_REMINDER_TITLE) ?: "Reminder"
                val reminder = ReminderManager.getReminders(context).firstOrNull { it.id == id }
                    ?: BadgerReminder(
                        id = id,
                        title = title,
                        triggerTimestamp = System.currentTimeMillis(),
                        status = ReminderStatus.TRIGGERED
                    )
                ReminderManager.showReminderNotification(context, reminder)
            }
            ReminderManager.ACTION_REMINDER_DONE -> {
                val id = intent.getStringExtra(ReminderManager.EXTRA_REMINDER_ID) ?: return
                val title = intent.getStringExtra(ReminderManager.EXTRA_REMINDER_TITLE) ?: "Reminder"
                ReminderManager.markCompleted(context, id)
                Toast.makeText(context, "Completed: $title", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
