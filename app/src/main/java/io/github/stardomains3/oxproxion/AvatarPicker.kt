package io.github.stardomains3.oxproxion

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.ImageDecoder
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
        val dialog = BottomSheetDialog(ctx, R.style.ThemeOverlay_Grokion_BottomSheet)
        val sheet = LayoutInflater.from(ctx).inflate(R.layout.sheet_avatar_source, null)
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
    }

    private fun crop(source: Uri) {
        val ctx = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(ctx, source) }
            if (bitmap == null) {
                AppToast.makeText(ctx, ctx.getString(R.string.rp_avatar_save_failed), AppToast.LENGTH_SHORT).show()
                return@launch
            }
            if (!fragment.isAdded) return@launch
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
                val uri = cut?.let { withContext(Dispatchers.IO) { write(app, it) } }
                dialog.dismiss()
                if (uri == null) {
                    AppToast.makeText(app, app.getString(R.string.rp_avatar_save_failed), AppToast.LENGTH_SHORT).show()
                } else {
                    onPicked(uri)
                }
            }
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

        /** Decoded with the phone's own decoder, which also applies the photo's rotation. */
        fun decode(ctx: android.content.Context, uri: Uri): Bitmap? = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > DECODE_EDGE) {
                    decoder.setTargetSampleSize(Integer.highestOneBit(longest / DECODE_EDGE).coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        } catch (_: Exception) {
            null
        }

        /** The cut photo, in the cache; the screens' own save turns it into the stored avatar. */
        fun write(ctx: android.content.Context, bitmap: Bitmap): Uri? = try {
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
