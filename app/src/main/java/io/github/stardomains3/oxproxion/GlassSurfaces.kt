package io.github.stardomains3.oxproxion

import android.animation.AnimatorInflater
import android.app.Dialog
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.xmlpull.v1.XmlPullParser
import kotlin.math.max
import kotlin.math.min

/**
 * Static liquid-glass surface for places that float in their own window or have nothing live
 * behind them: dialogs and sheets (whose window already frosts the screen behind it), popups,
 * menus and toolbar buttons. Translucent tint, a soft top sheen for thickness, a specular rim
 * lit from the top-left with a weaker bounce opposite, and a hairline edge. Interactive glass
 * lights up while pressed.
 *
 * Inflatable from XML: `<drawable class="io.github.stardomains3.oxproxion.GlassDrawable"
 * app:glassTint=".." app:glassCornerRadius=".." />` (negative radius = capsule).
 */
class GlassDrawable() : Drawable() {

    var glassTint: Int = Color.argb(0xE6, 0x1C, 0x1C, 0x1C)
    /** Negative = capsule. */
    var cornerRadius: Float = -1f
    /** Round only the top corners (bottom sheets). */
    var topOnly: Boolean = false
    /** If > 0, draw a centered capsule/circle of this height instead of filling the bounds. */
    var diameter: Float = 0f
    var interactive: Boolean = false
    /** Solid (no see-through) when live blur is unavailable. */
    var opaqueWithoutBlur: Boolean = true
    /**
     * Tint while selected/checked ("on" tiles, active controls). Non-null makes the drawable
     * stateful; the change crossfades and the rim lights up, so "on" reads without inverting
     * into a solid slab.
     */
    var selectedTint: Int? = null

    private var density = 1f
    private var highlight = Color.argb(0x33, 255, 255, 255)
    private var edge = Color.argb(0x26, 255, 255, 255)
    private var glow = Color.argb(0x33, 255, 255, 255)
    private var sheen = Color.argb(0x0D, 255, 255, 255)
    private var pressed = false
    private var selected = false
    private var enabled = true
    /** 0 = normal tint, 1 = [selectedTint]; animated between. */
    private var selection = 0f
    private var selectionAnimator: android.animation.ValueAnimator? = null
    private var alphaMul = 255

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rect = RectF()
    private var shapeKey = 0L

    constructor(context: Context, tint: Int, cornerRadiusPx: Float) : this() {
        load(context.resources)
        this.glassTint = tint
        this.cornerRadius = cornerRadiusPx
    }

