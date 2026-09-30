package io.github.stardomains3.oxproxion

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations
import kotlinx.coroutines.launch

/**
 * The character panel's tiles that used to open small popovers now open these full-screen pages.
 * The panel stays open underneath; back returns to it.
 */
abstract class RpPageFragment : Fragment() {

    protected lateinit var prefs: SharedPreferencesHelper
    protected lateinit var body: LinearLayout
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
        prefs = SharedPreferencesHelper(requireContext())
        val pageTitle = title()
        view.findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = pageTitle
            setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        }
        body = view.findViewById(R.id.rpPageBody) ?: view as LinearLayout
        build(body)
    }

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    protected fun hint(text: String, top: Int = 4) = TextView(requireContext()).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.xai_mute))
        textSize = 13f
        setPadding(dp(4), dp(top), dp(4), dp(4))
    }

    protected fun card(gap: Int = 12): LinearLayout {
        val top = dp(gap)
        return LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_settings_card)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = top }
        }
    }

    /** One tappable line; [selected] non-null draws a check that only the chosen line shows. */
    protected fun row(title: String, subtitle: String?, selected: Boolean?, onClick: () -> Unit): Pair<View, ImageView?> {
        val ctx = requireContext()
        val ink = ContextCompat.getColor(ctx, R.color.xai_ink)
        val mute = ContextCompat.getColor(ctx, R.color.xai_mute)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setBackgroundResource(R.drawable.bg_press_svg)
            isClickable = true
            isFocusable = true
            contentDescription = listOfNotNull(title, subtitle).joinToString(", ")
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply { text = title; setTextColor(ink); textSize = 16f })
        if (subtitle != null) texts.addView(TextView(ctx).apply { text = subtitle; setTextColor(mute); textSize = 13f })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        var check: ImageView? = null
        if (selected != null) {
            check = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(ink)
                visibility = if (selected) View.VISIBLE else View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(check, LinearLayout.LayoutParams(dp(20), dp(20)))
        }
        return row to check
    }

    /** Full-screen page over this one, like the panel's own tiles. */
    protected fun pushPage(fragment: Fragment) {
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

/** Classic, Bubbles or Book: how this character's chat is drawn. */
class RpLayoutFragment : RpPageFragment() {
    override fun title() = getString(R.string.rp_panel_layout)

    override fun build(body: LinearLayout) {
        var current = prefs.getRpLayout(characterId)
        val options = listOf(
            Triple(SharedPreferencesHelper.RP_LAYOUT_CLASSIC, R.string.rp_layout_classic, R.string.rp_layout_classic_sub),
            Triple(SharedPreferencesHelper.RP_LAYOUT_BUBBLES, R.string.rp_layout_bubbles, R.string.rp_layout_bubbles_sub),
            Triple(SharedPreferencesHelper.RP_LAYOUT_BOOK, R.string.rp_layout_book, R.string.rp_layout_book_sub),
        )
        val card = card()
        val checks = ArrayList<Pair<String, ImageView>>()
        options.forEach { (key, name, sub) ->
            val (v, check) = row(getString(name), getString(sub), key == current) {
                current = key
                prefs.saveRpLayout(characterId, key)
                checks.forEach { (k, c) -> c.visibility = if (k == key) View.VISIBLE else View.INVISIBLE }
            }
            checks += key to check!!
            card.addView(v)
        }
        body.addView(card)
    }

    companion object {
        fun newInstance(characterId: Long?, name: String) = RpLayoutFragment().with(characterId, name)
    }
}

/** Pin a lorebook to this character, or follow whichever one is active. */
class RpLorePinFragment : RpPageFragment() {
    override fun title() = getString(R.string.rp_panel_lore)

    override fun build(body: LinearLayout) {
        val id = characterId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val books = chatViewModel.getRpRepository().getAllLorebooksOnce()
            if (view == null) return@launch
            var pinned = prefs.getRpLorebookId(id)
            val active = books.firstOrNull { it.isActive }
            val card = card()
            val checks = ArrayList<Pair<Long?, ImageView>>()
            fun add(bookId: Long?, name: String, sub: String?) {
                val (v, check) = row(name, sub, bookId == pinned) {
                    pinned = bookId
                    prefs.saveRpLorebookId(id, bookId)
                    checks.forEach { (k, c) -> c.visibility = if (k == bookId) View.VISIBLE else View.INVISIBLE }
                }
                checks += bookId to check!!
                card.addView(v)
            }
            add(null, getString(R.string.rp_lore_use_active), active?.name ?: getString(R.string.rp_ui_lore_none))
            books.forEach { add(it.id, it.name, if (it.isActive) getString(R.string.rp_lore_active_badge) else null) }
            body.addView(card)
            body.addView(card().apply {
                addView(row(getString(R.string.rp_lore_edit), null, null) {
                    pushPage(RpLorebookLibraryFragment.newInstance())
                }.first)
            })
        }
    }

    companion object {
        fun newInstance(characterId: Long, name: String) = RpLorePinFragment().with(characterId, name)
    }
}

