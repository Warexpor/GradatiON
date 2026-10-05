package io.github.stardomains3.oxproxion

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputLayout
import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The character panel's tiles that used to open small popovers now open these full-screen pages.
 * The panel stays open underneath; back returns to it.
 */
abstract class RpPageFragment : Fragment() {

    protected lateinit var prefs: SharedPreferencesHelper
    protected lateinit var body: LinearLayout
    protected var restoredState: Bundle? = null
    protected val chatViewModel: ChatViewModel by activityViewModels { AppViewModelFactory(requireActivity().application) }

    /** The character this page is about; null is the plain LLM speaker (its settings have no character id). */
    protected val characterId: Long? get() = arguments?.getLong(ARG_ID, NONE)?.takeIf { it != NONE }
    protected val characterName: String get() = arguments?.getString(ARG_NAME).orEmpty()

    protected abstract fun title(): String
    protected abstract fun build(body: LinearLayout)

    protected open fun layoutRes() = R.layout.fragment_rp_page

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(layoutRes(), container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        restoredState = savedInstanceState
        prefs = SharedPreferencesHelper(requireContext())
        val pageTitle = title()
        view.findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = pageTitle
            setNavigationOnClickListener { leave() }
        }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                // A page hidden under another one still holds this callback; it must not ask about its own edits then.
                override fun handleOnBackPressed() = if (isHidden) parentFragmentManager.popBackStack() else leave()
            }
        )
        body = view.findViewById(R.id.rpPageBody) ?: view as LinearLayout
        RpPageKit.applyInsets(view)
        intro()?.let { body.addView(RpPageKit.intro(requireContext(), it), 0) }
        build(body)
    }

    /** A line on what the page is for, at the top. */
    protected open fun intro(): String? = null

    /** True when leaving now would throw away edits; back then asks first. */
    protected open fun hasUnsavedChanges(): Boolean = false

    private fun leave() {
        if (!hasUnsavedChanges()) {
            parentFragmentManager.popBackStack()
            return
        }
        GrokConfirmDialog.show(
            fragment = this,
            title = getString(R.string.rp_discard_edits_title),
            message = getString(R.string.rp_discard_edits_body),
            confirmText = getString(R.string.rp_discard_edits_confirm),
            onConfirm = { parentFragmentManager.popBackStack() },
            destructive = true
        )
    }

    /** Shows the pinned Save under the scroll; the page stays open for back to discard. */
    protected fun pinSave(onSave: () -> Unit) {
        requireView().findViewById<View>(R.id.rpSaveButton).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                onSave()
                parentFragmentManager.popBackStack()
            }
        }
    }

    protected fun dp(v: Int) = RpPageKit.dp(requireContext(), v)

    protected fun section(text: String) = body.addView(RpPageKit.section(requireContext(), text))

    protected fun footnote(text: String) = body.addView(RpPageKit.footnote(requireContext(), text))

    protected fun card(): LinearLayout = RpPageKit.card(requireContext()).also { body.addView(it) }

    /** One tappable line; [selected] non-null draws a check that only the chosen line shows. */
    protected fun row(
        card: LinearLayout,
        title: String,
        subtitle: String?,
        selected: Boolean? = null,
        chevron: Boolean = false,
        onClick: () -> Unit,
    ): Pair<View, ImageView?> = RpPageKit.row(card, title, subtitle, selected, chevron, onClick = onClick)

    /** Full-screen page over this one, like the panel's own tiles. */
    protected fun pushPage(fragment: Fragment) {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .hide(this)
            .add(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    companion object {
        private const val ARG_ID = "characterId"
        private const val ARG_NAME = "characterName"
        private const val NONE = Long.MIN_VALUE

        fun <T : RpPageFragment> T.with(characterId: Long?, name: String): T = apply {
            arguments = Bundle().apply {
                putLong(ARG_ID, characterId ?: NONE)
                putString(ARG_NAME, name)
            }
        }
    }
}

/**
 * The pages' shared pieces, so each one reads like Persona: the tile's drawing on top, small
 * section labels, rounded cards of full-height rows with hairlines between them, footnotes.
 */
internal object RpPageKit {

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /** Status bar over the toolbar; the keyboard or nav bar under the whole page, so a pinned Save rides the keyboard. */
    fun applyInsets(root: View) {
        val toolbar = root.findViewById<View>(R.id.toolbar)
        val scroll = root.findViewById<ScrollView>(R.id.rpPageScroll)
        val toolbarPadTop = toolbar?.paddingTop ?: 0
        val rootPadBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            toolbar?.updatePadding(top = toolbarPadTop + bars.top)
            scroll?.updatePadding(left = bars.left, right = bars.right)
            v.updatePadding(bottom = rootPadBottom + max(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun roundOutline(radius: Float) = object : ViewOutlineProvider() {
        override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(0, 0, v.width, v.height, radius)
    }

    /** A drawing as the tile shows it, on the tile's own fill. */
    fun art(ctx: Context, kind: RpTileArt.Kind, radiusDp: Int, photo: android.graphics.Bitmap? = null): FrameLayout {
        val radius = dp(ctx, radiusDp).toFloat()
        val fill = ContextCompat.getColor(ctx, R.color.panel_tile)
        return FrameLayout(ctx).apply {
            background = RpCharacterPanel.solidShape(ctx, R.color.panel_tile, radiusPx = radius)
            outlineProvider = roundOutline(radius)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(RpTileArt(ctx, kind, ContextCompat.getColor(ctx, R.color.xai_ink), fill).apply { this.photo = photo },
                FrameLayout.LayoutParams(-1, -1))
        }
    }

    /** The line on what a page is for, under the toolbar; no drawing, the tile that opened it already had one. */
    fun intro(ctx: Context, caption: String) = TextView(ctx).apply {
        text = caption
        setTextAppearance(R.style.TextAppearance_Gradation_Footnote)
        textSize = 15f
        gravity = Gravity.CENTER_HORIZONTAL
        setLineSpacing(0f, 1.15f)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(ctx, 8)
            marginStart = dp(ctx, 12)
            marginEnd = dp(ctx, 12)
        }
    }

    fun section(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_Gradation_SectionHeader)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(ctx, 16)
            topMargin = dp(ctx, 24)
            bottomMargin = dp(ctx, 8)
        }
    }

    fun footnote(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_Gradation_Footnote)
        setLineSpacing(0f, 1.1f)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(ctx, 16)
            marginEnd = dp(ctx, 16)
            topMargin = dp(ctx, 8)
        }
    }

    fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.rp_bg_card)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    /** Adds a row to [card], with a hairline above it when it isn't the first. */
    fun row(
        card: LinearLayout,
        title: String,
        subtitle: String?,
        selected: Boolean? = null,
        chevron: Boolean = false,
        titleColor: Int = R.color.xai_ink,
        onClick: () -> Unit,
    ): Pair<View, ImageView?> {
        val ctx = card.context
        val ink = ContextCompat.getColor(ctx, titleColor)
        if (card.childCount > 0) {
            card.addView(View(ctx).apply { setBackgroundColor(ContextCompat.getColor(ctx, R.color.xai_hairline)) },
                LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(ctx, 16) })
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 56)
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
            setBackgroundResource(R.drawable.bg_press_svg)
            isClickable = true
            isFocusable = true
            contentDescription = listOfNotNull(title, subtitle).joinToString(", ")
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply { text = title; setTextColor(ink); textSize = 16f })
        if (subtitle != null) {
            texts.addView(TextView(ctx).apply {
                text = subtitle
                setTextAppearance(R.style.TextAppearance_Gradation_Footnote)
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val trailing = when {
            selected != null -> R.drawable.ic_check
            chevron -> R.drawable.ic_chevron_right
            else -> 0
        }
        var check: ImageView? = null
        if (trailing != 0) {
            val icon = ImageView(ctx).apply {
                setImageResource(trailing)
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, if (chevron) R.color.xai_mute else R.color.xai_ink))
                if (selected != null) visibility = if (selected) View.VISIBLE else View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)).apply { marginStart = dp(ctx, 12) })
            if (selected != null) check = icon
        }
        card.addView(row)
        return row to check
    }
}

