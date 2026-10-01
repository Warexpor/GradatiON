package io.github.stardomains3.oxproxion

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.Spanned
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import coil.target.Target
import io.noties.markwon.Markwon
import io.noties.markwon.utils.NoCopySpannableFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.File

class ChatAdapter(
    private val scope: CoroutineScope,
    private val markwon: Markwon,
    private val onSpeakText: (String, Int) -> Unit,
    private val onSynthesizeToWavFile: (String, Int) -> Unit,
    private val ttsAvailable: Boolean,
    private val onEditMessage: (Int, String) -> Unit,
    private val onRedoMessage: (Int, JsonElement) -> Unit,
    private val onInstructMessage: (Int) -> Unit,
    private val onDeleteMessage: (Int) -> Unit,
    private val onEditAssistantMessage: (Int, String) -> Unit,
    private val onCollapse: () -> Unit,
    private val forkNavStateForPosition: (Int) -> ChatViewModel.ForkNavState?,
    private val onForkNavigate: (Int) -> Unit

) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var isRpMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (messages.isNotEmpty()) notifyDataSetChanged()
        }

    /** Tap on a Roleplay reply's speaker line (portrait and name): the character panel. */
    var onSpeakerClick: (() -> Unit)? = null

    /** Shown above assistant bubbles in RP when a character is active. */
    /** RP layout ([SharedPreferencesHelper.RP_LAYOUT_CLASSIC] and friends) for the active character. */
    var rpLayout: String = SharedPreferencesHelper.RP_LAYOUT_CLASSIC
        @android.annotation.SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field == value) return
            field = value
            if (isRpMode && messages.isNotEmpty()) notifyDataSetChanged()
        }
    var rpSpeakerName: String? = null
        set(value) {
            if (field == value) return
            field = value
            if (isRpMode && messages.isNotEmpty()) notifyDataSetChanged()
        }

    var rpSpeakerAvatarUri: String? = null
        set(value) {
            if (field == value) return
            field = value
            if (isRpMode && messages.isNotEmpty()) notifyDataSetChanged()
        }

    var rpSpeakerAvatarFile: File? = null
        set(value) {
            if (field?.absolutePath == value?.absolutePath) {
                // Same path can still be a rewritten JPEG — force Coil rebind in RP.
                if (isRpMode && messages.isNotEmpty()) notifyDataSetChanged()
                return
            }
            field = value
            if (isRpMode && messages.isNotEmpty()) notifyDataSetChanged()
        }

    /** Bust avatar cache after character edit saves a new JPEG at the same path. */
    fun refreshRpSpeakerAvatars() {
        if (isRpMode && messages.isNotEmpty()) notifyDataSetChanged()
    }

    // --- STATE & CACHE ---
    // Changed to Map to use stable keys (content hash) instead of unstable positions
    private val collapsedStates = mutableMapOf<String, Boolean>()
    /** User bubble tap → show/hide action row (Grok-style). */
    private val userActionsExpanded = mutableSetOf<String>()
    // The "Baked" Cache for Markdown CharSequences
    private val renderCache = HashMap<FlexibleMessage, CharSequence>()
    /** Parsed user bubbles, keyed by the exact markdown shown. Scrolling rebinds; it should not re-parse. */
    private val userRenderCache = HashMap<String, Spanned>()

    private val noCopyFactory = NoCopySpannableFactory.getInstance()
    var isSpeaking = false
    var currentSpeakingPosition = -1
    /** A reply is being generated: the last assistant row keeps its action icons hidden. */
    var replyInFlight = false
    /** Opens a reply's ⋮ menu anchored to its button; the host owns the popover. */
    var onMessageMenu: ((View, List<MessageMenu.Item>) -> Unit)? = null

    /** Show the model's thinking above replies; off hides the block entirely. */
    var showThinking = true
    private var currentTypeface: Typeface = Typeface.DEFAULT

    // OPTIMIZATION: Conflated Channel for throttling updates
    private val updateChannel = Channel<FlexibleMessage>(Channel.CONFLATED)
    private val messages = mutableListOf<FlexibleMessage>()
    private var isUserApplyingEdit: Boolean = false
    private var editTargetPosition: Int = -1
    private var currentFontScale: Int = 100
    private var streamRevealBoundHolder: AssistantViewHolder? = null
    private var pendingStreamFinalize: Boolean = false
    /** The finished reply's parse + text layout, running off the main thread; the swap waits for it. */
    private var finalParse: kotlinx.coroutines.Job? = null
    /** Invoked when the stream reveal paints a new frame. */
    var onStreamVisualUpdate: (() -> Unit)? = null
    private val streamReveal = StreamRevealAnimator(
        onFrame = { displayed, _ ->
            streamRevealBoundHolder?.renderStreamFrame(displayed)
                ?: run {
                    if (messages.isNotEmpty()) {
                        notifyItemChanged(messages.size - 1, "STREAMING")
                    }
                }
            onStreamVisualUpdate?.invoke()
        },
        onCaughtUp = {
            if (!pendingStreamFinalize) return@StreamRevealAnimator
            pendingStreamFinalize = false
            if (messages.isNotEmpty()) {
                val lastIndex = messages.size - 1
                // Swap at once: waiting for the last words' fade left a dead beat between the final
                // word and the tools. The parse ran in the background, so the swap itself is cheap.
                val token = ++finalizeToken
                mainHandler.post { swapFinal(lastIndex, token, retried = false) }
            }
        }
    )
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var finalizeToken = 0

    // Streaming render state: cached closed blocks + per-chunk fade timestamps in rendered
    // coordinates, so each newly revealed run of words eases in on its own clock.
    private val streamMarkdown = IncrementalMarkdown(markwon, ::ensureTableSpacing) { text, from ->
        ChatMarkdown.polish(text, from)
    }
    private val fadeStarts = ArrayList<Int>()
    private val fadeTimes = ArrayList<Long>()
    private var lastRenderedLen = 0

    /** Needs a context for the animation setting; the list supplies one while it is attached. */
    private var listContext: Context? = null

    /**
     * Whether this stream eases words in. Read once when the stream starts (not per frame, and not
     * per word): with animations off the text simply appears as it arrives.
     */
    private var streamAnimated = true

    private fun beginStream() {
        streamAnimated = listContext?.let { Motion.areAnimationsEnabled(it) } ?: true
        streamReveal.instant = !streamAnimated
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        listContext = recyclerView.context
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        listContext = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    /**
     * Set while a reply grows in place (Roleplay's Continue): the text it started from. The first
     * streamed update seeds the reveal with it, so that text stays put and only the new words
     * ease in, rather than the whole reply replaying from its first letter.
     */
    var continuingFrom: String? = null

    private fun seedContinuation(previous: String) {
        streamReveal.seed(previous)
        streamMarkdown.reset()
        fadeStarts.clear()
        fadeTimes.clear()
        // Prime the incremental parser and the fade bookkeeping with what is already shown.
        val shown = streamMarkdown.render(previous)
        lastRenderedLen = shown.length
    }

    private fun resetStreamRender() {
        streamReveal.reset()
        streamMarkdown.reset()
        fadeStarts.clear()
        fadeTimes.clear()
        lastRenderedLen = 0
    }

    private fun applyStreamFades(text: android.text.SpannableStringBuilder, now: Long) {
        if (!streamAnimated) return
        val len = text.length
        if (len < lastRenderedLen) {
            while (fadeStarts.isNotEmpty() && fadeStarts.last() >= len) {
                fadeStarts.removeAt(fadeStarts.lastIndex)
                fadeTimes.removeAt(fadeTimes.lastIndex)
            }
        } else if (len > lastRenderedLen) {
            val tailActive = fadeTimes.isNotEmpty() &&
                now - fadeTimes.last() < StreamFadeSpan.DURATION_MS
            if (!tailActive) {
                fadeStarts.add(lastRenderedLen)
                fadeTimes.add(now)
            }
        }
        lastRenderedLen = len
        while (fadeTimes.isNotEmpty() && now - fadeTimes[0] >= StreamFadeSpan.DURATION_MS) {
            fadeStarts.removeAt(0)
            fadeTimes.removeAt(0)
        }
        for (i in fadeStarts.indices) {
            val start = fadeStarts[i].coerceAtMost(len)
            val end = (if (i + 1 < fadeStarts.size) fadeStarts[i + 1] else len).coerceAtMost(len)
            if (end > start) {
                text.setSpan(
                    StreamFadeSpan(fadeTimes[i]),
                    start,
                    end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }
    init {
        // Apply SSE updates at full speed (conflated = latest only; no artificial delay).
        scope.launch(Dispatchers.Main) {
            for (newMessage in updateChannel) {
                if (messages.isNotEmpty()) {
                    messages[messages.size - 1] = newMessage
                    val text = getMessageText(newMessage.content)
                    if (!ThinkingPlaceholder.matches(text) && text.isNotBlank()) {
                        if (streamReveal.displayed().isEmpty()) beginStream()
                        val from = continuingFrom
                        if (from != null && from.isNotBlank() && streamReveal.displayed().isEmpty() && text.startsWith(from)) {
                            seedContinuation(from)
                        }
                        streamReveal.setTarget(text)
                    }
                    // Holder already painting via Choreographer — skip notify. Rebind+markwon
                    // every token races stick-to-bottom scrollBy and flashes the UI.
                    if (streamRevealBoundHolder == null || ThinkingPlaceholder.matches(text) || text.isBlank()) {
                        notifyItemChanged(messages.size - 1, "STREAMING")
                    }
                }
            }
        }
    }

    // --- PUBLIC METHODS ---

    fun clearCache() {
        renderCache.clear()
        userRenderCache.clear()
        collapsedStates.clear()
        resetStreamRender()
        streamRevealBoundHolder = null
    }
    fun getLatestPlainText(): String? {
        return messages.lastOrNull()?.let { getMessageText(it.content) }
    }

    fun updateTtsState(speaking: Boolean, position: Int) {
        isSpeaking = speaking
        currentSpeakingPosition = position
    }

    fun updateFont(newTypeface: Typeface?) {
        currentTypeface = newTypeface ?: Typeface.DEFAULT
        notifyDataSetChanged()
    }

    fun finalizeStreaming() {
        val text = getLatestPlainText().orEmpty()
        if (text.isBlank() || ThinkingPlaceholder.matches(text)) {
            pendingStreamFinalize = false
            resetStreamRender()
            if (messages.isNotEmpty()) notifyItemChanged(messages.size - 1)
            return
        }
        pendingStreamFinalize = true
        // Parse the finished reply now, off the main thread, while the last words still reveal.
        messages.lastOrNull()?.let { msg -> finalParse = scope.launch(Dispatchers.Main) { prepareFinal(msg) } }
        streamReveal.setTarget(text)
        streamReveal.finishFast()
    }

    /**
     * Parses [msg] and lays its text out off the main thread, then caches the result. The swap
     * to the full render then costs a bind and nothing else: no markdown parse, no line breaking.
     */
    private suspend fun prepareFinal(msg: FlexibleMessage) {
        val cached = renderCache[msg]
        val params = textParams()
        val ready = withContext(Dispatchers.Default) {
            val content = cached ?: try { renderContent(msg) } catch (e: Exception) { null }
            content?.let { precompute(it, params) }
        }
        if (ready != null) renderCache[msg] = ready
    }

    /** The reply text's measuring setup, read from the streaming row; it is the same on every row. */
    private fun textParams(): androidx.core.text.PrecomputedTextCompat.Params? {
        val tv = streamRevealBoundHolder?.messageTextView ?: return null
        tv.textSize = 16f * currentFontScale / 100f
        tv.typeface = currentTypeface
        return androidx.core.widget.TextViewCompat.getTextMetricsParams(tv)
    }

    /**
     * Line-breaks [content] ahead of time. Anything with inline drawn spans (tables, images) is
     * left alone: those measure against the view. A mismatch at bind time falls back to a normal
     * layout in [setReplyText], so this can only help.
     */
    private fun precompute(content: CharSequence, params: androidx.core.text.PrecomputedTextCompat.Params?): CharSequence {
        if (params == null || content !is Spanned || content.isEmpty()) return content
        if (content.getSpans(0, content.length, android.text.style.ReplacementSpan::class.java).isNotEmpty()) return content
        return try {
            androidx.core.text.PrecomputedTextCompat.create(content, params)
        } catch (e: Exception) {
            content
        }
    }

    /** Waits for the background work rather than repeating it on the main thread, then rebinds the reply. */
    private fun swapFinal(lastIndex: Int, token: Int, retried: Boolean) {
        if (token != finalizeToken || messages.size - 1 != lastIndex) return
        val msg = messages[lastIndex]
        val job = finalParse
        if (job != null && job.isActive) {
            job.invokeOnCompletion { mainHandler.post { swapFinal(lastIndex, token, retried) } }
            return
        }
        if (!retried && !renderCache.containsKey(msg)) {
            // A late update dropped the cached parse: redo it in the background first.
            finalParse = scope.launch(Dispatchers.Main) { prepareFinal(msg) }
            swapFinal(lastIndex, token, retried = true)
            return
        }
        notifyItemChanged(lastIndex)
        onStreamVisualUpdate?.invoke()
    }

    /** A copy of what the list shows, so a mode's thread can be put back instantly. */
    fun currentMessages(): List<FlexibleMessage> = messages.toList()

    fun setMessages(newMessages: List<FlexibleMessage>) {
        // The same thread arriving again (a mode's reload after its cached copy was shown):
        // nothing to redraw, and rebinding would replay the last reply's reveal.
        if (!isUserApplyingEdit && newMessages.isNotEmpty() && newMessages == messages) return
        if (isUserApplyingEdit) {
            applyEditUpdate(newMessages)
            return // Stop here, don't run the rest
        }
        // Clear cache if loading a fresh list or switching chats
        if (newMessages.isEmpty() || (messages.isEmpty() && newMessages.isNotEmpty())) {
            renderCache.clear()
        }

        if (newMessages.isEmpty()) {
            messages.clear()
            resetStreamRender()
            streamRevealBoundHolder = null
            notifyDataSetChanged()
            return
        }

        // PERFECT CASE: Only 1 new message added
        if (messages.size == newMessages.size - 1 &&
            sameMessages(messages, newMessages, messages.size)) {
            addMessage(newMessages.last())
            return
        }

        // STREAMING CASE: Same size, only last message content changed.
        // Identity first: a stream copies the list but keeps the earlier message objects.
        if (messages.size == newMessages.size && messages.isNotEmpty() &&
            sameMessages(messages, newMessages, messages.size - 1)) {
            updateLastMessage(newMessages.last())
            return
        }

        // Fallback: Full refresh
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun addMessage(message: FlexibleMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
    private fun applyEditUpdate(newMessages: List<FlexibleMessage>) {
        // 3. Use the stored position directly (Fast!)
        val index = editTargetPosition
        // Safety check: ensure index is valid
        if (index != -1 && index < messages.size && index < newMessages.size) {
            val oldMsg = messages[index]

            // 4. Clear cache
            renderCache.remove(oldMsg)

            // 5. Update list
            messages[index] = newMessages[index]

            // 6. Notify
            notifyItemChanged(index)
        }
        // 7. Reset BOTH flags
        isUserApplyingEdit = false
        editTargetPosition = -1
    }
    fun removeLastMessage() {
        if (messages.isNotEmpty()) {
            val lastIndex = messages.size - 1
            messages.removeAt(lastIndex)
            notifyItemRemoved(lastIndex)
        }
    }

    fun updateLastMessage(newMessage: FlexibleMessage) {
        if (messages.isNotEmpty()) {
            val oldMessage = messages.last()
            // Only when the text actually changed: the same reply arriving again must keep its
            // background-parsed render, or the swap at the end of a stream parses on the main thread.
            if (oldMessage != newMessage) renderCache.remove(oldMessage)
        }
        updateChannel.trySend(newMessage)
    }

    fun streamDisplayedText(): String = streamReveal.displayed()

    fun attachStreamRevealHolder(holder: AssistantViewHolder?) {
        streamRevealBoundHolder = holder
    }

    // --- DATA HELPERS ---
    fun flagEditUpdate(position: Int) {
        isUserApplyingEdit = true
        editTargetPosition = position
    }
    fun updateFontSize(scalePercent: Int) {
        currentFontScale = scalePercent.coerceIn(50, 200) // clamp 50%-200%
        notifyDataSetChanged()
    }
    private fun getMessageText(content: JsonElement): String = MessageContent.text(content)

    private fun getImageBase64(content: JsonElement): String? =
        MessageContent.imageUrl(content)?.substringAfter(",")

    /** What a message shows: its text, or for a turn that only called tools a line naming them. */
    private fun visibleText(message: FlexibleMessage): String {
        val text = getMessageText(message.content)
        if (message.role == "assistant" && message.toolCalls != null && text.isBlank()) {
            val names = message.toolCalls.map { it.function.name }.distinct().joinToString()
            return listContext?.getString(R.string.tool_used_format, names) ?: "**Tool used:** $names"
        }
        return text
    }

    // --- OPTIMIZED BAKING FUNCTION ---
    private fun getPreRenderedContent(message: FlexibleMessage): CharSequence {
        renderCache[message]?.let { return it }
        return renderContent(message).also { renderCache[message] = it }
    }

    /** The markdown parse alone, with no cache access, so it can run off the main thread. */
    private fun renderContent(message: FlexibleMessage): CharSequence {
        // Reasoning lives in its own collapsible UI — do not bake it into the body.
        // 3. Run Regex (Expensive)
        val fullText = ensureTableSpacing(visibleText(message))

        // 4. Render Markdown with Safety (Expensive)
        val renderedContent = try {
            ChatMarkdown.polished(markwon.toMarkdown(fullText))
        } catch (e: RuntimeException) {
            // 5. Prism4j Crash Handler
            if (e.message?.contains("Prism4j") == true || e.message?.contains("entry nodes") == true) {
                fullText // Fallback: Return the plain text
            } else {
                throw e
            }
        }

        return renderedContent
    }

    private fun ensureTableSpacing(md: String): String =
        md.replace(TABLE_AFTER_ITEM) { "${it.value}\n\n" }

    /** Strip legacy ``` fences / --- separators from stored reasoning for the dedicated UI. */
    private fun normalizeReasoning(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```").removePrefix("thinking").removePrefix("reasoning").trimStart('\n')
            val close = s.lastIndexOf("```")
            if (close >= 0) s = s.substring(0, close)
        }
        s = s.replace(TRAILING_RULE, "").trim()
        return s
    }

    private fun reasoningSource(message: FlexibleMessage): String =
        normalizeReasoning(message.reasoning ?: message.thinking)

    // --- VIEW HOLDER LOGIC ---

    companion object {
        const val VIEW_TYPE_USER = 1
        const val VIEW_TYPE_ASSISTANT = 2
        const val VIEW_TYPE_THINKING = 3
        const val VIEW_TYPE_HIDDEN = 4
        private const val REASONING_KEY_CHARS = 80
        private const val ACTION_STAGGER_MS = 55L

        /**
         * Markwon's text setter: uses a reply's precomputed layout when it fits the view, else
         * lays it out the usual way (a different size or font than it was measured for).
         */
        fun setReplyText(tv: TextView, text: Spanned, type: TextView.BufferType) {
            if (text is androidx.core.text.PrecomputedTextCompat) {
                try {
                    androidx.core.widget.TextViewCompat.setPrecomputedText(tv, text)
                    return
                } catch (e: IllegalArgumentException) {
                    // Measured for other params: fall through to a normal layout.
                }
            }
            tv.setText(text, type)
        }

        /** A stored photo scaled to about [maxEdge] pixels across; the full 12 MB bitmap is never built. */
        private fun decodeSampled(base64: String, maxEdge: Int): android.graphics.Bitmap? {
            val bytes = try {
                android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxEdge && bounds.outHeight / (sample * 2) >= maxEdge) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        private val TABLE_AFTER_ITEM = Regex(
            """(^[\t >]*([-+*]|\d+\.)\s+(?:\\\$\\\[ ?[ xX]?\\]\\\s+)?[^\n]*)\n(?=\|)""",
            RegexOption.MULTILINE
        )
        private val TRAILING_RULE = Regex("""\n*-{3,}\n*$""")
    }

    /**
     * User rows rebind on every scroll. Parsing markdown there is the slow part; the spannable
     * for a given string does not change until the cache is cleared.
     */
    private fun setCachedUserMarkdown(view: TextView, markdown: String) {
        val cached = userRenderCache[markdown]
        if (cached != null) {
            markwon.setParsedMarkdown(view, cached)
            return
        }
        try {
            val rendered = markwon.toMarkdown(markdown)
            userRenderCache[markdown] = rendered
            markwon.setParsedMarkdown(view, rendered)
        } catch (e: RuntimeException) {
            if (e.message?.contains("Prism4j") == true || e.message?.contains("entry nodes") == true) {
                view.text = markdown
            } else {
                throw e
            }
        }
    }

    /** Prefix equality. Same instance counts; a copied-but-equal message still matches. */
    private fun sameMessages(a: List<FlexibleMessage>, b: List<FlexibleMessage>, n: Int): Boolean {
        for (i in 0 until n) {
            val x = a[i]
            val y = b[i]
            if (x !== y && x != y) return false
        }
        return true
    }

    override fun getItemViewType(position: Int): Int {
        val message = messages[position]

        // 1. ONLY hide the raw tool results (the giant data dump)
        if (message.role == "tool") return VIEW_TYPE_HIDDEN

        // 2. Do NOT hide the assistant's tool calls anymore.
        val contentText = getMessageText(message.content)

        return when (message.role) {
            "user" -> VIEW_TYPE_USER
            "assistant" -> {
                if (ThinkingPlaceholder.matches(contentText)) VIEW_TYPE_THINKING else VIEW_TYPE_ASSISTANT
            }
            else -> VIEW_TYPE_ASSISTANT
        }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HIDDEN -> { // <--- ADD THIS BLOCK
                val emptyView = View(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(0, 0)
                    visibility = View.GONE
                }
                HiddenViewHolder(emptyView)
            }
            VIEW_TYPE_USER -> {
                val view = inflater.inflate(R.layout.item_message_user, parent, false)
                view.findViewById<TextView>(R.id.messageTextView)
                    .setSpannableFactory(noCopyFactory)
                UserViewHolder(view, markwon)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_message_ai, parent, false)
                view.findViewById<TextView>(R.id.messageTextView)
                    .setSpannableFactory(noCopyFactory)
                AssistantViewHolder(view, markwon, onSpeakText, onSynthesizeToWavFile)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            if (payloads.first() == "STREAMING" && holder is AssistantViewHolder) {
                attachStreamRevealHolder(holder)
                holder.bindTextOnly(messages[position])
                return
            }
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        when (holder) {
            is UserViewHolder -> holder.bind(message)
            is AssistantViewHolder -> holder.bind(message, position, isSpeaking, currentSpeakingPosition)
        }
    }

    override fun getItemCount(): Int = messages.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is AssistantViewHolder) {
            if (streamRevealBoundHolder === holder) {
                streamRevealBoundHolder = null
            }
            holder.stopPulse()
        }
    }

    /** Disclosure (reasoning, long replies): height eases open/closed with a fade, iOS-style. */
    private fun animateDisclosure(target: View, expand: Boolean) {
        (target.getTag(R.id.tag_visibility_animator) as? android.animation.Animator)?.cancel()
        if (!Motion.areAnimationsEnabled(target.context)) {
            target.visibility = if (expand) View.VISIBLE else View.GONE
            return
        }
        val parentWidth = (target.parent as? View)?.width ?: 0
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            (parentWidth - target.marginStartCompat() - target.marginEndCompat()).coerceAtLeast(0),
            View.MeasureSpec.EXACTLY
        )
        target.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val full = target.measuredHeight
        val from = if (expand) 0 else target.height
        val to = if (expand) full else 0
        target.visibility = View.VISIBLE
        target.layoutParams.height = from
        target.alpha = if (expand) 0f else 1f
        target.requestLayout()
        val animator = android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = if (expand) 380L else 260L
            interpolator = if (expand) Motion.iosOut else Motion.iosIn
            addUpdateListener {
                target.layoutParams.height = it.animatedValue as Int
                target.alpha = if (expand) it.animatedFraction else 1f - it.animatedFraction
                target.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    target.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    target.alpha = 1f
                    target.visibility = if (expand) View.VISIBLE else View.GONE
                    target.setTag(R.id.tag_visibility_animator, null)
                    target.requestLayout()
                }
            })
        }
        target.setTag(R.id.tag_visibility_animator, animator)
        animator.start()
    }

    private fun View.marginStartCompat() = (layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0
    private fun View.marginEndCompat() = (layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0

    /**
     * Loads [data] into a rounded frame that keeps the picture's shape inside [maxW]×[maxH].
     * The size is applied when the bitmap arrives, so a portrait doesn't flash as a square.
     */
    private fun loadFramedPhoto(
        view: ImageView,
        data: Any,
        tagKey: Int,
        tag: String,
        maxW: Int,
        maxH: Int,
        onFramed: (Int, Int) -> Unit = { _, _ -> },
    ) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        if (view.getTag(tagKey) == tag && view.drawable != null && view.layoutParams.width > 0) {
            view.visibility = View.VISIBLE
            return
        }
        view.setTag(tagKey, tag)
        view.setImageDrawable(null)
        view.visibility = View.VISIBLE
        val request = ImageRequest.Builder(view.context)
            .data(data)
            .size(maxW, maxH)
            .scale(Scale.FIT)
            .target(object : Target {
                override fun onSuccess(result: Drawable) {
                    if (view.getTag(tagKey) != tag) return
                    val (w, h) = ChatPhoto.frame(result.intrinsicWidth, result.intrinsicHeight, maxW, maxH)
                    val lp = view.layoutParams
                    if (lp.width != w || lp.height != h) {
                        lp.width = w
                        lp.height = h
                        view.layoutParams = lp
                    }
                    view.setImageDrawable(result)
                    onFramed(w, h)
                }
            })
            .build()
        view.context.imageLoader.enqueue(request)
    }

    private fun wirePhotoOpen(view: View, uri: android.net.Uri) {
        view.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                view.context.startActivity(intent)
            } catch (e: Exception) {
                GlassNotice.show(view.context, view.context.getString(R.string.toast_could_not_open_image))
            }
        }
        ViewCompat.replaceAccessibilityAction(
            view,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            view.context.getString(R.string.a11y_view_photo),
            null
        )
    }

    /** The caption wraps to the picture, but a very thin photo still gets a readable line. */
    private fun captionWidthForPhoto(photoW: Int, maxW: Int, density: Float): Int {
        val floor = (160 * density).toInt()
        return maxOf(photoW, floor).coerceAtMost(maxW)
    }

    private fun applyDpBox(view: View, box: ChatPhoto.DpBox) {
        val d = view.resources.displayMetrics.density
        view.setPadding(
            (box.start * d).toInt(),
            (box.top * d).toInt(),
            (box.end * d).toInt(),
            (box.bottom * d).toInt()
        )
    }

    // --- VIEW HOLDERS ---

    inner class UserViewHolder(itemView: View, private val markwon: Markwon) : RecyclerView.ViewHolder(itemView) {
        val messageTextView: TextView = itemView.findViewById(R.id.messageTextView)
        private val messageContainer: ConstraintLayout = itemView.findViewById(R.id.messageContainer)
        private val buttonContainer: LinearLayout = itemView.findViewById(R.id.buttonContainer)
        private val copyButtonuser: ImageButton = itemView.findViewById(R.id.copyButtonuser)
        private val resendButton: ImageButton = itemView.findViewById(R.id.resendButton)
        private val editButton: ImageButton = itemView.findViewById(R.id.editButton)
        private val imageView: ImageView = itemView.findViewById(R.id.userImageView)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)
        private val collapseToggleButton: ImageButton = itemView.findViewById(R.id.collapseToggleButton)
        private var actionsMsgKey: String = ""

        private fun applyActionsVisibility(expanded: Boolean, animate: Boolean) {
            val running = buttonContainer.getTag(R.id.tag_visibility_animator) != null
            if (!running && (buttonContainer.visibility == View.VISIBLE) == expanded) return
            if (animate) {
                // Height eases open, so the rows below glide instead of jumping a step.
                animateDisclosure(buttonContainer, expand = expanded)
            } else {
                (buttonContainer.getTag(R.id.tag_visibility_animator) as? android.animation.Animator)?.cancel()
                buttonContainer.alpha = 1f
                buttonContainer.translationY = 0f
                buttonContainer.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                buttonContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            }
        }

        private fun toggleActions() {
            if (actionsMsgKey.isEmpty()) return
            val next = !userActionsExpanded.contains(actionsMsgKey)
            if (next) userActionsExpanded.add(actionsMsgKey) else userActionsExpanded.remove(actionsMsgKey)
            applyActionsVisibility(next, animate = true)
            labelActionsClick(next)
        }

        /** A tap on the bubble opens or closes the action row; TalkBack says so instead of a bare "double tap to activate". */
        private fun labelActionsClick(expanded: Boolean) {
            ViewCompat.replaceAccessibilityAction(
                messageContainer,
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                itemView.context.getString(if (expanded) R.string.a11y_hide_message_actions else R.string.a11y_show_message_actions),
                null
            )
        }

        /** The picture stored on the message, when the file link is missing or will not open. */
        private fun showInlinePhoto(base64: String, tag: String, maxW: Int, maxH: Int, density: Float) {
            imageView.setOnClickListener(null)
            imageView.setImageDrawable(null)
            imageView.setTag(R.id.userImageView, tag)
            imageView.visibility = View.VISIBLE
            val maxEdge = itemView.resources.displayMetrics.widthPixels
            scope.launch {
                val bitmap = withContext(Dispatchers.Default) { decodeSampled(base64, maxEdge) }
                if (bitmap != null && imageView.getTag(R.id.userImageView) == tag) {
                    val (w, h) = ChatPhoto.frame(bitmap.width, bitmap.height, maxW, maxH)
                    imageView.layoutParams.width = w
                    imageView.layoutParams.height = h
                    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                    imageView.setImageBitmap(bitmap)
                    messageTextView.maxWidth = captionWidthForPhoto(w, maxW, density)
                }
            }
        }

        private fun hideUserPhoto() {
            imageView.visibility = View.GONE
            imageView.setTag(R.id.userImageView, null)
            imageView.setOnClickListener(null)
            messageTextView.maxWidth = Int.MAX_VALUE
        }

        fun bind(message: FlexibleMessage) {
            messageTextView.textSize = 16f * currentFontScale / 100f
            messageTextView.typeface = currentTypeface
            val rawUserContent = getMessageText(message.content)
            val pos = bindingAdapterPosition
            collapseToggleButton.visibility = View.GONE
            actionsMsgKey = rawUserContent.hashCode().toString() + "_" + (message.imageUri ?: "")
            applyActionsVisibility(userActionsExpanded.contains(actionsMsgKey), animate = false)
            labelActionsClick(userActionsExpanded.contains(actionsMsgKey))

            val tapToggle = View.OnClickListener { toggleActions() }
            messageContainer.setOnClickListener(tapToggle)
            messageTextView.setOnClickListener(tapToggle)

            if (pos >= 0 && message.role == "user") {
                val displayMetrics = itemView.resources.displayMetrics
                val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
                val isTablet = screenWidthDp >= 600
                val maxChars = if (isTablet) 300 else 150
                val msgKey = rawUserContent.hashCode().toString()
                val longMessage = UserMessageFold.isLong(rawUserContent, maxChars)
                val collapsed = collapsedStates.getOrDefault(msgKey, true)
                val displayContent = if (longMessage && collapsed) {
                    UserMessageFold.collapse(rawUserContent, maxChars)
                } else {
                    rawUserContent
                }
                setCachedUserMarkdown(messageTextView, displayContent)
                if (longMessage) {
                    collapseToggleButton.visibility = View.VISIBLE
                    collapseToggleButton.setImageResource(
                        if (collapsed) R.drawable.ic_msg_expand else R.drawable.ic_msg_collapse
                    )
                    collapseToggleButton.contentDescription = itemView.context.getString(
                        if (collapsed) R.string.cd_show_more else R.string.cd_show_less
                    )
                    collapseToggleButton.setOnClickListener {
                        val current = bindingAdapterPosition
                        if (current == RecyclerView.NO_POSITION) return@setOnClickListener
                        collapsedStates[msgKey] = !collapsed
                        this@ChatAdapter.notifyItemChanged(current)
                        onCollapse()
                    }
                } else {
                    collapseToggleButton.setOnClickListener(null)
                }
            } else {
                setCachedUserMarkdown(messageTextView, rawUserContent)
            }

            // A data URL is not a file. The picture then comes from the message itself,
            // which is also how a photo shows after its file is gone.
            val fileUri = message.imageUri?.takeUnless { it.startsWith("data:") }.orEmpty()
            val inline = getImageBase64(message.content)
            val d = itemView.resources.displayMetrics.density
            val maxW = (240 * d).toInt()
            val maxH = (300 * d).toInt()
            if (fileUri.isNotEmpty() || inline != null) {
                // Until the picture's own width is known, don't let the caption blow the bubble out past the cap.
                messageTextView.maxWidth = maxW
                if (fileUri.isNotEmpty()) {
                    try {
                        val userImageUri = fileUri.toUri()
                        loadFramedPhoto(imageView, userImageUri, R.id.userImageView, fileUri, maxW, maxH) { w, _ ->
                            messageTextView.maxWidth = captionWidthForPhoto(w, maxW, d)
                        }
                        wirePhotoOpen(imageView, userImageUri)
                    } catch (e: Exception) {
                        if (inline != null) showInlinePhoto(inline, fileUri, maxW, maxH, d)
                        else hideUserPhoto()
                    }
                } else {
                    showInlinePhoto(inline, "inline:${inline.hashCode()}", maxW, maxH, d)
                }
            } else {
                hideUserPhoto()
            }
            // A photo sent on its own is just the picture, in a slim frame. With a caption, the
            // picture keeps a 4dp rim and the words stay on the same inset as a text bubble.
            val hasPhoto = imageView.visibility == View.VISIBLE
            val photoOnly = hasPhoto && rawUserContent.isBlank()
            messageTextView.visibility = if (photoOnly) View.GONE else View.VISIBLE
            applyDpBox(messageContainer, ChatPhoto.containerInsets(hasPhoto, photoOnly))
            applyDpBox(messageTextView, ChatPhoto.captionInsets(hasPhoto, photoOnly))

            copyButtonuser.setOnClickListener {
                val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Copied Text", rawUserContent)
                clipboard.setPrimaryClip(clip)
                Haptics.tap(copyButtonuser, android.view.HapticFeedbackConstants.CONFIRM)
                CopyFeedbackAnimator.play(copyButtonuser)
            }
            copyButtonuser.setOnLongClickListener {
                val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Copied Markdown", rawUserContent)
                clipboard.setPrimaryClip(clip)
                Haptics.tap(copyButtonuser, android.view.HapticFeedbackConstants.CONFIRM)
                CopyFeedbackAnimator.play(copyButtonuser)
                true
            }
            editButton.setOnClickListener {
                // A photo with no caption is still a scene beat. Edit puts the picture back.
                if (rawUserContent.isNotBlank() || fileUri.isNotEmpty() || inline != null) {
                    Haptics.tap(editButton)
                    onEditMessage(bindingAdapterPosition, rawUserContent)
                }
            }
            // Regenerated from AI row now; keep listener no-op for ID stability
            resendButton.setOnClickListener(null)
            deleteButton.setOnClickListener {
                Haptics.tap(deleteButton)
                onDeleteMessage(bindingAdapterPosition)
            }
        }
    }

    inner class AssistantViewHolder(
        itemView: View,
        private val markwon: Markwon,
        private val onSpeakText: (String, Int) -> Unit,
        private val onSynthesizeToWavFile: (String, Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        val messageTextView: TextView = itemView.findViewById(R.id.messageTextView)
        private val copyButton: ImageButton = itemView.findViewById(R.id.copyButton)
        val ttsButton: ImageButton = itemView.findViewById(R.id.ttsButton)
        private val regenerateButton: ImageButton = itemView.findViewById(R.id.regenerateButton)
        private val moreActionsButton: ImageButton = itemView.findViewById(R.id.moreActionsButton)

        /** ⋮ after Regenerate: Read aloud, Rewrite (any Roleplay reply) and Edit. */
        private fun bindMoreActions(speakingHere: Boolean, canRewrite: Boolean) {
            val ctx = itemView.context
            val rows = buildList {
                if (ttsAvailable) add(MessageMenu.Item(
                    ctx.getString(if (speakingHere) R.string.msg_menu_stop_reading else R.string.msg_menu_read),
                    if (speakingHere) R.drawable.ic_msg_stop else R.drawable.ic_msg_speak,
                ) { ttsButton.performClick() })
                if (canRewrite) add(MessageMenu.Item(ctx.getString(R.string.msg_menu_instruct), R.drawable.ic_msg_instruct) {
                    instructButton.performClick()
                })
                add(MessageMenu.Item(ctx.getString(R.string.msg_menu_edit), R.drawable.ic_msg_edit) {
                    editButton.performClick()
                })
            }
            moreActionsButton.setOnClickListener { onMessageMenu?.invoke(moreActionsButton, rows) }
        }
        private val instructButton: ImageButton = itemView.findViewById(R.id.instructButton)
        private val generatedImageView: ImageView = itemView.findViewById(R.id.generatedImageView)
        val messageContainer: ConstraintLayout = itemView.findViewById(R.id.messageContainer)
        private var pulseAnimator: ObjectAnimator? = null
        private var bgColorAnimator: ObjectAnimator? = null
        private val aiActionRow: View = itemView.findViewById(R.id.aiActionRow)
        private val editButton: ImageButton = itemView.findViewById(R.id.editButton)
        private val reasoningBlock: View = itemView.findViewById(R.id.reasoningBlock)
        private val reasoningHeader: View = itemView.findViewById(R.id.reasoningHeader)
        private val reasoningChevron: ImageView = itemView.findViewById(R.id.reasoningChevron)
        private val reasoningTitle: TextView = itemView.findViewById(R.id.reasoningTitle)
        private val reasoningTextView: TextView = itemView.findViewById(R.id.reasoningTextView)
        private val thinkingRow: View = itemView.findViewById(R.id.thinkingRow)
        private val forkNavigator: View = itemView.findViewById(R.id.forkNavigator)
        private val forkPrev: ImageButton = itemView.findViewById(R.id.forkPrev)
        private val forkNext: ImageButton = itemView.findViewById(R.id.forkNext)
        private val forkLabel: TextView = itemView.findViewById(R.id.forkLabel)
        private val rpSpeakerHeader: View = itemView.findViewById<View>(R.id.rpSpeakerHeader).also {
            it.setOnClickListener { onSpeakerClick?.invoke() }
        }
        private val rpSpeakerAvatar: ImageView = itemView.findViewById(R.id.rpSpeakerAvatar)
        private val rpSpeakerNameView: TextView = itemView.findViewById(R.id.rpSpeakerName)
        private var rpBubbleLayoutApplied: Boolean? = null
        /** The bubble look last set on [messageContainer], so a rebind only touches it when it changes. */
        private var bubbleLookApplied: Boolean? = null

        private val thinkingLabel: TextView = itemView.findViewById(R.id.thinkingLabel)

        // The "Thinking" label glints while the model works.
        private fun startThinkingBars() {
            ShimmerText.post(thinkingLabel, ShimmerText.highlightFor(thinkingLabel))
        }

        private fun stopThinkingBars() {
            ShimmerText.stop(thinkingLabel)
        }

        /** Items of the action row in reading order: the icons, then the fork navigator. */
        private fun actionItems(row: View, visibleOnly: Boolean = true): List<View> {
            val buttons = row.findViewById<ViewGroup>(R.id.aiActionButtons)
            val icons = (0 until buttons.childCount).map { buttons.getChildAt(it) }
            val rest = (row as ViewGroup).let { g -> (0 until g.childCount).map { g.getChildAt(it) } }
                .filter { it !is android.widget.HorizontalScrollView }
            return (icons + rest).filter { !visibleOnly || it.visibility == View.VISIBLE }
        }

        /** The finished reply's tools fade up out of nothing, one after another, left to right; nothing slides. */
        private fun revealActions(row: View) {
            actionItems(row).forEachIndexed { i, v ->
                v.animate().cancel()
                v.alpha = 0f
                v.translationX = 0f
                v.animate().alpha(1f)
                    .setStartDelay(i * ACTION_STAGGER_MS)
                    .setDuration(300)
                    .setInterpolator(Motion.easeOut)
                    .start()
            }
        }

        private fun settleActions(row: View) {
            actionItems(row, visibleOnly = false).forEach { v ->
                v.animate().cancel()
                v.alpha = 1f
                v.translationX = 0f
            }
        }

        private fun bindThinkingState(isThinking: Boolean) {
            if (isThinking) {
                thinkingRow.visibility = View.VISIBLE
                messageContainer.visibility = View.GONE
                rpSpeakerHeader.visibility = View.GONE
                startThinkingBars()
            } else {
                thinkingRow.visibility = View.GONE
                messageContainer.visibility = View.VISIBLE
                stopThinkingBars()
            }
        }

        private fun bindRpSpeakerHeader(isThinking: Boolean) {
            val name = rpSpeakerName
            if (!isRpMode || isThinking || name.isNullOrBlank() || rpLayout == SharedPreferencesHelper.RP_LAYOUT_BOOK) {
                rpSpeakerHeader.visibility = View.GONE
                return
            }
            rpSpeakerHeader.visibility = View.VISIBLE
            rpSpeakerNameView.text = name
            val model: Any? = when {
                !rpSpeakerAvatarUri.isNullOrBlank() -> rpSpeakerAvatarUri
                rpSpeakerAvatarFile?.exists() == true -> rpSpeakerAvatarFile
                else -> null
            }
            if (model != null) {
                val request = ImageRequest.Builder(itemView.context)
                    .data(model)
                    .transformations(coil.transform.CircleCropTransformation())
                    .target(rpSpeakerAvatar)
                if (model is File) {
                    request.memoryCacheKey("rp-avatar-${model.absolutePath}-${model.lastModified()}")
                    request.diskCacheKey("rp-avatar-${model.absolutePath}-${model.lastModified()}")
                }
                // The placeholder mark is tinted mute; a photo must not be.
                ImageViewCompat.setImageTintList(rpSpeakerAvatar, null)
                itemView.context.imageLoader.enqueue(request.build())
            } else {
                rpSpeakerAvatar.dispose()
                ImageViewCompat.setImageTintList(
                    rpSpeakerAvatar, ColorStateList.valueOf(ContextCompat.getColor(itemView.context, R.color.xai_mute))
                )
                rpSpeakerAvatar.setImageResource(R.drawable.ic_gradation_mark)
            }
        }

        /**
         * Bubbles keep the classic header (avatar + name above), just with a larger mark and
         * more air before the bubble. Gap must live on the bubble's topMargin — ConstraintLayout
         * ignores the header's bottomMargin when the header has no bottom constraint.
         */
        private fun applyRpBubbleLayout(bubble: Boolean) {
            if (rpBubbleLayoutApplied == bubble) return
            rpBubbleLayoutApplied = bubble
            val d = itemView.resources.displayMetrics.density
            val headerLp = rpSpeakerHeader.layoutParams as ConstraintLayout.LayoutParams
            val msgLp = messageContainer.layoutParams as ConstraintLayout.LayoutParams
            val actionRow = aiActionRow
            val actionLp = actionRow.layoutParams as ConstraintLayout.LayoutParams

            rpSpeakerNameView.visibility = View.VISIBLE
            val avatarEdge = ((if (bubble) 40 else 36) * d).toInt()
            val avatarLp = rpSpeakerAvatar.layoutParams
            if (avatarLp.width != avatarEdge || avatarLp.height != avatarEdge) {
                avatarLp.width = avatarEdge
                avatarLp.height = avatarEdge
                rpSpeakerAvatar.layoutParams = avatarLp
            }

            headerLp.width = 0
            headerLp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            headerLp.bottomMargin = 0

            msgLp.width = if (bubble) ViewGroup.LayoutParams.WRAP_CONTENT else 0
            msgLp.constrainedWidth = bubble
            msgLp.startToEnd = ConstraintLayout.LayoutParams.UNSET
            msgLp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            msgLp.topToTop = ConstraintLayout.LayoutParams.UNSET
            msgLp.topToBottom = R.id.rpSpeakerHeader
            msgLp.topMargin = (6 * d).toInt()
            msgLp.marginStart = 0
            msgLp.horizontalBias = 0f

            actionLp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID

            rpSpeakerHeader.layoutParams = headerLp
            messageContainer.layoutParams = msgLp
            actionRow.layoutParams = actionLp
        }

        private fun bindForkNavigator(position: Int) {
            if (isRpMode) {
                forkNavigator.visibility = View.GONE
                return
            }
            val forkNav = forkNavStateForPosition(position)
            if (forkNav == null) {
                forkNavigator.visibility = View.GONE
                return
            }
            forkNavigator.visibility = View.VISIBLE
            forkLabel.text = "${forkNav.variantIndex} / ${forkNav.totalVariants}"
            forkPrev.isEnabled = forkNav.canGoPrev
            forkNext.isEnabled = forkNav.canGoNext
            forkPrev.alpha = if (forkNav.canGoPrev) 1f else 0.35f
            forkNext.alpha = if (forkNav.canGoNext) 1f else 0.35f
            forkPrev.setOnClickListener {
                if (forkNav.canGoPrev) onForkNavigate(-1)
            }
            forkNext.setOnClickListener {
                if (forkNav.canGoNext) onForkNavigate(1)
            }
        }

        private fun bindReasoning(message: FlexibleMessage, streaming: Boolean) {
            (reasoningTextView.getTag(R.id.tag_visibility_animator) as? android.animation.Animator)?.cancel()
            reasoningTextView.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            reasoningTextView.alpha = 1f
            val reasoning = reasoningSource(message)
            if (reasoning.isBlank() || !showThinking) {
                ShimmerText.stop(reasoningTitle)
                reasoningBlock.animate().cancel()
                reasoningBlock.alpha = 1f
                reasoningBlock.translationY = 0f
                reasoningBlock.visibility = View.GONE
                reasoningTextView.visibility = View.GONE
                return
            }
            if (reasoningBlock.visibility != View.VISIBLE && streaming && itemView.isAttachedToWindow &&
                Motion.areAnimationsEnabled(itemView.context)
            ) {
                // The line glints in as the model starts thinking instead of popping.
                reasoningBlock.alpha = 0f
                reasoningBlock.translationY = -4f * itemView.resources.displayMetrics.density
                reasoningBlock.animate().alpha(1f).translationY(0f)
                    .setDuration(320).setInterpolator(Motion.iosOut).start()
            }
            reasoningBlock.visibility = View.VISIBLE
            val stillThinking = streaming && getMessageText(message.content).isBlank()
            reasoningTitle.text = if (stillThinking) {
                itemView.context.getString(R.string.thinking_label)
            } else {
                itemView.context.getString(R.string.thinking_label_idle)
            }
            if (stillThinking) {
                if (reasoningTitle.getTag(R.id.tag_shimmer_animator) == null) {
                    ShimmerText.post(reasoningTitle, ShimmerText.highlightFor(reasoningTitle))
                }
            } else {
                ShimmerText.stop(reasoningTitle)
            }
            // Keyed on the opening of the thoughts so the key holds while they stream in.
            val key = "reasoning_${reasoning.take(REASONING_KEY_CHARS).hashCode()}"
            // Folded, streaming or not: the reply is the point; a tap opens the thoughts.
            val defaultCollapsed = true
            val collapsed = collapsedStates.getOrDefault(key, defaultCollapsed)
            reasoningTextView.text = reasoning
            reasoningTextView.visibility = if (collapsed) View.GONE else View.VISIBLE
            reasoningChevron.setImageResource(
                if (collapsed) R.drawable.ic_msg_expand else R.drawable.ic_msg_collapse
            )
            reasoningHeader.setOnClickListener {
                val next = !collapsedStates.getOrDefault(key, defaultCollapsed)
                collapsedStates[key] = next
                animateDisclosure(reasoningTextView, expand = !next)
                reasoningChevron.setImageResource(
                    if (next) R.drawable.ic_msg_expand else R.drawable.ic_msg_collapse
                )
                onCollapse()
            }
        }

        private var fadeTicker: android.view.Choreographer.FrameCallback? = null

        private fun ensureFadeTicker() {
            if (fadeTicker != null) return
            val ticker = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNs: Long) {
                    if (!messageTextView.isAttachedToWindow) {
                        fadeTicker = null
                        return
                    }
                    val text = messageTextView.text
                    val fades = if (text is android.text.Spanned) {
                        text.getSpans(0, text.length, StreamFadeSpan::class.java)
                    } else emptyArray()
                    // Word fades need every frame; once they are all done there is nothing left to tick.
                    if (fades.isEmpty() || fades.all { it.isDone() }) {
                        fadeTicker = null
                        if (text is android.text.Spannable) {
                            fades.forEach { text.removeSpan(it) }
                        }
                        return
                    }
                    messageTextView.invalidate()
                    android.view.Choreographer.getInstance().postFrameCallback(this)
                }
            }
            fadeTicker = ticker
            android.view.Choreographer.getInstance().postFrameCallback(ticker)
        }

        fun renderStreamFrame(displayed: String) {
            // Thinking is over once the answer itself shows: the label stops glinting and settles.
            if (displayed.isNotBlank() && (reasoningTitle.getTag(R.id.tag_shimmer_animator) != null ||
                    reasoningTitle.getTag(R.id.tag_shimmer_pending) == true)
            ) {
                ShimmerText.stop(reasoningTitle)
                reasoningTitle.text = itemView.context.getString(R.string.thinking_label_idle)
            }
            pulseAnimator?.cancel()
            pulseAnimator = null
            messageContainer.alpha = 1f
            try {
                // No cursor glyph: new words ease in on their own (Claude-style), and nothing
                // hops from line end to line end while the reply flows.
                val spanned = streamMarkdown.render(displayed)
                applyStreamFades(spanned, android.os.SystemClock.uptimeMillis())
                messageTextView.setText(spanned, TextView.BufferType.SPANNABLE)
                if (streamAnimated) ensureFadeTicker()
            } catch (_: Exception) {
                messageTextView.text = displayed
            }
        }

        fun bindTextOnly(message: FlexibleMessage) {
            attachStreamRevealHolder(this)
            // Hold the row's space while streaming so the finished reply doesn't jump a step.
            aiActionRow.visibility = View.INVISIBLE
            val text = getMessageText(message.content)

            if (ThinkingPlaceholder.matches(text) || text.isBlank()) {
                bindReasoning(message, streaming = true)
                if (ThinkingPlaceholder.matches(text)) {
                    resetStreamRender()
                    messageTextView.text = ""
                    bindThinkingState(true)
                    pulseAnimator?.cancel()
                    pulseAnimator = null
                    messageContainer.alpha = 1f
                } else {
                    bindThinkingState(false)
                    messageTextView.text = ""
                }
                return
            }

            bindThinkingState(false)
            streamReveal.setTarget(text)
            if (streamReveal.displayed().isEmpty()) {
                bindReasoning(message, streaming = true)
            }
        }

        fun bind(message: FlexibleMessage, position: Int, isSpeaking: Boolean, currentPosition: Int) {
            if (streamRevealBoundHolder === this) {
                streamRevealBoundHolder = null
            }
            resetStreamRender()
            messageTextView.textSize = 16f * currentFontScale / 100f
            messageTextView.typeface = currentTypeface

            bindReasoning(message, streaming = false)

            val text = visibleText(message)
            val isThinking = ThinkingPlaceholder.matches(text)

            if (!isThinking) {
                val finalContent = getPreRenderedContent(message)
                markwon.setParsedMarkdown(messageTextView, finalContent as android.text.Spanned)
            } else {
                messageTextView.text = ""
            }

            // Replies always show in full; the fold-long-answers toggle is gone.
            messageTextView.maxLines = Int.MAX_VALUE
            messageTextView.ellipsize = null

            // 3. UI STATE LOGIC
            // Read aloud lives in the ⋮ menu now; the button only carries its actions.
            ttsButton.visibility = View.GONE

            val isError = message.role == "assistant" && isRpErrorText(text)

            // Copy, share, regenerate and the rest only make sense on a finished reply: the
            // row stays away while this one is still streaming and fades in once it lands.
            val actionRow = aiActionRow
            val streamingHere = replyInFlight && position == messages.lastIndex
            val showActions = !isThinking && !streamingHere
            if (showActions && actionRow.visibility != View.VISIBLE && itemView.isAttachedToWindow &&
                Motion.areAnimationsEnabled(itemView.context)
            ) {
                // Posted: the rest of bind still decides which icons this reply gets.
                actionRow.alpha = 0f
                actionRow.visibility = View.VISIBLE
                actionRow.post {
                    actionRow.alpha = 1f
                    revealActions(actionRow)
                }
            } else if (!showActions || actionRow.visibility != View.VISIBLE) {
                actionRow.alpha = 1f
                settleActions(actionRow)
                actionRow.visibility = when {
                    showActions -> View.VISIBLE
                    streamingHere -> View.INVISIBLE
                    else -> View.GONE
                }
            }

            bindThinkingState(isThinking)
            bindRpSpeakerHeader(isThinking)

            val bubble = isRpMode && rpLayout == SharedPreferencesHelper.RP_LAYOUT_BUBBLES && !isThinking
            if (bubbleLookApplied != bubble) {
                // Swapping the background drawable and padding re-lays the row out: only on a change.
                bubbleLookApplied = bubble
                val d = itemView.resources.displayMetrics.density
                if (bubble) {
                    messageContainer.setBackgroundResource(R.drawable.bg_rp_bubble)
                    messageContainer.setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
                } else {
                    messageContainer.setBackgroundResource(R.drawable.bg_ai_message)
                    val p = (4 * d).toInt()
                    messageContainer.setPadding(p, p, p, p)
                }
            }
            applyRpBubbleLayout(bubble)
            if (isError) {
                messageTextView.setTextColor(ContextCompat.getColor(itemView.context, R.color.xai_error))
            } else {
                // Restore after recycled error rows; Markwon spans still override for links.
                messageTextView.setTextColor(ContextCompat.getColor(itemView.context, R.color.xai_ink))
            }

            // 4. ANIMATIONS — thinking bars handled in bindThinkingState
            pulseAnimator?.cancel()
            bgColorAnimator?.cancel()
            pulseAnimator = null
            bgColorAnimator = null
            if (!isThinking) {
                messageContainer.alpha = 1f
            }

            // 5. IMAGE LOADING
            val generatedUriStr = message.imageUri
            if (!generatedUriStr.isNullOrEmpty()) {
                try {
                    val generatedUri = generatedUriStr.toUri()
                    val d = itemView.resources.displayMetrics.density
                    loadFramedPhoto(
                        generatedImageView, generatedUri, R.id.generatedImageView, generatedUriStr,
                        (280 * d).toInt(), (300 * d).toInt()
                    )
                    wirePhotoOpen(generatedImageView, generatedUri)
                } catch (e: Exception) {
                    generatedImageView.visibility = View.GONE
                }
            } else {
                generatedImageView.visibility = View.GONE
                generatedImageView.setTag(R.id.generatedImageView, null)
            }

            // 6. BUTTON LISTENERS
            editButton.setOnClickListener {
                // Edit the visible reply only — do not bake reasoning into content/swipe alts.
                onEditAssistantMessage(bindingAdapterPosition, ensureTableSpacing(text))
            }
            regenerateButton.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos <= 0 || pos >= messages.size) return@setOnClickListener
                val prev = messages[pos - 1]
                if (prev.role == "user") {
                    Haptics.tap(regenerateButton)
                    onRedoMessage(pos - 1, prev.content)
                }
            }
            instructButton.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos < 0 || pos >= messages.size) return@setOnClickListener
                onInstructMessage(pos)
            }
            // One pass from the end finds both: the last assistant and the last user turn.
            var lastAssistantIndex = -1
            var lastUserIndex = -1
            for (i in messages.indices.reversed()) {
                val role = messages[i].role
                if (lastAssistantIndex < 0 && role == "assistant") lastAssistantIndex = i
                if (lastUserIndex < 0 && role == "user") lastUserIndex = i
                if (lastAssistantIndex >= 0 && lastUserIndex >= 0) break
            }
            // Regen only when there is an assistant reply after the last user turn
            // (not the opening greeting after a cancelled request). Error bubbles still allow retry.
            instructButton.visibility = View.GONE
            regenerateButton.visibility = if (
                position > 0 &&
                position < messages.size &&
                messages[position - 1].role == "user" &&
                !isThinking &&
                (!isRpMode || (position == lastAssistantIndex && position > lastUserIndex)) &&
                (!isError || isRpMode)
            ) View.VISIBLE else View.GONE
            copyButton.setOnClickListener {
                val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Copied Text", messageTextView.text.toString())
                clipboard.setPrimaryClip(clip)
                Haptics.tap(copyButton, android.view.HapticFeedbackConstants.CONFIRM)
                CopyFeedbackAnimator.play(copyButton)
            }

            copyButton.setOnLongClickListener {
                val reasoning = reasoningSource(message).let { if (it.isBlank()) "" else "\n\n$it" }
                val fullRawMarkdown = ensureTableSpacing(reasoning + text)
                val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Copied Markdown", fullRawMarkdown)
                clipboard.setPrimaryClip(clip)
                Haptics.tap(copyButton, android.view.HapticFeedbackConstants.CONFIRM)
                CopyFeedbackAnimator.play(copyButton)
                true
            }

            val iconRes = if (isSpeaking && position == currentPosition) {
                R.drawable.ic_msg_stop
            } else {
                R.drawable.ic_msg_speak
            }
            ttsButton.setImageResource(iconRes)
            bindMoreActions(
                speakingHere = isSpeaking && position == currentPosition,
                // Any finished reply, the greeting included; an error bubble has nothing to rewrite.
                canRewrite = isRpMode && !isThinking && !isError && text.isNotBlank(),
            )

            ttsButton.setOnClickListener {
                val textToSpeak = messageTextView.text.toString()
                if (textToSpeak.isNotEmpty()) {
                    ForegroundService.stopTtsSpeaking()
                    onSpeakText(textToSpeak, position)
                } else {
                    GlassNotice.show(itemView.context, itemView.context.getString(R.string.toast_no_text_speak))
                }
            }

            ttsButton.setOnLongClickListener {
                val textToSpeak = messageTextView.text.toString()
                if (textToSpeak.isNotEmpty()) {
                    ForegroundService.stopTtsSpeaking()
                    onSynthesizeToWavFile(textToSpeak, position)
                } else {
                    GlassNotice.show(itemView.context, itemView.context.getString(R.string.toast_no_text_save))
                }
                true
            }

            bindForkNavigator(position)
        }

        internal fun stopPulse() {
            fadeTicker?.let { android.view.Choreographer.getInstance().removeFrameCallback(it) }
            fadeTicker = null
            pulseAnimator?.cancel()
            bgColorAnimator?.cancel()
            pulseAnimator = null
            bgColorAnimator = null
            stopThinkingBars()
            ShimmerText.stop(reasoningTitle)
            thinkingRow.visibility = View.GONE
            messageContainer.visibility = View.VISIBLE
            messageContainer.alpha = 1f
            messageContainer.clearAnimation()
        }

        private fun isRpErrorText(text: String): Boolean =
            text.startsWith("**Error:**")
    }
    inner class HiddenViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}