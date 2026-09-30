package io.github.stardomains3.oxproxion

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Choosing an avatar photo: the phone's own gallery app, or Android's photo picker, then a
 * pan-and-zoom step to pick which part of it shows. [onPicked] gets a square JPEG, already cut.
 *
 * Create it as a property of the fragment, so its result launchers register before it starts.
 */
class AvatarPicker(private val fragment: Fragment, private val onPicked: (Uri) -> Unit) {

    private val photos = fragment.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(::crop) }
    private val gallery = fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK }?.let(::crop)
    }
    private var popover: PickerPopover? = null

    /** Asks where to pick from, in a small card that grows out of [anchor]. */
    fun launch(anchor: View) {
        val activity = fragment.activity ?: return
        val host = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (popover?.isOpenOn(anchor) == true) {
            popover?.dismiss()
            return
        }
        popover?.dismiss(animated = false)
        val rows = listOf(
            PickerPopover.Row(
                title = activity.getString(R.string.avatar_source_gallery),
                subtitle = activity.getString(R.string.avatar_source_gallery_sub),
                iconRes = R.drawable.ic_imgup,
                onClick = ::openGallery
            ),
            PickerPopover.Row(
                title = activity.getString(R.string.avatar_source_photos),
                subtitle = activity.getString(R.string.avatar_source_photos_sub),
                iconRes = R.drawable.ic_photo_library,
                onClick = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            ),
        )
        popover = PickerPopover(host, anchor, null).also { p ->
            p.onDismiss = { if (popover === p) popover = null }
            p.show(activity.getString(R.string.avatar_source_title), rows, lifecycleOwner = fragment.viewLifecycleOwner)
        }
    }

    /** The gallery app itself (Samsung Gallery, Google Photos...); a phone without one gets the picker. */
    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).setType("image/*")
        try {
            gallery.launch(intent)
        } catch (_: ActivityNotFoundException) {
            photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun crop(source: Uri) {
        val ctx = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(ctx, source) }
            if (bitmap == null) {
                // GlassNotice needs an Activity, so the fragment's context, not the application's.
                if (fragment.isAdded) GlassNotice.show(fragment.requireContext(), ctx.getString(R.string.rp_avatar_save_failed))
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
                    if (fragment.isAdded) GlassNotice.show(fragment.requireContext(), app.getString(R.string.rp_avatar_save_failed))
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
