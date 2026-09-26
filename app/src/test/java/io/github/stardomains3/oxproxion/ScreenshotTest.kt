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
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        AppDatabase.setInstanceForTesting(db)
    }

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
            "Transformer attention, explained",
            "Grocery list for the week",
            "Fix Gradle build on AGP 9",
            "Llama 3 vs Qwen for coding",
            "Birthday message for Sam",
        ).forEachIndexed { i, t ->
            dao.insertSessionAndMessages(
                ChatSession(title = t, modelUsed = "openrouter/free", timestamp = System.currentTimeMillis() - i * 26L * 3600_000L),
                emptyList()
            )
        }
    }

    // ---- Chat ----

    @Test fun chatEmptyDark() = withChat { a, _ -> snap(root(a), "chat_empty_dark") }

    @Test @Config(qualifiers = LIGHT)
    fun chatEmptyLight() = withChat { a, _ -> snap(root(a), "chat_empty_light") }

    @Test fun chatConversationDark() = withChat { a, _ ->
        seedConversation(a); idle(); snap(root(a), "chat_conversation_dark")
    }

    @Test @Config(qualifiers = LIGHT)
    fun chatConversationLight() = withChat { a, _ ->
        seedConversation(a); idle(); snap(root(a), "chat_conversation_light")
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

    @Test @Config(qualifiers = LIGHT)
    fun chatStreamingLight() = withChat { a, _ -> streamInto(a, "chat_streaming_light") }

    @Test @Config(qualifiers = LIGHT)
    fun controlsPanelConversationLight() = withChat { a, _ ->
        seedConversation(a); idle()
        a.findViewById<View>(R.id.controlsButton).performClick(); idle()
        snap(root(a), "controls_panel_conversation_light")
    }

    @Test fun controlsPanelDark() = withChat { a, _ ->
        a.findViewById<View>(R.id.controlsButton).performClick(); idle()
        snap(root(a), "controls_panel_dark")
    }

    @Test fun attachMenuDark() = withChat { a, _ ->
        a.findViewById<View>(R.id.menuButton).performClick(); idle()
        snap(root(a), "attach_menu_dark")
    }

    @Test fun historyDark() = withChat { a, _ ->
        seedHistory()
        a.findViewById<View>(R.id.openSavedChatsButton).performClick(); idle()
        snap(root(a), "history_dark")
    }

    @Test @Config(qualifiers = LIGHT)
    fun historyLight() = withChat { a, _ ->
        seedHistory()
        a.findViewById<View>(R.id.openSavedChatsButton).performClick(); idle()
        snap(root(a), "history_light")
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

    @Test fun settingsDetailDark() = withChat { a, _ ->
        a.findViewById<View>(R.id.settingsButton).performClick(); idle()
        val first = a.supportFragmentManager.fragments.filterIsInstance<SettingsFragment>().first()
        first.view?.let { v -> firstClickableRow(v)?.performClick() }
        idle()
        snap(root(a), "settings_detail_dark")
    }

    private fun firstClickableRow(v: View): View? {
        if (v.isClickable && v !is android.widget.ScrollView && v.id != View.NO_ID &&
            v.resources.getResourceEntryName(v.id).contains("Row", ignoreCase = true)
        ) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstClickableRow(v.getChildAt(i))?.let { return it }
        return null
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

    private fun snapDialog(a: MainActivity, name: String) {
        val d: Dialog? = ShadowDialog.getLatestDialog()
        if (d == null) { snap(root(a), name); return }
        val bg = frostedBackdrop(a)
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
        paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, size.toFloat(), 0xFF8C8C8C.toInt(), 0xFF2A2A2A.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFFD6D6D6.toInt()
        c.drawCircle(size / 2f, size * 0.42f, size * 0.2f, paint)
        c.drawOval(size * 0.18f, size * 0.68f, size * 0.82f, size * 1.2f, paint)
        val file = RpAvatarStorage.avatarFile(ctx, ids[0])
        file.parentFile?.mkdirs()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        dao.insertLorebook(RpLorebook(name = "Outer Rim", content = "Ports, pirates and old wars.", isActive = true))
        val prefs = SharedPreferencesHelper(ctx)
        prefs.saveRpActiveCharacterId(if (withActive) ids[0] else null)
        prefs.saveRpPersona("Sam, a courier with a bad sense of direction.")
    }

    /** Room LiveData and Coil decode on real background threads; give them a moment. */
    private fun settle() {
        repeat(4) { Thread.sleep(250); idle() }
    }

    private fun rpScreen(name: String, seed: Boolean = true, f: () -> androidx.fragment.app.Fragment) = withChat { a, _ ->
        if (seed) seedRp()
        pushFragment(a, f()); settle(); snap(root(a), name)
    }

    @Test fun rpHubDark() = rpScreen("rp_hub_dark") { RpHubFragment() }
    @Test @Config(qualifiers = LIGHT)
    fun rpHubLight() = rpScreen("rp_hub_light") { RpHubFragment() }
    @Test fun rpHubEmptyDark() = rpScreen("rp_hub_empty_dark", seed = false) { RpHubFragment() }
    @Test fun rpCharactersDark() = rpScreen("rp_characters_dark") { RpCharacterLibraryFragment.newInstance() }
    @Test @Config(qualifiers = LIGHT)
    fun rpCharactersLight() = rpScreen("rp_characters_light") { RpCharacterLibraryFragment.newInstance() }
    @Test fun rpCharactersEmptyDark() = rpScreen("rp_characters_empty_dark", seed = false) { RpCharacterLibraryFragment.newInstance() }
    @Test @Config(qualifiers = LIGHT)
    fun rpPersonaLight() = rpScreen("rp_persona_light") { RpPersonaFragment.newInstance() }
    @Test fun rpCharacterEditExistingDark() = withChat { a, _ ->
        seedRp()
        val id = runBlocking { db.rpDao().getAllCharactersOnce().first { it.name == "Mira Vance" }.id }
        pushFragment(a, RpCharacterEditFragment.newInstance(id)); settle()
        snap(root(a), "rp_character_edit_existing_dark")
    }
    @Test @Config(qualifiers = LIGHT)
    fun rpCharacterEditLight() = rpScreen("rp_character_edit_light", seed = false) { RpCharacterEditFragment.newInstance(0L) }
    @Test fun rpLorebooksDark() = withChat { a, _ -> pushFragment(a, RpLorebookLibraryFragment.newInstance()); snap(root(a), "rp_lorebooks_dark") }
    @Test fun rpPersonaDark() = rpScreen("rp_persona_dark") { RpPersonaFragment.newInstance() }
    @Test fun rpCharacterEditDark() = withChat { a, _ -> pushFragment(a, RpCharacterEditFragment.newInstance(0L)); snap(root(a), "rp_character_edit_dark") }
    @Test fun rpLorebookEditDark() = withChat { a, _ -> pushFragment(a, RpLorebookEditFragment.newInstance(0L)); snap(root(a), "rp_lorebook_edit_dark") }
    @Test fun helpDark() = withChat { a, _ -> pushFragment(a, HelpFragment()); snap(root(a), "help_dark") }
    @Test fun licensesDark() = withChat { a, _ -> pushFragment(a, LicenseListFragment()); snap(root(a), "licenses_dark") }
    @Test fun advancedReasoningDark() = withChat { a, _ -> pushFragment(a, AdvancedReasoningFragment()); snap(root(a), "advanced_reasoning_dark") }
    @Test fun lanModelsDark() = withChat { a, _ -> pushFragment(a, LanModelsFragment()); snap(root(a), "lan_models_dark") }
    @Test fun openRouterModelsDark() = withChat { a, _ -> pushFragment(a, OpenRouterModelsFragment()); snap(root(a), "openrouter_models_dark") }
    @Test fun addPromptDark() = withChat { a, _ -> pushFragment(a, AddEditPromptFragment()); snap(root(a), "add_prompt_dark") }
    @Test fun addSystemMessageDark() = withChat { a, _ -> pushFragment(a, AddEditSystemMessageFragment()); snap(root(a), "add_system_message_dark") }
    @Test fun presetEditDark() = withChat { a, _ -> pushFragment(a, PresetEditFragment.newInstance(null)); snap(root(a), "preset_edit_dark") }
    @Test fun editMessageDark() = withChat { a, _ -> pushFragment(a, EditMessageFragment.newInstance(0, "Can you explain how attention works?")); snap(root(a), "edit_message_dark") }
    @Test fun markdownViewerDark() = withChat { a, _ -> pushFragment(a, MarkdownViewerFragment.newInstance("# Notes\n\nSome **bold** text and `code`.\n\n- one\n- two", "Inter", "Qwen 3")); snap(root(a), "markdown_viewer_dark") }
    @Test @Config(qualifiers = LIGHT)
    fun presetsLight() = withChat { a, _ -> pushFragment(a, PresetsListFragment()); snap(root(a), "presets_light") }
    @Test @Config(qualifiers = LIGHT)
    fun promptsLight() = withChat { a, _ -> pushFragment(a, PromptLibraryFragment()); snap(root(a), "prompts_light") }
    @Test fun presetsDark() = withChat { a, _ -> pushFragment(a, PresetsListFragment()); snap(root(a), "presets_dark") }
    @Test fun systemMessagesDark() = withChat { a, _ -> pushFragment(a, SystemMessageLibraryFragment()); snap(root(a), "system_messages_dark") }
    @Test @Config(qualifiers = LIGHT)
    fun systemMessagesLight() = withChat { a, _ -> pushFragment(a, SystemMessageLibraryFragment()); snap(root(a), "system_messages_light") }
    @Test fun toolsDark() = withChat { a, _ -> pushFragment(a, ToolsFragment()); snap(root(a), "tools_dark") }
    @Test fun promptsDark() = withChat { a, _ -> pushFragment(a, PromptLibraryFragment()); snap(root(a), "prompts_dark") }
    @Test fun rpSettingsDark() = withChat { a, _ -> pushFragment(a, RpSettingsFragment()); snap(root(a), "rp_settings_dark") }
    @Test fun inferenceDark() = withChat { a, _ -> pushFragment(a, InferenceParametersFragment()); snap(root(a), "inference_dark") }

    @Test fun confirmDialogDark() = withChat { a, chat ->
        GrokConfirmDialog.show(chat, "Delete conversation?", "This can't be undone.", "Delete", onConfirm = {})
        idle(); snapDialogCentered(a, "dialog_confirm_dark")
    }
    @Test @Config(qualifiers = LIGHT)
    fun inputDialogLight() = withChat { a, chat ->
        GrokInputDialog.show(chat, "Rename conversation", "Title", "Transformer attention", "Save", onConfirm = {})
        idle(); snapDialogCentered(a, "dialog_input_light")
    }


    @Test fun settingsModelsDark() = withChat { a, _ -> openSettingsRow(a, R.id.settingsRowModels); snap(root(a), "settings_models_dark") }
    @Test fun settingsDataDark() = withChat { a, _ -> openSettingsRow(a, R.id.settingsRowData); snap(root(a), "settings_data_dark") }
    @Test @Config(qualifiers = LIGHT)
    fun settingsAdvancedLight() = withChat { a, _ -> openSettingsRow(a, R.id.settingsRowAdvanced); snap(root(a), "settings_advanced_light") }
    @Test @Config(qualifiers = LIGHT)
    fun modelPickerLight() = withChat { a, _ ->
        a.findViewById<View>(R.id.modelNameTextView).performClick(); idle()
        snap(root(a), "model_picker_light")
    }

    private fun openSettingsRow(a: MainActivity, rowId: Int) {
        a.findViewById<View>(R.id.settingsButton).performClick(); idle()
        val sf = a.supportFragmentManager.fragments.filterIsInstance<SettingsFragment>().first()
        sf.requireView().findViewById<View>(rowId).performClick(); idle()
    }

    @Test fun lanDialogDark() = withChat { a, _ ->
        SaveLANDialogFragment().show(a.supportFragmentManager, "lan"); idle(); snapDialogCentered(a, "dialog_lan_dark")
    }
    @Test @Config(qualifiers = LIGHT)
    fun apiDialogLight() = withChat { a, _ ->
        SaveApiDialogFragment().show(a.supportFragmentManager, "api"); idle(); snapDialogCentered(a, "dialog_api_light")
    }
    @Test fun timeoutDialogDark() = withChat { a, _ ->
        TimeoutDialogFragment().show(a.supportFragmentManager, "t"); idle(); snapDialogCentered(a, "dialog_timeout_dark")
    }

    // ---- Grok-form chrome: mode tabs, anchored popover, pull-to-dismiss ----

    @Test fun chatRoleplayTabDark() = withChat { a, _ ->
        a.findViewById<View>(R.id.tabRoleplay).performClick(); idle()
        org.junit.Assert.assertTrue(a.findViewById<View>(R.id.tabRoleplay).isSelected)
        snap(root(a), "chat_rp_tab_dark")
        // Mode persists across tests; leave the app in Chat.
        a.findViewById<View>(R.id.tabChat).performClick(); idle()
    }

    @Test fun chatRoleplayCharacterDark() = withChat { a, _ ->
        a.findViewById<View>(R.id.tabRoleplay).performClick(); idle()
        org.junit.Assert.assertEquals(View.GONE, a.findViewById<View>(R.id.emptyAction).visibility)
        snap(root(a), "chat_rp_empty_dark")
        a.findViewById<View>(R.id.tabChat).performClick(); idle()
    }

    @Test fun manageModelsFromPopoverDark() = withChat { a, _ ->
        a.findViewById<View>(R.id.modelNameTextView).performClick(); idle()
        val footer = a.findViewById<android.view.ViewGroup>(R.id.popoverFooter)
        footer.getChildAt(0).performClick(); idle()
        snap(root(a), "manage_models_dark")
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
        a.findViewById<View>(R.id.tabChat).performClick(); idle()
        swipe(root, w * 0.85f, w * 0.15f, y)
        org.junit.Assert.assertTrue("left swipe → Roleplay", a.findViewById<View>(R.id.tabRoleplay).isSelected)
        swipe(root, w * 0.15f, w * 0.85f, y)
        org.junit.Assert.assertTrue("right swipe → Chat", a.findViewById<View>(R.id.tabChat).isSelected)
        // A short nudge must not navigate.
        swipe(root, w * 0.5f, w * 0.62f, y)
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
        SharedPreferencesHelper(a).saveBackgroundStyle(AmbientBackgroundView.Style.OFF.key)
    }
    @Test @Config(qualifiers = LIGHT)
    fun settingsAppearanceLight() = withChat { a, _ ->
        SharedPreferencesHelper(a).saveBackgroundStyle(AmbientBackgroundView.Style.FLOW.key)
        openSettingsRow(a, R.id.settingsRowAppearance); settle(); snap(root(a), "settings_appearance_light")
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
    @Test @Config(qualifiers = LIGHT)
    fun settingsTogglesLight() = withChat { a, _ -> toggles(a, "settings_toggles_light") }

    /** Every background style (and Adaptive in both modes), each full-screen over the canvas. */
    private fun ambientGrid(a: MainActivity, name: String) {
        val cells = listOf(
            "grain" to (AmbientBackgroundView.Style.GRAIN to ChatMode.ASK),
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
            if (cell.first == AmbientBackgroundView.Style.ADAPTIVE) {
                org.junit.Assert.assertEquals(AmbientBackgroundView.Style.FLOW, v.resolvedStyle)
            }
            snap(frame, "${name}_$label")
            host.removeView(frame)
        }
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
