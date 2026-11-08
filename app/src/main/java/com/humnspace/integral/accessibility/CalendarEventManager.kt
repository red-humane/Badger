package com.humnspace.integral.accessibility

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class DetectedEventItem(
    val title: String,
    val dateSummary: String,
    val startMillis: Long,
    val endMillis: Long,
    val bounds: Rect,
    val rawText: String
)

object CalendarEventManager {

    private val DAYS_MAP = mapOf(
        "monday" to Calendar.MONDAY, "mon" to Calendar.MONDAY,
        "tuesday" to Calendar.TUESDAY, "tue" to Calendar.TUESDAY, "tues" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "wed" to Calendar.WEDNESDAY,
        "thursday" to Calendar.THURSDAY, "thu" to Calendar.THURSDAY, "thur" to Calendar.THURSDAY, "thurs" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY, "fri" to Calendar.FRIDAY,
        "saturday" to Calendar.SATURDAY, "sat" to Calendar.SATURDAY,
        "sunday" to Calendar.SUNDAY, "sun" to Calendar.SUNDAY
    )

    private val MONTHS_MAP = mapOf(
        "jan" to Calendar.JANUARY, "feb" to Calendar.FEBRUARY, "mar" to Calendar.MARCH,
        "apr" to Calendar.APRIL, "may" to Calendar.MAY, "jun" to Calendar.JUNE,
        "jul" to Calendar.JULY, "aug" to Calendar.AUGUST, "sep" to Calendar.SEPTEMBER,
        "oct" to Calendar.OCTOBER, "nov" to Calendar.NOVEMBER, "dec" to Calendar.DECEMBER
    )

    private val timeRegex = Regex("""(?i)\b(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""")
    private val dowRegex = Regex("""(?i)\b(?:on\s+)?(?:next\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\b""")
    private val relDayRegex = Regex("""(?i)\b(today|tomorrow|tonight)\b""")
    private val explicitDateRegex = Regex("""(?i)\b(?:on\s+)?(?:(\d{1,2})(?:st|nd|rd|th)?\s+([a-z]{3,9})|([a-z]{3,9})\s+(\d{1,2})(?:st|nd|rd|th)?)\b""")
    private val numericDateRegex = Regex("""\b(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?\b""")
    private val eventCues = Regex("""(?i)\b(call|meet|meeting|appointment|lunch|dinner|breakfast|sync|catchup|interview|doctor|dentist|webinar|session|party|flight|event|reminder|remind|chat|talk|review|discussion|schedule)\b""")

