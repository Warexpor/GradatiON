package io.github.stardomains3.oxproxion

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import androidx.appcompat.widget.SearchView

/**
 * The Prompt library and System messages search field: AppCompat draws a Material underline
 * under it and its own thin clear glyph, neither of which the rest of the glass UI uses.
 */
internal fun SearchView.styleLibrarySearch() {
    findViewById<View>(androidx.appcompat.R.id.search_plate)?.background = null
    findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)?.apply {
        setImageResource(R.drawable.ic_close_x)
        imageTintList = ColorStateList.valueOf(context.getColor(R.color.xai_mute))
    }
}
