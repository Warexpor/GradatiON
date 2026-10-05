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
 * Shared setup and helpers for the screenshot classes. Split in two classes so both test JVMs
 * render at once: one class runs in one JVM, and a single 230 s class held the whole run.
 * Screenshots land in app/build/screenshots for visual review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ScreenshotApp::class, sdk = [35], qualifiers = DARK)
abstract class ScreenshotHarness {

    protected lateinit var db: AppDatabase

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

    protected fun snap(view: View, name: String) {
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
    protected fun renderHardware(view: View): Bitmap {
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

    protected fun idle() {
        repeat(8) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        }
    }

    protected fun withChat(block: (MainActivity, ChatFragment) -> Unit) {
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

    protected fun root(a: MainActivity) = a.window.decorView

    protected fun seedConversation(a: MainActivity) {
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

    protected fun seedHistory() = runBlocking {
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

    /** Spread of opaque reds. The liquid frame varies; the flat vector is one gray. */
    protected fun opaqueSpread(bmp: Bitmap): Int {
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

    /** Transcript scrolled so messages pass under the floating glass controls. */
    protected fun scrolledUnderGlass(a: MainActivity, name: String) {
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

    /** Mid-stream frame: the newest words are still fading in at the edge. */
    protected fun streamInto(a: MainActivity, name: String) {
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

    /** First row that is a session (the list may lead with section headers). */
    protected fun firstSessionRow(list: androidx.recyclerview.widget.RecyclerView): Int {
        for (i in 0 until (list.adapter?.itemCount ?: 0)) {
            val vh = list.findViewHolderForAdapterPosition(i) ?: continue
            if (vh.itemView.findViewById<View>(R.id.iconEditt) != null) return i
        }
        return 0
    }

    /** The frosted screen a dialog window sits on: cross-window blur plus the light dim. */
    protected fun frostedBackdrop(a: MainActivity): Bitmap {
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

    protected fun rpPanelGrid(a: MainActivity): android.widget.GridLayout {
        val grid = root(a).findViewById<android.widget.GridLayout>(R.id.rpPanelTiles)
        org.junit.Assert.assertNotNull("the character menu opened", grid)
        return grid!!
    }

    protected fun dismissRpPanel(a: MainActivity) {
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

    protected fun rpPanelShowing(a: MainActivity): Boolean =
        root(a).findViewById<View>(R.id.rpPanelTiles)?.isShown == true

    /** [sharp]: the dialog dims the screen but does not blur it (the opaque character panel). */
    protected fun snapDialog(a: MainActivity, name: String, sharp: Boolean = false) {
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

    protected fun snapWithPopup(a: MainActivity, name: String) {
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

    /** A glyph's shape: drawn in ink at 48px, the pixels at least half opaque. */
    private fun glyphMask(ctx: android.content.Context, d: android.graphics.drawable.Drawable): BooleanArray {
        val c = d.constantState!!.newDrawable().mutate().apply { setTint(ctx.getColor(R.color.xai_ink)) }
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        c.setBounds(0, 0, 48, 48); c.draw(Canvas(bmp))
        val px = IntArray(48 * 48).also { bmp.getPixels(it, 0, 48, 0, 0, 48, 48) }
        return BooleanArray(px.size) { (px[it] ushr 24) >= 0x80 }
    }

    /**
     * Same glyph give or take a few edge pixels. Anti-aliasing on a stroke's edge can shift by a
     * pixel depending on what ran earlier in the JVM; a different glyph differs by far more.
     */
    protected fun assertSameGlyph(
        message: String,
        ctx: android.content.Context,
        expected: android.graphics.drawable.Drawable,
        actual: android.graphics.drawable.Drawable,
    ) {
        val e = glyphMask(ctx, expected)
        val g = glyphMask(ctx, actual)
        val differ = e.indices.count { e[it] != g[it] }
        val drawn = maxOf(e.count { it }, g.count { it })
        org.junit.Assert.assertTrue("$message: $differ of $drawn pixels differ", drawn > 0 && differ <= maxOf(4, drawn / 25))
    }

    protected fun pushFragment(a: MainActivity, f: androidx.fragment.app.Fragment) {
        a.supportFragmentManager.beginTransaction().add(R.id.fragment_container, f).commitNow()
        idle()
    }

    /** A small library so the RP screens show real cards; one character has a photo. */
    protected fun seedRp(withActive: Boolean = true) = runBlocking {
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
        val portrait = RpAvatarStorage.personaFile(ctx, "persona_test.jpg")
        portrait.parentFile?.mkdirs()
        file.copyTo(portrait, overwrite = true)
        // Personas are per character: Mira (the first) has Sam, the rest have none.
        prefs.saveRpPersonaFor(ids[0], RpPersonaChoice("Sam", "Sam, a courier with a bad sense of direction.", portrait.name))
        prefs.saveRpPersonaPresets(listOf(
            RpPersonaPreset("Sam", "Sam, a courier with a bad sense of direction.", portrait.name),
            RpPersonaPreset("Captain Rhee", "A retired pilot who still salutes the sunrise.")
        ))
    }

    /** Room LiveData and Coil decode on real background threads; give them a moment. */
    /**
     * A mode switch lands the leaving chat's save first: several hops between the main looper
     * and Room's thread. Short real-time steps let each hop through; four long ones did not.
     */
    protected fun settle() {
        repeat(20) { Thread.sleep(50); idle() }
    }

    protected fun rpScreen(name: String, f: () -> androidx.fragment.app.Fragment) = withChat { a, _ ->
        seedRp()
        pushFragment(a, f()); settle(); snap(root(a), name)
    }

    /** Pushes each screen over the chat, snaps it and takes it off again, naming any that fails. */
    protected fun renderEach(
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

    // ── Voice input ────────────────────────────────────────────────────────────────────

    protected fun dictationOf(chat: ChatFragment): VoiceDictation =
        ChatFragment::class.java.getDeclaredField("dictation").apply { isAccessible = true }.get(chat) as VoiceDictation

    /** Mid-dictation: one settled phrase, one still being recognized, bars full of speech. */
    protected fun dictateInto(a: MainActivity, chat: ChatFragment): VoiceDictation {
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

    protected fun withVoice(block: (MainActivity, ChatFragment) -> Unit) {
        VoiceInput.deviceAvailableOverride = true
        try { withChat(block) } finally { VoiceInput.deviceAvailableOverride = null }
    }

    /** Pump the main looper in real time while a background stream (demo thread) runs. */
    protected fun waitFor(timeoutMs: Long, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        }
    }

    /** Chats with a few characters, oldest last, so the Roleplay home has rows to show. */
    protected fun seedRpChats() = runBlocking {
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

    protected fun openSettingsRow(a: MainActivity, rowId: Int) {
        a.findViewById<View>(R.id.settingsButton).performClick(); idle()
        val sf = a.supportFragmentManager.fragments.filterIsInstance<SettingsFragment>().first()
        sf.requireView().findViewById<View>(rowId).performClick(); idle()
    }

    protected fun swipe(v: View, fromX: Float, toX: Float, y: Float) {
        val t0 = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, x: Float, dt: Long) = android.view.MotionEvent.obtain(t0, t0 + dt, action, x, y, 0)
        v.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, fromX, 0))
        for (k in 1..8) v.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * k / 8f, 20L * k))
        v.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, toX, 200))
        idle()
    }

    /** Glass toggles on and off, one held down (thumb swells into a lens). */
    protected fun toggles(a: MainActivity, name: String) {
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

    /** Every background style (and Adaptive in both modes), each full-screen over the canvas. */
    protected fun ambientGrid(a: MainActivity, name: String) {
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
    protected fun seedBackgroundPhoto(a: MainActivity) {
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
    protected fun awaitPhoto() {
        repeat(20) {
            Thread.sleep(50)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    // ---- Wide swipes: the page follows the finger ----

    protected fun motion(v: View, action: Int, down: Long, t: Long, x: Float, y: Float) {
        val e = android.view.MotionEvent.obtain(down, t, action, x, y, 0)
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    /** Drag across [root] from its middle by [dx] in small steps; lifts only if [release]. */
    protected fun drag(root: View, dx: Float, release: Boolean, stepMs: Long = 16L, y: Float = root.height * 0.45f) {
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

    protected fun pagerShots(a: MainActivity): List<android.widget.ImageView> {
        val content = a.findViewById<android.view.ViewGroup>(R.id.rootLayout)
        return (0 until content.childCount).map { content.getChildAt(it) }.filterIsInstance<android.widget.ImageView>()
    }

    protected fun snapDialogCentered(a: MainActivity, name: String) {
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

internal const val DARK = "w411dp-h891dp-night-xxhdpi"
internal const val LIGHT = "w411dp-h891dp-notnight-xxhdpi"
