package io.github.stardomains3.oxproxion

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The user's own background picture (Settings > Appearance > Background > Photo): stored once,
 * downscaled, in app files; drawn by [AmbientBackgroundView] with the [Options] on top.
 * Grayscale unless "Keep color" is on, so it stays inside the neutral palette by default.
 */
object BackgroundPhoto {

    data class Options(
        val blur: Boolean = true,
        val dim: Boolean = true,
        val tint: Boolean = true,
        val liquid: Boolean = false,
        val color: Boolean = false,
    )

    const val KEY_BLUR = "bg_photo_blur"
    const val KEY_DIM = "bg_photo_dim"
    const val KEY_TINT = "bg_photo_tint"
    const val KEY_LIQUID = "bg_photo_liquid"
    const val KEY_COLOR = "bg_photo_color"
    /** Bumped on every new picture so views reload it. */
    const val KEY_VERSION = "bg_photo_version"
    val PREF_KEYS = setOf(KEY_BLUR, KEY_DIM, KEY_TINT, KEY_LIQUID, KEY_COLOR, KEY_VERSION)

    /** Long edge kept on disk; plenty for a blurred, dimmed backdrop. */
    private const val MAX_EDGE = 1600

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "bg-photo").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(SharedPreferencesHelper.MAIN_PREFS, Context.MODE_PRIVATE)

    fun readOptions(p: SharedPreferences) = Options(
        blur = p.getBoolean(KEY_BLUR, true),
        dim = p.getBoolean(KEY_DIM, true),
        tint = p.getBoolean(KEY_TINT, true),
        liquid = p.getBoolean(KEY_LIQUID, false),
        color = p.getBoolean(KEY_COLOR, false),
    )

    fun setOption(ctx: Context, key: String, on: Boolean) = prefs(ctx).edit { putBoolean(key, on) }

    fun version(ctx: Context): Long = prefs(ctx).getLong(KEY_VERSION, 0L)

    fun file(ctx: Context) = File(File(ctx.filesDir, "backgrounds"), "photo.jpg")

    fun hasPhoto(ctx: Context) = file(ctx).isFile

    /** Copy [uri] into app storage (downscaled JPEG), off the main thread; [done] on main. */
    fun import(ctx: Context, uri: Uri, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val ok = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
                val src = app.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: return@runCatching false
                val scale = MAX_EDGE / max(src.width, src.height).toFloat()
                val bmp = if (scale < 1f) {
                    Bitmap.createScaledBitmap(src, (src.width * scale).roundToInt(), (src.height * scale).roundToInt(), true)
                } else src
                val out = file(app)
                out.parentFile?.mkdirs()
                val tmp = File(out.parentFile, "photo.tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                tmp.renameTo(out)
            }.getOrDefault(false)
            main.post {
                if (ok) prefs(app).edit { putLong(KEY_VERSION, System.currentTimeMillis()) }
                done(ok)
            }
        }
    }

    /**
     * Decode, center-crop to [w]x[h], gray (unless color) and blur (if asked), off the main
     * thread; [done] runs on main with null when there is no picture.
     */
    fun loadAsync(ctx: Context, w: Int, h: Int, opts: Options, done: (Bitmap?) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val result = runCatching { process(app, w, h, opts) }.getOrNull()
            main.post { done(result) }
        }
    }

    private fun process(ctx: Context, w: Int, h: Int, opts: Options): Bitmap? {
        val f = file(ctx)
        if (!f.isFile || w <= 0 || h <= 0) return null
        val src = BitmapFactory.decodeFile(f.path) ?: return null
        // Center crop to the view's aspect.
        val target = w / h.toFloat()
        val srcAspect = src.width / src.height.toFloat()
        val crop = if (srcAspect > target) {
            val cw = (src.height * target).roundToInt()
            Rect((src.width - cw) / 2, 0, (src.width + cw) / 2, src.height)
        } else {
            val ch = (src.width / target).roundToInt()
            Rect(0, (src.height - ch) / 2, src.width, (src.height + ch) / 2)
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            if (!opts.color) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, crop, Rect(0, 0, w, h), paint)
        if (!opts.blur) return out
        // Blur at quarter size (it is drawn scaled up anyway): three box passes ≈ a gaussian.
        val small = Bitmap.createScaledBitmap(out, max(1, w / 4), max(1, h / 4), true)
        boxBlur(small, radius = max(2, small.width / 28), passes = 3)
        return small
    }

    /** In-place separable box blur on ARGB pixels; [passes] of it approximate a gaussian. */
    private fun boxBlur(bmp: Bitmap, radius: Int, passes: Int) {
        val w = bmp.width
        val h = bmp.height
        val src = IntArray(w * h)
        bmp.getPixels(src, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(passes) {
            blurLine(src, tmp, w, h, radius, horizontal = true)
            blurLine(tmp, src, w, h, radius, horizontal = false)
        }
        bmp.setPixels(src, 0, w, 0, 0, w, h)
    }

    private fun blurLine(input: IntArray, output: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return input[if (horizontal) line * w + c else c * w + line]
            }
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = at(i)
                sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (i in 0 until len) {
                output[if (horizontal) line * w + i else i * w + line] =
                    ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = at(i + r + 1)
                val sub = at(i - r)
                sa += (add ushr 24) - (sub ushr 24)
                sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }

    /** Brightness (0 dark .. 1 bright) of the phone's home wallpaper, or null if unknown. */
    fun systemWallpaperLuma(ctx: Context): Float? = runCatching {
        WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor?.luminance()
    }.getOrNull()
}
