package io.github.stardomains3.oxproxion.code

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
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
    private const val JPEG_QUALITY = 82

    private val allowedMime = setOf("image/jpeg", "image/png", "image/webp")

    /**
     * Read [uri], downscale/compress to JPEG (PNG kept only when already small), return a
     * [PromptAttachment] or null on failure / oversize.
     */
    fun fromUri(context: Context, uri: Uri): PromptAttachment? {
        val resolver = context.applicationContext.contentResolver
        val mime = resolver.getType(uri)?.lowercase()
        if (mime != null && mime !in allowedMime) return null
        val raw = try {
            resolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_SOURCE_BYTES) return null
                }
                buf.toByteArray()
            }
        } catch (_: Exception) {
            null
        } ?: return null
        if (raw.isEmpty()) return null

        // Small PNG/JPEG under the encoded cap: send as-is (preserve PNG when lossless-ish).
        if (raw.size <= MAX_ENCODED_BYTES && (mime == "image/png" || mime == "image/jpeg")) {
            return PromptAttachment(
                mimeType = mime,
                data = Base64.encodeToString(raw, Base64.NO_WRAP),
                previewUri = uri.toString(),
            )
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sample > MAX_EDGE_PX * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
        val scaled = scaleToMaxEdge(decoded, MAX_EDGE_PX)
        if (scaled !== decoded) decoded.recycle()
        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
            scaled.recycle()
            return null
        }
        scaled.recycle()
        var bytes = out.toByteArray()
        if (bytes.size > MAX_ENCODED_BYTES) {
            // Second pass at lower quality.
            val again = ByteArrayOutputStream()
            val bmp2 = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val ok = bmp2.compress(Bitmap.CompressFormat.JPEG, 60, again)
            bmp2.recycle()
            if (!ok) return null
            bytes = again.toByteArray()
            if (bytes.size > MAX_ENCODED_BYTES) return null
        }
        return PromptAttachment(
            mimeType = "image/jpeg",
            data = Base64.encodeToString(bytes, Base64.NO_WRAP),
            previewUri = uri.toString(),
        )
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
