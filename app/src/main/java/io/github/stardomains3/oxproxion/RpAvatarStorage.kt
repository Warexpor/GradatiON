package io.github.stardomains3.oxproxion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

object RpAvatarStorage {
    private const val DIR = "rp_avatars"
    private const val PERSONA_DIR = "rp_persona_avatars"
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

    /** Decode a packaged drawable/raw resource and write the usual JPEG avatar. */
    fun saveFromResource(context: Context, resId: Int, characterId: Long): String? {
        return try {
            val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
            val bitmap = decodeSampled(bytes) ?: return null
            writeJpeg(bitmap, characterId, context)
        } catch (_: Exception) {
            null
        }
    }

    fun encodeAvatarBase64(context: Context, characterId: Long): String? {
        val file = avatarFile(context, characterId)
        ScenePhoto.recover(file)
        // A half-written portrait is not a picture; leave the next phone's copy alone
        // (wallpaper encode already does this).
        if (!file.isFile || file.length() == 0L) return null
        if (!ScenePhoto.completeJpeg(file)) return null
        return try {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun deleteAvatar(context: Context, characterId: Long) {
        avatarFile(context, characterId).delete()
    }

    /** A persona portrait by file name. Names are never reused, so saved personas can share one safely. */
    fun personaFile(context: Context, name: String): File = File(File(context.filesDir, PERSONA_DIR), name)

    /** Writes a picked image as a new persona portrait and returns its file name. */
    fun savePersonaFromUri(context: Context, source: Uri): String? {
        return try {
            val bitmap = context.contentResolver.openInputStream(source)?.use { decodeSampled(it.readBytes()) }
                ?: return null
            val dir = File(context.filesDir, PERSONA_DIR).apply { mkdirs() }
            val name = "persona_${System.currentTimeMillis()}.jpg"
            val file = File(dir, name)
            if (!writeJpegBytes(bitmap, file)) return null
            name
        } catch (_: Exception) {
            null
        }
    }

    /** Deletes persona portraits nothing points at any more. */
    fun prunePersonas(context: Context, keep: Set<String>) {
        File(context.filesDir, PERSONA_DIR).listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    private fun writeJpeg(bitmap: Bitmap, characterId: Long, context: Context): String? {
        val file = avatarFile(context, characterId)
        if (!writeJpegBytes(bitmap, file)) return null
        return Uri.fromFile(file).toString()
    }

    /**
     * Compress, then replace [file] only when the JPEG is finished. A kill mid-write used to
     * leave a short file at the real name, and the next save treated that as the portrait.
     */
    private fun writeJpegBytes(bitmap: Bitmap, file: File): Boolean {
        val bytes = ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) return false
            out.toByteArray()
        }
        if (!ScenePhoto.completeJpeg(bytes)) return false
        return try {
            ScenePhoto.writeAtomically(file, bytes)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        // A camera JPEG is stored sideways. Re-encoding drops the flag, so turn it now.
        val upright = ScenePhoto.upright(decoded, bytes)
        if (upright !== decoded) decoded.recycle()
        return upright
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