/** This character's wallpaper: a preview, pick a new picture, or drop it. */
class RpWallpaperFragment : RpPageFragment() {
    override fun title() = getString(R.string.rp_panel_wallpaper)

    private var refresh: (() -> Unit)? = null

    private val pick = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = characterId
        if (uri == null || id == null) return@registerForActivityResult
        BackgroundPhoto.import(requireContext(), uri, BackgroundPhoto.slotForCharacter(id)) { ok ->
            if (!isAdded) return@import
            if (!ok) GlassNotice.show(requireContext(), getString(R.string.toast_could_not_open_image))
            refresh?.invoke()
        }
    }

    override fun build(body: LinearLayout) {
        val id = characterId ?: return
        val slot = BackgroundPhoto.slotForCharacter(id)
        val ctx = requireContext()
        val radius = dp(22).toFloat()
        val preview = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(ctx, R.color.panel_tile))
                cornerRadius = radius
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        body.addView(preview, LinearLayout.LayoutParams(-1, dp(360)).apply { topMargin = dp(8) })
        val actions = card()
        body.addView(actions)

        fun bindActions() {
            val file = BackgroundPhoto.file(ctx, slot).takeIf { it.isFile }
            preview.setImageBitmap(file?.let {
                BitmapFactory.decodeFile(it.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })
            })
            actions.removeAllViews()
            actions.addView(row(
                getString(R.string.rp_wallpaper_change),
                getString(R.string.rp_wallpaper_change_sub, characterName), null
            ) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.first)
            if (file != null) {
                actions.addView(row(
                    getString(R.string.rp_wallpaper_remove), getString(R.string.rp_wallpaper_remove_sub), null
                ) {
                    BackgroundPhoto.delete(ctx, slot)
                    bindActions()
                }.first)
            }
        }
        refresh = ::bindActions
        bindActions()
    }

    override fun onDestroyView() {
        refresh = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(characterId: Long, name: String) = RpWallpaperFragment().with(characterId, name)
    }
}

/** What this character remembers (a note that lasts) and the facts this chat has established. Saved when you leave. */
class RpMemoryFragment : RpPageFragment() {
    override fun title() = getString(R.string.rp_panel_memory)

    private var note: EditText? = null
    private var facts: EditText? = null
    private var noteStart = ""
    private var factsStart = ""

    override fun build(body: LinearLayout) {
        noteStart = prefs.getRpMemory(characterId)
        factsStart = chatViewModel.currentRpFacts()
        note = field(body, R.string.rp_memory_note, R.string.rp_memory_hint, noteStart)
        facts = field(body, R.string.rp_facts_title, R.string.rp_facts_hint, factsStart)
    }

    private fun field(body: LinearLayout, label: Int, hintRes: Int, text: String): EditText {
        val ctx = requireContext()
        body.addView(TextView(ctx).apply {
            setText(label)
            setTextColor(ContextCompat.getColor(ctx, R.color.xai_ink))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(14), dp(4), 0)
        })
        body.addView(hint(getString(hintRes), top = 2))
        val edit = EditText(ctx).apply {
            setText(text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
            minLines = 6
            background = null
            setTextColor(ContextCompat.getColor(ctx, R.color.xai_ink))
            textSize = 15f
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        body.addView(card(gap = 6).apply { addView(edit, LinearLayout.LayoutParams(-1, -2)) })
        return edit
    }

    override fun onPause() {
        super.onPause()
        note?.text?.toString()?.takeIf { it != noteStart }?.let {
            prefs.saveRpMemory(characterId, it)
            noteStart = it
        }
        facts?.text?.toString()?.takeIf { it != factsStart }?.let {
            chatViewModel.saveCurrentRpFacts(it)
            factsStart = it
        }
    }

    override fun onDestroyView() {
        note = null
        facts = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(characterId: Long?, name: String) = RpMemoryFragment().with(characterId, name)
    }
}

/** Voice, pitch and speed for reading this character aloud; every tap saves and previews. */
class RpVoiceFragment : RpPageFragment() {
    private var tts: TextToSpeech? = null

    override fun title() = getString(R.string.rp_voice_title, characterName)
    override fun layoutRes() = R.layout.fragment_rp_voice

    override fun build(body: LinearLayout) {
        // Voices arrive once the engine is up, so the list is bound then.
        tts = TextToSpeech(requireContext().applicationContext) { status ->
            if (!isAdded || view == null) return@TextToSpeech
            val engine = tts.takeIf { status == TextToSpeech.SUCCESS }
            RpVoiceDialog.bind(this, requireView(), characterName, engine, prefs.getRpVoice(characterId)) {
                prefs.saveRpVoice(characterId, it)
            }
        }
    }

    override fun onDestroyView() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(characterId: Long?, name: String) = RpVoiceFragment().with(characterId, name)
    }
}
