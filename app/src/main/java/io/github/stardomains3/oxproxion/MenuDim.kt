package io.github.stardomains3.oxproxion

import android.view.WindowManager
import android.widget.PopupWindow

/**
 * Dims the screen behind a floating menu that lives in its own window ([PopupWindow]). No blur:
 * the page stays sharp under menus (owner's call); the glass card itself carries the depth.
 */
object MenuDim {

    /** Call right after [PopupWindow.showAsDropDown]/showAtLocation. */
    fun behind(popup: PopupWindow) {
        val decor = popup.contentView?.rootView ?: return
        val lp = decor.layoutParams as? WindowManager.LayoutParams ?: return
        val wm = decor.context.getSystemService(WindowManager::class.java) ?: return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        lp.dimAmount = 0.3f
        runCatching { wm.updateViewLayout(decor, lp) }
    }
}
