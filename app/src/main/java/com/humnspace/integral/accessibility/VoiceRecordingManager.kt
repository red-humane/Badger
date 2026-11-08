package com.humnspace.integral.accessibility

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VoiceRecording(
    val file: File,
    val name: String,
    val timestamp: Long,
    val durationMs: Long,
    val sizeBytes: Long
)

object VoiceRecordingManager {
    fun getRecordingsDir(context: Context): File {
        val dir = File(context.filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getRecordings(context: Context): List<VoiceRecording> {
        val dir = getRecordingsDir(context)
        val files = dir.listFiles { f -> f.isFile && (f.name.endsWith(".m4a") || f.name.endsWith(".aac") || f.name.endsWith(".mp3")) }
            ?: return emptyList()

        val list = mutableListOf<VoiceRecording>()
        val retriever = MediaMetadataRetriever()
        val dateFormat = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())

        for (file in files.sortedByDescending { it.lastModified() }) {
            var duration = 0L
            try {
                retriever.setDataSource(file.absolutePath)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                duration = durStr?.toLongOrNull() ?: 0L
            } catch (_: Exception) {
                duration = 0L
            }

            val formattedDate = dateFormat.format(Date(file.lastModified()))
            list.add(
                VoiceRecording(
                    file = file,
                    name = formattedDate,
                    timestamp = file.lastModified(),
                    durationMs = duration,
                    sizeBytes = file.length()
                )
            )
        }

        try {
            retriever.release()
        } catch (_: Exception) {}

        return list
    }

    fun formatDuration(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    fun deleteRecording(file: File): Boolean {
        return try {
            file.delete()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Rename an existing recording file in local storage.
     */
    fun renameRecording(file: File, newBaseName: String): File? {
        return try {
            val clean = newBaseName.trim()
            if (clean.isBlank()) return null
            val ext = file.extension.ifEmpty { "m4a" }
            val newFile = File(file.parentFile, "$clean.$ext")
            if (file.renameTo(newFile)) newFile else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Export a voice recording clip to the public Downloads folder with an optional custom name.
     * Returns the saved file name on success, or null on failure.
     */
    fun exportToDownloads(context: Context, recording: VoiceRecording, customName: String? = null): String? {
        return try {
            val extension = recording.file.extension.ifEmpty { "m4a" }
            val baseName = if (!customName.isNullOrBlank()) {
                customName.trim().removeSuffix(".$extension")
            } else {
                val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(recording.timestamp))
                "VoiceNote_$timeStr"
            }
            val fileName = "$baseName.$extension"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    recording.file.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                fileName
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val dest = File(downloadsDir, fileName)
                recording.file.copyTo(dest, overwrite = true)
                fileName
            }
        } catch (e: Exception) {
            null
        }
    }
}