/** Classic, Bubbles or Book: how this character's chat is drawn, picked from drawings of each. */
class RpLayoutFragment : RpPageFragment() {
    override fun title() = getString(R.string.rp_panel_layout)
    override fun intro() = getString(R.string.rp_page_layout_caption, characterName.ifBlank { getString(R.string.rp_llm_speaker) })

    override fun build(body: LinearLayout) {
        var current = prefs.getRpLayout(characterId)
        val options = listOf(
            Triple(SharedPreferencesHelper.RP_LAYOUT_CLASSIC, R.string.rp_layout_classic, R.string.rp_layout_classic_sub),
            Triple(SharedPreferencesHelper.RP_LAYOUT_BUBBLES, R.string.rp_layout_bubbles, R.string.rp_layout_bubbles_sub),
            Triple(SharedPreferencesHelper.RP_LAYOUT_BOOK, R.string.rp_layout_book, R.string.rp_layout_book_sub),
        )
        val ctx = requireContext()
        val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(strip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        val description = RpPageKit.footnote(ctx, "").apply { gravity = Gravity.CENTER_HORIZONTAL; textSize = 14f }
        body.addView(description)
        val rims = ArrayList<Pair<String, View>>()
        val labels = ArrayList<Pair<String, TextView>>()
        fun select(key: String) {
            rims.forEach { (k, rim) -> rim.isSelected = k == key }
            labels.forEach { (k, t) -> t.alpha = if (k == key) 1f else 0.6f }
            description.setText(options.first { it.first == key }.third)
        }
        options.forEachIndexed { i, (key, name, sub) ->
            val kind = when (key) {
                SharedPreferencesHelper.RP_LAYOUT_BUBBLES -> RpTileArt.Kind.LAYOUT_BUBBLES
                SharedPreferencesHelper.RP_LAYOUT_BOOK -> RpTileArt.Kind.LAYOUT_BOOK
                else -> RpTileArt.Kind.LAYOUT_CLASSIC
            }
            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                contentDescription = "${getString(name)}, ${getString(sub)}"
                setOnClickListener {
                    current = key
                    prefs.saveRpLayout(characterId, key)
                    select(key)
                }
            }
            // A ring around the chosen drawing, drawn by the frame so the art keeps its own corners.
            val rim = FrameLayout(ctx).apply {
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply {
                        setStroke(dp(2), ContextCompat.getColor(ctx, R.color.xai_ink))
                        cornerRadius = dp(22).toFloat()
                    })
                }
                setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(RpPageKit.art(ctx, kind, 18), FrameLayout.LayoutParams(-1, -1))
            }
            val side = object : FrameLayout(ctx) {
                override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w), MeasureSpec.EXACTLY))
            }.apply { addView(rim, FrameLayout.LayoutParams(-1, -1)) }
            column.addView(side, LinearLayout.LayoutParams(-1, -2))
            val label = TextView(ctx).apply {
                setText(name)
                setTextColor(ContextCompat.getColor(ctx, R.color.xai_ink))
                textSize = 15f
                gravity = Gravity.CENTER_HORIZONTAL
            }
            column.addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            strip.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (i > 0) marginStart = dp(10)
            })
            rims += key to rim
            labels += key to label
        }
        select(current)

        // Your side of the chat: the persona's portrait and name over your lines, when there is one.
        section(getString(R.string.rp_layout_you_section))
        val card = card()
        val toggle = androidx.appcompat.widget.SwitchCompat(ctx).apply {
            applyGrokionSwitchStyle()
            isChecked = prefs.isRpShowPersona(characterId)
            contentDescription = getString(R.string.rp_layout_show_persona)
            setOnCheckedChangeListener { _, on -> prefs.saveRpShowPersona(characterId, on) }
        }
        val (row, _) = row(card, getString(R.string.rp_layout_show_persona), null) { toggle.toggle() }
        (row as LinearLayout).addView(toggle, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        footnote(getString(R.string.rp_layout_show_persona_note))
    }

    companion object {
        fun newInstance(characterId: Long?, name: String) = RpLayoutFragment().with(characterId, name)
    }
}

