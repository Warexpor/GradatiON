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


/**
 * Chat, History, Settings, dialogs and backgrounds, rendered to PNGs.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ScreenshotApp::class, sdk = [35], qualifiers = DARK)
class ScreenshotTest : ScreenshotHarness() {

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

    @Test fun chatGlassDark() = withChat { a, _ -> scrolledUnderGlass(a, "chat_glass_dark") }

    @Test @Config(qualifiers = LIGHT)
    fun chatGlassLight() = withChat { a, _ -> scrolledUnderGlass(a, "chat_glass_light") }

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
        // History reads the database on its own thread; a busy run needs more than one idle.
        waitFor(5000) { list.findViewHolderForAdapterPosition(firstSessionRow(list))?.itemView?.findViewById<View>(R.id.iconEditt) != null }
        list.findViewHolderForAdapterPosition(firstSessionRow(list))!!.itemView.performLongClick(); idle()
        snapDialog(a, "history_options_dark")
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

    @Test fun dialogsDark() = withChat { a, chat ->
        val dialogs: List<Pair<String, () -> Unit>> = listOf(
            "dialog_confirm_dark" to {
                GrokConfirmDialog.show(chat, "Delete conversation?", "This can't be undone.", "Delete", onConfirm = {})
            },
            "dialog_api_dark" to { SaveApiDialogFragment().show(a.supportFragmentManager, "api") },
            "dialog_lan_dark" to { SaveLANDialogFragment().show(a.supportFragmentManager, "lan") },
            "dialog_timeout_dark" to { TimeoutDialogFragment().show(a.supportFragmentManager, "t") },
            "dialog_chat_memory_dark" to { ChatMemoryDialogFragment().show(a.supportFragmentManager, "m") },
            "dialog_brave_dark" to { SaveBraveApiDialogFragment().show(a.supportFragmentManager, "b") },
            "dialog_max_tokens_dark" to { MaxTokensDialogFragment().show(a.supportFragmentManager, "x") },
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

    /** Settings' own-layout dialogs wrapped their content: the key dialog split Cancel, the timeout one hit the edges. */
    @Test fun settingsDialogsKeepSideInsets() = withChat { a, _ ->
        val d = a.resources.displayMetrics.density
        val expected = minOf(a.resources.displayMetrics.widthPixels - (48 * d).toInt(), (420 * d).toInt())
        val dialogs: List<Pair<String, () -> androidx.fragment.app.DialogFragment>> = listOf(
            "api" to { SaveApiDialogFragment() },
            "brave" to { SaveBraveApiDialogFragment() },
            "lan" to { SaveLANDialogFragment() },
            "max_tokens" to { MaxTokensDialogFragment() },
            "timeout" to { TimeoutDialogFragment() },
        )
        for ((name, make) in dialogs) {
            val f = make()
            f.show(a.supportFragmentManager, name); idle()
            org.junit.Assert.assertEquals("$name card width", expected, f.requireDialog().window!!.attributes.width)
            if (name == "api") {
                val cancel = f.requireView().findViewById<android.widget.TextView>(R.id.button_cancelapi)
                org.junit.Assert.assertEquals("Cancel stays on one line", 1, cancel.lineCount)
            }
            f.dismiss(); idle()
        }
    }

    @Test @Config(qualifiers = LIGHT)
    fun inputDialogLight() = withChat { a, chat ->
        GrokInputDialog.show(chat, "Rename conversation", "Title", "Transformer attention", "Save", onConfirm = {})
        idle(); snapDialogCentered(a, "dialog_input_light")
    }

    /** Help's in-app links open the app's own screens, and its inline glyphs are drawn in ink. */
    @Test fun helpLinksAndIconsDark() = withChat { a, _ ->
        pushFragment(a, HelpFragment())
        val text = a.findViewById<android.widget.TextView>(R.id.helpContentTextView).text as android.text.Spanned
        val leftover = text.getSpans(0, text.length, android.text.style.URLSpan::class.java)
            .map { it.url }.filter { it.startsWith("action://") || it.startsWith("oxproxion://") }
        org.junit.Assert.assertEquals("in-app links left as plain URLs", emptyList<String>(), leftover)
        for (label in listOf("Re-select folder", "View in app")) {
            val at = text.indexOf(label)
            org.junit.Assert.assertTrue("$label is in help", at >= 0)
            org.junit.Assert.assertTrue("$label is tappable",
                text.getSpans(at, at + label.length, android.text.style.ClickableSpan::class.java).isNotEmpty())
        }
        val ink = a.getColor(R.color.xai_ink) and 0xFFFFFF
        val icons = text.getSpans(0, text.length, android.text.style.ImageSpan::class.java)
        org.junit.Assert.assertTrue("help has inline glyphs", icons.size >= 8)
        for (span in icons) {
            val bmp = android.graphics.Bitmap.createBitmap(span.drawable.bounds.width(), span.drawable.bounds.height(),
                android.graphics.Bitmap.Config.ARGB_8888)
            span.drawable.draw(android.graphics.Canvas(bmp))
            val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            val solid = px.maxByOrNull { it ushr 24 }!!
            org.junit.Assert.assertEquals("glyph drawn in ink", ink, solid and 0xFFFFFF)
        }
        val tv = a.findViewById<android.widget.TextView>(R.id.helpContentTextView)
        val line = tv.layout.getLineForOffset(text.indexOf("Composer"))
        (tv.parent as android.widget.ScrollView).scrollTo(0, tv.layout.getLineTop(line)); idle()
        snap(root(a), "help_composer_dark")
    }

    /** Export used to read History's list before anything loaded it, so it always said there was nothing to save. */
    @Test fun settingsExportOpensSaveDialog() = withChat { a, _ ->
        seedHistory()
        openSettingsRow(a, R.id.settingsRowData)
        a.findViewById<View>(R.id.exportHistoryButton).performClick()
        settle()
        // Launch also asks for permissions, so look through everything that was started.
        val started = generateSequence { shadowOf(a).nextStartedActivityForResult?.intent }.toList()
        val save = started.firstOrNull { it.action == android.content.Intent.ACTION_CREATE_DOCUMENT }
        org.junit.Assert.assertNotNull("export opened no save dialog, only ${started.map { it.action }}", save)
        org.junit.Assert.assertEquals("gradation-chats.json", save!!.getStringExtra(android.content.Intent.EXTRA_TITLE))
    }

    /** The master switch is a toolbar action view; it used to sit flush against the screen edge. */
    @Test fun settingsAdvancedReasoningDark() = withChat { a, _ ->
        pushFragment(a, AdvancedReasoningFragment())
        val toolbar = a.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        val toggle = toolbar.menu.findItem(R.id.menu_advanced_toggle).actionView!!
        val include = a.findViewById<View>(R.id.includeSwitch)
        fun trackEnd(v: View) = IntArray(2).also { v.getLocationInWindow(it) }[0] + v.width - v.paddingEnd
        val d = a.resources.displayMetrics.density
        org.junit.Assert.assertEquals("toggle lines up with the switch under it", trackEnd(include).toFloat(), trackEnd(toggle).toFloat(), 2 * d)
        org.junit.Assert.assertEquals(a.getString(R.string.settings_advanced_reasoning), toolbar.title)
        org.junit.Assert.assertFalse("no glass capsule under the switch", toggle.background is GlassDrawable)
        snap(root(a), "settings_advanced_reasoning_dark")
    }

    /** The expanded preset reads like the editor: "Read aloud", not "Convo: On", and only what is on. */
    @Test fun settingsPresetsDark() = withChat { a, _ ->
        SharedPreferencesHelper(a).savePresets(listOf(
            Preset("p1", "Morning brief", "openai/gpt-5", SystemMessage("Summarizer", "Be brief."),
                streaming = true, reasoning = false, conversationMode = true, tools = false, webSearch = true),
            Preset("p2", "Code review", "anthropic/claude-opus-4.1", SystemMessage("Default", ""),
                streaming = false, reasoning = true, conversationMode = false),
        ))
        pushFragment(a, PresetsListFragment())
        val list = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewPresets)
        list.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<View>(R.id.iconExpand).performClick(); idle()
        val summary = list.findViewHolderForAdapterPosition(0)!!.itemView.findViewById<android.widget.TextView>(R.id.textPresetSubtitle)
        org.junit.Assert.assertEquals("openai/gpt-5 · Summarizer · Streaming, Read aloud, Web search", summary.text.toString())
        snap(root(a), "settings_presets_dark")
        SharedPreferencesHelper(a).savePresets(emptyList())
    }

    /** Add in the libraries used to replace the whole stack, so Back rebuilt Chat and dropped the draft. */
    @Test fun settingsLibraryAddKeepsTheStack() = withChat { a, chat ->
        val chatView = chat.requireView()
        a.findViewById<android.widget.EditText>(R.id.chatEditText).setText("half-written question")
        val prefs = SharedPreferencesHelper(a)
        data class Library(val row: Int, val add: Int, val list: Int, val shot: String, val saveInEditor: () -> Unit)
        val libraries = listOf(
            Library(R.id.promptsButton, R.id.fab_add_prompt, R.id.prompt_recycler_view, "settings_prompts_after_add_dark") {
                prefs.saveCustomPrompts(prefs.getCustomPrompts() + Prompt("Standup", "Summarize yesterday."))
            },
            Library(R.id.systemMessagesButton, R.id.fab_add_system_message, R.id.system_message_recycler_view, "settings_system_messages_after_add_dark") {
                prefs.saveCustomSystemMessages(prefs.getCustomSystemMessages() + SystemMessage("Terse", "Answer in one line."))
            },
            Library(R.id.presetsButton, R.id.fabAddPreset, R.id.recyclerViewPresets, "settings_presets_after_add_dark") {
                prefs.savePresets(prefs.getPresets() + Preset("p1", "Morning brief", "openai/gpt-5", SystemMessage("Default", ""),
                    streaming = true, reasoning = false, conversationMode = false))
            },
        )
        for (lib in libraries) {
            openSettingsRow(a, R.id.settingsRowAdvanced)
            a.findViewById<View>(lib.row).performClick(); idle()
            val list = a.findViewById<androidx.recyclerview.widget.RecyclerView>(lib.list)
            val before = list.adapter!!.itemCount
            a.findViewById<View>(lib.add).performClick(); idle()
            lib.saveInEditor()
            val fm = a.supportFragmentManager
            fm.popBackStackImmediate(); idle()
            org.junit.Assert.assertEquals("the library shows what the editor saved", before + 1, list.adapter!!.itemCount)
            snap(root(a), lib.shot)
            while (fm.backStackEntryCount > 0) { fm.popBackStackImmediate(); idle() }
            org.junit.Assert.assertSame("Chat kept its view", chatView, chat.view)
            org.junit.Assert.assertEquals("half-written question",
                a.findViewById<android.widget.EditText>(R.id.chatEditText).text.toString())
        }
    }

    /** License rows were the framework's simple_list_item_1: no press state and no dividers. */
    @Test fun settingsLicensesDark() = withChat { a, _ ->
        openSettingsRow(a, R.id.settingsRowData)
        a.findViewById<View>(R.id.licensesButton).performClick(); idle()
        val list = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerView)
        val row = list.getChildAt(0)
        org.junit.Assert.assertNotNull("glass license row", row.findViewById<View>(R.id.licenseName))
        org.junit.Assert.assertTrue("row shows the press wash", row.background is android.graphics.drawable.StateListDrawable)
        snap(root(a), "settings_licenses_dark")
    }

    @Test fun settingsSectionsDark() = withChat { a, _ ->
        for ((row, name) in listOf(
            R.id.settingsRowModels to "settings_models_dark",
            R.id.settingsRowAdvanced to "settings_advanced_dark",
            R.id.settingsRowData to "settings_data_dark",
        )) {
            openSettingsRow(a, row)
            snap(root(a), name)
            a.supportFragmentManager.popBackStackImmediate(); a.supportFragmentManager.popBackStackImmediate(); idle()
        }
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

    @Test fun settingsTogglesDark() = withChat { a, _ -> toggles(a, "settings_toggles_dark") }

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
        val choose = a.findViewById<View>(R.id.backgroundPhotoChoose)
        choose.requestRectangleOnScreen(android.graphics.Rect(0, 0, choose.width, choose.height), true); idle()
        snap(root(a), "settings_appearance_photo_options_dark")
        SharedPreferencesHelper(a).saveBackgroundStyle(AmbientBackgroundView.Style.OFF.key)
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
}
