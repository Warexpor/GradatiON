package io.github.stardomains3.oxproxion

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Choosing an avatar photo: where it comes from (the photo picker, a gallery or other app, or
 * the file browser, since phones differ in which of these actually shows their gallery), then a
 * pan-and-zoom step to pick which part of it shows. [onPicked] gets a square JPEG, already cut.
 *
 * Create it as a property of the fragment, so its result launchers register before it starts.
 */
class AvatarPicker(private val fragment: Fragment, private val onPicked: (Uri) -> Unit) {

    private val photos = fragment.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(::crop) }
    private val gallery = fragment.registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let(::crop) }
    private val files = fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::crop) }

    /** Asks where to pick from. */
    fun launch() {
        val ctx = fragment.requireContext()
        val dialog = BottomSheetDialog(ctx, R.style.ThemeOverlay_Grokion_BottomSheet_Sharp)
        val sheet = LayoutInflater.from(ctx).inflate(R.layout.sheet_avatar_source, null)
        // The same opaque sheet and tonal rows as the character panel; the bare sheet theme is see-through.
        sheet.background = RpCharacterPanel.solidSheet(ctx)
        val d = ctx.resources.displayMetrics.density
        val radius = 22 * d
        listOf(R.id.avatarSourcePhotos, R.id.avatarSourceGallery, R.id.avatarSourceFiles).forEach { id ->
            sheet.findViewById<View>(id).apply {
                background = RpCharacterPanel.solidShape(ctx, R.color.panel_tile, radiusPx = radius)
                foreground = RpCharacterPanel.pressRipple(ctx, radius)
            }
        }
        val base = sheet.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { v, insets ->
            v.updatePadding(bottom = base + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }
        fun row(id: Int, action: () -> Unit) = sheet.findViewById<MaterialButton>(id).setOnClickListener {
            dialog.dismiss()
            action()
        }
        row(R.id.avatarSourcePhotos) {
            if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(ctx)) {
                photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } else {
                gallery.launch("image/*")
            }
        }
        row(R.id.avatarSourceGallery) { gallery.launch("image/*") }
        row(R.id.avatarSourceFiles) { files.launch(arrayOf("image/*")) }
        dialog.setContentView(sheet)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { container ->
            container.background = null
            container.backgroundTintList = null
        }
    }

    private fun crop(source: Uri) {
        val ctx = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(ctx, source) }
            if (bitmap == null) {
                AppToast.makeText(ctx, ctx.getString(R.string.rp_avatar_save_failed), AppToast.LENGTH_SHORT).show()
                return@launch
            }
            if (!fragment.isAdded) {
                bitmap.recycle()
                return@launch
            }
            showCropper(bitmap)
        }
    }

    private fun showCropper(bitmap: Bitmap) {
        val ctx = fragment.requireContext()
        val dialog = Dialog(ctx, R.style.Theme_Grokion)
        val root = LayoutInflater.from(ctx).inflate(R.layout.dialog_avatar_crop, null)
        val view = root.findViewById<AvatarCropView>(R.id.avatarCropView)
        view.setImage(bitmap)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        root.findViewById<View>(R.id.avatarCropCancel).setOnClickListener { dialog.dismiss() }
        root.findViewById<View>(R.id.avatarCropDone).setOnClickListener { button ->
            button.isEnabled = false
            val cut = view.crop(OUT_SIZE)
            val app = ctx.applicationContext
            fragment.lifecycleScope.launch {
                val uri = withContext(Dispatchers.IO) {
                    val out = cut?.let { write(app, it) }
                    cut?.let { if (!it.isRecycled) it.recycle() }
                    out
                }
                dialog.dismiss()
                if (uri == null) {
                    AppToast.makeText(app, app.getString(R.string.rp_avatar_save_failed), AppToast.LENGTH_SHORT).show()
                } else {
                    onPicked(uri)
                }
            }
        }
        dialog.setOnDismissListener {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(R.color.xai_grouped)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this, false)
        }
        dialog.show()
    }

    private companion object {
        const val OUT_SIZE = 512
        const val DECODE_EDGE = 2048

        /** Decoded with the phone's own decoder when possible; EXIF upright in both paths. */
        fun decode(ctx: Context, uri: Uri): Bitmap? {
            val fromDecoder = decodeWithImageDecoder(ctx, uri)
            if (fromDecoder != null) return fromDecoder
            return decodeWithBitmapFactory(ctx, uri)
        }

        private fun decodeWithImageDecoder(ctx: Context, uri: Uri): Bitmap? = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > DECODE_EDGE) {
                    var sample = 1
                    while (longest / sample > DECODE_EDGE) sample *= 2
                    decoder.setTargetSampleSize(sample)
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } catch (_: Exception) {
            null
        }

        private fun decodeWithBitmapFactory(ctx: Context, uri: Uri): Bitmap? = try {
            val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longest = max(bounds.outWidth, bounds.outHeight)
            while (longest / sample > DECODE_EDGE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
            val oriented = applyExifOrientation(decoded, raw)
            if (oriented !== decoded) decoded.recycle()
            oriented
        } catch (_: Exception) {
            null
        }

        private fun readOrientation(raw: ByteArray): Int = try {
            ExifInterface(ByteArrayInputStream(raw))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        private fun applyExifOrientation(src: Bitmap, raw: ByteArray): Bitmap {
            val orientation = readOrientation(raw)
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

        /** The cut photo, in the cache; the screens' own save turns it into the stored avatar. */
        fun write(ctx: Context, bitmap: Bitmap): Uri? = try {
            val stale = ctx.cacheDir.listFiles { f -> f.name.startsWith("avatar_crop_") }
            val file = File(ctx.cacheDir, "avatar_crop_${System.nanoTime()}.jpg")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            stale?.forEach { it.delete() }
            Uri.fromFile(file)
        } catch (_: Exception) {
            null
        }
    }
}