/** Pin a lorebook to this character, or follow whichever one is active. */
class RpLorePinFragment : RpPageFragment() {
    override fun title() = getString(R.string.rp_panel_lore)
    override fun intro() = getString(R.string.rp_page_lore_caption, characterName)

    override fun build(body: LinearLayout) {
        val id = characterId ?: return footnote(getString(R.string.rp_page_no_character))
        viewLifecycleOwner.lifecycleScope.launch {
            val books = chatViewModel.getRpRepository().getAllLorebooksOnce()
            if (view == null) return@launch
            var pinned = prefs.getRpLorebookId(id)
            val active = books.firstOrNull { it.isActive }
            section(getString(R.string.rp_page_lore_section))
            val card = card()
            val checks = ArrayList<Pair<Long?, ImageView>>()
            fun add(bookId: Long?, name: String, sub: String?) {
                val (_, check) = row(card, name, sub, selected = bookId == pinned) {
                    pinned = bookId
                    prefs.saveRpLorebookId(id, bookId)
                    checks.forEach { (k, c) -> c.visibility = if (k == bookId) View.VISIBLE else View.INVISIBLE }
                }
                checks += bookId to check!!
            }
            add(null, getString(R.string.rp_lore_use_active), active?.name ?: getString(R.string.rp_ui_lore_none))
            books.forEach { add(it.id, it.name, if (it.isActive) getString(R.string.rp_lore_active_badge) else null) }
            val edit = card()
            (edit.layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
            row(edit, getString(R.string.rp_lore_edit), null, chevron = true) {
                pushPage(RpLorebookLibraryFragment.newInstance())
            }
        }
    }

    companion object {
        fun newInstance(characterId: Long, name: String) = RpLorePinFragment().with(characterId, name)
    }
}

/** This character's wallpaper: a preview, pick a new picture, or drop it. */
class RpWallpaperFragment : RpPageFragment() {
    override fun title() = getString(R.string.rp_panel_wallpaper)
    override fun intro() = getString(R.string.rp_page_wallpaper_caption, characterName)

