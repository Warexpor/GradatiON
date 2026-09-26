package io.github.stardomains3.oxproxion

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compiles the liquid-glass lens shader and runs it through the real RenderEffect chain
 * (blur, then lens) on the hardware pipeline.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ScreenshotApp::class, sdk = [35])
class GlassLensShaderTest {

    @Test
    fun lensCompilesAndLightsTheRim() {
        val w = 160
        val h = 80
        val shader = RuntimeShader(GlassMaterial.LENS_AGSL)
        shader.setFloatUniform("size", w.toFloat(), h.toFloat())
        shader.setFloatUniform("pad", 0f)
        shader.setFloatUniform("radius", h / 2f)
        shader.setFloatUniform("band", 24f)
        shader.setFloatUniform("bend", 16f)
        shader.setFloatUniform("px", 2f)
        shader.setFloatUniform("tint", 0.14f, 0.14f, 0.14f, 0.4f)
        shader.setFloatUniform("spec", 0.2f)

        val glass = RenderNode("glass").apply {
            setPosition(0, 0, w, h)
            setRenderEffect(
                RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(shader, "content"),
                    RenderEffect.createBlurEffect(8f, 8f, Shader.TileMode.CLAMP)
                )
            )
            val c = beginRecording()
            c.drawColor(Color.rgb(30, 30, 30))
            c.drawRect(0f, 30f, w.toFloat(), 50f, Paint().apply { color = Color.rgb(200, 200, 200) })
            endRecording()
        }
        val root = RenderNode("root").apply {
            setPosition(0, 0, w, h)
            val c = beginRecording()
            c.drawRenderNode(glass)
            endRecording()
        }
        val reader = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val renderer = HardwareRenderer().apply {
            setContentRoot(root)
            setSurface(reader.surface)
        }
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val image = reader.acquireNextImage()
        val plane = image.planes[0]
        val bmp = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        image.close()
        renderer.destroy()

        val mid = Color.red(bmp.getPixel(w / 2, 8))
        val rim = Color.red(bmp.getPixel(w / 2, 1))
        assertTrue("rim $rim should be brighter than inside $mid", rim > mid + 4)
    }
}
