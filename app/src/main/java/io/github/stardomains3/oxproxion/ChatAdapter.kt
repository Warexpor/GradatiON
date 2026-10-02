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
    /** (position, direction). Chat steps its branch; Roleplay steps that earlier reply's versions. */
    private val onForkNavigate: (Int, Int) -> Unit

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
    /**
     * False after the transcript is replaced. A frame already queued for the old tail
     * must not be written onto whatever row is last now.
     */
    private var acceptStreamFrames = false
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
                if (!acceptStreamFrames || messages.isEmpty()) continue
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
            acceptStreamFrames = false
            applyEditUpdate(newMessages)
            return // Stop here, don't run the rest
        }
        // Clear cache if loading a fresh list or switching chats
        if (newMessages.isEmpty() || (messages.isEmpty() && newMessages.isNotEmpty())) {
            renderCache.clear()
        }

        if (newMessages.isEmpty()) {
            acceptStreamFrames = false
            messages.clear()
            resetStreamRender()
            streamRevealBoundHolder = null
            notifyDataSetChanged()
            return
        }

        // PERFECT CASE: Only 1 new message added
        if (messages.size == newMessages.size - 1 &&
            sameMessages(messages, newMessages, messages.size)) {
            acceptStreamFrames = false
            addMessage(newMessages.last())
            return
        }

        // STREAMING CASE: Same size, only last message content changed.
        // Identity first: a stream copies the list but keeps the earlier message objects.
        if (messages.size == newMessages.size && messages.isNotEmpty() &&
            sameMessages(messages, newMessages, messages.size - 1)) {
            acceptStreamFrames = true
            updateLastMessage(newMessages.last())
            return
        }

        // Fallback: Full refresh. A shorter list is a cut: drop a reveal that belonged to the tail.
        val shrunk = newMessages.size < messages.size
        acceptStreamFrames = false
        if (shrunk) {
            pendingStreamFinalize = false
            finalizeToken++
            resetStreamRender()
            streamRevealBoundHolder = null
        }
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
        /** Only the versions control of a reply changed. */
        const val RP_VERSIONS_PAYLOAD = "RP_VERSIONS"
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
            if (payloads.all { it == RP_VERSIONS_PAYLOAD }) {
                (holder as? AssistantViewHolder)?.bindVersionsOnly(position)
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
        onFailed: () -> Unit = {},
    ) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        if (view.getTag(tagKey) == tag && view.drawable != null && view.layoutParams.width > 0) {
            view.visibility = View.VISIBLE
            onFramed(view.layoutParams.width, view.layoutParams.height)
            return
        }
        view.setTag(tagKey, tag)
        view.setImageDrawable(null)
        clearPhotoTap(view)
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

                override fun onError(error: Drawable?) {
                    if (view.getTag(tagKey) != tag) return
                    onFailed()
                }
            })
            .build()
        view.context.imageLoader.enqueue(request)
    }

    private fun clearPhotoTap(view: View) {
        view.setOnClickListener(null)
        ViewCompat.removeAccessibilityAction(
            view,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK.id,
        )
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

    /** Bubble chrome for a character reply. Classic ignores photo vs text; Bubbles do not. */
    private fun applyAssistantPhotoFrame(
        container: View,
        caption: View,
        bubble: Boolean,
        hasPhoto: Boolean,
        photoOnly: Boolean,
    ) {
        applyDpBox(container, ChatPhoto.assistantBubbleInsets(bubble, hasPhoto, photoOnly))
        applyDpBox(caption, if (bubble) ChatPhoto.captionInsets(hasPhoto, photoOnly) else ChatPhoto.DpBox(0, 0, 0, 0))
    }

    // --- VIEW HOLDERS ---

    inner class UserViewHolder(itemView: View, private val markwon: Markwon) : RecyclerView.ViewHolder(itemView) {
        val messageTextView: ChatTextView = itemView.findViewById(R.id.messageTextView)
        private val messageContainer: ConstraintLayout = itemView.findViewById(R.id.messageContainer)
        private val buttonContainer: LinearLayout = itemView.findViewById(R.id.buttonContainer)
        private val copyButtonuser: ImageButton = itemView.findViewById(R.id.copyButtonuser)
        private val resendButton: ImageButton = itemView.findViewById(R.id.resendButton)
        private val editButton: ImageButton = itemView.findViewById(R.id.editButton)
        private val imageView: ImageView = itemView.findViewById(R.id.userImageView)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)
        private val collapseToggleButton: TextView = itemView.findViewById(R.id.collapseToggleButton)
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
            val label = itemView.context.getString(
                if (expanded) R.string.a11y_hide_message_actions else R.string.a11y_show_message_actions
            )
            val click = AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK
            ViewCompat.replaceAccessibilityAction(messageContainer, click, label, null)
            ViewCompat.replaceAccessibilityAction(messageTextView, click, label, null)
        }

        /**
         * The words of the message, including when the bubble is folded. Opens the action
         * row so the copy check has somewhere to show.
         */
        private fun copyFullMessage(text: String) {
            val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Copied Text", text))
            if (actionsMsgKey.isNotEmpty() && !userActionsExpanded.contains(actionsMsgKey)) {
                userActionsExpanded.add(actionsMsgKey)
                applyActionsVisibility(true, animate = true)
                labelActionsClick(true)
            }
            Haptics.tap(copyButtonuser, android.view.HapticFeedbackConstants.CONFIRM)
            CopyFeedbackAnimator.play(copyButtonuser)
        }

        /** The picture stored on the message, when the file link is missing or will not open. */
        private fun showInlinePhoto(base64: String, tag: String, maxW: Int, maxH: Int, density: Float) {
            clearPhotoTap(imageView)
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
            imageView.setImageDrawable(null)
            clearPhotoTap(imageView)
            messageTextView.maxWidth = Int.MAX_VALUE
        }

        fun bind(message: FlexibleMessage) {
            messageTextView.textSize = 16f * currentFontScale / 100f
            messageTextView.typeface = currentTypeface
            val rawUserContent = getMessageText(message.content)
            val pos = bindingAdapterPosition
            collapseToggleButton.visibility = View.GONE
            val copy = if (pos >= 0 && message.role == "user") {
                UserMessageFold.earlierCopies(pos) { i ->
                    val other = messages[i]
                    other.role == "user" &&
                        getMessageText(other.content) == rawUserContent &&
                        other.imageUri == message.imageUri
                }
            } else {
                0
            }
            actionsMsgKey = UserMessageFold.rowKey(rawUserContent, message.imageUri, copy)
            applyActionsVisibility(userActionsExpanded.contains(actionsMsgKey), animate = false)
            labelActionsClick(userActionsExpanded.contains(actionsMsgKey))

            // A link's own click still runs. This one stays quiet when the finger is on that link.
            val tapToggle = View.OnClickListener {
                if (!messageTextView.gestureOnClickableSpan) toggleActions()
            }
            messageContainer.setOnClickListener(tapToggle)
            messageTextView.setOnClickListener(tapToggle)
            val longClick = AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK
            if (rawUserContent.isNotBlank()) {
                // Hold the words to copy them all. A hold on a link does not copy the message.
                messageTextView.setOnLongClickListener {
                    if (messageTextView.gestureOnClickableSpan) return@setOnLongClickListener false
                    copyFullMessage(rawUserContent)
                    true
                }
                ViewCompat.replaceAccessibilityAction(
                    messageTextView, longClick, itemView.context.getString(R.string.cd_copy_text), null
                )
            } else {
                messageTextView.setOnLongClickListener(null)
                messageTextView.isLongClickable = false
                ViewCompat.replaceAccessibilityAction(messageTextView, longClick, null, null)
            }

            if (pos >= 0 && message.role == "user") {
                val displayMetrics = itemView.resources.displayMetrics
                val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
                val isTablet = screenWidthDp >= 600
                val maxChars = if (isTablet) 300 else 150
                val longMessage = UserMessageFold.isLong(rawUserContent, maxChars)
                val collapsed = collapsedStates.getOrDefault(actionsMsgKey, true)
                val displayContent = if (longMessage && collapsed) {
                    UserMessageFold.collapse(rawUserContent, maxChars)
                } else {
                    rawUserContent
                }
                setCachedUserMarkdown(messageTextView, displayContent)
                if (longMessage) {
                    collapseToggleButton.visibility = View.VISIBLE
                    val label = itemView.context.getString(
                        if (collapsed) R.string.cd_show_more else R.string.cd_show_less
                    )
                    collapseToggleButton.text = label
                    collapseToggleButton.contentDescription = label
                    collapseToggleButton.setOnClickListener {
                        val current = bindingAdapterPosition
                        if (current == RecyclerView.NO_POSITION) return@setOnClickListener
                        collapsedStates[actionsMsgKey] = !collapsed
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
            // which is also how a photo shows after its file is gone. The tap that opens
            // the file is attached only after that file has drawn. A failure falls back
            // to the stored JPEG, or drops the frame when there is nothing to show.
            val fileUri = message.imageUri?.takeUnless { it.startsWith("data:") }.orEmpty()
            val inline = getImageBase64(message.content)
            val d = itemView.resources.displayMetrics.density
            val maxW = (240 * d).toInt()
            val maxH = (300 * d).toInt()
            fun applyFrame() {
                val hasPhoto = imageView.visibility == View.VISIBLE
                val photoOnly = hasPhoto && rawUserContent.isBlank()
                messageTextView.visibility = if (photoOnly) View.GONE else View.VISIBLE
                applyDpBox(messageContainer, ChatPhoto.containerInsets(hasPhoto, photoOnly))
                applyDpBox(messageTextView, ChatPhoto.captionInsets(hasPhoto, photoOnly))
            }
            if (fileUri.isNotEmpty() || inline != null) {
                // Until the picture's own width is known, don't let the caption blow the bubble out past the cap.
                messageTextView.maxWidth = maxW
                if (fileUri.isNotEmpty()) {
                    try {
                        val userImageUri = fileUri.toUri()
                        val inlineTag = "inline:${inline?.hashCode()}"
                        loadFramedPhoto(
                            imageView, userImageUri, R.id.userImageView, fileUri, maxW, maxH,
                            onFramed = { w, _ ->
                                messageTextView.maxWidth = captionWidthForPhoto(w, maxW, d)
                                wirePhotoOpen(imageView, userImageUri)
                            },
                            onFailed = {
                                if (inline != null) showInlinePhoto(inline, inlineTag, maxW, maxH, d)
                                else hideUserPhoto()
                                applyFrame()
                            },
                        )
                    } catch (e: Exception) {
                        if (inline != null) showInlinePhoto(inline, fileUri, maxW, maxH, d)
                        else hideUserPhoto()
                    }
                } else if (inline != null) {
                    showInlinePhoto(inline, "inline:${inline.hashCode()}", maxW, maxH, d)
                }
            } else {
                hideUserPhoto()
            }
            // A photo sent on its own is just the picture, in a slim frame. With a caption, the
            // picture keeps a 4dp rim and the words stay on the same inset as a text bubble.
            applyFrame()

            copyButtonuser.setOnClickListener { copyFullMessage(rawUserContent) }
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

        /** The reply as words. Skips the language name and padding painted into a code card. */
        private fun replyPlainText(): String = ChatMarkdown.readable(messageTextView.text)

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

        fun bindVersionsOnly(position: Int) = bindForkNavigator(position)

        private fun bindForkNavigator(position: Int) {
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
                if (forkNav.canGoPrev) onForkNavigate(bindingAdapterPosition, -1)
            }
            forkNext.setOnClickListener {
                if (forkNav.canGoNext) onForkNavigate(bindingAdapterPosition, 1)
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
                // Swapping the background drawable re-lays the row: only on a layout change.
                // Padding follows the photo below — a pictured bubble must not keep the text inset.
                bubbleLookApplied = bubble
                messageContainer.setBackgroundResource(
                    if (bubble) R.drawable.bg_rp_bubble else R.drawable.bg_ai_message
                )
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
            // The file is what a tap opens. The JPEG in the message is what shows when that
            // file is gone, the same way a photo you sent still shows.
            val inlineUrl = MessageContent.imageUrl(message.content)
            val source = ChatPhoto.bubbleSource(message.imageUri, inlineUrl)
            val d = itemView.resources.displayMetrics.density
            val maxW = (280 * d).toInt()
            val maxH = (300 * d).toInt()
            fun hideGenerated() {
                generatedImageView.visibility = View.GONE
                generatedImageView.setTag(R.id.generatedImageView, null)
                generatedImageView.setImageDrawable(null)
                clearPhotoTap(generatedImageView)
            }
            fun showEmbedded(dataUrl: String) {
                clearPhotoTap(generatedImageView)
                val tag = "inline:${dataUrl.hashCode()}"
                generatedImageView.setTag(R.id.generatedImageView, tag)
                generatedImageView.setImageDrawable(null)
                generatedImageView.visibility = View.VISIBLE
                val maxEdge = itemView.resources.displayMetrics.widthPixels
                scope.launch {
                    val bitmap = withContext(Dispatchers.Default) { ScenePhoto.bitmap(dataUrl, maxEdge) }
                    if (bitmap != null && generatedImageView.getTag(R.id.generatedImageView) == tag) {
                        val (w, h) = ChatPhoto.frame(bitmap.width, bitmap.height, maxW, maxH)
                        generatedImageView.layoutParams.width = w
                        generatedImageView.layoutParams.height = h
                        generatedImageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        generatedImageView.setImageBitmap(bitmap)
                    }
                }
            }
            when (source) {
                null -> hideGenerated()
                is ChatPhoto.BubbleSource.Embedded -> showEmbedded(source.dataUrl)
                is ChatPhoto.BubbleSource.File -> {
                    try {
                        val generatedUri = source.uri.toUri()
                        loadFramedPhoto(
                            generatedImageView, generatedUri, R.id.generatedImageView, source.uri,
                            maxW, maxH,
                            onFramed = { _, _ -> wirePhotoOpen(generatedImageView, generatedUri) },
                            onFailed = {
                                val embedded = inlineUrl?.takeIf { it.startsWith("data:image") }
                                if (embedded != null) {
                                    showEmbedded(embedded)
                                } else {
                                    hideGenerated()
                                    applyAssistantPhotoFrame(messageContainer, messageTextView, bubble, hasPhoto = false, photoOnly = false)
                                }
                            },
                        )
                    } catch (e: Exception) {
                        val embedded = inlineUrl?.takeIf { it.startsWith("data:image") }
                        if (embedded != null) {
                            showEmbedded(embedded)
                        } else {
                            hideGenerated()
                            applyAssistantPhotoFrame(messageContainer, messageTextView, bubble, hasPhoto = false, photoOnly = false)
                        }
                    }
                }
            }
            // Bubbles share the user photo frame. Classic stays a flat 4dp rim.
            // Re-apply every bind: recycling text→photo used to keep the 16dp text inset.
            val photoOnly = source != null && text.isBlank()
            if (!isThinking) {
                messageTextView.visibility = if (photoOnly) View.GONE else View.VISIBLE
            }
            applyAssistantPhotoFrame(messageContainer, messageTextView, bubble, hasPhoto = source != null, photoOnly = photoOnly)

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
                val clip = ClipData.newPlainText("Copied Text", replyPlainText())
                clipboard.setPrimaryClip(clip)
                Haptics.tap(copyButton, android.view.HapticFeedbackConstants.CONFIRM)
                CopyFeedbackAnimator.play(copyButton)
            }

            copyButton.setOnLongClickListener {
                val reasoning = reasoningSource(message).trim()
                val fullRawMarkdown = ensureTableSpacing(
                    if (reasoning.isEmpty()) text else "$reasoning\n\n$text"
                )
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
                val textToSpeak = replyPlainText()
                if (textToSpeak.isNotEmpty()) {
                    ForegroundService.stopTtsSpeaking()
                    onSpeakText(textToSpeak, position)
                } else {
                    GlassNotice.show(itemView.context, itemView.context.getString(R.string.toast_no_text_speak))
                }
            }

            ttsButton.setOnLongClickListener {
                val textToSpeak = replyPlainText()
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