package com.humnspace.integral.accessibility

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SavedSnippet(
    val id: String,
    val text: String,
    val source: String,
    val savedAt: Long
)

object SavedSnippetsManager {
    private const val PREFS = "badger_saved_snippets"
    private const val KEY = "items"
    private const val MAX_ITEMS = 100

    fun all(context: Context): List<SavedSnippet> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { i ->
                json.optJSONObject(i)?.let {
                    SavedSnippet(it.optString("id"), it.optString("text"), it.optString("source", "Unknown app"), it.optLong("savedAt"))
                }
            }.sortedByDescending { it.savedAt }
        } catch (_: Exception) { emptyList() }
    }

    fun save(context: Context, text: String, sourcePackage: String?): SavedSnippet? {
        val clean = text.trim()
        if (clean.isBlank()) return null
        val sourceLabel = sourcePackage?.takeIf { it.isNotBlank() && it != context.packageName }?.let { pkg ->
            try {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(info).toString()
            } catch (_: Exception) { pkg }
        } ?: "Screen capture"
        val snippet = SavedSnippet(UUID.randomUUID().toString(), clean, sourceLabel, System.currentTimeMillis())
        val updated = (listOf(snippet) + all(context)).take(MAX_ITEMS)
        persist(context, updated)
        return snippet
    }

    fun delete(context: Context, id: String) = persist(context, all(context).filterNot { it.id == id })

    fun formatDate(timestamp: Long): String = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(timestamp))

    private fun persist(context: Context, items: List<SavedSnippet>) {
        val json = JSONArray()
        items.forEach { item ->
            json.put(JSONObject().put("id", item.id).put("text", item.text).put("source", item.source).put("savedAt", item.savedAt))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }
}
