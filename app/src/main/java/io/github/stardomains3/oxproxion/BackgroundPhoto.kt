package io.github.stardomains3.oxproxion

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

    /** A picker file bigger than this is refused instead of decoded whole. */
    private const val MAX_SOURCE = 32 * 1024 * 1024

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

    /*
     * [slot] picks whose picture: null is the app background, "char_<id>" a roleplay
     * character's own wallpaper (shown while that character is active).
     */
    fun slotForCharacter(id: Long) = "char_$id"

    private fun versionKey(slot: String?) = if (slot == null) KEY_VERSION else "${KEY_VERSION}_$slot"

    fun version(ctx: Context, slot: String? = null): Long = prefs(ctx).getLong(versionKey(slot), 0L)

    fun file(ctx: Context, slot: String? = null) =
        File(File(ctx.filesDir, "backgrounds"), if (slot == null) "photo.jpg" else "$slot.jpg")

    fun hasPhoto(ctx: Context, slot: String? = null): Boolean {
        val f = file(ctx, slot)
        ScenePhoto.recover(f)
        // A torn write left a file at the name; that is not a wallpaper we can draw.
        return ScenePhoto.completeJpeg(f)
    }

    fun delete(ctx: Context, slot: String) {
        val f = file(ctx, slot)
        f.delete()
        File(f.parentFile, "${f.name}.bak").delete()
        File(f.parentFile, "${f.name}.partial").delete()
        prefs(ctx).edit { putLong(versionKey(slot), System.currentTimeMillis()) }
    }

    /** Replace the slot with [jpeg] and bump the version so an open chat redraws it. */
    fun writeBytes(ctx: Context, slot: String?, jpeg: ByteArray): Boolean {
        // A short file must not replace the picture already there. Deleting first, then
        // renaming, used to leave the slot empty when the process died in between.
        if (!ScenePhoto.completeJpeg(jpeg)) return false
        val out = file(ctx, slot)
        return try {
            out.parentFile?.mkdirs()
            ScenePhoto.writeAtomically(out, jpeg)
            prefs(ctx).edit { putLong(versionKey(slot), System.currentTimeMillis()) }
            out.isFile && ScenePhoto.completeJpeg(out)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Copy [uri] into app storage (downscaled, upright JPEG), off the main thread; [done] on main.
     * The picker is read once. A second open used to fail on a link that only allows one read,
     * and replacing a picture could miss the file that was already there.
     */
    fun import(ctx: Context, uri: Uri, slot: String? = null, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val ok = runCatching {
                val raw = readBounded(app, uri) ?: return@runCatching false
                val jpeg = prepare(raw) ?: return@runCatching false
                writeBytes(app, slot, jpeg)
            }.getOrDefault(false)
            main.post { done(ok) }
        }
    }

    /**
     * An upright JPEG whose long edge is at most [MAX_EDGE], or null when [raw] is not a picture.
     * Phone photos are stored sideways with an EXIF flag, and re-compressing drops the flag.
     */
    internal fun prepare(raw: ByteArray): ByteArray? {
        if (raw.isEmpty() || raw.size > MAX_SOURCE) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (sample < 64 && max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) {
                sample *= 2
            }
            val decoded = BitmapFactory.decodeByteArray(
                raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val exif = runCatching {
                ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val src = upright(decoded, exif)
            val scale = MAX_EDGE / max(src.width, src.height).toFloat()
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    src,
                    (src.width * scale).roundToInt().coerceAtLeast(1),
                    (src.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
            } else {
                src
            }
            if (bmp !== src) src.recycle()
            val out = ByteArrayOutputStream()
            val ok = bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bmp.recycle()
            if (!ok) null else out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readBounded(ctx: Context, uri: Uri): ByteArray? = try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_SOURCE) return null
                out.write(buf, 0, n)
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    }

    /** [src] turned so it reads upright for an EXIF [orientation] (the same bitmap when it already does). */
    internal fun upright(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        val turned = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (turned !== src) src.recycle()
        return turned
    }

    /**
     * Decode, center-crop to [w]x[h], gray (unless color) and blur (if asked), off the main
     * thread; [done] runs on main with null when there is no picture.
     */
    fun loadAsync(ctx: Context, w: Int, h: Int, opts: Options, slot: String? = null, done: (Bitmap?) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val result = runCatching { process(app, w, h, opts, slot) }.getOrNull()
            main.post { done(result) }
        }
    }

    private fun process(ctx: Context, w: Int, h: Int, opts: Options, slot: String?): Bitmap? {
        val f = file(ctx, slot)
        ScenePhoto.recover(f)
        if (!f.isFile || w <= 0 || h <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val need = max(w, h)
        while (sample < 32 && max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= need) sample *= 2
        var src = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        // A file that still carries a sideways flag (a backup written before it was turned)
        // has to be upright here. upright recycles the bitmap it replaces.
        val orientation = runCatching {
            ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        src = upright(src, orientation)
        val drawn = try {
            // Center crop to the view's aspect. The rect stays inside the bitmap: a rounding
            // step past the edge used to fail the draw and drop the picture.
            val target = w / h.toFloat()
            val srcAspect = src.width / src.height.toFloat()
            val crop = if (srcAspect > target) {
                val cw = (src.height * target).roundToInt().coerceIn(1, src.width)
                val left = ((src.width - cw) / 2).coerceAtLeast(0)
                Rect(left, 0, left + cw, src.height)
            } else {
                val ch = (src.width / target).roundToInt().coerceIn(1, src.height)
                val top = ((src.height - ch) / 2).coerceAtLeast(0)
                Rect(0, top, src.width, top + ch)
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                if (!opts.color) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(src, crop, Rect(0, 0, w, h), paint)
            if (!opts.blur) {
                out
            } else {
                // Blur at a third of the size (it is drawn scaled up anyway): three box passes ≈ a
                // gaussian of ~1.5% of the width, a frost the photo still reads through. The old
                // quarter-size, width/28 pass (~3.5%) smeared it into a wash.
                val small = Bitmap.createScaledBitmap(out, max(1, w / 3), max(1, h / 3), true)
                if (small !== out) out.recycle()
                boxBlur(small, radius = max(1, small.width / 72), passes = 3)
                small
            }
        } finally {
            if (!src.isRecycled) src.recycle()
        }
        return drawn
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
