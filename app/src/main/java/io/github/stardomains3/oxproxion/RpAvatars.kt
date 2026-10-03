package io.github.stardomains3.oxproxion

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.dispose
import coil.load

/**
 * Character portraits for the RP screens: the saved photo when there is one, otherwise a
 * monogram (first letter on the neutral gray fill the image view already carries).
 * Shape comes from the view (ShapeableImageView), so no bitmap transforms are needed.
 */
object RpAvatars {

    fun initial(name: String): String =
        name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString().orEmpty()

    /** Photo model for [character], or null when it has none. */
    fun photoModel(view: View, character: RpCharacter): Any? {
        // photoUri is the avatar file; a torn write must not skip the completeness check.
        if (!RpAvatarStorage.hasAvatar(view.context, character.id)) return null
        return character.photoUri?.takeIf { it.isNotBlank() }
            ?: RpAvatarStorage.avatarFile(view.context, character.id)
    }

    fun bind(image: ImageView, monogram: TextView, character: RpCharacter) {
        val model = photoModel(image, character)
        bindModel(image, monogram, model, character.name, cacheKey(image, character))
    }

    fun bindModel(image: ImageView, monogram: TextView, model: Any?, name: String, key: String? = null) {
        monogram.text = initial(name)
        if (model == null) {
            image.dispose()
            image.setImageDrawable(null)
            monogram.visibility = View.VISIBLE
            return
        }
        // Monogram stays up until the photo lands, so a slow or failed decode never shows a blank tile.
        monogram.visibility = View.VISIBLE
        image.setImageDrawable(null)
        image.load(model) {
            crossfade(true)
            if (key != null) {
                memoryCacheKey(key)
                diskCacheKey(key)
            }
            listener(
                onSuccess = { _, _ -> monogram.visibility = View.GONE },
                onError = { _, _ -> monogram.visibility = View.VISIBLE }
            )
        }
    }

    private fun cacheKey(view: View, character: RpCharacter): String {
        val file = RpAvatarStorage.avatarFile(view.context, character.id)
        val stamp = if (RpAvatarStorage.hasAvatar(view.context, character.id)) file.lastModified() else character.updatedAt
        return "rp-avatar-${character.id}-$stamp"
    }
}
