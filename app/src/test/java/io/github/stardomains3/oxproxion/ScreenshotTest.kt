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
        snapWithPopup(a, "attach_menu_dark")
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
        snapDialog(a, "model_picker_dark")
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

    private fun snapDialog(a: MainActivity, name: String) {
        val d: Dialog? = ShadowDialog.getLatestDialog()
        if (d == null) { snap(root(a), name); return }
        val bg = Bitmap.createBitmap(root(a).width, root(a).height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bg)
        root(a).draw(c)
        c.drawColor(0x99000000.toInt())
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

    @Test fun rpHubDark() = withChat { a, _ -> pushFragment(a, RpHubFragment()); snap(root(a), "rp_hub_dark") }
    @Test @Config(qualifiers = LIGHT)
    fun rpHubLight() = withChat { a, _ -> pushFragment(a, RpHubFragment()); snap(root(a), "rp_hub_light") }
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

    private fun snapDialogCentered(a: MainActivity, name: String) {
        val d: Dialog = ShadowDialog.getLatestDialog() ?: return snap(root(a), name)
        val bg = Bitmap.createBitmap(root(a).width, root(a).height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bg)
        root(a).draw(c)
        c.drawColor(0x8C000000.toInt())
        val dv = d.window!!.decorView
        c.save(); c.translate(((bg.width - dv.width) / 2f), ((bg.height - dv.height) / 2f))
        dv.draw(c); c.restore()
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bg.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

private const val DARK = "w411dp-h891dp-night-xxhdpi"
private const val LIGHT = "w411dp-h891dp-notnight-xxhdpi"
