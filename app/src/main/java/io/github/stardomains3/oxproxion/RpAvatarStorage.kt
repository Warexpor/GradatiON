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

    /** Larger than this and the backup carries a scaled JPEG instead of the raw file. */
    internal const val EXPORT_MAX_BYTES = 1_000_000

    private const val MAX_READ = 32 * 1024 * 1024

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

    fun saveFromBase64(context: Context, base64: String, characterId: Long): String? =
        (saveFromBase64Result(context, base64, characterId) as? SaveResult.Saved)?.uri

    /**
     * [SaveResult.NotAPicture] is not retried: the phone keeps the portrait it has.
     * [SaveResult.Failed] means the new JPEG did not land, so the import tries again.
     */
    internal fun saveFromBase64Result(context: Context, base64: String, characterId: Long): SaveResult {
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return SaveResult.NotAPicture
        }
        return try {
            val bitmap = decodeSampled(bytes) ?: return SaveResult.NotAPicture
            val uri = writeJpeg(bitmap, characterId, context) ?: return SaveResult.Failed
            SaveResult.Saved(uri)
        } catch (_: Exception) {
            SaveResult.Failed
        }
    }

    internal sealed class SaveResult {
        data class Saved(val uri: String) : SaveResult()
        data object NotAPicture : SaveResult()
        data object Failed : SaveResult()
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

    /**
     * True when [characterId] has a finished portrait. Recovers a side file first; a torn
     * write at the real name is not a picture (same rule as wallpaper).
     */
    fun hasAvatar(context: Context, characterId: Long): Boolean {
        val file = avatarFile(context, characterId)
        ScenePhoto.recover(file)
        return ScenePhoto.completeJpeg(file)
    }

    /**
     * Portrait as Base64. Empty when there is no file, so a backup can clear one.
     * Null when the file is torn or cannot be read: the other phone keeps its copy
     * (same split as wallpaper encode). A file over [maxBytes] is scaled to the usual
     * portrait instead of being embedded whole, which used to push the backup past
     * what an import will read. If that portrait is still over the cap, it is shrunk
     * again until it fits.
     */
    fun encodeAvatarBase64(context: Context, characterId: Long): String? =
        encodeAvatarBase64(context, characterId, EXPORT_MAX_BYTES)

    internal fun encodeAvatarBase64(context: Context, characterId: Long, maxBytes: Int): String? {
        val file = avatarFile(context, characterId)
        ScenePhoto.recover(file)
        if (!file.isFile || file.length() == 0L) return ""
        // A half-written portrait is not a picture; leave the next phone's copy alone.
        if (!ScenePhoto.completeJpeg(file)) return null
        if (maxBytes <= 0 || file.length() > MAX_READ) return null
        return try {
            val raw = file.readBytes()
            val bytes = if (raw.size <= maxBytes) {
                raw
            } else {
                val bitmap = decodeSampled(raw) ?: return null
                val out = ByteArrayOutputStream()
                val ok = try {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                if (!ok) return null
                val jpeg = out.toByteArray()
                if (!ScenePhoto.completeJpeg(jpeg)) return null
                // The usual 512px portrait can still be over the cap. Shrink until it fits.
                // A torn file never reaches this; it was returned above.
                if (jpeg.size <= maxBytes) {
                    jpeg
                } else {
                    ScenePhoto.encode(raw, MAX_EDGE, maxBytes)?.takeIf {
                        it.size <= maxBytes && ScenePhoto.completeJpeg(it)
                    } ?: return null
                }
            }
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun deleteAvatar(context: Context, characterId: Long) {
        // Side files go first. A kill after the live name was removed used to leave
        // `.bak` or `.partial`, and the next open put that portrait back.
        ScenePhoto.deleteWithSides(avatarFile(context, characterId))
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

    /** True when [name] is a finished persona portrait (recovers a side file first). */
    fun hasPersonaPhoto(context: Context, name: String): Boolean {
        val file = personaFile(context, name)
        ScenePhoto.recover(file)
        return ScenePhoto.completeJpeg(file)
    }

    /** Deletes persona portraits nothing points at any more, including their side files. */
    fun prunePersonas(context: Context, keep: Set<String>) {
        val dir = File(context.filesDir, PERSONA_DIR)
        dir.listFiles()?.forEach { file ->
            val base = file.name.removeSuffix(".bak").removeSuffix(".partial")
            if (base !in keep) file.delete()
        }
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
