package com.humnspace.integral.accessibility

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object SavedCropsManager {
    private const val CROPS_DIR_NAME = "badger_saved_crops"

    fun getCropsDir(context: Context): File {
        val dir = File(context.filesDir, CROPS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveCroppedBitmap(context: Context, bitmap: Bitmap?): File? {
        if (bitmap == null) return null
        return try {
            val dir = getCropsDir(context)
            val fileName = "crop_${System.currentTimeMillis()}.png"
            val file = File(dir, fileName)
            val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.flush()
            stream.close()

            // Also insert into MediaStore so it is accessible in user's gallery
            try {
                android.provider.MediaStore.Images.Media.insertImage(
                    context.contentResolver,
                    file.absolutePath,
                    fileName,
                    "Cropped with Badger"
                )
            } catch (_: Exception) {}

            Toast.makeText(context, "Image saved to Badger", Toast.LENGTH_SHORT).show()
            file
        } catch (e: Exception) {
            Toast.makeText(context, "Could not save image", Toast.LENGTH_SHORT).show()
            null
        }
    }

    fun getSavedCrops(context: Context): List<File> {
        val dir = getCropsDir(context)
        return dir.listFiles { f -> f.extension.equals("png", ignoreCase = true) || f.extension.equals("jpg", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun deleteCrop(file: File): Boolean {
        return try {
            file.delete()
        } catch (_: Exception) {
            false
        }
    }

    fun shareCrop(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share Image").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share image", Toast.LENGTH_SHORT).show()
        }
    }
}