    private var refresh: (() -> Unit)? = null
    private var previewJob: kotlinx.coroutines.Job? = null

    private val pick = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = characterId
        if (uri == null || id == null || !isAdded) return@registerForActivityResult
        BackgroundPhoto.import(requireContext(), uri, BackgroundPhoto.slotForCharacter(id)) { ok ->
            if (!isAdded) return@import
            if (!ok) GlassNotice.show(requireContext(), getString(R.string.toast_could_not_open_image))
            refresh?.invoke()
        }
    }

    override fun build(body: LinearLayout) {
        val id = characterId ?: return footnote(getString(R.string.rp_page_no_character))
        val slot = BackgroundPhoto.slotForCharacter(id)
        val ctx = requireContext()
        // Phone-shaped, so the picture is judged the way it will sit behind the chat.
        val radius = dp(26).toFloat()
        val frame = FrameLayout(ctx).apply {
            background = RpCharacterPanel.solidShape(ctx, R.color.panel_tile, radiusPx = radius)
            outlineProvider = RpPageKit.roundOutline(radius)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val empty = RpPageKit.art(ctx, RpTileArt.Kind.WALLPAPER, 26)
        val preview = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        body.addView(frame, LinearLayout.LayoutParams(dp(172), dp(344)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(20)
        })
        val state = TextView(ctx).apply {
            setTextAppearance(R.style.TextAppearance_Gradation_Footnote)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        body.addView(state, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val actions = card()
        (actions.layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)

        fun bindActions() {
            val file = BackgroundPhoto.file(ctx, slot).takeIf { it.isFile }
            preview.setImageDrawable(null)
            previewJob?.cancel()
            empty.visibility = if (file == null) View.VISIBLE else View.INVISIBLE
            state.text = if (file == null) getString(R.string.rp_page_wallpaper_none) else ""
            state.visibility = if (file == null) View.VISIBLE else View.GONE
            if (file != null) {
                val maxEdge = dp(480)
                previewJob = viewLifecycleOwner.lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) { decodeWallpaperPreview(file.absolutePath, maxEdge) }
                    if (!isAdded || view == null) {
                        bmp?.recycle()
                        return@launch
                    }
                    preview.setImageBitmap(bmp)
                }
            }
            actions.removeAllViews()
            row(actions, getString(R.string.rp_wallpaper_change), getString(R.string.rp_wallpaper_change_sub, characterName), chevron = true) {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            if (file != null) {
                RpPageKit.row(
                    actions, getString(R.string.rp_wallpaper_remove), getString(R.string.rp_wallpaper_remove_sub),
                    titleColor = R.color.delete_action
                ) {
                    GrokConfirmDialog.show(
                        fragment = this,
                        title = getString(R.string.rp_wallpaper_remove_title),
                        message = getString(R.string.rp_wallpaper_remove_body, characterName),
                        confirmText = getString(R.string.rp_wallpaper_remove),
                        onConfirm = {
                            BackgroundPhoto.delete(ctx, slot)
                            if (view != null) bindActions()
                        },
                        destructive = true
                    )
                }
            }
        }
        refresh = ::bindActions
        bindActions()
    }

    override fun onDestroyView() {
        previewJob?.cancel()
        previewJob = null
        refresh = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(characterId: Long, name: String) = RpWallpaperFragment().with(characterId, name)

        private fun decodeWallpaperPreview(path: String, maxEdge: Int): android.graphics.Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longest = max(bounds.outWidth, bounds.outHeight)
            while (sample < 32 && longest / sample > maxEdge) sample *= 2
            val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            // The chat turns a leftover sideways flag. The preview has to match it.
            val orientation = runCatching {
                ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            return BackgroundPhoto.upright(decoded, orientation)
        }
    }
}

/** What this character remembers (a note that lasts) and the facts this chat has established. Save writes both; back discards. */
class RpMemoryFragment : RpPageFragment() {
    override fun title() = getString(R.string.rp_panel_memory)

    private var note: EditText? = null
    private var facts: EditText? = null
    private var noteStart = ""
    private var factsStart = ""

    override fun intro() = getString(R.string.rp_page_memory_caption, speaker())

    private fun speaker() = characterName.ifBlank { getString(R.string.rp_llm_speaker) }

    override fun hasUnsavedChanges() =
        note?.text?.toString()?.let { it != noteStart } == true || facts?.text?.toString()?.let { it != factsStart } == true

    override fun build(body: LinearLayout) {
        noteStart = prefs.getRpMemory(characterId)
        factsStart = chatViewModel.currentRpFacts()
        val saved = restoredState
        section(getString(R.string.rp_page_memory_section))
        note = field(R.string.rp_memory_hint, saved?.getString(KEY_NOTE) ?: noteStart)
        footnote(getString(R.string.rp_page_memory_foot, speaker()))
        section(getString(R.string.rp_page_facts_section))
        facts = field(R.string.rp_facts_hint, saved?.getString(KEY_FACTS) ?: factsStart)
        footnote(getString(R.string.rp_page_facts_foot))
        pinSave(::save)
    }

    private fun field(hintRes: Int, text: String): EditText {
        val box = layoutInflater.inflate(R.layout.view_rp_page_field, body, false) as TextInputLayout
        box.hint = getString(hintRes)
        // Past this the prompt cuts the note off, so the count says where that happens.
        box.counterMaxLength = RpPromptEngine.MEMORY_MAX_CHARS
        body.addView(box)
        return box.findViewById<EditText>(R.id.rpPageFieldInput).apply { setText(text) }
    }

    private fun save() {
        note?.text?.toString()?.takeIf { it != noteStart }?.let {
            prefs.saveRpMemory(characterId, it)
            noteStart = it
        }
        facts?.text?.toString()?.takeIf { it != factsStart }?.let {
            chatViewModel.saveCurrentRpFacts(it)
            factsStart = it
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        note?.text?.toString()?.let { outState.putString(KEY_NOTE, it) }
        facts?.text?.toString()?.let { outState.putString(KEY_FACTS, it) }
    }

    override fun onDestroyView() {
        note = null
        facts = null
        super.onDestroyView()
    }

    companion object {
        private const val KEY_NOTE = "rpMemoryDraftNote"
        private const val KEY_FACTS = "rpMemoryDraftFacts"

        fun newInstance(characterId: Long?, name: String) = RpMemoryFragment().with(characterId, name)
    }
}

/** Voice, pitch and speed for reading this character aloud; every tap previews, Save keeps it, back discards. */
class RpVoiceFragment : RpPageFragment() {
    /** The engine the list was last bound to; the shared one can be rebuilt while the app is in the background. */
    private var boundEngine: TextToSpeech? = null

    override fun title() = getString(R.string.rp_panel_voice)
    override fun intro() = getString(R.string.rp_page_voice_caption, speaker())
    override fun layoutRes() = R.layout.fragment_rp_voice

    private var pending: SharedPreferencesHelper.RpVoice? = null

    private fun speaker() = characterName.ifBlank { getString(R.string.rp_llm_speaker) }

    // Every tap reports a choice, so "pending" alone would count a tap back onto the saved voice as an edit.
    override fun hasUnsavedChanges() = pending?.let { it != prefs.getRpVoice(characterId) } == true

    override fun build(body: LinearLayout) {
        pending = restorePending(restoredState) ?: pending
        pinSave { pending?.let { prefs.saveRpVoice(characterId, it) } }
        val start = pending ?: prefs.getRpVoice(characterId)
        // Default and the steps show at once; the engine's own voices join the list once it is up.
        RpVoiceDialog.bind(this, requireView(), speaker(), null, start) { pending = it }
        TtsHolder.hold(this)
        bindEngine()
    }

    private fun bindEngine() {
        TtsHolder.whenReady(requireContext()) { engine ->
            if (!isAdded || view == null || engine == null || engine === boundEngine) return@whenReady
            boundEngine = engine
            RpVoiceDialog.bind(this, requireView(), speaker(), engine, pending ?: prefs.getRpVoice(characterId)) { pending = it }
        }
    }

    override fun onStart() {
        super.onStart()
        // Back from the background, the shared engine may have been let go and rebuilt.
        if (boundEngine != null) bindEngine()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pending?.let { savePending(outState, it) }
    }

    override fun onDestroyView() {
        // A preview may still be talking; the engine is shared, so only this page's voice is stopped.
        runCatching { boundEngine?.stop() }
        boundEngine = null
        TtsHolder.release(this)
        super.onDestroyView()
    }

    companion object {
        private const val KEY_VOICE_NAME = "rpVoiceDraftName"
        private const val KEY_VOICE_PITCH = "rpVoiceDraftPitch"
        private const val KEY_VOICE_RATE = "rpVoiceDraftRate"

        fun savePending(out: Bundle, voice: SharedPreferencesHelper.RpVoice) {
            out.putString(KEY_VOICE_NAME, voice.name)
            out.putFloat(KEY_VOICE_PITCH, voice.pitch)
            out.putFloat(KEY_VOICE_RATE, voice.rate)
        }

        fun restorePending(saved: Bundle?): SharedPreferencesHelper.RpVoice? {
            if (saved == null || !saved.containsKey(KEY_VOICE_PITCH)) return null
            return SharedPreferencesHelper.RpVoice(
                saved.getString(KEY_VOICE_NAME),
                saved.getFloat(KEY_VOICE_PITCH, 1f),
                saved.getFloat(KEY_VOICE_RATE, 1f),
            )
        }

        fun newInstance(characterId: Long?, name: String) = RpVoiceFragment().with(characterId, name)
    }
}
