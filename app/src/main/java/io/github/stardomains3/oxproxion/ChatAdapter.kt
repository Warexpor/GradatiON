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
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.os.StrictMode
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import com.google.android.material.snackbar.Snackbar
import io.noties.markwon.Markwon
import io.noties.markwon.utils.NoCopySpannableFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val onSaveMarkdown: (Int, String) -> Unit,
    private val onCaptureItemToBitmap: (Int, String) -> Unit,
    private val onShowMarkdown: (String) -> Unit,
    private val onSaveHtml: (String) -> Unit,
    private val onSaveText: (Int, String) -> Unit,
    private val onCollapse: () -> Unit,
    private val onSaveAsFile: (String) -> Unit,
    private val forkNavStateForPosition: (Int) -> ChatViewModel.ForkNavState?,
    private val onForkNavigate: (Int) -> Unit

) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var isRpMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (messages.isNotEmpty()) notifyDataSetChanged()
        }

    /** Shown above assistant bubbles in RP when a character is active. */
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

    private val noCopyFactory = NoCopySpannableFactory.getInstance()
    var isSpeaking = false
    var currentSpeakingPosition = -1
    private var currentTypeface: Typeface = Typeface.DEFAULT

    // OPTIMIZATION: Conflated Channel for throttling updates
    private val updateChannel = Channel<FlexibleMessage>(Channel.CONFLATED)
    private val messages = mutableListOf<FlexibleMessage>()
    private var isUserApplyingEdit: Boolean = false
    private var editTargetPosition: Int = -1
    private var currentFontScale: Int = 100
    private var streamRevealBoundHolder: AssistantViewHolder? = null
    private var pendingStreamFinalize: Boolean = false
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
                getPreRenderedContent(messages[lastIndex])
                // Let the last words finish fading in before swapping to the full render.
                val token = ++finalizeToken
                mainHandler.postDelayed({
                    if (token == finalizeToken && messages.size - 1 == lastIndex) {
                        notifyItemChanged(lastIndex)
                        onStreamVisualUpdate?.invoke()
                    }
                }, StreamFadeSpan.DURATION_MS)
            }
        }
    )
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var finalizeToken = 0

    // Streaming render state: cached closed blocks + per-chunk fade timestamps in rendered
    // coordinates, so each newly revealed run of words eases in on its own clock.
    private val streamMarkdown = IncrementalMarkdown(markwon, ::ensureTableSpacing)
    private val fadeStarts = ArrayList<Int>()
    private val fadeTimes = ArrayList<Long>()
    private var lastRenderedLen = 0

    private fun resetStreamRender() {
        streamReveal.reset()
        streamMarkdown.reset()
        fadeStarts.clear()
        fadeTimes.clear()
        lastRenderedLen = 0
    }

    private fun applyStreamFades(text: android.text.SpannableStringBuilder, now: Long) {
        val len = text.length
        if (len < lastRenderedLen) {
            while (fadeStarts.isNotEmpty() && fadeStarts.last() >= len) {
                fadeStarts.removeAt(fadeStarts.lastIndex)
                fadeTimes.removeAt(fadeTimes.lastIndex)
            }
        } else if (len > lastRenderedLen) {
            fadeStarts.add(lastRenderedLen)
            fadeTimes.add(now)
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
        streamReveal.setTarget(text)
        streamReveal.finishFast()
    }

    fun setMessages(newMessages: List<FlexibleMessage>) {
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
            messages == newMessages.dropLast(1)) {
            addMessage(newMessages.last())
            return
        }

        // STREAMING CASE: Same size, only last message content changed
        if (messages.size == newMessages.size &&
            messages.dropLast(1) == newMessages.dropLast(1)) {
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
            renderCache.remove(oldMessage) // Invalidate cache for the streaming message
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
    private fun getMessageText(content: JsonElement): String {
        if (content is JsonPrimitive) return content.content
        if (content is JsonArray) {
            return content.firstNotNullOfOrNull { item ->
                (item as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }?.get("text")?.jsonPrimitive?.content
            } ?: ""
        }
        return ""
    }

    private fun getImageBase64(content: JsonElement): String? {
        if (content is JsonArray) {
            return content.firstNotNullOfOrNull { item ->
                (item as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "image_url" }?.get("image_url")?.jsonObject?.get("url")?.jsonPrimitive?.content?.substringAfter(",")
            }
        }
        return null
    }

    // --- OPTIMIZED BAKING FUNCTION ---
    private fun getPreRenderedContent(message: FlexibleMessage): CharSequence {
        // 1. Check Cache
        if (renderCache.containsKey(message)) {
            return renderCache[message]!!
        }

        // 2. Extract Text (JSON Logic)
        val text = if (message.role == "assistant" && message.toolCalls != null && getMessageText(message.content).isBlank()) {
            // Show a clean, formatted indicator of what tool was used
            "🔧 **Tool Used:** ${message.toolCalls.map { it.function.name }.distinct().joinToString()}"
        } else {
            getMessageText(message.content)
        }

        // Reasoning lives in its own collapsible UI — do not bake it into the body.
        val rawText = text

        // 3. Run Regex (Expensive)
        val fullText = ensureTableSpacing(rawText)

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

        // 6. Save to Cache
        renderCache[message] = renderedContent

        return renderedContent
    }

    private fun ensureTableSpacing(md: String): String {
        val pattern = Regex(
            """(^[\t >]*([-+*]|\d+\.)\s+(?:\\\$\\\[ ?[ xX]?\\]\\\s+)?[^\n]*)\n(?=\|)""",
            RegexOption.MULTILINE
        )
        return md.replace(pattern) { "${it.value}\n\n" }
    }

    /** Strip legacy ``` fences / --- separators from stored reasoning for the dedicated UI. */
    private fun normalizeReasoning(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```").removePrefix("thinking").removePrefix("reasoning").trimStart('\n')
            val close = s.lastIndexOf("```")
            if (close >= 0) s = s.substring(0, close)
        }
        s = s.replace(Regex("""\n*-{3,}\n*$"""), "").trim()
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
        var contentText = getMessageText(message.content)

        if (message.role == "assistant" && message.toolCalls != null && contentText.isBlank()) {
            contentText = "🔧 **Tool Used:** ${message.toolCalls.map { it.function.name }.distinct().joinToString()}"
        }

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

    // --- VIEW HOLDERS ---

    inner class UserViewHolder(itemView: View, private val markwon: Markwon) : RecyclerView.ViewHolder(itemView) {
        private val messageTextView: TextView = itemView.findViewById(R.id.messageTextView)
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
            buttonContainer.animate().cancel()
            if (expanded) {
                if (buttonContainer.visibility == View.VISIBLE && buttonContainer.alpha >= 0.99f) return
                buttonContainer.visibility = View.VISIBLE
                if (animate && Motion.areAnimationsEnabled(itemView.context)) {
                    buttonContainer.alpha = 0f
                    buttonContainer.translationY = -6f
                    buttonContainer.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(180L)
                        .setInterpolator(Motion.easeOut)
                        .start()
                } else {
                    buttonContainer.alpha = 1f
                    buttonContainer.translationY = 0f
                }
            } else {
                if (buttonContainer.visibility != View.VISIBLE) return
                if (animate && Motion.areAnimationsEnabled(itemView.context)) {
                    buttonContainer.animate()
                        .alpha(0f)
                        .translationY(-6f)
                        .setDuration(150L)
                        .setInterpolator(Motion.easeOut)
                        .withEndAction {
                            buttonContainer.visibility = View.GONE
                            buttonContainer.translationY = 0f
                        }
                        .start()
                } else {
                    buttonContainer.visibility = View.GONE
                    buttonContainer.alpha = 0f
                    buttonContainer.translationY = 0f
                }
            }
        }

        private fun toggleActions() {
            if (actionsMsgKey.isEmpty()) return
            val next = !userActionsExpanded.contains(actionsMsgKey)
            if (next) userActionsExpanded.add(actionsMsgKey) else userActionsExpanded.remove(actionsMsgKey)
            applyActionsVisibility(next, animate = true)
        }

        fun bind(message: FlexibleMessage) {
            messageTextView.textSize = 16f * currentFontScale / 100f
            messageTextView.typeface = currentTypeface
            val rawUserContent = getMessageText(message.content)
            val pos = bindingAdapterPosition
            collapseToggleButton.visibility = View.GONE
            actionsMsgKey = rawUserContent.hashCode().toString() + "_" + (message.imageUri ?: "")
            applyActionsVisibility(userActionsExpanded.contains(actionsMsgKey), animate = false)

            val tapToggle = View.OnClickListener { toggleActions() }
            messageContainer.setOnClickListener(tapToggle)
            messageTextView.setOnClickListener(tapToggle)

            if (pos >= 0 && message.role == "user") {
                val displayMetrics = itemView.resources.displayMetrics
                val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
                val isTablet = screenWidthDp >= 600
                val MAX_CHARS_THRESHOLD = if (isTablet) 300 else 150
                val MAX_LINES_THRESHOLD = 3

                val rawLines = rawUserContent.lines().size
                val charLength = rawUserContent.length
                val isLongMessage = rawLines > MAX_LINES_THRESHOLD || charLength > MAX_CHARS_THRESHOLD

                if (isLongMessage) {
                    // Use stable key (content hash) instead of position
                    val msgKey = rawUserContent.hashCode().toString()
                    val isCollapsed = collapsedStates.getOrDefault(msgKey, true)

                    val displayContent = if (isCollapsed) {
                        if (charLength > MAX_CHARS_THRESHOLD) {
                            val cutOffIndex =
                                rawUserContent.take(MAX_CHARS_THRESHOLD).lastIndexOf(' ')
                            val safeIndex = if (cutOffIndex > 0) cutOffIndex else MAX_CHARS_THRESHOLD
                            rawUserContent.take(safeIndex) + "...(continued)"
                        } else {
                            rawUserContent.lines().take(MAX_LINES_THRESHOLD).joinToString("\n") + "\n\n**...(continued)**"
                        }
                    } else {
                        rawUserContent
                    }

                    try {
                        markwon.setMarkdown(messageTextView, displayContent)
                    } catch (e: RuntimeException) {
                        if (e.message?.contains("Prism4j") == true || e.message?.contains("entry nodes") == true) {
                            messageTextView.text = displayContent
                        } else {
                            throw e
                        }
                    }

                    collapseToggleButton.visibility = View.VISIBLE
                    collapseToggleButton.setImageResource(
                        if (isCollapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less2
                    )
                    collapseToggleButton.setOnClickListener {
                        collapsedStates[msgKey] = !isCollapsed
                        this@ChatAdapter.notifyItemChanged(pos)
                        onCollapse()
                    }
                } else {
                    try {
                        markwon.setMarkdown(messageTextView, rawUserContent)
                    } catch (e: RuntimeException) {
                        if (e.message?.contains("Prism4j") == true || e.message?.contains("entry nodes") == true) {
                            messageTextView.text = rawUserContent
                        } else {
                            throw e
                        }
                    }
                }
            } else {
                try {
                    markwon.setMarkdown(messageTextView, rawUserContent)
                } catch (e: RuntimeException) {
                    if (e.message?.contains("Prism4j") == true || e.message?.contains("entry nodes") == true) {
                        messageTextView.text = rawUserContent
                    } else {
                        throw e
                    }
                }
            }

            // ... (Image and Button logic) ...
            val imageUriStr = message.imageUri
            if (!imageUriStr.isNullOrEmpty()) {
                try {
                    val userImageUri = imageUriStr.toUri()
                    val request = ImageRequest.Builder(itemView.context)
                        .data(userImageUri)
                        .target(imageView)
                        .build()
                    ImageLoader(itemView.context).enqueue(request)
                    imageView.visibility = View.VISIBLE
                    imageView.setOnClickListener {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(userImageUri, "image/*")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            itemView.context.startActivity(intent)
                        } catch (e: Exception) {
                            AppToast.makeText(itemView.context, itemView.context.getString(R.string.toast_could_not_open_image), AppToast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    val base64 = getImageBase64(message.content)
                    if (base64 != null) {
                        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        imageView.setImageBitmap(bitmap)
                        imageView.visibility = View.VISIBLE
                    } else {
                        imageView.visibility = View.GONE
                    }
                }
            } else {
                imageView.visibility = View.GONE
            }

            copyButtonuser.setOnClickListener {
                val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Copied Text", rawUserContent)
                clipboard.setPrimaryClip(clip)
                CopyFeedbackAnimator.play(copyButtonuser)
            }
            copyButtonuser.setOnLongClickListener {
                val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Copied Markdown", rawUserContent)
                clipboard.setPrimaryClip(clip)
                AppToast.makeText(itemView.context, itemView.context.getString(R.string.toast_raw_md_copied), AppToast.LENGTH_SHORT).show()
                true
            }
            editButton.setOnClickListener {
                if (rawUserContent.isNotBlank()) {
                    onEditMessage(bindingAdapterPosition, rawUserContent)
                }
            }
            // Regenerated from AI row now; keep listener no-op for ID stability
            resendButton.setOnClickListener(null)
            deleteButton.setOnClickListener {
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

        private val messageTextView: TextView = itemView.findViewById(R.id.messageTextView)
        private val copyButton: ImageButton = itemView.findViewById(R.id.copyButton)
        private val aipdfButton: ImageButton = itemView.findViewById(R.id.aipdfButton)
        private val shareButton: ImageButton = itemView.findViewById(R.id.shareButton)
        private val markdownButton: ImageButton = itemView.findViewById(R.id.markdownButton)
        private val pngButton: ImageButton = itemView.findViewById(R.id.pngButton)
        val ttsButton: ImageButton = itemView.findViewById(R.id.ttsButton)
        private val regenerateButton: ImageButton = itemView.findViewById(R.id.regenerateButton)
        private val instructButton: ImageButton = itemView.findViewById(R.id.instructButton)
        private val generatedImageView: ImageView = itemView.findViewById(R.id.generatedImageView)
        val messageContainer: ConstraintLayout = itemView.findViewById(R.id.messageContainer)
        private var pulseAnimator: ObjectAnimator? = null
        private var bgColorAnimator: ObjectAnimator? = null
        private val htmlButton: ImageButton = itemView.findViewById(R.id.htmlButton)
        private val collapseToggleButton: ImageButton = itemView.findViewById(R.id.collapseToggleButton)
        private val saveFileButton: ImageButton = itemView.findViewById(R.id.saveFileButton)
        private val editButton: ImageButton = itemView.findViewById(R.id.editButton)
        private val reasoningBlock: View = itemView.findViewById(R.id.reasoningBlock)
        private val reasoningHeader: View = itemView.findViewById(R.id.reasoningHeader)
        private val reasoningChevron: ImageView = itemView.findViewById(R.id.reasoningChevron)
        private val reasoningTitle: TextView = itemView.findViewById(R.id.reasoningTitle)
        private val reasoningTextView: TextView = itemView.findViewById(R.id.reasoningTextView)
        private val thinkingRow: View = itemView.findViewById(R.id.thinkingRow)
        private val thinkingBar1: View = itemView.findViewById(R.id.thinkingBar1)
        private val thinkingBar2: View = itemView.findViewById(R.id.thinkingBar2)
        private val thinkingBar3: View = itemView.findViewById(R.id.thinkingBar3)
        private val forkNavigator: View = itemView.findViewById(R.id.forkNavigator)
        private val forkPrev: ImageButton = itemView.findViewById(R.id.forkPrev)
        private val forkNext: ImageButton = itemView.findViewById(R.id.forkNext)
        private val forkLabel: TextView = itemView.findViewById(R.id.forkLabel)
        private val rpSpeakerHeader: View = itemView.findViewById(R.id.rpSpeakerHeader)
        private val rpSpeakerAvatar: ImageView = itemView.findViewById(R.id.rpSpeakerAvatar)
        private val rpSpeakerNameView: TextView = itemView.findViewById(R.id.rpSpeakerName)
        private var thinkingBarAnimators: List<ObjectAnimator>? = null
        // Configuration for "Long Message" detection
        private val CHAR_THRESHOLD = 350

        private val thinkingLabel: TextView = itemView.findViewById(R.id.thinkingLabel)

        // "Thinking" sheen replaces the old bar meter; bars stay in the layout for ID stability.
        private fun startThinkingBars() {
            itemView.findViewById<View>(R.id.thinkingBars).visibility = View.GONE
            thinkingLabel.post { ShimmerText.start(thinkingLabel, ShimmerText.highlightFor(thinkingLabel)) }
        }

        private fun stopThinkingBars() {
            thinkingBarAnimators?.forEach { it.cancel() }
            thinkingBarAnimators = null
            ShimmerText.stop(thinkingLabel)
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
            if (!isRpMode || isThinking || name.isNullOrBlank()) {
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
                ImageLoader(itemView.context).enqueue(request.build())
            } else {
                rpSpeakerAvatar.setImageResource(R.drawable.ic_gradation_mark)
            }
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
            if (reasoning.isBlank()) {
                ShimmerText.stop(reasoningTitle)
                reasoningBlock.visibility = View.GONE
                reasoningTextView.visibility = View.GONE
                return
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
                    reasoningTitle.post {
                        ShimmerText.start(reasoningTitle, ShimmerText.highlightFor(reasoningTitle))
                    }
                }
            } else {
                ShimmerText.stop(reasoningTitle)
            }
            val key = "reasoning_${reasoning.hashCode()}"
            // Expanded while streaming so the user can watch thoughts; collapse default after.
            val defaultCollapsed = !streaming
            val collapsed = collapsedStates.getOrDefault(key, defaultCollapsed)
            reasoningTextView.text = reasoning
            reasoningTextView.visibility = if (collapsed) View.GONE else View.VISIBLE
            reasoningChevron.setImageResource(
                if (collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less2
            )
            reasoningHeader.setOnClickListener {
                val next = !collapsedStates.getOrDefault(key, defaultCollapsed)
                collapsedStates[key] = next
                animateDisclosure(reasoningTextView, expand = !next)
                reasoningChevron.setImageResource(
                    if (next) R.drawable.ic_expand_more else R.drawable.ic_expand_less2
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
                    val cursors = if (text is android.text.Spanned) {
                        text.getSpans(0, text.length, StreamCursorSpan::class.java)
                    } else emptyArray()
                    val fadesDone = fades.isEmpty() || fades.all { it.isDone() }
                    if (fadesDone && cursors.isEmpty()) {
                        fadeTicker = null
                        if (text is android.text.Spannable) {
                            fades.forEach { text.removeSpan(it) }
                        }
                        return
                    }
                    if (fadesDone && text is android.text.Spannable) {
                        fades.forEach { text.removeSpan(it) }
                    }
                    messageTextView.invalidate()
                    // Word fades need every frame; the breathing cursor alone runs at ~15fps.
                    if (fadesDone) {
                        android.view.Choreographer.getInstance().postFrameCallbackDelayed(this, 66L)
                    } else {
                        android.view.Choreographer.getInstance().postFrameCallback(this)
                    }
                }
            }
            fadeTicker = ticker
            android.view.Choreographer.getInstance().postFrameCallback(ticker)
        }

        fun renderStreamFrame(displayed: String) {
            pulseAnimator?.cancel()
            pulseAnimator = null
            messageContainer.alpha = 1f
            val cursorColor = ContextCompat.getColor(itemView.context, R.color.xai_mute)
            try {
                val spanned = streamMarkdown.render(displayed)
                ChatMarkdown.polish(spanned)
                applyStreamFades(spanned, android.os.SystemClock.uptimeMillis())
                val cursorStart = spanned.length
                spanned.append(StreamCursorSpan.GLYPH)
                spanned.setSpan(
                    StreamCursorSpan(cursorColor),
                    cursorStart,
                    spanned.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                messageTextView.setText(spanned, TextView.BufferType.SPANNABLE)
                ensureFadeTicker()
            } catch (_: Exception) {
                messageTextView.text = displayed
            }
        }

        fun bindTextOnly(message: FlexibleMessage) {
            attachStreamRevealHolder(this)
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
                renderStreamFrame(text.take(1))
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

            val text = if (message.role == "assistant" && message.toolCalls != null && getMessageText(message.content).isBlank()) {
                "Tool Call: ${message.toolCalls.map { it.function.name }.distinct().joinToString()}"
            } else {
                getMessageText(message.content)
            }
            val isThinking = ThinkingPlaceholder.matches(text)

            if (!isThinking) {
                val finalContent = getPreRenderedContent(message)
                markwon.setParsedMarkdown(messageTextView, finalContent as android.text.Spanned)
            } else {
                messageTextView.text = ""
            }

            // --- NEW COLLAPSE LOGIC (INSTANT, NO POST DELAY) ---
            if (text.length > CHAR_THRESHOLD) {
                val msgKey = text.hashCode().toString()
                val isCollapsed = collapsedStates.getOrDefault(msgKey, false) // Default Expanded (false)

                applyCollapseState(isCollapsed)

                collapseToggleButton.visibility = View.VISIBLE
                collapseToggleButton.setImageResource(
                    if (isCollapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less2
                )

                collapseToggleButton.setOnClickListener {
                    val newState = !collapsedStates.getOrDefault(msgKey, false)
                    collapsedStates[msgKey] = newState

                    applyCollapseState(newState)
                    collapseToggleButton.setImageResource(
                        if (newState) R.drawable.ic_expand_more else R.drawable.ic_expand_less2
                    )
                    onCollapse()
                }
            } else {
                messageTextView.maxLines = Int.MAX_VALUE
                messageTextView.ellipsize = null
                collapseToggleButton.visibility = View.GONE
                collapseToggleButton.setOnClickListener(null)
            }
            // ---------------------------------------------------

            val reasoningText = reasoningSource(message).let { if (it.isBlank()) "" else "\n\n$it" }

            // 3. UI STATE LOGIC
            ttsButton.visibility = if (ttsAvailable) View.VISIBLE else View.GONE

            val isError = message.role == "assistant" && isRpErrorText(text)

            itemView.findViewById<View>(R.id.aiActionRow).visibility =
                if (isThinking) View.GONE else View.VISIBLE

            bindThinkingState(isThinking)
            bindRpSpeakerHeader(isThinking)

            messageContainer.setBackgroundResource(R.drawable.bg_ai_message)
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
                    val request = ImageRequest.Builder(itemView.context)
                        .data(generatedUri)
                        .target(generatedImageView)
                        .build()
                    ImageLoader(itemView.context).enqueue(request)
                    generatedImageView.visibility = View.VISIBLE

                    generatedImageView.setOnClickListener {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(generatedUri, "image/*")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            itemView.context.startActivity(intent)
                        } catch (e: Exception) {
                            AppToast.makeText(itemView.context, itemView.context.getString(R.string.toast_could_not_open_image), AppToast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    generatedImageView.visibility = View.GONE
                }
            } else {
                generatedImageView.visibility = View.GONE
            }

            // 6. BUTTON LISTENERS (Lazy Calculation)
            htmlButton.setOnClickListener {
                val fullRawMarkdown = ensureTableSpacing(reasoningText + text)
                if (fullRawMarkdown.isNotBlank()) {
                    onShowMarkdown.invoke(fullRawMarkdown)
                }
            }
            htmlButton.setOnLongClickListener {
                val fullRawMarkdown = ensureTableSpacing(reasoningText + text)
                if (fullRawMarkdown.isNotBlank()) {
                    onSaveHtml.invoke(fullRawMarkdown)
                    true // Consume long press
                } else false
            }
            editButton.setOnClickListener {
                // Edit the visible reply only — do not bake reasoning into content/swipe alts.
                onEditAssistantMessage(bindingAdapterPosition, ensureTableSpacing(text))
            }
            regenerateButton.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos <= 0 || pos >= messages.size) return@setOnClickListener
                val prev = messages[pos - 1]
                if (prev.role == "user") {
                    onRedoMessage(pos - 1, prev.content)
                }
            }
            instructButton.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos < 0 || pos >= messages.size) return@setOnClickListener
                onInstructMessage(pos)
            }
            val lastAssistantIndex = messages.indexOfLast { it.role == "assistant" }
            val lastUserIndex = messages.indexOfLast { it.role == "user" }
            val hasUserTurn = lastUserIndex >= 0
            // Instruct/regen only when there is an assistant reply after the last user turn
            // (not the opening greeting after a cancelled request). Error bubbles still allow retry.
            val showRpActions = isRpMode &&
                hasUserTurn &&
                position == lastAssistantIndex &&
                lastAssistantIndex > lastUserIndex &&
                !isThinking
            instructButton.visibility = if (showRpActions) View.VISIBLE else View.GONE
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
                val clip = ClipData.newPlainText("Copied Text", messageTextView.text.toString().trimEnd('\u258C'))
                clipboard.setPrimaryClip(clip)
                CopyFeedbackAnimator.play(copyButton)
            }

            copyButton.setOnLongClickListener {
                val fullRawMarkdown = ensureTableSpacing(reasoningText + text)
                val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Copied Markdown", fullRawMarkdown)
                clipboard.setPrimaryClip(clip)
                AppToast.makeText(itemView.context, itemView.context.getString(R.string.toast_raw_md_copied), AppToast.LENGTH_SHORT).show()
                true
            }

            shareButton.setOnClickListener {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, messageTextView.text.toString())
                    putExtra(Intent.EXTRA_SUBJECT, itemView.context.getString(R.string.share_subject_message))
                }
                itemView.context.startActivity(Intent.createChooser(shareIntent, itemView.context.getString(R.string.toast_share_message)))
            }

            shareButton.setOnLongClickListener {
                val fullRawMarkdown = ensureTableSpacing(reasoningText + text)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, fullRawMarkdown)
                    putExtra(Intent.EXTRA_SUBJECT, itemView.context.getString(R.string.share_subject_markdown))
                }
                itemView.context.startActivity(Intent.createChooser(shareIntent, itemView.context.getString(R.string.toast_share_markdown)))
                AppToast.makeText(itemView.context, itemView.context.getString(R.string.toast_sharing_markdown), AppToast.LENGTH_SHORT).show()
                true
            }

            val iconRes = if (isSpeaking && position == currentPosition) {
                R.drawable.ic_stop_circle
            } else {
                R.drawable.ic_volume_up
            }
            ttsButton.setImageResource(iconRes)

            ttsButton.setOnClickListener {
                val textToSpeak = messageTextView.text.toString()
                if (textToSpeak.isNotEmpty()) {
                    ForegroundService.stopTtsSpeaking()
                    onSpeakText(textToSpeak, position)
                } else {
                    AppToast.makeText(itemView.context, itemView.context.getString(R.string.toast_no_text_speak), AppToast.LENGTH_SHORT).show()
                }
            }

            ttsButton.setOnLongClickListener {
                val textToSpeak = messageTextView.text.toString()
                if (textToSpeak.isNotEmpty()) {
                    ForegroundService.stopTtsSpeaking()
                    onSynthesizeToWavFile(textToSpeak, position)
                } else {
                    AppToast.makeText(itemView.context, itemView.context.getString(R.string.toast_no_text_save), AppToast.LENGTH_SHORT).show()
                }
                true
            }

            aipdfButton.setOnClickListener {
                val fullRawMarkdown = ensureTableSpacing(reasoningText + text)
                CoroutineScope(Dispatchers.Main).launch {
                    val pdfUri = withContext(Dispatchers.IO) {
                        try {
                            val generator = PdfGenerator(itemView.context)
                            val imageUriStr = message.imageUri
                            val imageUri = imageUriStr?.toUri()
                            if (imageUri != null) {
                                generator.generateMarkdownPdfWithImage(fullRawMarkdown, imageUri.toString())
                            } else {
                                generator.generateMarkdownPdf(fullRawMarkdown)
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (pdfUri != null) {
                        val context = itemView.context

                        // Disable StrictMode check for file:// URI
                        try {
                            val m = StrictMode::class.java.getMethod("disableDeathOnFileUriExposure")
                            m.invoke(null)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }

                        val path = WorkspacePaths.workspaceDirForRead()

                        // Create intent to view the folder
                        val intent = Intent(Intent.ACTION_VIEW)
                        intent.setDataAndType(Uri.fromFile(path), "resource/folder")
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                        // Create the system chooser intent
                        val chooserIntent = Intent.createChooser(intent, itemView.context.getString(R.string.action_open_folder))

                        Snackbar.make(itemView, R.string.toast_pdf_saved, Snackbar.LENGTH_LONG)
                            .setAction(R.string.action_open_folder) {
                                context.startActivity(chooserIntent)
                            }
                            .show()
                    } else {
                        // Keep the failure toast as it provides immediate error feedback
                        AppToast.makeText(itemView.context, itemView.context.getString(R.string.toast_pdf_failed), AppToast.LENGTH_SHORT).show()
                    }
                }
            }

            pngButton.setOnClickListener {
                onCaptureItemToBitmap(bindingAdapterPosition, "png")
            }

            pngButton.setOnLongClickListener {
                onCaptureItemToBitmap(bindingAdapterPosition, "webp")
                true
            }

            aipdfButton.setOnLongClickListener {
                onCaptureItemToBitmap(bindingAdapterPosition, "jpg")
                true
            }

            markdownButton.setOnClickListener {
                val fullRawMarkdown = ensureTableSpacing(reasoningText + text)
                onSaveMarkdown(bindingAdapterPosition, fullRawMarkdown)
            }

            markdownButton.setOnLongClickListener {
                onSaveText(bindingAdapterPosition, messageTextView.text.toString())
                true
            }
            saveFileButton.setOnClickListener {
                //  onSaveAsFile.invoke(messageTextView.text.toString())
                onSaveAsFile.invoke(text)
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
            thinkingRow.visibility = View.GONE
            messageContainer.visibility = View.VISIBLE
            messageContainer.alpha = 1f
            messageContainer.clearAnimation()
            messageContainer.background = ContextCompat.getDrawable(itemView.context, R.drawable.bg_ai_message)
        }

        private fun applyCollapseState(isCollapsed: Boolean) {
            messageTextView.maxLines = if (isCollapsed) 4 else Int.MAX_VALUE
            messageTextView.ellipsize = if (isCollapsed) android.text.TextUtils.TruncateAt.END else null
        }

        private fun isRpErrorText(text: String): Boolean =
            text.startsWith("**Error:**")
    }
    inner class HiddenViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}