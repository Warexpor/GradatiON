package io.github.stardomains3.oxproxion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import androidx.annotation.RequiresApi
import android.os.Build

/**
 * Draws something through the GPU into a bitmap, off the view's own draw pass. A software
 * canvas can't run RenderEffects or AGSL, so glass edges, the liquid mark and the ambient
 * fields vanish from a plain `drawToBitmap`; this keeps them.
 *
 * Never call it from inside onDraw: nested GPU work mid-draw crashes the process.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal object HardwareRaster {

    /**
     * Renders [draw] at [w] x [h] with a transparent start, so whatever it leaves undrawn stays
     * see-through. [software] copies the result to a regular bitmap (for software canvases);
     * otherwise the GPU buffer is returned as a hardware bitmap, with no readback.
     */
    fun render(w: Int, h: Int, software: Boolean, draw: (Canvas) -> Unit): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val node = RenderNode("raster").apply { setPosition(0, 0, w, h) }
        draw(node.beginRecording())
        node.endRecording()
        // CPU_READ is only for the plane fallback below. GPU_SAMPLED lets the buffer be wrapped
        // as a bitmap.
        val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
            HardwareBuffer.USAGE_CPU_READ_OFTEN
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1, usage)
        val renderer = HardwareRenderer()
        // An opaque renderer never clears its buffer, so undrawn pixels come out black.
        renderer.isOpaque = false
        renderer.setContentRoot(node)
        renderer.setSurface(reader.surface)
        try {
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage() ?: return null
            try {
                return bitmapFrom(image, w, h, software)
            } finally {
                image.close()
            }
        } finally {
            renderer.destroy()
            reader.close()
            node.discardDisplayList()
        }
    }

    /**
     * Prefer wrapping the GPU buffer. [android.media.Image.getPlanes] calls NewDirectByteBuffer
     * on the locked address and aborts the process (not throws) when a driver returns a buffer
     * it cannot map, as some Samsung GPUs do. Planes are only for hosts with no hardware buffer
     * (unit tests).
     */
    private fun bitmapFrom(image: android.media.Image, w: Int, h: Int, software: Boolean): Bitmap? {
        val buffer = runCatching { image.hardwareBuffer }.getOrNull()
        if (buffer != null) {
            try {
                val hw = Bitmap.wrapHardwareBuffer(buffer, null) ?: return null
                if (!software) return crop(hw, w, h)
                try {
                    val soft = hw.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                    return crop(soft, w, h)
                } finally {
                    hw.recycle()
                }
            } finally {
                buffer.close()
            }
        }
        val plane = image.planes[0]
        val full = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer)
        return crop(full, w, h)
    }

    private fun crop(full: Bitmap, w: Int, h: Int): Bitmap {
        if (full.width == w && full.height == h) return full
        return Bitmap.createBitmap(full, 0, 0, w.coerceAtMost(full.width), h.coerceAtMost(full.height)).also {
            if (it !== full) full.recycle()
        }
    }
}