    private fun load(res: Resources, theme: Resources.Theme? = null) {
        density = res.displayMetrics.density
        highlight = res.getColor(R.color.glass_highlight, theme)
        edge = res.getColor(R.color.glass_edge, theme)
        glow = res.getColor(R.color.glass_glow, theme)
        sheen = res.getColor(R.color.glass_sheen, theme)
        glassTint = res.getColor(R.color.glass_sheet_tint, theme)
        rimPaint.strokeWidth = max(1f, density)
        edgePaint.strokeWidth = max(1f, density * 0.75f)
    }

    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(r, parser, attrs, theme)
        load(r, theme)
        val a = theme?.obtainStyledAttributes(attrs, R.styleable.GlassDrawable, 0, 0)
            ?: r.obtainAttributes(attrs, R.styleable.GlassDrawable)
        try {
            glassTint = a.getColor(R.styleable.GlassDrawable_glassTint, glassTint)
            cornerRadius = a.getDimension(R.styleable.GlassDrawable_glassCornerRadius, cornerRadius)
            topOnly = a.getBoolean(R.styleable.GlassDrawable_glassTopOnly, false)
            interactive = a.getBoolean(R.styleable.GlassDrawable_glassInteractive, false)
            diameter = a.getDimension(R.styleable.GlassDrawable_glassDiameter, 0f)
            if (a.hasValue(R.styleable.GlassDrawable_glassSelectedTint)) {
                selectedTint = a.getColor(R.styleable.GlassDrawable_glassSelectedTint, glassTint)
            }
            opaqueWithoutBlur = a.getBoolean(R.styleable.GlassDrawable_glassOpaqueWithoutBlur, true)
        } finally {
            a.recycle()
        }
    }

    private fun shapeRect(out: RectF) {
        val b = bounds
        if (diameter > 0f) {
            val d = min(diameter, min(b.width(), b.height()).toFloat())
            out.set(b.exactCenterX() - d / 2f, b.exactCenterY() - d / 2f, b.exactCenterX() + d / 2f, b.exactCenterY() + d / 2f)
        } else {
            out.set(b)
        }
    }

    private fun radiusFor(r: RectF): Float {
        val half = min(r.width(), r.height()) / 2f
        return if (cornerRadius < 0f) half else min(cornerRadius, half)
    }

    private fun rebuild() {
        shapeRect(rect)
        val key = (rect.width().toLong() shl 32) or rect.height().toLong()
        if (key == shapeKey && !path.isEmpty) return
        shapeKey = key
        val r = radiusFor(rect)
        path.reset()
        if (topOnly) {
            path.addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
        } else {
            path.addRoundRect(rect, r, r, Path.Direction.CW)
        }
        rimPaint.shader = LinearGradient(
            rect.left, rect.top, rect.left + rect.width() * 0.55f, rect.bottom,
            intArrayOf(highlight, Color.TRANSPARENT, Color.TRANSPARENT, scaleAlpha(highlight, 0.45f)),
            floatArrayOf(0f, 0.42f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        val sheenH = min(rect.height() * 0.45f, 64f * density)
        sheenPaint.shader = LinearGradient(
            0f, rect.top, 0f, rect.top + sheenH,
            sheen, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        shapeKey = 0L
        path.reset()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        rebuild()
        val solid = opaqueWithoutBlur && GlassQuality.level == GlassQuality.Level.SOLID
        val sel = selectedTint
        val tint = if (sel != null && selection > 0f) blend(glassTint, sel, selection) else glassTint
        // Disabled controls fade back into the surface instead of greying out a slab.
        val mul = if (enabled) alphaMul else alphaMul * 45 / 100
        fill.color = if (solid) Color.argb(max(Color.alpha(tint), 0xF5), Color.red(tint), Color.green(tint), Color.blue(tint)) else tint
        fill.alpha = fill.alpha * mul / 255
        canvas.drawPath(path, fill)
        sheenPaint.alpha = mul
        canvas.drawPath(path, sheenPaint)
        if (pressed) {
            fill.color = glow
            fill.alpha = fill.alpha * mul / 255
            canvas.drawPath(path, fill)
        }
        canvas.save()
        canvas.clipPath(path)
        rimPaint.alpha = mul
        canvas.drawPath(path, rimPaint)
        if (selection > 0f) {
            // "On": the rim catches more light all the way round (a soft inner glow, no hue).
            rimPaint.alpha = (mul * selection).toInt()
            canvas.drawPath(path, rimPaint)
            edgePaint.color = glow
            edgePaint.alpha = (Color.alpha(glow) * selection * mul / 255).toInt()
            val w = edgePaint.strokeWidth
            edgePaint.strokeWidth = w * 2.5f
            canvas.drawPath(path, edgePaint)
            edgePaint.strokeWidth = w
        }
        edgePaint.color = edge
        edgePaint.alpha = edgePaint.alpha * mul / 255
        canvas.drawPath(path, edgePaint)
        canvas.restore()
    }

    override fun isStateful() = interactive || selectedTint != null

    override fun onStateChange(state: IntArray): Boolean {
        if (!isStateful) return false
        var changed = false
        val p = interactive && state.contains(android.R.attr.state_pressed)
        if (p != pressed) {
            pressed = p
            changed = true
            val host = callback as? View
            if (p && host?.stateListAnimator != null) {
                PressRoom.open(host, max(host.width, host.height) * (ANIMATOR_PRESS_SCALE - 1f) / 2f + 2f * density)
            }
        }
        // Views always report state_enabled while enabled; an empty set means "no state yet".
        val en = state.isEmpty() || state.contains(android.R.attr.state_enabled)
        if (en != enabled) { enabled = en; changed = true }
        if (selectedTint != null) {
            val s = state.contains(android.R.attr.state_selected) || state.contains(android.R.attr.state_checked) ||
                state.contains(android.R.attr.state_activated)
            if (s != selected) {
                selected = s
                animateSelection(if (s) 1f else 0f)
                changed = true
            }
        }
        if (changed) invalidateSelf()
        return changed
    }

    override fun jumpToCurrentState() {
        selectionAnimator?.cancel()
        selection = if (selected) 1f else 0f
        invalidateSelf()
    }

    private fun animateSelection(target: Float) {
        selectionAnimator?.cancel()
        // First state after inflation (or while hidden): no animation.
        if (!canAnimateOnScreen()) { selection = target; return }
        selectionAnimator = android.animation.ValueAnimator.ofFloat(selection, target).apply {
            duration = 180L
            interpolator = Motion.iosOut
            addUpdateListener { selection = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    override fun getOutline(outline: Outline) {
        shapeRect(rect)
        val r = radiusFor(rect)
        outline.setRoundRect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt(), r)
        outline.alpha = 1f
    }

    override fun setAlpha(alpha: Int) {
        alphaMul = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = alphaMul

    /**
     * Ignored on purpose: a leftover `backgroundTint` on a button would otherwise flood the
     * glass into a solid slab (AppCompat applies tints as a color filter). Tint via [glassTint].
     */
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun scaleAlpha(c: Int, f: Float) =
        Color.argb((Color.alpha(c) * f).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        /** Peak scale in `animator/glass_press.xml`. */
        private const val ANIMATOR_PRESS_SCALE = 1.08f

        fun sheet(context: Context, topOnly: Boolean = false): GlassDrawable =
            GlassDrawable(
                context,
                ContextCompat.getColor(context, R.color.glass_sheet_tint),
                context.resources.getDimension(R.dimen.glass_sheet_radius)
            ).also { it.topOnly = topOnly }

        fun control(context: Context, diameterDp: Float = 40f): GlassDrawable =
            GlassDrawable(context, ContextCompat.getColor(context, R.color.glass_control_solid_tint), -1f).also {
                it.diameter = diameterDp * context.resources.displayMetrics.density
                it.interactive = true
            }
    }
}

/**
 * Alert dialogs on a glass card over a frosted screen. Drop-in for [MaterialAlertDialogBuilder].
 */
class GlassAlertDialogBuilder : MaterialAlertDialogBuilder {
    constructor(context: Context) : super(context, R.style.CustomMaterialAlertDialogTheme)
    constructor(context: Context, overrideThemeResId: Int) : super(context, overrideThemeResId)

    override fun create(): androidx.appcompat.app.AlertDialog {
        background = GlassDrawable.sheet(context)
        val dialog = super.create()
        dialog.window?.let { GlassDialogs.frost(it) }
        return dialog
    }
}

/**
 * App-wide glass chrome: every screen's toolbar buttons become glass capsules, and every
 * dialog fragment and bottom sheet gets a glass surface over a frosted screen. Hooked once per
 * activity, so new screens are covered without touching their layouts.
 */
object GlassChrome {

    fun install(fm: FragmentManager) {
        fm.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                decorateToolbars(v)
            }

            override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                if (f is DialogFragment) f.dialog?.let { glassDialog(it) }
            }
        }, true)
    }

    fun glassDialog(dialog: Dialog) {
        val window = dialog.window ?: return
        GlassDialogs.frost(window)
        if (dialog is BottomSheetDialog) {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.background = GlassDrawable.sheet(dialog.context, topOnly = true)
                sheet.backgroundTintList = null
            }
        }
    }

    fun decorateToolbars(root: View) {
        if (root is Toolbar) decorateToolbar(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) decorateToolbars(root.getChildAt(i))
    }

    private fun decorateToolbar(toolbar: Toolbar) {
        if (toolbar.getTag(R.id.tag_glass_decorated) == true) return
        toolbar.setTag(R.id.tag_glass_decorated, true)
        decorateToolbarChildren(toolbar)
        toolbar.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View, child: View) = decorateToolbarChildren(toolbar)
            override fun onChildViewRemoved(parent: View, child: View) = Unit
        })
    }

    private fun decorateToolbarChildren(toolbar: Toolbar) {
        // Many action icons are drawn white; tint them to ink so they read in light mode too.
        val ink = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(toolbar.context, R.color.xai_ink))
        val menu = toolbar.menu
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (item.icon != null) androidx.core.view.MenuItemCompat.setIconTintList(item, ink)
        }
        toolbar.overflowIcon?.setTintList(ink)
        for (i in 0 until toolbar.childCount) {
            when (val c = toolbar.getChildAt(i)) {
                is ImageButton -> glassButton(c)
                is ActionMenuView -> {
                    for (j in 0 until c.childCount) glassMenuItem(c.getChildAt(j))
                    if (c.getTag(R.id.tag_glass_decorated) != true) {
                        c.setTag(R.id.tag_glass_decorated, true)
                        c.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                            override fun onChildViewAdded(parent: View, child: View) {
                                glassMenuItem(child)
                                decorateToolbarChildren(toolbar)
                            }
                            override fun onChildViewRemoved(parent: View, child: View) = Unit
                        })
                    }
                }
            }
        }
    }

    /** Icon-only menu items get a capsule; text actions stay plain (iOS keeps "Done" as text). */
    private fun glassMenuItem(item: View) {
        val text = (item as? android.widget.TextView)?.text
        if (item is android.widget.TextView && !text.isNullOrBlank() &&
            item.compoundDrawablesRelative.all { it == null }
        ) return
        glassButton(item)
    }

    fun glassButton(button: View, diameterDp: Float = 40f) {
        if (button.getTag(R.id.tag_glass_button) == true) return
        button.setTag(R.id.tag_glass_button, true)
        button.background = GlassDrawable.control(button.context, diameterDp)
        button.stateListAnimator = AnimatorInflater.loadStateListAnimator(button.context, R.animator.glass_press)
    }
}
