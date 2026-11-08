package com.humnspace.integral.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import android.util.Patterns
import java.util.ArrayDeque

data class ScreenDetail(
    val kind: String, // "Screen text", "Phone number", "Link", "Email", "Address", "OTP", "Price", "Date / time", "Measurement", "Picture"
    val value: String,
    val canCopy: Boolean = true,
    val bounds: android.graphics.Rect? = null,
    val secondaryText: String? = null
)

data class OcrTextItem(
    val text: String,
    val bounds: android.graphics.Rect,
    val confidence: Float = 1f
)

/**
 * Deterministic, high-accuracy semantic screen content extraction pipeline.
 * Extracts: Phone numbers, URLs, Emails, Addresses, Events, Text, and Images.
 */
object ScreenContentAnalyzer {

    // Phone candidate regex
    private val phoneCandidateRegex = Regex(
        """(?<![\w$€£₹¥])(?:(?:\+\d{1,4}[-.\s]?)?(?:\(?\d{2,5}\)?[-.\s]?)?\d{3,5}[-.\s]?\d{3,5}|\b\d{10,12}\b|\b\d{5}[-\s]\d{5}\b|\b\d{3,4}[-\s]\d{3,4}[-\s]\d{3,4}\b|\b\d{4}[-\s]\d{3}[-\s]\d{3,4}\b)(?!\w)"""
    )
    private val isoDate = Regex("""^\d{4}[-/]\d{1,2}[-/]\d{1,2}$""")
    private val commonDate = Regex("""^\d{1,2}[/-]\d{1,2}[/-]\d{2,4}$""")
    private val timePattern = Regex("""^\d{1,2}:\d{2}(:\d{2})?(\s?[APap][Mm])?$""")

    // URLs and Emails
    private val broadUrlRegex = Regex(
        """(?i)\b(?:https?://[^\s<>"'{}|\\^`]+|www\.[a-zA-Z0-9.-]+\.[a-z]{2,}[^\s<>"'{}|\\^`]*|(?:[a-zA-Z0-9-]+\.)+(?:com|org|net|edu|gov|io|co|in|app|ai|dev|me|info|biz|tech|xyz|online|store|site|ly|to|be|gl|tv|news|blog|link|live|space|club|design|shop|cc|is|us|uk|ca|de|fr|jp|au|ru|br|eu|co\.uk)(?:/[^\s<>"'{}|\\^`]*)?)"""
    )
    private val emailPattern = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

    // Addresses (Physical street / road / sector / colony patterns)
    private val addressPattern = Regex(
        """(?i)\b(?:\d{1,5}[,\s]+)?(?:[A-Za-z0-9.-]+[,\s]+){1,4}(?:Street|St|Road|Rd|Avenue|Ave|Boulevard|Blvd|Lane|Ln|Drive|Dr|Way|Nagar|Colony|Sector\s*\d+|Layout|Cross|Main\s*Road)\b(?:[,\s]+[A-Za-z\s]+)?(?:[,\s]+\b\d{5,6}\b)?"""
    )

    private val otpPattern = Regex("(?i)(?:otp|verification code|one[- ]time code|security code|login code|passcode|pin code|auth code|code is\\s*:?)\\D{0,20}\\b(\\d{4,8})\\b")
    private val pricePattern = Regex("(?i)(?:[₹$€£¥₩]\\s?\\d[\\d,]*(?:\\.\\d{1,2})?|\\b(?:INR|USD|EUR|GBP|CAD|AUD)\\s?\\d[\\d,]*(?:\\.\\d{1,2})?)")
    private val dateTimePattern = Regex("\\b(?:\\d{1,2}[/-]\\d{1,2}[/-](?:\\d{2}|\\d{4})|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}:\\d{2}(?:\\s?[APap][Mm])|\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{2,4})\\b")
    private val measurementPattern = Regex("(?i)\\b\\d+(?:[.,]\\d+)?\\s?(?:kg|g|mg|lb|lbs|cm|mm|km|m|ft|in|ml|l|°c|°f|%)\\b")

