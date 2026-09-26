package io.github.stardomains3.oxproxion.code

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Encode gallery/camera images for Code [session/prompt] image content blocks.
 * Thin Code-local path (chat's PickVisualMedia + byte read pattern, with JPEG downscale).
 */
object CodePromptImages {

    const val MAX_COUNT = 4
    /** Reject source files larger than this before decode. */
    const val MAX_SOURCE_BYTES = 12_000_000
    /** Soft cap on encoded (pre-base64) JPEG bytes after compress. */
    const val MAX_ENCODED_BYTES = 1_500_000
    const val MAX_EDGE_PX = 1536
    /** Chip strip thumbnail long edge (≈56dp × 3). */
    const val THUMB_EDGE_PX = 168
    private const val JPEG_QUALITY = 82

    private val allowedMime = setOf("image/jpeg", "image/png", "image/webp")

    /** Outcome of [fromUri] so callers can toast too-large vs generic failure. */
    sealed class Result {
        data class Ok(val attachment: PromptAttachment) : Result()
        data object TooLarge : Result()
        data object Failed : Result()
    }

    /**
     * Read [uri], always bounds-check + downsample + EXIF-orient, compress to JPEG
     * (PNG/JPEG preserved only when already tiny and upright with no scale), return a
     * [Result] with a chip [PromptAttachment.previewBitmap] or TooLarge/Failed.
     */
    fun fromUri(context: Context, uri: Uri): Result {
        val resolver = context.applicationContext.contentResolver
        val mime = resolver.getType(uri)?.lowercase()
        if (mime != null && mime !in allowedMime) return Result.Failed
        val raw = try {
            resolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_SOURCE_BYTES) return Result.TooLarge
                }
                buf.toByteArray()
            }
        } catch (_: Exception) {
            null
        } ?: return Result.Failed
        if (raw.isEmpty()) return Result.Failed

        return try {
            encode(raw, mime, uri)
        } catch (_: Throwable) {
            // OutOfMemoryError and friends — do not abort the IO coroutine harshly.
            Result.Failed
        }
    }

    private fun encode(raw: ByteArray, mime: String?, uri: Uri): Result {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Result.Failed
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        // Tighten sample so the decoded long edge is ≤ MAX_EDGE_PX (not 2×).
        var sample = 1
        while (maxSide / sample > MAX_EDGE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return Result.Failed
        val oriented = applyExifOrientation(decoded, raw)
        if (oriented !== decoded) decoded.recycle()
        val scaled = scaleToMaxEdge(oriented, MAX_EDGE_PX)
        if (scaled !== oriented) oriented.recycle()

        val orientation = readOrientation(raw)
        val upright = orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        val unchangedPixels = scaled.width == bounds.outWidth && scaled.height == bounds.outHeight
        val preserveMime = upright &&
            unchangedPixels &&
            sample == 1 &&
            raw.size <= MAX_ENCODED_BYTES &&
            (mime == "image/png" || mime == "image/jpeg")

        val (outMime, outBytes) = if (preserveMime) {
            mime to raw
        } else {
            val compressed = compressJpeg(scaled) ?: run {
                scaled.recycle()
                return Result.TooLarge
            }
            "image/jpeg" to compressed
        }

        val thumb = scaleToMaxEdge(scaled, THUMB_EDGE_PX)
        // Keep [scaled] alive only if it is the thumb; otherwise recycle after thumb copy.
        if (thumb !== scaled) scaled.recycle()

        val att = PromptAttachment(
            mimeType = outMime,
            data = Base64.encodeToString(outBytes, Base64.NO_WRAP),
            previewUri = uri.toString(),
        )
        att.previewBitmap = thumb
        return Result.Ok(att)
    }

    private fun compressJpeg(bmp: Bitmap): ByteArray? {
        val out = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
        var bytes = out.toByteArray()
        if (bytes.size > MAX_ENCODED_BYTES) {
            val again = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 60, again)) return null
            bytes = again.toByteArray()
            if (bytes.size > MAX_ENCODED_BYTES) return null
        }
        return bytes
    }

    private fun readOrientation(raw: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(raw))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Rotate/flip [src] per EXIF orientation; returns [src] unchanged when upright. */
    private fun applyExifOrientation(src: Bitmap, raw: ByteArray): Bitmap {
        val orientation = readOrientation(raw)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setScale(1f, -1f)
            }
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
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            out
        } catch (_: Throwable) {
            src
        }
    }

    private fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        val edge = maxOf(w, h)
        if (edge <= maxEdge) return src
        val scale = maxEdge.toFloat() / edge
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }
}
