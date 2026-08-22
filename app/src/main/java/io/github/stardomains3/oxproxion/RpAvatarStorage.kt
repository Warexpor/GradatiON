package io.github.stardomains3.oxproxion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

object RpAvatarStorage {
    private const val DIR = "rp_avatars"
    private const val MAX_EDGE = 512

    fun avatarFile(context: Context, characterId: Long): File {
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "char_$characterId.jpg")
    }

    fun saveFromUri(context: Context, source: Uri, characterId: Long): String? {
        return try {
            val bitmap = context.contentResolver.openInputStream(source)?.use { stream ->
                decodeSampled(stream.readBytes())
            } ?: return null
            writeJpeg(bitmap, characterId, context)
        } catch (_: Exception) {
            null
        }
    }

    fun saveFromBase64(context: Context, base64: String, characterId: Long): String? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bitmap = decodeSampled(bytes) ?: return null
            writeJpeg(bitmap, characterId, context)
        } catch (_: Exception) {
            null
        }
    }

    fun encodeAvatarBase64(context: Context, characterId: Long): String? {
        val file = avatarFile(context, characterId)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun deleteAvatar(context: Context, characterId: Long) {
        avatarFile(context, characterId).delete()
    }

    private fun writeJpeg(bitmap: Bitmap, characterId: Long, context: Context): String? {
        val file = avatarFile(context, characterId)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        return Uri.fromFile(file).toString()
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / sample > maxEdge || h / sample > maxEdge) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }
}