    // Status bar & UI artifacts filtering
    private val statusBarClock = Regex("(?i)^(\\d{1,2}:\\d{2}(:\\d{2})?(\\s?[APap][Mm])?)$")
    private val statusBarBattery = Regex("^\\d{1,3}\\s?%?$")
    private val statusBarNetwork = Regex("(?i)^(5G|4G|3G|2G|LTE|VoLTE|Wi[- ]?Fi|Jio|Airtel|Vi|BSNL|Verizon|AT&T|T-Mobile|Emergency calls only|No SIM|No service)$")
    private val statusBarGeneric = Regex("(?i)^(volte\\s?\\d?|hd|4g\\+|5g\\+|lte\\+|silent|alarm|do not disturb)$")

    fun isValidPhone(raw: String): Boolean {
        val trimmed = raw.trim().trimEnd('.', ',', ';', ':', ')', ']', '>', '"', '\'')
        val digits = trimmed.filter(Char::isDigit)
        if (digits.length !in 7..15) return false
        if (isoDate.matches(trimmed) || commonDate.matches(trimmed) || timePattern.matches(trimmed)) return false
        if (trimmed.any { it == '$' || it == '€' || it == '£' || it == '₹' || it == '¥' || it == '%' }) return false
        if (digits.length >= 7 && digits.toSet().size <= 2) return false
        if (digits == "1234567890" || digits == "0123456789") return false
        return true
    }

