package io.github.stardomains3.oxproxion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * A photo staged for a chat or a scene: upright, and small enough to send and to keep in the
 * transcript. The gallery's own file is often sideways (the rotation lives in EXIF, which the
 * model ignores) and far bigger than a reply needs.
 */
object ScenePhoto {
    const val MAX_EDGE = 1536
    const val MIME = "image/jpeg"
    private const val MAX_ENCODED = 1_500_000
    private const val QUALITY = 82

    /**
     * A photo already in a message, so Edit can put it back in the composer.
     * [fileUri] is a file we own. A data URL in that field is not one.
     */
    data class EditPhoto(val dataUrl: String?, val fileUri: String?)

    /** Bytes to stage, the mime to send, and the file to show when we still have it. */
    class Staged(val bytes: ByteArray, val mime: String, val fileUri: String?)

    fun editPhoto(contentImageUrl: String?, imageUri: String?): EditPhoto? {
        val data = contentImageUrl?.takeIf { it.startsWith("data:image/", ignoreCase = true) }
        val file = imageUri?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        if (data == null && file == null) return null
        return EditPhoto(data, file)
    }

    /** The mime of a data URL, or null when it is not an image. */
    fun mimeFromDataUrl(url: String): String? {
        if (!url.startsWith("data:image/", ignoreCase = true)) return null
        val rest = url.substring(5)
        val end = rest.indexOfAny(charArrayOf(';', ','))
        if (end <= "image/".length) return null
        val mime = rest.substring(0, end).lowercase()
        return mime.takeIf { it.startsWith("image/") && it.length < 40 }
    }

    /** Decoded bytes of a data URL, or null when it is not an image or the payload is bad. */
    fun bytesFromDataUrl(url: String): ByteArray? {
        if (mimeFromDataUrl(url) == null) return null
        val comma = url.indexOf(',')
        if (comma < 0 || comma == url.lastIndex) return null
        val payload = url.substring(comma + 1).trim()
        if (payload.isEmpty()) return null
        return try {
            android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun stagedFromDataUrl(url: String, maxBytes: Int = 12_000_000): Staged? {
        val bytes = bytesFromDataUrl(url) ?: return null
        if (bytes.isEmpty() || bytes.size > maxBytes) return null
        return Staged(bytes, mimeFromDataUrl(url) ?: MIME, null)
    }

    /** JPEG bytes, or null when [raw] is not a picture this device can decode. */
    fun encode(raw: ByteArray): ByteArray? {
        if (raw.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (maxSide / sample > MAX_EDGE) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(
                raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val oriented = applyExif(decoded, raw)
            if (oriented !== decoded) decoded.recycle()
            val scaled = scale(oriented, MAX_EDGE)
            if (scaled !== oriented) oriented.recycle()
            val jpeg = compress(scaled)
            scaled.recycle()
            jpeg
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * A copy we own, so the bubble can still open the picture after the picker link dies.
     * The message also carries the JPEG, which is what shows if this file is gone.
     */
    fun store(context: Context, jpeg: ByteArray): Uri? = try {
        val dir = File(context.cacheDir, "scene_photos").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.writeBytes(jpeg)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (_: Exception) {
        null
    }

    private fun compress(bmp: Bitmap): ByteArray? {
        val out = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) return null
        var bytes = out.toByteArray()
        if (bytes.size > MAX_ENCODED) {
            val again = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 60, again)) return null
            bytes = again.toByteArray()
            if (bytes.size > MAX_ENCODED) return null
        }
        return bytes
    }

    private fun scale(src: Bitmap, maxEdge: Int): Bitmap {
        val edge = maxOf(src.width, src.height)
        if (edge <= maxEdge) return src
        val scale = maxEdge.toFloat() / edge
        val nw = (src.width * scale).toInt().coerceAtLeast(1)
        val nh = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun applyExif(src: Bitmap, raw: ByteArray): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(raw))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_UNDEFINED
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return src
        }
        return try {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        } catch (_: Throwable) {
            src
        }
    }
}