    /**
     * Parses a text line or phrase to detect calendar event intent (e.g. "Call Geetha on Wednesday at 3 PM").
     */
    fun parseEventCandidate(text: String, bounds: Rect): DetectedEventItem? {
        val trimmed = text.trim()
        if (trimmed.length < 8) return null

        val cueMatch = eventCues.find(trimmed)
        val dowMatch = dowRegex.find(trimmed)
        val relMatch = relDayRegex.find(trimmed)
        val expDateMatch = explicitDateRegex.find(trimmed)
        val numDateMatch = numericDateRegex.find(trimmed)

        val hasDayIndicator = dowMatch != null || relMatch != null || expDateMatch != null || numDateMatch != null

        // Find valid time
        var validHour: Int? = null
        var validMinute = 0
        var isPm: Boolean? = null

        val timeMatches = timeRegex.findAll(trimmed).toList()
        for (tm in timeMatches) {
            val fullMatch = tm.value.lowercase()
            val hr = tm.groupValues[1].toIntOrNull() ?: continue
            val mn = tm.groupValues[2].toIntOrNull() ?: 0
            val ampm = tm.groupValues[3].lowercase()

            if (fullMatch.startsWith("at ") || ampm.isNotEmpty() || tm.groupValues[2].isNotEmpty()) {
                if (hr in 1..23) {
                    validHour = hr
                    validMinute = mn
                    isPm = when (ampm) {
                        "pm" -> true
                        "am" -> false
                        else -> null
                    }
                    break
                }
            }
        }

        // We require a time specification and (a day indicator OR an explicit event cue)
        if (validHour == null) return null
        if (!hasDayIndicator && cueMatch == null) return null

        val calendar = Calendar.getInstance()
        val nowMillis = System.currentTimeMillis()
        calendar.timeInMillis = nowMillis

        // 1. Resolve Day
        if (relMatch != null) {
            val rel = relMatch.groupValues[1].lowercase()
            if (rel == "tomorrow") {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
        } else if (dowMatch != null) {
            val targetDow = DAYS_MAP[dowMatch.groupValues[1].lowercase()] ?: Calendar.MONDAY
            val currentDow = calendar.get(Calendar.DAY_OF_WEEK)
            var daysAhead = targetDow - currentDow
            if (daysAhead <= 0) daysAhead += 7
            calendar.add(Calendar.DAY_OF_YEAR, daysAhead)
        } else if (expDateMatch != null) {
            val d1 = expDateMatch.groupValues[1]
            val m1 = expDateMatch.groupValues[2]
            val m2 = expDateMatch.groupValues[3]
            val d2 = expDateMatch.groupValues[4]
            val mStr = (if (m1.isNotEmpty()) m1 else m2).take(3).lowercase()
            val dayInt = (if (d1.isNotEmpty()) d1 else d2).toIntOrNull() ?: 1
            val monthInt = MONTHS_MAP[mStr] ?: calendar.get(Calendar.MONTH)
            calendar.set(Calendar.MONTH, monthInt)
            calendar.set(Calendar.DAY_OF_MONTH, dayInt)
            if (calendar.timeInMillis < nowMillis - (86400000L * 2)) {
                calendar.add(Calendar.YEAR, 1)
            }
        } else if (numDateMatch != null) {
            val d = numDateMatch.groupValues[1].toIntOrNull() ?: 1
            val m = (numDateMatch.groupValues[2].toIntOrNull() ?: 1) - 1
            calendar.set(Calendar.MONTH, m)
            calendar.set(Calendar.DAY_OF_MONTH, d)
            if (calendar.timeInMillis < nowMillis - (86400000L * 2)) {
                calendar.add(Calendar.YEAR, 1)
            }
        }

        // 2. Resolve Time
        var hr24 = validHour
        if (isPm == true && hr24 < 12) {
            hr24 += 12
        } else if (isPm == false && hr24 == 12) {
            hr24 = 0
        } else if (isPm == null) {
            // Heuristic: for standard meeting / call phrases, 1..7 without am/pm is PM
            if (hr24 in 1..7) hr24 += 12
        }

        calendar.set(Calendar.HOUR_OF_DAY, hr24)
        calendar.set(Calendar.MINUTE, validMinute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        val startMillis = calendar.timeInMillis
        val endMillis = startMillis + (60 * 60 * 1000L) // Default 1-hour duration

        // 3. Extract Clean Title
        var title = trimmed
            .replace(Regex("""(?i)^(can you|could you|please|let\'s|lets|kindly)\s+"""), "")
            .trim()

        val splitParts = title.split(Regex("""(?i)\s+(?:on|at)\s+"""))
        if (splitParts.isNotEmpty() && splitParts[0].isNotBlank()) {
            title = splitParts[0].trim()
        }
        title = title.trimEnd('.', ',', ';', ':', '?', '!')
        if (title.length > 36) {
            title = title.take(36).trim()
        }
        title = title.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        val summaryFormat = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
        val dateSummary = summaryFormat.format(calendar.time)

        return DetectedEventItem(
            title = title,
            dateSummary = dateSummary,
            startMillis = startMillis,
            endMillis = endMillis,
            bounds = bounds,
            rawText = trimmed
        )
    }

    /**
     * Attempts to create the calendar event directly in the background.
     * If calendar permissions are granted, creates the event via ContentResolver with zero app switching.
     * If permissions are not granted, launches the prefilled calendar editor as an instant fallback.
     */
    fun createCalendarEvent(context: Context, event: DetectedEventItem): Boolean {
        val hasWrite = ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val hasRead = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

        if (hasWrite && hasRead) {
            try {
                val calId = getPrimaryCalendarId(context) ?: 1L
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calId)
                    put(CalendarContract.Events.TITLE, event.title)
                    put(CalendarContract.Events.DESCRIPTION, "Created by Badger Get-Content from: \"${event.rawText}\"")
                    put(CalendarContract.Events.DTSTART, event.startMillis)
                    put(CalendarContract.Events.DTEND, event.endMillis)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
                    put(CalendarContract.Events.HAS_ALARM, 1)
                }

                val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                if (uri != null) {
                    val eventId = ContentUris.parseId(uri)
                    // Add standard 15-minute reminder
                    try {
                        val reminderValues = ContentValues().apply {
                            put(CalendarContract.Reminders.EVENT_ID, eventId)
                            put(CalendarContract.Reminders.MINUTES, 15)
                            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                        }
                        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
                    } catch (_: Exception) {}
                    return true
                }
            } catch (_: Exception) {
                // SecurityException or provider failure -> proceed to fallback
            }
        }

        // Fallback: Launch prefilled calendar invite without requiring manual typing
        try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.startMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMillis)
                putExtra(CalendarContract.Events.TITLE, event.title)
                putExtra(CalendarContract.Events.DESCRIPTION, "Created by Badger Get-Content from: \"${event.rawText}\"")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return false
        } catch (_: Exception) {
            return false
        }
    }

    private fun getPrimaryCalendarId(context: Context): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.VISIBLE)
        return try {
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            ) ?: return null
            cursor.use {
                var firstId: Long? = null
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val isPrimary = if (it.columnCount > 1) it.getInt(1) else 0
                    if (isPrimary == 1) return id
                    if (firstId == null) firstId = id
                }
                firstId
            }
        } catch (_: Exception) {
            null
        }
    }
}