    fun cleanUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('.', ',', ';', ':', ')', ']', '>', '"', '\'', '}')
        if (trimmed.length < 4) return null
        val lower = trimmed.lowercase()
        val isExplicitUrl = lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.")
        val hasDomain = lower.contains(".") && !lower.contains(" ") && (
            lower.contains(".com") || lower.contains(".org") || lower.contains(".net") ||
            lower.contains(".in") || lower.contains(".co") || lower.contains(".io") ||
            lower.contains(".ai") || lower.contains(".app") || lower.contains(".dev") ||
            lower.contains(".me") || lower.contains(".tech") || lower.contains(".xyz") ||
            lower.contains(".ly") || lower.contains(".to") || lower.contains(".be") ||
            lower.contains(".gl") || lower.contains(".edu") || lower.contains(".gov")
        )
        val matchesWebPattern = Patterns.WEB_URL.matcher(trimmed).matches()
        return if (isExplicitUrl || hasDomain || matchesWebPattern) trimmed else null
    }

    fun extractPhones(text: String): List<String> {
        val results = mutableListOf<String>()
        val seen = HashSet<String>()
        phoneCandidateRegex.findAll(text).forEach { m ->
            val num = m.value.trim().trimEnd('.', ',', ';', ':', ')', ']', '>', '"', '\'')
            if (isValidPhone(num)) {
                val digits = num.filter(Char::isDigit)
                val key = if (digits.length >= 10) digits.takeLast(10) else digits
                if (seen.add(key)) {
                    results.add(num)
                }
            }
        }
        return results
    }

    fun extractLinks(text: String): List<String> {
        val results = mutableListOf<String>()
        val seen = HashSet<String>()
        broadUrlRegex.findAll(text).forEach { m ->
            val atEmail = m.range.first > 0 && text[m.range.first - 1] == '@'
            if (!atEmail) {
                cleanUrl(m.value)?.let { url ->
                    val norm = url.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
                    if (seen.add(norm)) {
                        results.add(url)
                    }
                }
            }
        }
        return results
    }

    fun extractEmails(text: String): List<String> {
        val results = mutableListOf<String>()
        val seen = HashSet<String>()
        emailPattern.findAll(text).forEach { m ->
            val email = m.value.trim().trimEnd('.', ',', ';', ':', ')', ']')
            if (Patterns.EMAIL_ADDRESS.matcher(email).matches() && seen.add(email.lowercase())) {
                results.add(email)
            }
        }
        return results
    }

    fun extractAddresses(text: String): List<String> {
        val results = mutableListOf<String>()
        val seen = HashSet<String>()
        addressPattern.findAll(text).forEach { m ->
            val addr = m.value.trim().trimEnd('.', ',', ';', ':')
            if (addr.length in 8..80 && seen.add(addr.lowercase())) {
                results.add(addr)
            }
        }
        return results
    }

    fun scanAccessibilityNodes(root: AccessibilityNodeInfo?, density: Float = 1f): List<ScreenDetail> {
        if (root == null) return emptyList()
        val found = LinkedHashMap<String, ScreenDetail>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 500) {
            val node = pending.removeLast()
            try {
                if (node.isVisibleToUser && !node.isPassword) {
                    val className = node.className?.toString().orEmpty()
                    val description = node.contentDescription?.toString().orEmpty().trim()
                    val text = node.text?.toString().orEmpty().trim()
                    val bounds = android.graphics.Rect().also(node::getBoundsInScreen)

                    // 1. Picture detection
                    val isImage = className.contains("Image", ignoreCase = true) ||
                        className.contains("Photo", ignoreCase = true)
                    if (isImage) {
                        val minSize = (64f * density).toInt()
                        if (bounds.top >= (56f * density).toInt() && description.length >= 3 && bounds.width() > minSize && bounds.height() > minSize) {
                            found.putIfAbsent("picture:$description", ScreenDetail("Picture", description, false, bounds))
                        }
                    }

                    // 2. Semantic entity detection from native Accessibility text
                    val combinedText = if (text.isNotEmpty()) text else description
                    if (combinedText.isNotEmpty() && bounds.top >= (48f * density).toInt() && bounds.width() > 10 && bounds.height() > 10) {
                        // Phone numbers
                        extractPhones(combinedText).forEach { phone ->
                            val digits = phone.filter(Char::isDigit)
                            val key = if (digits.length >= 10) digits.takeLast(10) else digits
                            found.putIfAbsent("phone:$key", ScreenDetail("Phone number", phone, true, bounds))
                        }
                        // URLs
                        extractLinks(combinedText).forEach { url ->
                            val norm = url.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
                            found.putIfAbsent("link:$norm", ScreenDetail("Link", url, true, bounds))
                        }
                        // Emails
                        extractEmails(combinedText).forEach { email ->
                            found.putIfAbsent("email:${email.lowercase()}", ScreenDetail("Email", email, true, bounds))
                        }
                        // Addresses
                        extractAddresses(combinedText).forEach { addr ->
                            found.putIfAbsent("addr:${addr.lowercase()}", ScreenDetail("Address", addr, true, bounds))
                        }
                    }
                }
                if (!node.isPassword) {
                    for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
                }
            } catch (_: RuntimeException) {
            } finally {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        }
        return found.values.toList()
    }

    fun isAcceptableOcrBlock(text: String, bounds: android.graphics.Rect, topGuardPx: Int, bottomGuardPx: Int): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !trimmed.any { it.isLetterOrDigit() }) return false
        if (bounds.top < topGuardPx) return false
        if (statusBarClock.matches(trimmed) || statusBarBattery.matches(trimmed) ||
            statusBarNetwork.matches(trimmed) || statusBarGeneric.matches(trimmed)) return false
        if (bounds.bottom > bottomGuardPx && (trimmed.equals("back", true) || trimmed.equals("home", true) || trimmed.equals("overview", true))) return false
        return true
    }

    fun classifyRecognizedText(rawText: String): List<ScreenDetail> {
        val clean = rawText.trim().replace("\r", "").lines().joinToString("\n") { it.trim().replace(Regex("[\\t ]+"), " ") }
        if (clean.isBlank()) return emptyList()

        val results = mutableListOf<ScreenDetail>()
        val extractedEntities = HashSet<String>()

        // 1. Emails
        extractEmails(clean).forEach { email ->
            results += ScreenDetail("Email", email)
            extractedEntities.add(email.lowercase())
        }

        // 2. Phone Numbers
        extractPhones(clean).forEach { phone ->
            val digits = phone.filter(Char::isDigit)
            results += ScreenDetail("Phone number", phone)
            extractedEntities.add(digits)
        }

        // 3. Web Links
        extractLinks(clean).forEach { link ->
            results += ScreenDetail("Link", link)
            extractedEntities.add(link.lowercase())
        }

        // 4. Addresses
        extractAddresses(clean).forEach { addr ->
            results += ScreenDetail("Address", addr)
            extractedEntities.add(addr.lowercase())
        }

        // 5. Prices
        pricePattern.findAll(clean).forEach { match ->
            val price = match.value.trim()
            results += ScreenDetail("Price", price)
            extractedEntities.add(price.lowercase())
        }

        // 6. Dates & Times
        dateTimePattern.findAll(clean).forEach { match ->
            val dt = match.value.trim()
            results += ScreenDetail("Date / time", dt)
            extractedEntities.add(dt.lowercase())
        }

        // 7. Measurements
        measurementPattern.findAll(clean).forEach { match ->
            val measurement = match.value.trim()
            results += ScreenDetail("Measurement", measurement)
            extractedEntities.add(measurement.lowercase())
        }

        // 8. Meaningful Screen Text
        val lettersCount = clean.count(Char::isLetter)
        val cleanLower = clean.lowercase()
        val isPureEntity = extractedEntities.any { entity ->
            cleanLower == entity || clean.filter(Char::isDigit) == entity || (cleanLower.contains(entity) && clean.length <= entity.length + 4)
        }

        if (!isPureEntity && lettersCount >= 4 && clean.length in 5..4000) {
            results += ScreenDetail("Screen text", clean)
        }

        return results
    }

    fun rankAndDeduplicate(details: List<ScreenDetail>): List<ScreenDetail> {
        val nonTextItems = mutableListOf<ScreenDetail>()
        val textCandidates = mutableListOf<ScreenDetail>()

        val seenPhones = HashMap<String, ScreenDetail>()
        val seenLinks = HashSet<String>()
        val seenEmails = HashSet<String>()
        val seenAddresses = HashSet<String>()
        val seenGeneral = HashSet<String>()

        for (item in details) {
            when (item.kind) {
                "Phone number" -> {
                    val digits = item.value.filter(Char::isDigit)
                    val key = if (digits.length >= 10) digits.takeLast(10) else digits
                    val existing = seenPhones[key]
                    if (existing == null || (item.value.startsWith("+") && !existing.value.startsWith("+"))) {
                        seenPhones[key] = item
                    }
                }
                "Link" -> {
                    val normUrl = item.value.trim().lowercase()
                        .removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
                    if (seenLinks.add(normUrl)) {
                        nonTextItems.add(item)
                    }
                }
                "Email" -> {
                    if (seenEmails.add(item.value.trim().lowercase())) {
                        nonTextItems.add(item)
                    }
                }
                "Address" -> {
                    if (seenAddresses.add(item.value.trim().lowercase())) {
                        nonTextItems.add(item)
                    }
                }
                "Screen text" -> {
                    textCandidates.add(item)
                }
                else -> {
                    val key = "${item.kind}:${item.value.trim().lowercase()}"
                    if (seenGeneral.add(key)) {
                        nonTextItems.add(item)
                    }
                }
            }
        }
        nonTextItems.addAll(seenPhones.values)

        val cleanedTextItems = mutableListOf<ScreenDetail>()
        val sortedCandidates = textCandidates.sortedByDescending { it.value.length }

        for (candidate in sortedCandidates) {
            val candNorm = candidate.value.trim().lowercase().replace(Regex("\\s+"), " ")
            if (candNorm.length < 5) continue

            val isDuplicate = cleanedTextItems.any { existing ->
                val existNorm = existing.value.trim().lowercase().replace(Regex("\\s+"), " ")
                existNorm.contains(candNorm) || (candNorm.length > 20 && existNorm.take(candNorm.length) == candNorm)
            }

            val isActionDuplicate = nonTextItems.any { action ->
                val actionVal = action.value.trim().lowercase()
                candNorm == actionVal || (action.kind == "Phone number" && candNorm.filter(Char::isDigit) == actionVal.filter(Char::isDigit))
            }

            if (!isDuplicate && !isActionDuplicate) {
                cleanedTextItems.add(candidate)
            }
        }

        val typePriority = mapOf(
            "Phone number" to 1,
            "Address" to 2,
            "Date / time" to 3,
            "Link" to 4,
            "Email" to 5,
            "Picture" to 6,
            "Price" to 7,
            "Measurement" to 8,
            "Screen text" to 9
        )

        return (nonTextItems + cleanedTextItems).sortedWith(compareBy<ScreenDetail> {
            typePriority[it.kind] ?: 10
        }.thenBy {
            it.bounds?.top ?: 0
        })
    }
}
