package io.github.stardomains3.oxproxion

import android.app.Application
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

class ScreenshotApp : Application()

/**
 * Renders key screens to PNGs under app/build/screenshots for visual review.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ScreenshotApp::class, sdk = [35], qualifiers = DARK)
class ScreenshotTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        DemoModel.pace = 0.02f
        // The static background field renders on a worker thread on the phone; snapshots need it now.
        AmbientBackgroundView.renderFieldInline = true
        TestEnv.resetViewModelFactory()
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        AppDatabase.setInstanceForTesting(db)
        // Roleplay is opt-in now; these screens cover it, so switch it on (see ModesDefaultTest).
        SharedPreferencesHelper(ctx).setRoleplayEnabled(true)
        // Tests that stop mid-swipe leave the peeked mode saved; always start on Chat.
        SharedPreferencesHelper(ctx).saveChatMode(ChatMode.ASK)
        SharedPreferencesHelper(ctx).saveChatMarkStyle(SharedPreferencesHelper.CHAT_MARK_LIQUID)
    }

    /** CodeHub is a process singleton: a test that ends on the Code tab must not start the next one there. */
    @org.junit.After
    fun tearDown() = io.github.stardomains3.oxproxion.code.CodeHub.resetForTesting()

    private fun snap(view: View, name: String) {
        val bmp = runCatching { renderHardware(view) }.getOrNull() ?: Bitmap.createBitmap(
            view.width, view.height, Bitmap.Config.ARGB_8888
        ).also { view.draw(Canvas(it)) }
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Draws through the real hardware pipeline (RenderNode, RenderEffect, AGSL), so glass
     * renders exactly as on a device instead of via the software fallback.
     */
    private fun renderHardware(view: View): Bitmap {
        val w = view.width
        val h = view.height
        val root = RenderNode("snap").apply {
            setPosition(0, 0, w, h)
            val c = beginRecording()
            view.draw(c)
            endRecording()
        }
        val reader = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val renderer = HardwareRenderer().apply {
            setContentRoot(root)
            setSurface(reader.surface)
        }
        try {
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage()
            val plane = image.planes[0]
            val full = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            image.close()
            return Bitmap.createBitmap(full, 0, 0, w, h)
        } finally {
            renderer.destroy()
            reader.close()
        }
    }

    private fun idle() {
        repeat(8) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        }
    }

    private fun withChat(block: (MainActivity, ChatFragment) -> Unit) {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val night = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        SharedPreferencesHelper(ctx).saveThemeMode(
            if (night) SharedPreferencesHelper.THEME_DARK else SharedPreferencesHelper.THEME_LIGHT
        )
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            idle()
            sc.onActivity { a ->
                idle()
                // Every screen starts on Chat, whatever mode an earlier test left behind.
                val vm = ViewModelProvider(a)[ChatViewModel::class.java]
                if (vm.chatMode.value != ChatMode.ASK) { vm.setChatMode(ChatMode.ASK); idle() }
                val chat = a.supportFragmentManager.findFragmentByTag("ChatFragment") as ChatFragment
                block(a, chat)
            }
        }
    }

    private fun root(a: MainActivity) = a.window.decorView

    private fun seedConversation(a: MainActivity) {
        val vm = ViewModelProvider(a)[ChatViewModel::class.java]
        val f = ChatViewModel::class.java.getDeclaredField("_chatMessages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val live = f.get(vm) as MutableLiveData<List<FlexibleMessage>>
        live.value = listOf(
            FlexibleMessage("user", JsonPrimitive("Can you explain how attention works in transformers? Keep it short.")),
            FlexibleMessage(
                "assistant",
                JsonPrimitive(
                    """
                    **Attention** lets each token look at every other token and decide what matters.

                    ### The short version
                    1. Each token becomes a *query*, a *key* and a *value*.
                    2. Scores are `softmax(QKᵀ / √d)`.
                    3. The output is a weighted mix of the values.

                    ```python
                    weights = softmax(q @ k.T / sqrt(d))
                    out = weights @ v
                    ```

                    > Multi-head attention runs this several times in parallel.
                    """.trimIndent()
                )
            ),
            FlexibleMessage("user", JsonPrimitive("Nice. And why divide by √d?")),
        )
    }

    private fun seedHistory() = runBlocking {
        val dao = db.chatDao()
        listOf(
            "Transformer **attention**, explained",
            "Grocery list for the *week*",
            "Fix `Gradle` build on AGP 9",
            "Llama 3 vs **Qwen** for coding",
            "Birthday message for Sam",
        ).forEachIndexed { i, t ->
            dao.insertSessionAndMessages(
                ChatSession(title = t, modelUsed = "openrouter/free", timestamp = System.currentTimeMillis() - i * 26L * 3600_000L),
                if (i == 0) listOf(ChatMessage(sessionId = 0, role = "assistant", content = "\"Attention maps the query to the keys.\""))
                else emptyList()
            )
        }
    }

    // ---- Chat ----

    @Test fun chatEmptyDark() = withChat { a, _ -> snap(root(a), "chat_empty_dark") }

    /** The empty-chat mark is liquid glass (AGSL) on API 33+: three moments of its motion, close up. */
    @Test fun chatEmptyLiquidMarkDark() = withChat { a, _ ->
        val mark = a.findViewById<LiquidMarkView>(R.id.centerWatermarkIcon)
        listOf(2.4f, 6.1f, 9.8f).forEachIndexed { i, t ->
            mark.animTime = t
            snap(mark, "chat_empty_mark_${i + 1}_dark")
        }
        org.junit.Assert.assertTrue("the mark draws through the liquid shader", mark.isLiquid)
    }

    /**
     * A page swipe snapshots the chat onto a software canvas. The mark has to stay liquid there;
     * the flat vector is only the Plain setting.
     */
    @Test fun chatEmptyMarkStaysLiquidOnSoftwareCanvas() = withChat { a, _ ->
        val mark = a.findViewById<LiquidMarkView>(R.id.centerWatermarkIcon)
        mark.animTime = 6.1f
        // Cache must be built off the draw path (HardwareRenderer mid-draw crashes on device).
        val refresh = LiquidMarkView::class.java.getDeclaredMethod("refreshLastFrame").apply {
            isAccessible = true
        }
        refresh.invoke(mark)
        val soft = Bitmap.createBitmap(mark.width, mark.height, Bitmap.Config.ARGB_8888)
        mark.draw(Canvas(soft))
        org.junit.Assert.assertTrue("software snapshot keeps the liquid frame", opaqueSpread(soft) > 20)
        mark.markStyle = LiquidMarkView.MarkStyle.PLAIN
        val plain = Bitmap.createBitmap(mark.width, mark.height, Bitmap.Config.ARGB_8888)
        mark.draw(Canvas(plain))
        org.junit.Assert.assertTrue("plain is the flat vector", opaqueSpread(plain) < 12)

        mark.markStyle = LiquidMarkView.MarkStyle.OFF
        org.junit.Assert.assertEquals(View.GONE, mark.visibility)
    }

    @Test fun chatMarkSetting() = withChat { a, _ ->
        openSettingsRow(a, R.id.settingsRowAppearance)
        a.findViewById<View>(R.id.chatMarkOff).performClick(); idle()
        org.junit.Assert.assertEquals(SharedPreferencesHelper.CHAT_MARK_OFF, SharedPreferencesHelper(a).getChatMarkStyle())
        a.supportFragmentManager.popBackStackImmediate(); a.supportFragmentManager.popBackStackImmediate(); idle()
        val mark = a.findViewById<LiquidMarkView>(R.id.centerWatermarkIcon)
        org.junit.Assert.assertEquals(View.GONE, mark.visibility)

        openSettingsRow(a, R.id.settingsRowAppearance)
        a.findViewById<View>(R.id.chatMarkPlain).performClick(); idle()
        a.supportFragmentManager.popBackStackImmediate(); a.supportFragmentManager.popBackStackImmediate(); idle()
        org.junit.Assert.assertEquals(View.VISIBLE, mark.visibility)
        org.junit.Assert.assertEquals(LiquidMarkView.MarkStyle.PLAIN, mark.markStyle)

        openSettingsRow(a, R.id.settingsRowAppearance)
        a.findViewById<View>(R.id.chatMarkLiquid).performClick(); idle()
        a.supportFragmentManager.popBackStackImmediate(); a.supportFragmentManager.popBackStackImmediate(); idle()
        org.junit.Assert.assertEquals(LiquidMarkView.MarkStyle.LIQUID, mark.markStyle)
        org.junit.Assert.assertEquals(View.VISIBLE, mark.visibility)
    }

    /** Spread of opaque reds. The liquid frame varies; the flat vector is one gray. */
    private fun opaqueSpread(bmp: Bitmap): Int {
        var n = 0
        var min = 255
        var max = 0
        val step = 3
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                if (android.graphics.Color.alpha(c) >= 240) {
                    val r = android.graphics.Color.red(c)
                    if (r < min) min = r
                    if (r > max) max = r
                    n++
                }
                x += step
            }
            y += step
        }
        return if (n < 20) 0 else max - min
    }

    @Test fun chatConversationDark() = withChat { a, _ ->
        seedConversation(a); idle(); snap(root(a), "chat_conversation_dark")
    }

    /** A long message you sent folds, with Show more under the bubble. */
    @Test fun userMessageFoldDark() = withChat { a, _ ->
        val vm = ViewModelProvider(a)[ChatViewModel::class.java]
        val f = ChatViewModel::class.java.getDeclaredField("_chatMessages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val live = f.get(vm) as MutableLiveData<List<FlexibleMessage>>
        val long = (1..8).joinToString("\n") { "line $it stays in the message you sent" }
        live.value = listOf(FlexibleMessage("user", JsonPrimitive(long)))
        idle()
        val expand = a.findViewById<TextView>(R.id.collapseToggleButton)
        org.junit.Assert.assertEquals(View.VISIBLE, expand.visibility)
        org.junit.Assert.assertEquals(a.getString(R.string.cd_show_more), expand.text.toString())
        snap(root(a), "user_message_fold_dark")
    }

    /**
     * Edit cuts the turn out and says so on the composer. Cancel puts the turn back and
     * restores the line that was already in the field.
     */
    @Test fun chatEditingDark() = withChat { a, _ ->
        seedConversation(a); idle()
        val field = a.findViewById<android.widget.EditText>(R.id.chatEditText)
        field.setText("still thinking")
        idle()
        val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.chatRecyclerView)
        val row = rv.findViewHolderForAdapterPosition(2)!!.itemView
        row.findViewById<View>(R.id.editButton).performClick()
        idle()
        val banner = a.findViewById<View>(R.id.composerEditBanner)
        val cancel = a.findViewById<android.widget.TextView>(R.id.composerEditCancel)
        val d = a.resources.displayMetrics.density
        org.junit.Assert.assertEquals(View.VISIBLE, banner.visibility)
        org.junit.Assert.assertEquals(a.getString(R.string.chat_editing), a.findViewById<android.widget.TextView>(R.id.composerEditLabel).text.toString())
        org.junit.Assert.assertEquals(a.getString(R.string.action_cancel), cancel.text.toString())
        org.junit.Assert.assertTrue(cancel.textSize / d >= 13f)
        org.junit.Assert.assertTrue(cancel.layoutParams.height >= (44 * d).toInt() - 1)
        org.junit.Assert.assertEquals("Nice. And why divide by √d?", field.text.toString())
        org.junit.Assert.assertEquals(2, rv.adapter!!.itemCount)
        snap(root(a), "chat_editing_dark")
        cancel.performClick()
        idle()
        org.junit.Assert.assertEquals(View.GONE, banner.visibility)
        org.junit.Assert.assertEquals("still thinking", field.text.toString())
        org.junit.Assert.assertEquals(3, rv.adapter!!.itemCount)
    }

    /** A sent photo keeps its shape inside the bubble, and the composer chip can send with no text. */
    @Test fun chatPhotoDark() = withChat { a, chat ->
        val d = a.resources.displayMetrics.density
        val vm = ViewModelProvider(a)[ChatViewModel::class.java]
        val f = ChatViewModel::class.java.getDeclaredField("_chatMessages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val live = f.get(vm) as MutableLiveData<List<FlexibleMessage>>
        // A real file: a missing one with no stored JPEG drops the frame once the load fails.
        val photo = java.io.File(a.cacheDir, "chat_photo_test.jpg")
        photo.outputStream().use {
            android.graphics.Bitmap.createBitmap(60, 100, android.graphics.Bitmap.Config.ARGB_8888)
                .compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it)
        }
        live.value = listOf(
            FlexibleMessage("user", JsonPrimitive("The north window, this morning."), imageUri = android.net.Uri.fromFile(photo).toString())
        )
        idle()
        val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.chatRecyclerView)
        val row = rv.findViewHolderForAdapterPosition(0)!!.itemView
        val image = row.findViewById<android.widget.ImageView>(R.id.userImageView)
        val text = row.findViewById<android.widget.TextView>(R.id.messageTextView)
        val container = row.findViewById<View>(R.id.messageContainer)
        org.junit.Assert.assertEquals(View.VISIBLE, image.visibility)
        org.junit.Assert.assertEquals((4 * d).toInt(), container.paddingTop)
        org.junit.Assert.assertEquals((12 * d).toInt(), text.paddingStart)
        val portrait = android.graphics.Bitmap.createBitmap(600, 1000, android.graphics.Bitmap.Config.ARGB_8888)
        portrait.eraseColor(android.graphics.Color.rgb(58, 58, 58))
        android.graphics.Canvas(portrait).drawRect(
            80f, 140f, 520f, 860f,
            android.graphics.Paint().apply { color = android.graphics.Color.rgb(96, 96, 96) }
        )
        val (iw, ih) = ChatPhoto.frame(portrait.width, portrait.height, (240 * d).toInt(), (300 * d).toInt())
        image.layoutParams.width = iw
        image.layoutParams.height = ih
        image.setImageBitmap(portrait)
        text.maxWidth = maxOf(iw, (160 * d).toInt())

        val wide = android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888)
        wide.eraseColor(android.graphics.Color.rgb(90, 90, 90))
        val max = (156 * d).toInt()
        val (pw, ph) = ChatPhoto.frame(wide.width, wide.height, max, max, (64 * d).toInt())
        val preview = a.findViewById<android.widget.ImageView>(R.id.previewImageView)
        preview.layoutParams.width = pw
        preview.layoutParams.height = ph
        preview.setImageBitmap(wide)
        a.findViewById<View>(R.id.attachmentPreviewContainer).visibility = View.VISIBLE
        ChatFragment::class.java.getDeclaredField("selectedImageBytes").apply { isAccessible = true }
            .set(chat, byteArrayOf(1, 2, 3))
        ChatFragment::class.java.getDeclaredMethod("updateSendButtonChrome").apply { isAccessible = true }
            .invoke(chat)
        org.junit.Assert.assertTrue(a.findViewById<View>(R.id.sendChatButton).isEnabled)
        val remove = a.findViewById<View>(R.id.removeAttachmentButton)
        org.junit.Assert.assertTrue(remove.layoutParams.width >= (44 * d).toInt() - 1)
        idle()
        snap(root(a), "chat_photo_dark")
    }

    /** Android 12: code cards must not call API 34-only text layout methods. */
    @Test @Config(sdk = [31])
    fun chatConversationApi31() = withChat { a, _ ->
        seedConversation(a); idle(); snap(root(a), "chat_conversation_api31")
    }

    /** Transcript scrolled so messages pass under the floating glass controls. */
    private fun scrolledUnderGlass(a: MainActivity, name: String) {
        seedConversation(a)
        val vm = ViewModelProvider(a)[ChatViewModel::class.java]
        val f = ChatViewModel::class.java.getDeclaredField("_chatMessages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val live = f.get(vm) as MutableLiveData<List<FlexibleMessage>>
        live.value = live.value!! + FlexibleMessage(
            "assistant",
            JsonPrimitive(
                """
                Because dot products grow with dimension. With **d** = 512, raw scores get large, softmax saturates, and gradients all but vanish.

                Scaling by **√d** keeps the variance near 1, so attention stays soft and learnable.

                - Small *d*: barely matters
                - Large *d*: training stalls without it
                """.trimIndent()
            )
        )
        idle()
        val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.chatRecyclerView)
        rv.scrollToPosition(0); idle()
        rv.scrollBy(0, (110 * a.resources.displayMetrics.density).toInt()); idle()
        snap(root(a), name)
    }

    @Test fun chatGlassDark() = withChat { a, _ -> scrolledUnderGlass(a, "chat_glass_dark") }
    @Test @Config(qualifiers = LIGHT)
    fun chatGlassLight() = withChat { a, _ -> scrolledUnderGlass(a, "chat_glass_light") }

    /** Mid-stream frame: the newest words are still fading in at the edge. */
    private fun streamInto(a: MainActivity, name: String) {
        seedConversation(a); idle()
        val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.chatRecyclerView)
        val holder = rv.findViewHolderForAdapterPosition(1) as ChatAdapter.AssistantViewHolder
        // As bindTextOnly does for the live row: action icons wait until the reply lands.
        holder.itemView.findViewById<View>(R.id.aiActionRow).visibility = View.INVISIBLE
        val full = """
            Dividing by **√d** keeps the dot products from growing with the key size.

            Without it, large scores push softmax into regions where one weight is ~1 and the rest ~0, so gradients vanish and training stalls.

            - With scaling, scores stay near unit variance
            - Softmax stays soft, so every token still gets
        """.trimIndent()
        // Drive frames by hand: advance the clock without running the looper, so the fade
        // ticker (which reposts every frame while the cursor breathes) can't spin the test.
        var n = 0
        while (n < full.length) {
            n = minOf(full.length, n + 7)
            holder.renderStreamFrame(full.substring(0, n))
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(22))
        }
        val r = root(a)
        r.measure(
            View.MeasureSpec.makeMeasureSpec(r.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(r.height, View.MeasureSpec.EXACTLY)
        )
        r.layout(0, 0, r.width, r.height)
        snap(r, name)
        // End the stream so the ticker stops before the activity is torn down.
        holder.itemView.findViewById<android.widget.TextView>(R.id.messageTextView).text = ""
    }

    @Test fun chatStreamingDark() = withChat { a, _ -> streamInto(a, "chat_streaming_dark") }

    @Test fun controlsPanelDark() = withChat { a, _ ->
        a.findViewById<View>(R.id.controlsButton).performClick(); idle()
        // A card floating above the composer, lined up with it; never covering it.
        val sheet = a.findViewById<View>(R.id.headerContainer)
        val composer = a.findViewById<View>(R.id.chatInputContainer)
        val s = IntArray(2).also { sheet.getLocationInWindow(it) }
        val c = IntArray(2).also { composer.getLocationInWindow(it) }
        org.junit.Assert.assertTrue("card must end above the composer", s[1] + sheet.height <= c[1])
        org.junit.Assert.assertEquals(c[0], s[0])
        org.junit.Assert.assertEquals(composer.width, sheet.width)
        snap(root(a), "controls_panel_dark")
    }

    @Test fun controlsPanelMoreDark() = withChat { a, _ ->
        a.findViewById<View>(R.id.controlsButton).performClick(); idle()
        a.findViewById<View>(R.id.controlsMoreRow).performClick(); idle()
        org.junit.Assert.assertTrue(a.findViewById<View>(R.id.buttonsContainer).isShown)
        snap(root(a), "controls_panel_more_dark")
    }

    @Test fun controlsPanelEffortPicksReasoning() = withChat { a, _ ->
        a.findViewById<View>(R.id.controlsButton).performClick(); idle()
        a.findViewById<View>(R.id.effortHigh).performClick(); idle()
        val prefs = SharedPreferencesHelper(a)
        org.junit.Assert.assertEquals("high", prefs.getReasoningEffort())
        org.junit.Assert.assertTrue(prefs.getAdvancedReasoningEnabled())
        val group = a.findViewById<GlassSegmentedGroup>(R.id.controlsEffortGroup)
        org.junit.Assert.assertEquals(R.id.effortHigh, group.checkedButtonId)
        a.findViewById<View>(R.id.effortOff).performClick(); idle()
        org.junit.Assert.assertEquals(R.id.effortOff, group.checkedButtonId)
        a.findViewById<View>(R.id.effortAuto).performClick(); idle()
        org.junit.Assert.assertEquals(R.id.effortAuto, group.checkedButtonId)
        org.junit.Assert.assertFalse(prefs.getAdvancedReasoningEnabled())
    }

    @Test fun attachMenuDark() = withChat { a, _ ->
        a.findViewById<View>(R.id.menuButton).performClick(); idle()
        snap(root(a), "attach_menu_dark")
    }

    @Test fun historyDraftDark() {
        try {
            withChat { a, _ ->
                seedHistory()
                val target = runBlocking {
                    db.chatDao().getAllSessionsOnce().first { it.title.contains("attention") }
                }
                SharedPreferencesHelper(a).saveAskComposerDrafts(
                    ComposerDrafts.remember(emptyMap(), target.id, "still writing the question")
                )
                a.findViewById<View>(R.id.openSavedChatsButton).performClick(); idle()
                val list = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.savedChatsRecyclerView)
                var row: View? = null
                for (i in 0 until (list.adapter?.itemCount ?: 0)) {
                    val item = list.findViewHolderForAdapterPosition(i)?.itemView ?: continue
                    val title = item.findViewById<android.widget.TextView>(R.id.savedChatTitle)?.text?.toString().orEmpty()
                    if (title.contains("attention")) {
                        row = item
                        break
                    }
                }
                val preview = row?.findViewById<android.widget.TextView>(R.id.savedChatPreview)
                org.junit.Assert.assertEquals(
                    a.getString(R.string.history_preview_draft, "still writing the question"),
                    preview?.text?.toString(),
                )
                snap(root(a), "history_draft_dark")
                row!!.performLongClick(); idle()
                val discard = ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.menu_discard_draft)
                org.junit.Assert.assertEquals(View.VISIBLE, discard?.visibility)
                snapDialog(a, "history_draft_options_dark")
            }
        } finally {
            // The next test seeds a fresh database. A session id or draft left here would
            // attach to those new rows and scroll History off the top.
            val ctx = ApplicationProvider.getApplicationContext<Application>()
            SharedPreferencesHelper(ctx).saveAskComposerDrafts(emptyMap())
            SharedPreferencesHelper(ctx).saveComposerDraft(ChatMode.ASK, "")
            SharedPreferencesHelper(ctx).saveRpDraftSessionId(ChatMode.ASK, null)
        }
    }

    @Test fun historyDark() = withChat { a, _ ->
        seedHistory()
        a.findViewById<View>(R.id.openSavedChatsButton).performClick(); idle()
        val list = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.savedChatsRecyclerView)
        val header = list.findViewHolderForAdapterPosition(0)?.itemView
            ?.findViewById<android.widget.TextView>(R.id.historySectionHeader)
        org.junit.Assert.assertEquals(a.getString(R.string.history_section_today), header?.text?.toString())
        val preview = list.findViewHolderForAdapterPosition(1)?.itemView
            ?.findViewById<android.widget.TextView>(R.id.savedChatPreview)
        org.junit.Assert.assertEquals("Attention maps the query to the keys.", preview?.text?.toString())
        snap(root(a), "history_dark")
    }

    /** The press swell must not be sliced by the padded row it sits in (it lost its top and bottom). */
    @Test fun pressedGlassButtonIsNotClipped() = withChat { a, _ ->
        a.findViewById<View>(R.id.openSavedChatsButton).performClick(); idle()
        val button = a.findViewById<View>(R.id.historySettingsButton)
        val bar = a.findViewById<android.view.ViewGroup>(R.id.historyBottomBar)
        org.junit.Assert.assertTrue(bar.clipToPadding)
        button.isPressed = true
        org.junit.Assert.assertFalse("row opens its padding for the swell", bar.clipToPadding)
        org.junit.Assert.assertTrue("room already fits, so the clip stops there", (bar.parent as android.view.ViewGroup).clipChildren)
        button.isPressed = false
    }

    @Test fun historyOptionsSheetDark() = withChat { a, _ ->
        seedHistory()
        a.findViewById<View>(R.id.openSavedChatsButton).performClick(); idle()
        val list = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.savedChatsRecyclerView)
        list.findViewHolderForAdapterPosition(firstSessionRow(list))!!.itemView.performLongClick(); idle()
        snapDialog(a, "history_options_dark")
    }

    /** First row that is a session (the list may lead with section headers). */
    private fun firstSessionRow(list: androidx.recyclerview.widget.RecyclerView): Int {
        for (i in 0 until (list.adapter?.itemCount ?: 0)) {
            val vh = list.findViewHolderForAdapterPosition(i) ?: continue
            if (vh.itemView.findViewById<View>(R.id.iconEditt) != null) return i
        }
        return 0
    }

    @Test fun modelPickerDark() = withChat { a, _ ->
        a.findViewById<View>(R.id.modelNameTextView).performClick(); idle()
        snap(root(a), "model_picker_dark")
    }

    // ---- Settings ----

    @Test fun settingsDark() = withChat { a, _ ->
        a.findViewById<View>(R.id.settingsButton).performClick(); idle()
        snap(root(a), "settings_dark")
    }

    @Test @Config(qualifiers = LIGHT)
    fun settingsLight() = withChat { a, _ ->
        a.findViewById<View>(R.id.settingsButton).performClick(); idle()
        snap(root(a), "settings_light")
    }

    /** The frosted screen a dialog window sits on: cross-window blur plus the light dim. */
    private fun frostedBackdrop(a: MainActivity): Bitmap {
        val r = root(a)
        val scale = 0.25f
        val small = Bitmap.createBitmap((r.width * scale).toInt(), (r.height * scale).toInt(), Bitmap.Config.ARGB_8888)
        val sc = Canvas(small)
        sc.scale(scale, scale)
        r.draw(sc)
        GlassMaterial.boxBlur(small, (22 * a.resources.displayMetrics.density * scale / 2f).toInt().coerceAtLeast(1))
        val bg = Bitmap.createScaledBitmap(small, r.width, r.height, true).copy(Bitmap.Config.ARGB_8888, true)
        Canvas(bg).drawColor(0x47000000)
        return bg
    }

    private fun rpPanelGrid(a: MainActivity): android.widget.GridLayout {
        val grid = root(a).findViewById<android.widget.GridLayout>(R.id.rpPanelTiles)
        org.junit.Assert.assertNotNull("the character menu opened", grid)
        return grid!!
    }

    private fun dismissRpPanel(a: MainActivity) {
        var view: View = rpPanelGrid(a)
        while (view.parent is View) {
            view = view.parent as View
            if (view is androidx.coordinatorlayout.widget.CoordinatorLayout) {
                view.getChildAt(0).performClick()
                break
            }
        }
        idle()
    }

    private fun rpPanelShowing(a: MainActivity): Boolean =
        root(a).findViewById<View>(R.id.rpPanelTiles)?.isShown == true

    /** [sharp]: the dialog dims the screen but does not blur it (the opaque character panel). */
    private fun snapDialog(a: MainActivity, name: String, sharp: Boolean = false) {
        val d: Dialog? = ShadowDialog.getLatestDialog()
        if (d == null) {
            if (sharp) {
                val r = root(a)
                val bg = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888).also { b ->
                    Canvas(b).apply { r.draw(this); drawColor(0x80000000.toInt()) }
                }
                val out = File("build/screenshots").apply { mkdirs() }
                File(out, "$name.png").outputStream().use { bg.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } else {
                snap(root(a), name)
            }
            return
        }
        val bg = if (sharp) {
            val r = root(a)
            Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888).also { b ->
                Canvas(b).apply { r.draw(this); drawColor(0x80000000.toInt()) }
            }
        } else frostedBackdrop(a)
        val c = Canvas(bg)
        val dv = d.window!!.decorView
        c.save(); c.translate(0f, (bg.height - dv.height).toFloat().coerceAtLeast(0f))
        dv.draw(c); c.restore()
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bg.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun snapWithPopup(a: MainActivity, name: String) {
        val p: PopupWindow? = shadowOf(ApplicationProvider.getApplicationContext<Application>()).latestPopupWindow
        val bg = Bitmap.createBitmap(root(a).width, root(a).height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bg)
        root(a).draw(c)
        val pv = p?.contentView
        if (pv != null) {
            val anchor = a.findViewById<View>(R.id.menuButton)
            val loc = IntArray(2); anchor.getLocationInWindow(loc)
            val w = if (pv.width > 0) pv.width else { pv.measure(0, 0); pv.layout(0, 0, pv.measuredWidth, pv.measuredHeight); pv.measuredWidth }
            c.save(); c.translate(loc[0].toFloat(), (loc[1] - pv.height - 8 * a.resources.displayMetrics.density))
            pv.draw(c); c.restore()
        }
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bg.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- Secondary screens ----

    private fun pushFragment(a: MainActivity, f: androidx.fragment.app.Fragment) {
        a.supportFragmentManager.beginTransaction().add(R.id.fragment_container, f).commitNow()
        idle()
    }

    /** A small library so the RP screens show real cards; one character has a photo. */
    private fun seedRp(withActive: Boolean = true) = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val dao = db.rpDao()
        val ids = listOf(
            RpCharacter(name = "Mira Vance", personality = "A sharp-tongued starship mechanic who hides a soft heart behind grease and sarcasm.", greeting = "*wipes her hands* You again?"),
            RpCharacter(name = "Professor Hale", personality = "Retired historian, endlessly curious, speaks in long digressions about forgotten empires."),
            RpCharacter(name = "Kestrel", scenario = "A rain-soaked city where you hire a detective who owes you a favor."),
            RpCharacter(name = "Ondine", personality = "Calm tide spirit. Answers in riddles, remembers every ship that sank."),
            RpCharacter(name = "Theo", greeting = "Hey! You made it. Grab a seat, the coffee is terrible but free."),
            RpCharacter(name = "Aurelia"),
        ).map { dao.insertCharacter(it) }
        // Grayscale portrait for the first character, saved where RpAvatarStorage keeps avatars.
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF3A3A3A.toInt())
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFFA6A6A6.toInt()
        c.drawCircle(size / 2f, size * 0.4f, size * 0.18f, paint)
        c.drawOval(size * 0.16f, size * 0.7f, size * 0.84f, size * 1.3f, paint)
        val file = RpAvatarStorage.avatarFile(ctx, ids[0])
        file.parentFile?.mkdirs()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        dao.insertLorebook(RpLorebook(name = "Outer Rim", content = "Ports, pirates and old wars.", isActive = true))
        val prefs = SharedPreferencesHelper(ctx)
        prefs.saveRpActiveCharacterId(if (withActive) ids[0] else null)
        prefs.saveRpPersona("Sam, a courier with a bad sense of direction.")
        prefs.saveRpPersonaName("Sam")
        val portrait = RpAvatarStorage.personaFile(ctx, "persona_test.jpg")
        portrait.parentFile?.mkdirs()
        file.copyTo(portrait, overwrite = true)
        prefs.saveRpPersonaPhoto(portrait.name)
        prefs.saveRpPersonaPresets(listOf(
            RpPersonaPreset("Sam", "Sam, a courier with a bad sense of direction.", portrait.name),
            RpPersonaPreset("Captain Rhee", "A retired pilot who still salutes the sunrise.")
        ))
    }

    @Test fun rpPersonaSwitchesWithOneTap() = withChat { a, _ ->
        seedRp()
        pushFragment(a, RpPersonaFragment.newInstance()); idle()
        val list = a.findViewById<android.view.ViewGroup>(R.id.rpPersonaList)
        val rows = (0 until list.childCount).map { list.getChildAt(it) }
            .filter { it.findViewById<android.view.View?>(R.id.rpPersonaRowName) != null }
        org.junit.Assert.assertEquals(2, rows.size)
        val check = { i: Int -> rows[i].findViewById<android.view.View>(R.id.rpPersonaRowCheck).visibility }
        org.junit.Assert.assertEquals(android.view.View.VISIBLE, check(0))
        rows[1].performClick(); idle()
        org.junit.Assert.assertEquals("Captain Rhee", a.findViewById<android.widget.EditText>(R.id.rpPersonaNameInput).text.toString())
        org.junit.Assert.assertEquals(android.view.View.GONE, check(0))
        org.junit.Assert.assertEquals(android.view.View.VISIBLE, check(1))
    }

    /** Room LiveData and Coil decode on real background threads; give them a moment. */
    /**
     * A mode switch lands the leaving chat's save first: several hops between the main looper
     * and Room's thread. Short real-time steps let each hop through; four long ones did not.
     */
    private fun settle() {
        repeat(20) { Thread.sleep(50); idle() }
    }

    private fun rpScreen(name: String, f: () -> androidx.fragment.app.Fragment) = withChat { a, _ ->
        seedRp()
        pushFragment(a, f()); settle(); snap(root(a), name)
    }

    @Test @Config(qualifiers = LIGHT)
    fun rpHubLight() = rpScreen("rp_hub_light") { RpHubFragment() }

    @Test fun rpScreensDark() = withChat { a, _ ->
        seedRp()
        renderEach(a, settleEach = true, screens = listOf(
            "rp_hub_dark" to { RpHubFragment() },
            "rp_characters_dark" to { RpCharacterLibraryFragment.newInstance() },
            "rp_persona_dark" to { RpPersonaFragment.newInstance() },
        ))
    }

    @Test fun rpEmptyScreensDark() = withChat { a, _ ->
        renderEach(a, settleEach = true, screens = listOf(
            "rp_hub_empty_dark" to { RpHubFragment() },
            "rp_characters_empty_dark" to { RpCharacterLibraryFragment.newInstance() },
            "rp_lorebooks_dark" to { RpLorebookLibraryFragment.newInstance() },
        ))
    }

    @Test fun rpCharacterEditExistingDark() = withChat { a, _ ->
        seedRp()
        val id = runBlocking { db.rpDao().getAllCharactersOnce().first { it.name == "Mira Vance" }.id }
        pushFragment(a, RpCharacterEditFragment.newInstance(id)); settle()
        snap(root(a), "rp_character_edit_existing_dark")
    }

    @Test fun rpLorebookEditDark() = withChat { a, _ ->
        pushFragment(a, RpLorebookEditFragment.newInstance(0L)); idle()
        val hits = ArrayList<android.view.View>()
        a.findViewById<android.view.ViewGroup>(android.R.id.content)
            .findViewsWithText(hits, "phrase] blocks", android.view.View.FIND_VIEWS_WITH_TEXT)
        val help = hits.filterIsInstance<android.widget.TextView>().first()
        val layout = help.layout
        org.junit.Assert.assertNotNull(layout)
        val cut = (0 until layout.lineCount).sumOf { layout.getEllipsisCount(it) }
        org.junit.Assert.assertEquals("lore format hint is clipped", 0, cut)
        snap(root(a), "rp_lorebook_edit_dark")
    }
    /** Screens with nothing to assert: each must inflate and render. One activity for all of them. */
    @Test fun secondaryScreensDark() = withChat { a, _ ->
        renderEach(a, settleEach = false, screens = listOf(
            "help_dark" to { HelpFragment() },
            "licenses_dark" to { LicenseListFragment() },
            "advanced_reasoning_dark" to { AdvancedReasoningFragment() },
            "lan_models_dark" to { LanModelsFragment() },
            "add_prompt_dark" to { AddEditPromptFragment() },
            "add_system_message_dark" to { AddEditSystemMessageFragment() },
            "preset_edit_dark" to { PresetEditFragment.newInstance(null) },
            "edit_message_dark" to { EditMessageFragment.newInstance(0, "Can you explain how attention works?") },
            "presets_dark" to { PresetsListFragment() },
            "system_messages_dark" to { SystemMessageLibraryFragment() },
            "tools_dark" to { ToolsFragment() },
            "prompts_dark" to { PromptLibraryFragment() },
            "inference_dark" to { InferenceParametersFragment() },
        ))
    }

    /** Pushes each screen over the chat, snaps it and takes it off again, naming any that fails. */
    private fun renderEach(
        a: MainActivity,
        settleEach: Boolean,
        screens: List<Pair<String, () -> androidx.fragment.app.Fragment>>
    ) {
        for ((name, make) in screens) {
            try {
                val f = make()
                pushFragment(a, f)
                if (settleEach) settle()
                snap(root(a), name)
                a.supportFragmentManager.beginTransaction().remove(f).commitNow(); idle()
            } catch (e: Throwable) {
                throw AssertionError("screen $name failed to render", e)
            }
        }
    }
    @Test fun rpSettingsDark() = withChat { a, _ ->
        pushFragment(a, RpSettingsFragment()); idle()
        val facts = a.findViewById<android.widget.TextView>(R.id.rpAutoMemorySwitch)
        val layout = facts.layout
        org.junit.Assert.assertNotNull("facts switch laid out", layout)
        val cut = (0 until layout.lineCount).sumOf { layout.getEllipsisCount(it) }
        org.junit.Assert.assertEquals("facts row must show its whole label", 0, cut)
        // The promise that Memory stays put is the footnote under the card, never clipped with the label.
        val found = ArrayList<View>()
        root(a).findViewsWithText(found, a.getString(R.string.rp_auto_memory_sub), View.FIND_VIEWS_WITH_TEXT)
        org.junit.Assert.assertTrue(found.any { it.isShown })
        snap(root(a), "rp_settings_dark")
    }

    @Test fun dialogsDark() = withChat { a, chat ->
        val dialogs: List<Pair<String, () -> Unit>> = listOf(
            "dialog_confirm_dark" to {
                GrokConfirmDialog.show(chat, "Delete conversation?", "This can't be undone.", "Delete", onConfirm = {})
            },
            "dialog_api_dark" to { SaveApiDialogFragment().show(a.supportFragmentManager, "api") },
            "dialog_lan_dark" to { SaveLANDialogFragment().show(a.supportFragmentManager, "lan") },
            "dialog_timeout_dark" to { TimeoutDialogFragment().show(a.supportFragmentManager, "t") },
        )
        for ((name, open) in dialogs) {
            try {
                open(); idle()
                snapDialogCentered(a, name)
                ShadowDialog.getLatestDialog()?.dismiss(); idle()
            } catch (e: Throwable) {
                throw AssertionError("dialog $name failed to render", e)
            }
        }
    }
    @Test @Config(qualifiers = LIGHT)
    fun inputDialogLight() = withChat { a, chat ->
        GrokInputDialog.show(chat, "Rename conversation", "Title", "Transformer attention", "Save", onConfirm = {})
        idle(); snapDialogCentered(a, "dialog_input_light")
    }

    @Test fun settingsSectionsDark() = withChat { a, _ ->
        for ((row, name) in listOf(R.id.settingsRowModels to "settings_models_dark", R.id.settingsRowData to "settings_data_dark")) {
            openSettingsRow(a, row)
            snap(root(a), name)
            a.supportFragmentManager.popBackStackImmediate(); a.supportFragmentManager.popBackStackImmediate(); idle()
        }
    }

    // ── Voice input ────────────────────────────────────────────────────────────────────

    private fun dictationOf(chat: ChatFragment): VoiceDictation =
        ChatFragment::class.java.getDeclaredField("dictation").apply { isAccessible = true }.get(chat) as VoiceDictation

    /** Mid-dictation: one settled phrase, one still being recognized, bars full of speech. */
    private fun dictateInto(a: MainActivity, chat: ChatFragment): VoiceDictation {
        val d = dictationOf(chat)
        a.findViewById<android.widget.EditText>(R.id.chatEditText).setText("Quick question.")
        d.onStateChanged(VoiceInput.State.LISTENING)
        d.onCommit("how do I center a div")
        d.onPartial("in flexbox without")
        val speech = floatArrayOf(0.1f, 0.5f, 0.9f, 0.7f, 0.3f, 0.8f, 1f, 0.6f, 0.2f, 0.05f, 0.4f, 0.75f)
        repeat(60) { i ->
            d.onLevel(speech[i % speech.size])
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(66))
        }
        return d
    }

    private fun withVoice(block: (MainActivity, ChatFragment) -> Unit) {
        VoiceInput.deviceAvailableOverride = true
        try { withChat(block) } finally { VoiceInput.deviceAvailableOverride = null }
    }

    @Test fun chatDictatingDark() = withVoice { a, chat ->
        dictateInto(a, chat)
        snap(root(a), "chat_dictating_dark")
    }

    @Test fun chatTranscribingDark() = withVoice { a, chat ->
        val d = dictationOf(chat)
        d.onStateChanged(VoiceInput.State.LISTENING)
        d.onStateChanged(VoiceInput.State.TRANSCRIBING)
        idle()
        snap(root(a), "chat_transcribing_dark")
    }

    @Test fun dictationPastesAndNeverSends() = withVoice { a, chat ->
        val d = dictateInto(a, chat)
        d.onStateChanged(VoiceInput.State.IDLE)
        idle()
        val field = a.findViewById<android.widget.EditText>(R.id.chatEditText)
        org.junit.Assert.assertEquals("Quick question. How do I center a div in flexbox without", field.text.toString())
        org.junit.Assert.assertTrue(
            field.text.getSpans(0, field.length(), android.text.style.ForegroundColorSpan::class.java).isEmpty()
        )
        org.junit.Assert.assertTrue(ViewModelProvider(a)[ChatViewModel::class.java].chatMessages.value.isNullOrEmpty())
        org.junit.Assert.assertEquals(View.VISIBLE, a.findViewById<View>(R.id.modelNameTextView).visibility)
        org.junit.Assert.assertEquals(View.GONE, a.findViewById<View>(R.id.voiceWave).visibility)
    }

    @Test fun cloudResultPastesAtCaret() = withVoice { a, chat ->
        val d = dictationOf(chat)
        val field = a.findViewById<android.widget.EditText>(R.id.chatEditText)
        field.setText("Summarize: ")
        d.onStateChanged(VoiceInput.State.LISTENING)
        d.onStateChanged(VoiceInput.State.TRANSCRIBING)
        d.onStateChanged(VoiceInput.State.IDLE)
        d.onCommit("the meeting notes")
        org.junit.Assert.assertEquals("Summarize: the meeting notes", field.text.toString())
    }

    @Test fun sendWithoutKeySaysWhy() = withChat { a, _ ->
        a.findViewById<android.widget.EditText>(R.id.chatEditText).setText("Hello?")
        a.findViewById<View>(R.id.sendChatButton).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        val notice = a.findViewById<android.view.ViewGroup>(android.R.id.content).findViewWithTag<android.widget.TextView>("glass_notice")
        org.junit.Assert.assertNotNull("no notice shown", notice)
        org.junit.Assert.assertEquals(a.getString(R.string.notice_need_key), notice.text.toString())
        snap(root(a), "notice_need_key_dark")
    }

    @Test fun backdropRunsUnderStatusBar() = withChat { a, _ ->
        // Pretend a phone: 40dp status bar, 24dp gesture bar.
        val d = a.resources.displayMetrics.density
        val insets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, (40 * d).toInt(), 0, 0))
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, (24 * d).toInt()))
            .build()
        val content = a.findViewById<View>(R.id.rootLayout)
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(content, insets)
        idle()
        val backdrop = a.findViewById<View>(R.id.chatBackdrop)
        val loc = IntArray(2); backdrop.getLocationInWindow(loc)
        val rootLoc = IntArray(2); content.getLocationInWindow(rootLoc)
        org.junit.Assert.assertEquals("backdrop starts at the very top", rootLoc[1], loc[1])
        org.junit.Assert.assertEquals(content.height, backdrop.height)
        val bar = a.findViewById<View>(R.id.topBarGlass)
        org.junit.Assert.assertTrue("bar content clears the status bar", bar.paddingTop >= (40 * d).toInt())
        snap(root(a), "chat_insets_dark")
    }

    @Test fun chatTextSizeFromAppearance() = withChat { a, _ ->
        openSettingsRow(a, R.id.settingsRowAppearance)
        a.findViewById<View>(R.id.chatTextXL).performClick(); idle()
        org.junit.Assert.assertEquals(130, SharedPreferencesHelper(a).getFontSizeCh())
        a.supportFragmentManager.popBackStackImmediate(); a.supportFragmentManager.popBackStackImmediate(); idle()
        seedConversation(a); idle()
        snap(root(a), "chat_text_xl_dark")
    }

    /** Pump the main looper in real time while a background stream (demo thread) runs. */
    private fun waitFor(timeoutMs: Long, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        }
    }

    /** Long RP chats keep Facts current: after a reply near the API window, the model rewrites the
     *  chat's Facts. The Memory note the user wrote is never saved over. */
    @Test fun rpAutoMemoryUpdatesAfterReply() = withChat { a, _ ->
        seedRp()
        val vm = ViewModelProvider(a)[ChatViewModel::class.java]
        val prefs = SharedPreferencesHelper(a)
        val oldBudget = prefs.getChatMemoryCount()
        prefs.saveChatMemoryCount(8) // tiny window, so the first exchange is already near its end
        try {
            a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
            vm.setModel(DemoModel.ID); idle()
            val mira = runBlocking { vm.getRpRepository().getAllCharactersOnce() }.first { it.name == "Mira Vance" }
            vm.startRpChatWithCharacter(mira); settle()
            val input = a.findViewById<android.widget.EditText>(R.id.chatEditText)
            val send = a.findViewById<View>(R.id.sendChatButton)
            // Wait for the reply to this send, not the greeting that's already there.
            fun sendAndWait(text: String) {
                val before = vm.chatMessages.value.orEmpty().size
                input.setText(text); send.performClick()
                waitFor(20_000) {
                    vm.isAwaitingResponse.value == false && vm.chatMessages.value.orEmpty().let {
                        it.size >= before + 2 && it.last().role == "assistant"
                    }
                }
            }
            // Switched off: a reply that would qualify leaves Facts empty.
            prefs.saveRpAutoMemory(false)
            prefs.saveRpMemory(mira.id, "kept")
            sendAndWait("Where are we going?")
            Thread.sleep(300); idle()
            org.junit.Assert.assertEquals("switch off leaves Facts alone", "", vm.currentRpFacts())
            // Switched on: the next reply folds the story into Facts; Memory stays the user's note.
            prefs.saveRpAutoMemory(true)
            sendAndWait("And then?")
            waitFor(10_000) { vm.currentRpFacts().isNotEmpty() }
            org.junit.Assert.assertEquals(DemoModel.DEMO_MEMORY.trim(), vm.currentRpFacts())
            org.junit.Assert.assertEquals("Memory is never rewritten", "kept", prefs.getRpMemory(mira.id))
        } finally {
            prefs.saveChatMemoryCount(oldBudget)
            prefs.saveRpAutoMemory(true)
            prefs.saveRpMemory(null, "")
            a.findViewById<View>(R.id.tabChat).performClick(); settle()
        }
    }

    /** Chats with a few characters, oldest last, so the Roleplay home has rows to show. */
    private fun seedRpChats() = runBlocking {
        val dao = db.chatDao()
        val chars = db.rpDao().getAllCharactersOnce().associateBy { it.name }
        fun chat(name: String, hoursAgo: Long, vararg lines: Pair<String, String>) {
            val id = chars.getValue(name).id
            runBlocking {
                dao.insertSessionAndMessages(
                    ChatSession(
                        title = name, modelUsed = "openrouter/free", mode = ChatMode.RP.storageValue, characterId = id,
                        timestamp = System.currentTimeMillis() - hoursAgo * 3600_000L
                    ),
                    lines.map { (role, text) -> ChatMessage(sessionId = 0, role = role, content = JsonPrimitive(text).toString()) }
                )
            }
        }
        chat("Mira Vance", 1, "assistant" to "*wipes her hands* You again?", "user" to "The coupling is still leaking.", "assistant" to "*sighs and grabs a wrench* Fine. Show me.")
        chat("Mira Vance", 30, "assistant" to "*wipes her hands* You again?", "user" to "Long story.")
        chat("Professor Hale", 5, "assistant" to "Ah, you have come at last. Sit, sit. Have I ever told you about the Vell empire?", "user" to "Not yet.")
        chat("Kestrel", 26, "assistant" to "It never stops raining in this city. *lights a cigarette*", "user" to "I need a detective.")
        chat("Ondine", 80, "assistant" to "Ask me again when the tide turns.")
    }

    /** The Roleplay tab opens on the characters list: mid-story ones first, then everyone else. */
    @Test fun rpHomeDark() = withChat { a, _ ->
        seedRp(); seedRpChats()
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        val home = a.findViewById<View>(R.id.rpHome)
        org.junit.Assert.assertEquals(View.VISIBLE, home.visibility)
        // Composer and chip step aside; the tabs stay.
        org.junit.Assert.assertEquals(View.GONE, a.findViewById<View>(R.id.composerDock).visibility)
        val rows = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rpHomeList)
        // Seven characters (the six seeded plus the stock one), one row each: Mira has two chats
        // but one row, and three have no chat yet.
        org.junit.Assert.assertEquals(7, rows.adapter!!.itemCount)
        snap(root(a), "rp_home_dark")

        // Opening a row resumes that chat: the list goes, the composer and the chip come back.
        rows.findViewHolderForAdapterPosition(0)!!.itemView.performClick(); settle()
        org.junit.Assert.assertEquals(View.GONE, home.visibility)
        org.junit.Assert.assertEquals(View.VISIBLE, a.findViewById<View>(R.id.composerDock).visibility)
        // No History in Roleplay: the top-left button is the way back to the list.
        org.junit.Assert.assertEquals(a.getString(R.string.rp_home_back), a.findViewById<View>(R.id.openSavedChatsButton).contentDescription)
        snap(root(a), "rp_thread_dark")

        // The Roleplay tab, tapped again inside a chat, goes back to the list.
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        org.junit.Assert.assertEquals(View.VISIBLE, home.visibility)
        // On the list the top-left button is Settings, not the History panel.
        val topLeft = a.findViewById<View>(R.id.openSavedChatsButton)
        org.junit.Assert.assertEquals(a.getString(R.string.settings_title), topLeft.contentDescription)
        topLeft.performClick(); settle()
        org.junit.Assert.assertTrue("settings opened",
            a.supportFragmentManager.fragments.any { it is SettingsFragment && it.isVisible })
        org.junit.Assert.assertNotEquals("history stays shut", View.VISIBLE, a.findViewById<View>(R.id.historyDrawerContainer).visibility)
        a.supportFragmentManager.popBackStackImmediate(); settle()
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
        org.junit.Assert.assertEquals(View.GONE, home.visibility)
        org.junit.Assert.assertEquals(a.getString(R.string.cd_history), topLeft.contentDescription)
    }

    /** A character you have not talked to yet starts a chat from the list. */
    @Test fun rpHomeStartsChatWithNewCharacter() = withChat { a, _ ->
        seedRp(); seedRpChats()
        val vm = ViewModelProvider(a)[ChatViewModel::class.java]
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        val rows = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rpHomeList)
        // Chats first, then the rest by name: Aurelia, then Theo.
        val aurelia = rows.findViewHolderForAdapterPosition(4)!!.itemView
        org.junit.Assert.assertEquals("Aurelia", aurelia.findViewById<android.widget.TextView>(R.id.rpChatName).text)
        org.junit.Assert.assertEquals(a.getString(R.string.rp_home_start), aurelia.findViewById<android.widget.TextView>(R.id.rpChatPreview).text)
        aurelia.performClick(); settle()
        org.junit.Assert.assertEquals(View.GONE, a.findViewById<View>(R.id.rpHome).visibility)
        org.junit.Assert.assertEquals("Aurelia", vm.activeRpCharacter.value?.name)
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
    }

    /** In Roleplay the composer's settings button is the character menu; the controls moved under +. */
    @Test fun rpControlsButtonOpensCharacterPanel() = withChat { a, _ ->
        seedRp()
        val vm = ViewModelProvider(a)[ChatViewModel::class.java]
        // In Chat it stays the controls button.
        org.junit.Assert.assertEquals(a.getString(R.string.cd_controls), a.findViewById<View>(R.id.controlsButton).contentDescription)
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        val mira = runBlocking { vm.getRpRepository().getAllCharactersOnce() }.first { it.name == "Mira Vance" }
        vm.startRpChatWithCharacter(mira); settle()
        val button = a.findViewById<View>(R.id.controlsButton)
        org.junit.Assert.assertEquals(a.getString(R.string.cd_rp_scene), button.contentDescription)
        button.performClick(); settle()
        org.junit.Assert.assertTrue("the character menu opened", rpPanelShowing(a))
        org.junit.Assert.assertEquals("the old controls card stays shut", View.GONE, a.findViewById<View>(R.id.headerContainer).visibility)
        snapDialog(a, "rp_character_panel_tiles_dark", sharp = true)
        val grid = rpPanelGrid(a)
        for (i in 0 until grid.childCount) {
            val tile = grid.getChildAt(i)
            org.junit.Assert.assertTrue("tile $i has a size", tile.width > 0)
            org.junit.Assert.assertEquals("tile $i is square", tile.width, tile.height)
        }
        dismissRpPanel(a)
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
        org.junit.Assert.assertEquals(a.getString(R.string.cd_controls), button.contentDescription)
    }

    /** Every tile opens its page, and back lands on the sheet exactly where it was. */
    @Test fun rpPanelPagesDark() = withChat { a, _ ->
        seedRp()
        val vm = ViewModelProvider(a)[ChatViewModel::class.java]
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        val mira = runBlocking { vm.getRpRepository().getAllCharactersOnce() }.first { it.name == "Mira Vance" }
        vm.startRpChatWithCharacter(mira); settle()
        a.findViewById<View>(R.id.controlsButton).performClick(); settle()
        fun sheetTop(): Int {
            var v: View = rpPanelGrid(a)
            while ((v.parent as? View) !is androidx.coordinatorlayout.widget.CoordinatorLayout) v = v.parent as View
            return IntArray(2).also { v.getLocationInWindow(it) }[1]
        }
        val top = sheetTop()
        val names = listOf(
            R.string.rp_panel_memory to "memory", R.string.rp_panel_voice to "voice",
            R.string.rp_panel_layout to "layout", R.string.rp_panel_wallpaper to "wallpaper",
            R.string.rp_panel_style to "style", R.string.rp_panel_lore to "lore",
            R.string.rp_panel_persona to "persona", R.string.rp_panel_edit to "edit",
        )
        for ((label, name) in names) {
            val grid = rpPanelGrid(a)
            (0 until grid.childCount).map { grid.getChildAt(it) }
                .first { it.contentDescription.toString().startsWith(a.getString(label)) }
                .performClick(); settle()
            snap(root(a), "rp_page_${name}_dark")
            if (name == "persona") {
                // A photo comes from the gallery app or the photo picker, in a card under the portrait.
                a.findViewById<View>(R.id.rpPersonaAvatarFrame).performClick(); settle()
                val found = ArrayList<View>()
                root(a).findViewsWithText(found, a.getString(R.string.avatar_source_photos), View.FIND_VIEWS_WITH_TEXT)
                org.junit.Assert.assertTrue("the source card opened", found.isNotEmpty())
                snap(root(a), "rp_avatar_source_dark")
                a.onBackPressedDispatcher.onBackPressed(); settle()
            }
            a.supportFragmentManager.popBackStack(); settle()
            org.junit.Assert.assertTrue("panel still up after $name", rpPanelShowing(a))
            org.junit.Assert.assertEquals("sheet moved after $name", top, sheetTop())
        }
        dismissRpPanel(a)
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
    }

    /** Roleplay opens where it was left: on the list, or inside the chat. */
    @Test fun rpResumesWhereItWasLeft() = withChat { a, _ ->
        seedRp(); seedRpChats()
        val home = a.findViewById<View>(R.id.rpHome)
        // Left on the list: the list again.
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        org.junit.Assert.assertEquals(View.VISIBLE, home.visibility)
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        org.junit.Assert.assertEquals("left on the list", View.VISIBLE, home.visibility)
        // Left inside a chat: that chat again, not the list.
        a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rpHomeList)
            .findViewHolderForAdapterPosition(0)!!.itemView.performClick(); settle()
        org.junit.Assert.assertEquals(View.GONE, home.visibility)
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        org.junit.Assert.assertEquals("left in a chat", View.GONE, home.visibility)
        org.junit.Assert.assertEquals(View.VISIBLE, a.findViewById<View>(R.id.composerDock).visibility)
        // Back to the list, leave, return: the list.
        a.findViewById<View>(R.id.openSavedChatsButton).performClick(); settle()
        org.junit.Assert.assertEquals(View.VISIBLE, home.visibility)
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        org.junit.Assert.assertEquals(View.VISIBLE, home.visibility)
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
    }

    /** A chat's ⋮ on the list: new chat, edit the character, delete (set apart, in the dim red). */
    @Test fun rpHomeMenuDark() = withChat { a, _ ->
        seedRp(); seedRpChats()
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        val rows = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rpHomeList)
        rows.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<View>(R.id.rpChatMore).performClick(); idle()
        var card: View = a.findViewById<View>(R.id.messageMenuLabel)
        while (card !is GlassLinearLayout) card = card.parent as View
        val labels = (0 until card.childCount).mapNotNull {
            card.getChildAt(it).findViewById<android.widget.TextView>(R.id.messageMenuLabel)?.text?.toString()
        }
        org.junit.Assert.assertEquals(listOf("New chat", "Edit character", "Delete chat"), labels)
        snap(root(a), "rp_home_menu_dark")
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
    }

    /** The panel's History tile lists this character's chats, newest first, with a fresh one last. */
    @Test fun rpPanelHistoryDark() = withChat { a, _ ->
        seedRp(); seedRpChats()
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rpHomeList)
            .findViewHolderForAdapterPosition(0)!!.itemView.performClick(); settle()
        a.findViewById<View>(R.id.controlsButton).performClick(); settle()
        val grid = rpPanelGrid(a)
        val history = (0 until grid.childCount).map { grid.getChildAt(it) }
            .first { it.contentDescription == a.getString(R.string.rp_panel_history) }
        history.performClick(); settle()
        val page = a.supportFragmentManager.fragments.filterIsInstance<RpChatHistoryFragment>().single().requireView()
        val list = page.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rpHistoryList)
        // Rows are built from Room queries on its own thread; a busy run needs more than one settle.
        waitFor(5000) {
            (0 until (list.adapter?.itemCount ?: 0)).count {
                list.findViewHolderForAdapterPosition(it)?.itemView?.findViewById<View>(R.id.rpHistoryPreview) != null
            } >= 2
        }
        val previews = mutableListOf<String>()
        val current = mutableListOf<Boolean>()
        for (i in 0 until list.adapter!!.itemCount) {
            val row = list.findViewHolderForAdapterPosition(i)?.itemView ?: continue
            row.findViewById<android.widget.TextView>(R.id.rpHistoryPreview)?.let {
                previews += it.text.toString()
                current += row.findViewById<View>(R.id.rpHistoryCurrent).visibility == View.VISIBLE
            }
        }
        org.junit.Assert.assertEquals(listOf("sighs and grabs a wrench Fine. Show me.", "You: Long story."), previews)
        org.junit.Assert.assertEquals("the open chat is marked", listOf(true, false), current)
        snap(root(a), "rp_panel_history_dark")
        // Picking the older chat opens it and closes the page.
        val vm = ViewModelProvider(a)[ChatViewModel::class.java]
        val opened = vm.getCurrentSessionId()
        (0 until list.adapter!!.itemCount).mapNotNull { list.findViewHolderForAdapterPosition(it)?.itemView }
            .last { it.findViewById<View>(R.id.rpHistoryPreview) != null }.performClick(); settle()
        org.junit.Assert.assertTrue(a.supportFragmentManager.fragments.none { it is RpChatHistoryFragment })
        org.junit.Assert.assertNotEquals(opened, vm.getCurrentSessionId())
        // Picking a chat lands in it: the sheet closes instead of staying over the new chat.
        org.junit.Assert.assertFalse("panel closes after picking a chat", rpPanelShowing(a))
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
    }

    @Test fun rpHomeEmptyDark() = withChat { a, _ ->
        // No characters at all, the stock one included.
        runBlocking { db.rpDao().getAllCharactersOnce().forEach { db.rpDao().deleteCharacter(it.id) } }
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        org.junit.Assert.assertEquals(View.VISIBLE, a.findViewById<View>(R.id.rpHomeEmpty).visibility)
        snap(root(a), "rp_home_empty_dark")
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
    }

    @Test @Config(qualifiers = LIGHT)
    fun rpHomeLight() = withChat { a, _ ->
        seedRp(); seedRpChats()
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        snap(root(a), "rp_home_light")
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
    }

    /** RP with the demo model: a character reply, then Continue on an empty composer takes the next beat. */
    @Test fun rpConversationContinueDark() = withChat { a, _ ->
        seedRp()
        val vm = ViewModelProvider(a)[ChatViewModel::class.java]
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        // RP keeps its own model; pick the demo once we're there.
        vm.setModel(DemoModel.ID); idle()
        val mira = runBlocking { vm.getRpRepository().getAllCharactersOnce() }.first { it.name == "Mira Vance" }
        vm.startRpChatWithCharacter(mira); settle()
        val input = a.findViewById<android.widget.EditText>(R.id.chatEditText)
        val send = a.findViewById<com.google.android.material.button.MaterialButton>(R.id.sendChatButton)
        input.setText("I shake the rain off and sit down across from her.")
        send.performClick()
        waitFor(30_000) { vm.isAwaitingResponse.value == false && vm.chatMessages.value.orEmpty().lastOrNull()?.role == "assistant" }
        idle()
        org.junit.Assert.assertEquals(DemoModel.ID, vm.activeChatModel.value)
        org.junit.Assert.assertTrue("continue offered after a reply: rp=${vm.isRpMode()} send=${vm.canSendRpMessage()} " +
            "await=${vm.isAwaitingResponse.value} roles=${vm.chatMessages.value.orEmpty().map { it.role }}", vm.canContinueRpStory())
        org.junit.Assert.assertEquals("empty composer offers Continue",
            a.getString(R.string.rp_continue), send.contentDescription)
        val beforeMsgs = vm.chatMessages.value.orEmpty()
        val before = beforeMsgs.size
        val beforeText = vm.getMessageText(beforeMsgs.last().content)
        send.performClick()
        waitFor(30_000) { vm.isAwaitingResponse.value == false && vm.getMessageText(vm.chatMessages.value.orEmpty().last().content).length > beforeText.length }
        idle()
        val msgs = vm.chatMessages.value.orEmpty()
        // Continue is a hidden turn: no bubble for the prompt, and no new reply bubble either.
        // The words land at the end of the last reply.
        org.junit.Assert.assertEquals(before, msgs.size)
        org.junit.Assert.assertEquals("assistant", msgs.last().role)
        val afterText = vm.getMessageText(msgs.last().content)
        org.junit.Assert.assertTrue("kept what was there: $afterText", afterText.startsWith(beforeText))
        org.junit.Assert.assertTrue(afterText.length > beforeText.length)
        org.junit.Assert.assertTrue(msgs.none { vm.getMessageText(it.content) == a.getString(R.string.rp_continue_prompt) })
        org.junit.Assert.assertNull("nothing left to extend once the turn is over", vm.continuationText)
        snap(root(a), "rp_conversation_dark")
        SharedPreferencesHelper(a).saveRpMemory(mira.id, "Owes Sam a favor from the Kessel run. Hates the innkeeper.")
        // Top of a chat with messages: no stray rule under the tabs (the old scroll-progress bar).
        org.junit.Assert.assertEquals(0f, a.findViewById<View>(R.id.progressBar).alpha)
        // Per-character wallpaper, voice and bubbles, as the panel shows them.
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val wp = BackgroundPhoto.file(ctx, BackgroundPhoto.slotForCharacter(mira.id))
        wp.parentFile?.mkdirs()
        wp.outputStream().use { out ->
            Bitmap.createBitmap(400, 700, Bitmap.Config.ARGB_8888).apply {
                val c = Canvas(this)
                val paint = android.graphics.Paint()
                paint.shader = android.graphics.LinearGradient(0f, 0f, 400f, 700f, 0xFF505050.toInt(), 0xFF1A1A1A.toInt(), android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, 400f, 700f, paint)
            }.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        SharedPreferencesHelper(a).saveRpLayout(mira.id, SharedPreferencesHelper.RP_LAYOUT_BUBBLES)
        SharedPreferencesHelper(a).saveRpVoice(mira.id, SharedPreferencesHelper.RpVoice(null, 0.8f, 1f))
        // The panel opens from the character's speaker line; the composer pill is Ask's model picker.
        org.junit.Assert.assertEquals(View.GONE, a.findViewById<View>(R.id.modelNameTextView).visibility)
        (a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.chatRecyclerView).adapter as ChatAdapter).onSpeakerClick!!.invoke(); settle()
        snapDialog(a, "rp_character_panel_dark", sharp = true)
        dismissRpPanel(a)
        // Re-apply RP chrome (the panel reads prefs; the chat reads them on mode change).
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        val bg = a.findViewById<AmbientBackgroundView>(R.id.ambientBackground)
        org.junit.Assert.assertEquals(BackgroundPhoto.slotForCharacter(mira.id), bg.photoSlot)
        snap(root(a), "rp_conversation_bubbles_dark")
        SharedPreferencesHelper(a).saveRpLayout(mira.id, SharedPreferencesHelper.RP_LAYOUT_CLASSIC)
        BackgroundPhoto.delete(ctx, BackgroundPhoto.slotForCharacter(mira.id))
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
    }

    @Test fun demoModelStreamsWithoutKey() = withChat { a, _ ->
        val vm = ViewModelProvider(a)[ChatViewModel::class.java]
        org.junit.Assert.assertTrue("demo is in the model list",
            SharedPreferencesHelper(a).getCustomModels().any { DemoModel.isDemo(it.apiIdentifier) })
        vm.setModel(DemoModel.ID); idle()
        a.findViewById<android.widget.EditText>(R.id.chatEditText).setText("Hi! What can you do?")
        a.findViewById<View>(R.id.sendChatButton).performClick()
        waitFor(30_000) { vm.isAwaitingResponse.value == false && (vm.chatMessages.value?.size ?: 0) >= 2 }
        idle()
        val reply = vm.chatMessages.value.orEmpty().last()
        org.junit.Assert.assertEquals("assistant", reply.role)
        org.junit.Assert.assertTrue(vm.getMessageText(reply.content).contains("demo model"))
        org.junit.Assert.assertFalse("thinking came through", reply.reasoning.isNullOrBlank())
        snap(root(a), "chat_demo_reply_dark")
    }

    @Test fun micHiddenWithoutAnyEngine() {
        // No recognizer and no voice model: nothing to dictate with.
        VoiceInput.deviceAvailableOverride = false
        try {
            withChat { a, _ -> org.junit.Assert.assertEquals(View.GONE, a.findViewById<View>(R.id.speechButton).visibility) }
        } finally { VoiceInput.deviceAvailableOverride = null }
    }

    @Test fun settingsVoiceCloudDark() = withVoice { a, _ ->
        SharedPreferencesHelper(a).setVoiceInputProvider(VoiceEngine.CLOUD.key)
        SharedPreferencesHelper(a).setVoiceInputModel("openai/whisper-1")
        openSettingsRow(a, R.id.settingsRowVoice)
        snap(root(a), "settings_voice_cloud_dark")
    }

    private fun openSettingsRow(a: MainActivity, rowId: Int) {
        a.findViewById<View>(R.id.settingsButton).performClick(); idle()
        val sf = a.supportFragmentManager.fragments.filterIsInstance<SettingsFragment>().first()
        sf.requireView().findViewById<View>(rowId).performClick(); idle()
    }

    // ---- Grok-form chrome: mode tabs, anchored popover, pull-to-dismiss ----

    @Test fun chatRoleplayTabDark() = withChat { a, _ ->
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        org.junit.Assert.assertTrue(a.findViewById<View>(R.id.tabRoleplay).isSelected)
        org.junit.Assert.assertEquals(View.GONE, a.findViewById<View>(R.id.emptyAction).visibility)
        snap(root(a), "chat_rp_empty_dark")
        // Mode persists across tests; leave the app in Chat.
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
    }

    @Test fun manageModelsFromPopoverDark() = withChat { a, _ ->
        a.findViewById<View>(R.id.modelNameTextView).performClick(); idle()
        val footer = a.findViewById<android.view.ViewGroup>(R.id.popoverFooter)
        footer.getChildAt(0).performClick(); idle()
        snap(root(a), "manage_models_dark")
        // Long-press a row: the options sheet names the model, then Edit / Open page / Remove.
        val list = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewModels)
        val row = (0 until list.childCount).map { list.getChildAt(it) }.first {
            it.findViewById<android.widget.TextView>(R.id.textModelName).text != "Free"
        }
        row.performLongClick(); idle()
        snapDialog(a, "model_options_dark")
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.dismiss(); idle()
        // The add button's menu: catalog, local network, or by id.
        a.findViewById<View>(R.id.modelPickerAdd).performClick(); idle()
        snap(root(a), "models_add_menu_dark")
    }

    @Test fun openRouterCatalogDark() = withChat { a, _ ->
        SharedPreferencesHelper(a).saveOpenRouterModels(listOf(
            LlmModel("Anthropic: Claude Opus 4.1", "anthropic/claude-opus-4.1", isVisionCapable = true, isReasoningCapable = true, created = 5),
            LlmModel("OpenAI: GPT-5", "openai/gpt-5", isVisionCapable = true, isReasoningCapable = true, created = 6),
            LlmModel("Google: Gemini 2.5 Flash Image", "google/gemini-2.5-flash-image", isVisionCapable = true, isImageGenerationCapable = true, created = 4),
            LlmModel("DeepSeek: V3.1 (free)", "deepseek/deepseek-chat-v3.1:free", isVisionCapable = false, created = 3),
            LlmModel("Mistral: Magistral Medium", "mistralai/magistral-medium-2506", isVisionCapable = false, created = 2),
            LlmModel("Qwen: Qwen3 Coder", "qwen/qwen3-coder", isVisionCapable = false, created = 1),
            LlmModel("Sao10K: Euryale 70B", "sao10k/l3-euryale-70b", isVisionCapable = false, created = 0),
        ))
        pushFragment(a, OpenRouterModelsFragment())
        snap(root(a), "openrouter_models_dark")
    }

    /** Regression: the grabber panel must follow a downward drag and dismiss on release. */
    @Test fun controlsPanelPullDownDismissesDark() = withChat { a, _ ->
        a.findViewById<View>(R.id.controlsButton).performClick(); idle()
        val panel = a.findViewById<View>(R.id.headerContainer)
        org.junit.Assert.assertEquals(View.VISIBLE, panel.visibility)
        val loc = IntArray(2).also { panel.getLocationOnScreen(it) }
        val x = loc[0] + panel.width / 2f
        val y = loc[1] + 12f
        val t0 = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, dy: Float, dt: Long) =
            android.view.MotionEvent.obtain(t0, t0 + dt, action, x, y + dy, 0).also { it.setLocation(x - loc[0], y + dy - loc[1]) }
        panel.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, 0f, 0))
        panel.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_MOVE, 40f, 30))
        panel.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_MOVE, panel.height * 0.45f, 80))
        org.junit.Assert.assertTrue("panel should follow the finger", panel.translationY > panel.height * 0.3f)
        snap(root(a), "controls_panel_pull_dark")
        panel.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, panel.height * 0.45f, 400))
        idle()
        org.junit.Assert.assertEquals(View.GONE, panel.visibility)
    }

    private fun swipe(v: View, fromX: Float, toX: Float, y: Float) {
        val t0 = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, x: Float, dt: Long) = android.view.MotionEvent.obtain(t0, t0 + dt, action, x, y, 0)
        v.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, fromX, 0))
        for (k in 1..8) v.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * k / 8f, 20L * k))
        v.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, toX, 200))
        idle()
    }

    /** Regression: wide swipes page History | Chat | Roleplay, and swipe the history closed. */
    @Test fun wideSwipesNavigateDark() = withChat { a, _ ->
        val root = a.findViewById<View>(R.id.fragment_container).let { it as? SwipeNavLayout ?: (it.parent as View) }
        val w = root.width.toFloat()
        val y = root.height * 0.45f
        // Mode persists across tests; start from Chat.
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
        swipe(root, w * 0.85f, w * 0.15f, y)
        org.junit.Assert.assertTrue("left swipe → Roleplay", a.findViewById<View>(R.id.tabRoleplay).isSelected)
        swipe(root, w * 0.15f, w * 0.85f, y)
        org.junit.Assert.assertTrue("right swipe → Chat", a.findViewById<View>(R.id.tabChat).isSelected)
        // A short nudge must not navigate (a sixth of the width commits; this is well under).
        swipe(root, w * 0.5f, w * 0.56f, y)
        org.junit.Assert.assertEquals(View.GONE, a.findViewById<View>(R.id.historyDrawerContainer).visibility)
        swipe(root, w * 0.15f, w * 0.85f, y)
        val drawer = a.findViewById<View>(R.id.historyDrawerContainer)
        org.junit.Assert.assertEquals("right swipe in Chat → history", View.VISIBLE, drawer.visibility)
        swipe(drawer, w * 0.85f, w * 0.15f, y)
        org.junit.Assert.assertEquals("left swipe closes history", View.GONE, drawer.visibility)
    }

    /** Regression: the tab underline sits centred under the active word. */
    @Test fun modeTabUnderlineCentredDark() = withChat { a, _ ->
        for (id in listOf(R.id.tabRoleplay, R.id.tabChat)) {
            a.findViewById<View>(id).performClick(); idle()
            val tab = a.findViewById<android.widget.TextView>(id)
            val ind = a.findViewById<View>(R.id.modeTabIndicator)
            val tl = IntArray(2).also { tab.getLocationInWindow(it) }
            val il = IntArray(2).also { ind.getLocationInWindow(it) }
            val textCenter = tl[0] + tab.width / 2f
            val indCenter = il[0] + ind.width / 2f
            org.junit.Assert.assertEquals(textCenter, indCenter, 2f)
        }
    }

    // ---- Theme UI: glass toggles, ambient backgrounds ----

    @Test fun settingsAppearanceDark() = withChat { a, _ ->
        SharedPreferencesHelper(a).saveBackgroundStyle(AmbientBackgroundView.Style.DRIFT.key)
        openSettingsRow(a, R.id.settingsRowAppearance); settle(); snap(root(a), "settings_appearance_dark")
        // Only the open section is inflated; the others are never built.
        org.junit.Assert.assertNotNull(a.findViewById<View>(R.id.appearanceSection))
        for (id in listOf(R.id.voiceSection, R.id.hapticsSection, R.id.modelsSection, R.id.advancedSection, R.id.dataSection))
            org.junit.Assert.assertNull(a.findViewById<View>(id))
        SharedPreferencesHelper(a).saveBackgroundStyle(AmbientBackgroundView.Style.OFF.key)
    }

    /** Glass toggles on and off, one held down (thumb swells into a lens). */
    private fun toggles(a: MainActivity, name: String) {
        openSettingsRow(a, R.id.settingsRowAdvanced)
        val detail = a.supportFragmentManager.fragments.filterIsInstance<SettingsDetailFragment>().first().requireView()
        val switches = mutableListOf<androidx.appcompat.widget.SwitchCompat>()
        fun collect(v: View) {
            if (v is androidx.appcompat.widget.SwitchCompat && v.isShown) switches += v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
        }
        collect(detail)
        generateSequence(switches.firstOrNull()?.parent) { it.parent }.filterIsInstance<android.widget.ScrollView>().firstOrNull()
            ?.let { sv -> sv.scrollTo(0, (switches.first().top + 0).coerceAtLeast(0)); sv.fullScroll(View.FOCUS_DOWN) }
        switches.forEachIndexed { i, s -> s.isChecked = i % 2 == 0 }
        switches.getOrNull(2)?.isPressed = true
        idle()
        snap(root(a), name)
    }

    @Test fun settingsTogglesDark() = withChat { a, _ -> toggles(a, "settings_toggles_dark") }

    /** Every background style (and Adaptive in both modes), each full-screen over the canvas. */
    private fun ambientGrid(a: MainActivity, name: String) {
        seedBackgroundPhoto(a)
        val cells = listOf(
            "photo" to (AmbientBackgroundView.Style.PHOTO to ChatMode.ASK),
            "drift" to (AmbientBackgroundView.Style.DRIFT to ChatMode.ASK),
            "flow" to (AmbientBackgroundView.Style.FLOW to ChatMode.ASK),
            "adaptive_rp" to (AmbientBackgroundView.Style.ADAPTIVE to ChatMode.RP),
        )
        val host = a.findViewById<android.view.ViewGroup>(android.R.id.content)
        for ((label, cell) in cells) {
            val frame = android.widget.FrameLayout(a).apply {
                setBackgroundColor(androidx.core.content.ContextCompat.getColor(a, R.color.xai_canvas))
            }
            val v = AmbientBackgroundView(a).apply {
                styleOverride = cell.first
                mode = cell.second
                animated = false
            }
            frame.addView(v, android.view.ViewGroup.LayoutParams(-1, -1))
            host.addView(frame, android.view.ViewGroup.LayoutParams(-1, -1))
            idle()
            if (cell.first == AmbientBackgroundView.Style.PHOTO) awaitPhoto()
            if (cell.first == AmbientBackgroundView.Style.ADAPTIVE) {
                org.junit.Assert.assertEquals(AmbientBackgroundView.Style.FLOW, v.resolvedStyle)
            }
            snap(frame, "${name}_$label")
            host.removeView(frame)
        }
    }

    /** A synthetic "photo": soft light blobs over a dark-to-light sweep, saved where Photo reads it. */
    private fun seedBackgroundPhoto(a: MainActivity) {
        val w = 540; val h = 1200
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            android.graphics.Color.rgb(40, 70, 120), android.graphics.Color.rgb(230, 180, 120),
            android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = android.graphics.Color.argb(200, 250, 250, 250)
        c.drawCircle(w * 0.3f, h * 0.3f, 140f, p)
        c.drawCircle(w * 0.75f, h * 0.65f, 190f, p)
        val f = BackgroundPhoto.file(a)
        f.parentFile?.mkdirs()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        a.getSharedPreferences(SharedPreferencesHelper.MAIN_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putLong(BackgroundPhoto.KEY_VERSION, 1L).commit()
    }

    /** Photo decodes on a worker thread and posts back to main. */
    private fun awaitPhoto() {
        repeat(20) {
            Thread.sleep(50)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    @Test fun chatWithBackgroundDark() = withChat { a, _ ->
        SharedPreferencesHelper(a).saveBackgroundStyle(AmbientBackgroundView.Style.DRIFT.key)
        idle()
        val ambient = a.findViewById<AmbientBackgroundView>(R.id.ambientBackground)
        org.junit.Assert.assertEquals(AmbientBackgroundView.Style.DRIFT, ambient.resolvedStyle)
        snap(root(a), "chat_background_drift_dark")
        SharedPreferencesHelper(a).saveBackgroundStyle(AmbientBackgroundView.Style.OFF.key)
    }

    @Test fun chatWithPhotoBackgroundDark() = withChat { a, _ ->
        seedBackgroundPhoto(a)
        SharedPreferencesHelper(a).saveBackgroundStyle(AmbientBackgroundView.Style.PHOTO.key)
        idle(); awaitPhoto(); idle()
        snap(root(a), "chat_background_photo_dark")
        SharedPreferencesHelper(a).saveBackgroundStyle(AmbientBackgroundView.Style.OFF.key)
    }

    @Test fun settingsAppearancePhotoDark() = withChat { a, _ ->
        seedBackgroundPhoto(a)
        SharedPreferencesHelper(a).saveBackgroundStyle(AmbientBackgroundView.Style.PHOTO.key)
        openSettingsRow(a, R.id.settingsRowAppearance); settle(); awaitPhoto(); settle()
        org.junit.Assert.assertTrue(a.findViewById<View>(R.id.backgroundPhotoOptions).isShown)
        snap(root(a), "settings_appearance_photo_dark")
        SharedPreferencesHelper(a).saveBackgroundStyle(AmbientBackgroundView.Style.OFF.key)
    }

    // ---- Wide swipes: the page follows the finger ----

    private fun motion(v: View, action: Int, down: Long, t: Long, x: Float, y: Float) {
        val e = android.view.MotionEvent.obtain(down, t, action, x, y, 0)
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    /** Drag across [root] from its middle by [dx] in small steps; lifts only if [release]. */
    private fun drag(root: View, dx: Float, release: Boolean, stepMs: Long = 16L, y: Float = root.height * 0.45f) {
        val x0 = root.width * 0.5f
        val down = android.os.SystemClock.uptimeMillis()
        var t = down
        motion(root, android.view.MotionEvent.ACTION_DOWN, down, t, x0, y)
        val steps = 12
        for (i in 1..steps) {
            t += stepMs
            motion(root, android.view.MotionEvent.ACTION_MOVE, down, t, x0 + dx * i / steps, y)
        }
        if (release) {
            t += stepMs
            motion(root, android.view.MotionEvent.ACTION_UP, down, t, x0 + dx, y)
        }
    }

    private fun pagerShots(a: MainActivity): List<android.widget.ImageView> {
        val content = a.findViewById<android.view.ViewGroup>(R.id.rootLayout)
        return (0 until content.childCount).map { content.getChildAt(it) }.filterIsInstance<android.widget.ImageView>()
    }

    @Test fun swipeMidDragDark() = withChat { a, chat ->
        val root = chat.requireView()
        drag(root, -root.width * 0.3f, release = false, stepMs = 40L)
        // Side by side: the old page (a snapshot) under the finger, the next page right beside it.
        val shot = pagerShots(a).single()
        org.junit.Assert.assertEquals(-root.width * 0.3f, shot.translationX, root.width * 0.05f)
        val page = a.findViewById<View>(R.id.chatFrameView)
        org.junit.Assert.assertEquals(root.width * 0.7f, page.translationX, root.width * 0.05f)
        org.junit.Assert.assertTrue("next mode is already behind the snapshot", a.findViewById<View>(R.id.tabRoleplay).isSelected)
        snap(root(a), "swipe_mid_dark")
    }

    /** Drift (and any live ambient) used to break the pager snapshot, so swipes did nothing. */
    @Test fun swipeMidDragWithDriftBackground() = withChat { a, chat ->
        SharedPreferencesHelper(a).saveBackgroundStyle(AmbientBackgroundView.Style.DRIFT.key)
        idle()
        val ambient = a.findViewById<AmbientBackgroundView>(R.id.ambientBackground)
        // Simulate a live AGSL frame that left RuntimeShader on the shared paint, then a
        // software pager snapshot (drawToBitmap). That used to throw and cancel the swipe.
        val paint = AmbientBackgroundView::class.java.getDeclaredField("fieldPaint").apply {
            isAccessible = true
        }.get(ambient) as android.graphics.Paint
        paint.shader = android.graphics.RuntimeShader(
            "uniform float2 res; half4 main(float2 p) { return half4(0.5, 0.5, 0.5, 0.1); }"
        )
        val soft = android.graphics.Bitmap.createBitmap(
            ambient.width.coerceAtLeast(1), ambient.height.coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888
        )
        ambient.draw(android.graphics.Canvas(soft))
        soft.recycle()
        org.junit.Assert.assertNull("AGSL must not stay on the paint after a software draw", paint.shader)

        val root = chat.requireView()
        drag(root, -root.width * 0.3f, release = false, stepMs = 40L)
        org.junit.Assert.assertTrue(
            "pager snapshot must succeed with a live ambient background",
            pagerShots(a).isNotEmpty()
        )
        org.junit.Assert.assertTrue(a.findViewById<View>(R.id.tabRoleplay).isSelected)
        SharedPreferencesHelper(a).saveBackgroundStyle(AmbientBackgroundView.Style.OFF.key)
    }

    @Test fun swipeCancelComesBack() = withChat { a, chat ->
        val root = chat.requireView()
        drag(root, -root.width * 0.08f, release = true, stepMs = 120L)
        idle()
        org.junit.Assert.assertTrue(a.findViewById<View>(R.id.tabChat).isSelected)
        org.junit.Assert.assertTrue(pagerShots(a).isEmpty())
        org.junit.Assert.assertEquals(0f, a.findViewById<View>(R.id.chatFrameView).translationX, 0.5f)
    }

    @Test fun tabTapSlidesWithoutGap() = withChat { a, _ ->
        Settings.Global.putFloat(a.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        a.findViewById<View>(R.id.tabRoleplay).performClick()
        // First frame: the old page (snapshot) in place, the new one exactly a page to its right.
        val shot = pagerShots(a).single()
        val page = a.findViewById<View>(R.id.chatFrameView)
        org.junit.Assert.assertEquals(0f, shot.translationX, 0.5f)
        org.junit.Assert.assertEquals(page.width.toFloat(), page.translationX - shot.translationX, 2f)
        idle()
        org.junit.Assert.assertTrue(a.findViewById<View>(R.id.tabRoleplay).isSelected)
        org.junit.Assert.assertTrue(pagerShots(a).isEmpty())
        org.junit.Assert.assertEquals(0f, page.translationX, 0.5f)
    }

    @Test fun swipeCommitsToNextTab() = withChat { a, chat ->
        val root = chat.requireView()
        drag(root, -root.width * 0.3f, release = true, stepMs = 40L)
        idle()
        org.junit.Assert.assertTrue(a.findViewById<View>(R.id.tabRoleplay).isSelected)
        org.junit.Assert.assertEquals(0f, a.findViewById<View>(R.id.chatFrameView).translationX, 0.5f)
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
    }

    @Test fun swipeRightFromChatPullsHistory() = withChat { a, chat ->
        val root = chat.requireView()
        drag(root, root.width * 0.3f, release = false, stepMs = 40L)
        val panel = a.findViewById<View>(R.id.historyDrawerContainer)
        org.junit.Assert.assertTrue(panel.isShown)
        org.junit.Assert.assertTrue("drawer tracks the finger", panel.translationX > -root.width * 0.8f && panel.translationX < 0f)
        snap(root(a), "swipe_history_mid_dark")
    }

    /** Closing history: the chat rides the panel's edge like the next page, never underneath it. */
    @Test fun historyCloseMidDragDark() = withChat { a, _ ->
        seedHistory()
        a.findViewById<View>(R.id.openSavedChatsButton).performClick(); idle()
        val panel = a.findViewById<View>(R.id.historyDrawerContainer)
        // Through the window like a real finger: the panel moves under it, so its own
        // coordinates shift with every step (it used to flip between two positions).
        drag(root(a), -panel.width * 0.4f, release = false, stepMs = 40L)
        val chat = a.findViewById<View>(R.id.rootLayout)
        org.junit.Assert.assertEquals("panel follows the finger", -panel.width * 0.4f, panel.translationX, panel.width * 0.06f)
        org.junit.Assert.assertEquals(panel.translationX + panel.width, chat.translationX, 2f)
        snap(root(a), "history_close_mid_dark")
    }

    /** Mid-swipe the tab highlight is part way: neither tab is fully lit, neither fully dim. */
    @Test fun tabHighlightFollowsTheSwipe() = withChat { a, chat ->
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
        val root = chat.requireView()
        drag(root, -root.width * 0.5f, release = false, stepMs = 40L)
        val ink = androidx.core.content.ContextCompat.getColor(a, R.color.xai_ink)
        val mute = androidx.core.content.ContextCompat.getColor(a, R.color.xai_mute)
        val chatTab = a.findViewById<android.widget.TextView>(R.id.tabChat).currentTextColor
        val rpTab = a.findViewById<android.widget.TextView>(R.id.tabRoleplay).currentTextColor
        for (c in listOf(chatTab, rpTab)) {
            org.junit.Assert.assertNotEquals(ink, c)
            org.junit.Assert.assertNotEquals(mute, c)
        }
    }

    /** A cancelled swipe with animations on must leave every page (composer included) home. */
    @Test fun swipeCancelWithAnimationsLeavesPagesHome() = withChat { a, chat ->
        Settings.Global.putFloat(a.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val root = chat.requireView()
        drag(root, -root.width * 0.12f, release = true, stepMs = 120L)
        idle()
        org.junit.Assert.assertTrue(a.findViewById<View>(R.id.tabChat).isSelected)
        org.junit.Assert.assertTrue(pagerShots(a).isEmpty())
        for (id in listOf(R.id.chatFrameView, R.id.composerDock, R.id.composerFade)) {
            org.junit.Assert.assertEquals(0f, a.findViewById<View>(id).translationX, 0.5f)
        }
    }

    /** The bottom bar's pills and chips scroll sideways: a swipe there must never change page. */
    @Test fun swipeOnComposerDoesNotPage() = withChat { a, chat ->
        val root = chat.requireView()
        val bar = a.findViewById<View>(R.id.chatInputContainer)
        val rl = IntArray(2).also { root.getLocationInWindow(it) }
        val bl = IntArray(2).also { bar.getLocationInWindow(it) }
        drag(root, -root.width * 0.4f, release = true, stepMs = 40L, y = (bl[1] - rl[1] + bar.height / 2).toFloat())
        idle()
        org.junit.Assert.assertTrue(a.findViewById<View>(R.id.tabChat).isSelected)
        org.junit.Assert.assertFalse(a.findViewById<View>(R.id.tabRoleplay).isSelected)
        org.junit.Assert.assertTrue(pagerShots(a).isEmpty())
    }

    /** A rebuilt chat view (a screen replaced it) must put the tab underline back under its tab. */
    @Test fun tabUnderlineSurvivesChatViewRebuild() = withChat { a, _ ->
        val before = a.findViewById<View>(R.id.modeTabIndicator).translationX
        org.junit.Assert.assertTrue(before > 0f)
        a.supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, LanModelsFragment())
            .addToBackStack(null).commit()
        idle()
        a.supportFragmentManager.popBackStack(); idle()
        org.junit.Assert.assertEquals(before, a.findViewById<View>(R.id.modeTabIndicator).translationX, 1f)
    }

    /** Settings can cover the chat without hiding it; the Roleplay switch must still apply at once. */
    @Test fun roleplaySwitchAppliesImmediately() = withChat { a, _ ->
        val tab = a.findViewById<View>(R.id.tabRoleplay)
        org.junit.Assert.assertEquals(View.VISIBLE, tab.visibility)
        SharedPreferencesHelper(a).setRoleplayEnabled(false); idle()
        org.junit.Assert.assertEquals(View.GONE, tab.visibility)
        SharedPreferencesHelper(a).setRoleplayEnabled(true); idle()
        org.junit.Assert.assertEquals(View.VISIBLE, tab.visibility)
    }

    /** Copy, share, regenerate... only once the reply has landed. */
    @Test fun replyActionsWaitForTheStream() = withChat { a, _ ->
        seedConversation(a); idle()
        val vm = ViewModelProvider(a)[ChatViewModel::class.java]
        val f = ChatViewModel::class.java.getDeclaredField("_chatMessages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val live = f.get(vm) as MutableLiveData<List<FlexibleMessage>>
        live.value = live.value!! + FlexibleMessage("assistant", JsonPrimitive("Because dot products grow with d."))
        idle()
        val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.chatRecyclerView)
        val adapter = rv.adapter as ChatAdapter
        val last = adapter.itemCount - 1
        org.junit.Assert.assertEquals(ChatAdapter.VIEW_TYPE_ASSISTANT, adapter.getItemViewType(last))
        val vh = adapter.onCreateViewHolder(rv, ChatAdapter.VIEW_TYPE_ASSISTANT)
        adapter.replyInFlight = true
        adapter.onBindViewHolder(vh, last)
        // Invisible, not gone: the row's space is held so the finished reply doesn't jump.
        org.junit.Assert.assertEquals(View.INVISIBLE, vh.itemView.findViewById<View>(R.id.aiActionRow).visibility)
        adapter.replyInFlight = false
        adapter.onBindViewHolder(vh, last)
        org.junit.Assert.assertEquals(View.VISIBLE, vh.itemView.findViewById<View>(R.id.aiActionRow).visibility)
        // Message actions are bare 32dp icons, and the images name themselves for TalkBack.
        val d = a.resources.displayMetrics.density
        org.junit.Assert.assertEquals((32 * d + 0.5f).toInt(), vh.itemView.findViewById<View>(R.id.copyButton).layoutParams.width)
        org.junit.Assert.assertNotNull(vh.itemView.findViewById<View>(R.id.generatedImageView).contentDescription)
        org.junit.Assert.assertTrue(vh.itemView.findViewById<View>(R.id.reasoningHeader).minimumHeight >= (44 * d).toInt())
    }

    /** A reply's ⋮ opens a compact context menu with Edit (and Read aloud / Instruct where they apply). */
    @Test fun replyMoreMenuDark() = withChat { a, _ ->
        seedConversation(a); idle()
        val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.chatRecyclerView)
        val holder = rv.findViewHolderForAdapterPosition(1)!!
        org.junit.Assert.assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.editButton).visibility)
        val more = holder.itemView.findViewById<View>(R.id.moreActionsButton)
        more.performClick(); idle()
        // The card is the glass layout the labels sit in. Hug the ⋮, not the composer.
        var card: View = a.findViewById<View>(R.id.messageMenuLabel)
        while (card !is GlassLinearLayout) card = card.parent as View
        val labels = (0 until card.childCount).mapNotNull {
            card.getChildAt(it).findViewById<android.widget.TextView>(R.id.messageMenuLabel)?.text?.toString()
        }
        org.junit.Assert.assertTrue(labels.toString(), "Edit" in labels)
        val composer = a.findViewById<View>(R.id.chatInputContainer)
        org.junit.Assert.assertTrue(
            "menu width ${card.width} should be under the composer (${composer.width})",
            card.width > 0 && card.width < composer.width - 40
        )
        val moreLoc = IntArray(2).also { more.getLocationOnScreen(it) }
        val cardLoc = IntArray(2).also { card.getLocationOnScreen(it) }
        org.junit.Assert.assertTrue(
            "menu should open from the ⋮ (more=${moreLoc[0]} card=${cardLoc[0]})",
            kotlin.math.abs(moreLoc[0] - cardLoc[0]) <= card.width
        )
        snap(root(a), "reply_menu_dark")
    }

    /** Thoughts stay folded while the reply streams, and the Thoughts tile hides them outright. */
    @Test fun thinkingStaysFoldedWhileStreaming() = withChat { a, _ ->
        seedConversation(a); idle()
        val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.chatRecyclerView)
        val adapter = rv.adapter as ChatAdapter
        val vh = adapter.onCreateViewHolder(rv, ChatAdapter.VIEW_TYPE_ASSISTANT) as ChatAdapter.AssistantViewHolder
        val thinking = FlexibleMessage("assistant", JsonPrimitive(""), reasoning = "Scores grow with d, so scale them.")
        vh.bindTextOnly(thinking)
        org.junit.Assert.assertEquals(View.VISIBLE, vh.itemView.findViewById<View>(R.id.reasoningBlock).visibility)
        org.junit.Assert.assertEquals(View.GONE, vh.itemView.findViewById<View>(R.id.reasoningTextView).visibility)
        adapter.showThinking = false
        vh.bindTextOnly(thinking)
        org.junit.Assert.assertEquals(View.GONE, vh.itemView.findViewById<View>(R.id.reasoningBlock).visibility)
        adapter.showThinking = true
    }

    @Test fun grainMigratesToDrift() {
        org.junit.Assert.assertEquals(AmbientBackgroundView.Style.DRIFT, AmbientBackgroundView.Style.fromKey("grain"))
    }

    @Test fun ambientBackgroundsDark() = withChat { a, _ -> ambientGrid(a, "ambient_backgrounds_dark") }
    @Test @Config(qualifiers = LIGHT)
    fun ambientBackgroundsLight() = withChat { a, _ -> ambientGrid(a, "ambient_backgrounds_light") }
    /** API 31-32 path: static pre-rendered fields and the grain tile. */
    @Test @Config(sdk = [31])
    fun ambientBackgroundsApi31() = withChat { a, _ -> ambientGrid(a, "ambient_backgrounds_api31") }

    /** With animations off the appearance switch is instant and must not crash or leak an overlay. */
    @Test fun themeSwitchWithoutAnimation() = withChat { a, _ ->
        val before = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
        ThemeTransition.apply(a, a.findViewById(R.id.settingsButton), androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        idle()
        // The recreated activity re-applies the saved preference (dark), so only "no crash" is
        // asserted here, plus that nothing was left over the old window.
        val decor = a.window.decorView as android.view.ViewGroup
        org.junit.Assert.assertTrue((0 until decor.childCount).none { decor.getChildAt(it).javaClass.simpleName == "RevealOverlay" })
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(before)
    }

    private fun snapDialogCentered(a: MainActivity, name: String) {
        val d: Dialog = ShadowDialog.getLatestDialog() ?: return snap(root(a), name)
        val bg = frostedBackdrop(a)
        val c = Canvas(bg)
        val dv = d.window!!.decorView
        c.save(); c.translate(((bg.width - dv.width) / 2f), ((bg.height - dv.height) / 2f))
        dv.draw(c); c.restore()
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bg.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

private const val DARK = "w411dp-h891dp-night-xxhdpi"
private const val LIGHT = "w411dp-h891dp-notnight-xxhdpi"
