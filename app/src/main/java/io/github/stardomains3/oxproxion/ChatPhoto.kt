package io.github.stardomains3.oxproxion

import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How a chat photo is framed. The composer chip and the sent bubble both keep the
 * picture's shape inside a cap, instead of cropping everything to a square.
 */
object ChatPhoto {

    /** Padding in dp. */
    data class DpBox(val start: Int, val top: Int, val end: Int, val bottom: Int)

    /**
     * Fit [srcW]×[srcH] inside [maxW]×[maxH] without upscaling.
     * [minEdge] lifts a sliver up to a tappable chip (the composer). The lift stays
     * inside the cap, so a panorama's short side grows and the picture is cropped there.
     * Unknown bounds become a square of the shorter cap.
     */
    fun frame(srcW: Int, srcH: Int, maxW: Int, maxH: Int, minEdge: Int = 0): Pair<Int, Int> {
        if (maxW <= 0 || maxH <= 0) return 1 to 1
        if (srcW <= 0 || srcH <= 0) {
            val edge = min(maxW, maxH)
            return edge to edge
        }
        val scale = minOf(maxW.toFloat() / srcW, maxH.toFloat() / srcH, 1f)
        var w = (srcW * scale).roundToInt().coerceAtLeast(1)
        var h = (srcH * scale).roundToInt().coerceAtLeast(1)
        if (minEdge > 0 && min(w, h) < minEdge) {
            val bump = minEdge.toFloat() / min(w, h)
            w = (w * bump).roundToInt().coerceIn(1, maxW)
            h = (h * bump).roundToInt().coerceIn(1, maxH)
        }
        return w to h
    }

    /**
     * Pixel size of [bytes] after the EXIF rotation a viewer will apply.
     * Phone cameras store a landscape buffer with a quarter-turn flag; measuring the
     * buffer alone frames a portrait shot as a landscape chip.
     */
    fun orientedBounds(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var w = opts.outWidth
        var h = opts.outHeight
        if (w <= 0 || h <= 0) return 0 to 0
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
        ) {
            val swap = w
            w = h
            h = swap
        }
        return w to h
    }

    /** The bubble's own padding. A photo sits in a 4dp rim; a caption keeps the 16dp text inset. */
    fun containerInsets(hasPhoto: Boolean, photoOnly: Boolean): DpBox = when {
        photoOnly -> DpBox(4, 4, 4, 4)
        hasPhoto -> DpBox(4, 4, 4, 12)
        else -> DpBox(16, 12, 16, 12)
    }

    /** Extra padding on the caption so it lines up with a text-only bubble (4dp rim + 12dp = 16dp). */
    fun captionInsets(hasPhoto: Boolean, photoOnly: Boolean): DpBox = when {
        hasPhoto && !photoOnly -> DpBox(12, 0, 12, 0)
        else -> DpBox(0, 0, 0, 0)
    }
}
