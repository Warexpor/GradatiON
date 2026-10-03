package io.github.stardomains3.oxproxion

import android.app.Application
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
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.stardomains3.oxproxion.code.CodeChangesFragment
import io.github.stardomains3.oxproxion.code.CodeDiffFragment
import io.github.stardomains3.oxproxion.code.CodeEvent
import io.github.stardomains3.oxproxion.code.CodeHub
import io.github.stardomains3.oxproxion.code.CodeSessionFragment
import io.github.stardomains3.oxproxion.code.CodeSettingsFragment
import io.github.stardomains3.oxproxion.code.HarnessKind
import io.github.stardomains3.oxproxion.code.NewSessionRequest
import io.github.stardomains3.oxproxion.code.PermissionMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Code mode screens rendered to app/build/screenshots (code_*.png), driven by the built-in demo
 * machine, plus behaviour checks for the third tab. Separate from ScreenshotTest so Code mode
 * work never collides with chat screen work.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*CodeModeScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ScreenshotApp::class, sdk = [35], qualifiers = CODE_DARK)
class CodeModeScreenshotTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        TestEnv.resetViewModelFactory()
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        AppDatabase.setInstanceForTesting(
            Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        )
        CodeHub.resetForTesting()
    }

    @After
    fun tearDown() = CodeHub.resetForTesting()

    // ── screens ───────────────────────────────────────────────────────────────────────────

    @Test fun codeOnboardDark() = withCode(demo = false) { a, _ -> snap(root(a), "code_onboard_dark") }

    @Test fun codeHomeEmptyDark() = withCode(seedSessions = false) { a, _ ->
        // The list diffs on a real thread: wait in real time for the forgotten rows to go.
        val list = root(a).findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.codeHomeList)
        val until = System.currentTimeMillis() + 5_000
        while (list.findViewById<View>(R.id.codeHeroMark) == null && System.currentTimeMillis() < until) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        }
        assertTrue("the empty state shows once the list has no sessions", list.findViewById<View>(R.id.codeHeroMark) != null)
        snap(root(a), "code_home_empty_dark")
    }

    @Test fun codeHomeDark() = withCode { a, _ ->
        startDemo("Add a follow-system option to the theme setting")
        idle(4)
        val list = root(a).findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.codeHomeList)
        val lit = (0 until list.childCount).map { list.getChildAt(it) }.filter {
            it.findViewById<View>(R.id.codeSessionLed)?.visibility == View.VISIBLE
        }
        assertEquals("only the busy session's key is lit", 1, lit.size)
        assertTrue(lit.single().findViewById<View>(R.id.codeSessionGlyph).isActivated)
        snap(root(a), "code_home_dark")
    }

    @Test @Config(qualifiers = CODE_LIGHT)
    fun codeHomeLight() = withCode { a, _ ->
        startDemo("Add a follow-system option to the theme setting")
        idle(4)
        snap(root(a), "code_home_light")
    }

    @Test fun codeSessionApprovalDark() = withCode { a, _ ->
        val id = startDemo("Add a follow-system option to the theme setting")
        push(a, CodeSessionFragment.newInstance(id))
        idle(12)
        assertTrue("demo should be waiting on an approval",
            CodeHub.getLoaded(ctx).sessions.value[id]!!.events.any { it is CodeEvent.Approval && it.chosen == null })
        assertEquals("the header light is on while the agent waits", View.VISIBLE,
            root(a).findViewById<View>(R.id.codeSessionHeaderLed).visibility)
        assertTranscriptOnGlassSheet(a)
        snap(root(a), "code_session_approval_dark")
    }

    /**
     * Before its history lands, a session shows the agent's mark in its orbits over the loading
     * line. The demo attaches at once, so the screen is fed a not-yet-attached copy directly.
     */
    @Test fun codeSessionLoadingDark() = withCode { a, _ ->
        val hub = CodeHub.getLoaded(ctx)
        val id = hub.sessions.value.keys.first { it.startsWith("demo-seed") }
        push(a, CodeSessionFragment.newInstance(id))
        val f = a.supportFragmentManager.fragments.last { it is CodeSessionFragment }
        val loading = hub.sessions.value[id]!!.copy(events = emptyList(), attached = false, attaching = true)
        val stateView = f.requireView().findViewById<android.widget.TextView>(R.id.codeSessionState)
        CodeSessionFragment::class.java.getDeclaredMethod("render", io.github.stardomains3.oxproxion.code.CodeSessionState::class.java)
            .apply { isAccessible = true }.invoke(f, loading)
        CodeSessionFragment::class.java.getDeclaredMethod(
            "bindStateView", android.widget.TextView::class.java, io.github.stardomains3.oxproxion.code.CodeSessionState::class.java
        ).apply { isAccessible = true }.invoke(f, stateView, loading)
        idle(1)
        assertEquals(View.VISIBLE, stateView.visibility)
        assertEquals(ctx.getString(R.string.code_session_loading), stateView.text.toString())
        assertTrue("the loading line carries the agent's mark", stateView.compoundDrawablesRelative[1] != null)
        val y = IntArray(2)
        root(a).findViewById<View>(R.id.codeSessionSheet).getLocationInWindow(y)
        val clearTop = y[1]
        root(a).findViewById<View>(R.id.codeSessionDock).getLocationInWindow(y)
        val clearBottom = y[1]
        stateView.getLocationInWindow(y)
        assertEquals("the mark and its line centre between the header and the composer",
            (clearTop + clearBottom) / 2f, y[1] + stateView.height / 2f, 2f)
        snap(root(a), "code_session_loading_dark")
    }

    @Test fun codeSessionDoneDark() = withCode { a, _ ->
        val hub = CodeHub.getLoaded(ctx)
        val id = startDemo("Add a follow-system option to the theme setting")
        push(a, CodeSessionFragment.newInstance(id))
        idle(12)
        val approval = hub.sessions.value[id]!!.events.filterIsInstance<CodeEvent.Approval>().single()
        hub.answer(id, approval.requestId, approval.options.first { it.id == "allow" })
        idle(16)
        assertEquals(false, hub.sessions.value[id]!!.running)
        snap(root(a), "code_session_done_dark")
    }

    /** Mid-turn: a command streams into its pane while the Working footer closes the rail. */
    @Test fun codeSessionRunningDark() = withCode { a, _ ->
        val hub = CodeHub.getLoaded(ctx)
        val id = startDemo("Add a follow-system option to the theme setting")
        push(a, CodeSessionFragment.newInstance(id))
        idle(12)
        val approval = hub.sessions.value[id]!!.events.filterIsInstance<CodeEvent.Approval>().single()
        hub.answer(id, approval.requestId, approval.options.first { it.id == "allow" })
        fun midCommand() = hub.sessions.value[id]!!.events.any {
            it is CodeEvent.ToolCall && it.kind == io.github.stardomains3.oxproxion.code.ToolKind.EXECUTE &&
                it.status == io.github.stardomains3.oxproxion.code.ToolStatus.RUNNING && (it.output?.lines()?.size ?: 0) >= 3
        }
        repeat(40) { if (!midCommand()) shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        assertTrue("a command is streaming", midCommand())
        val list = root(a).findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.codeTranscript)
        val adapter = list.adapter as io.github.stardomains3.oxproxion.code.CodeTranscriptAdapter
        assertTrue("the Working footer closes the list",
            adapter.currentList.last() === io.github.stardomains3.oxproxion.code.TranscriptRow.Working)
        assertOnTranscriptGrid(list)
        snap(root(a), "code_session_running_dark")
    }

    /**
     * The top of a finished turn: prompt block, beads on the rail, plan, and the rail kinds that
     * thread it from the prompt to the turn's end. A command's pane leads with its $ prompt.
     */
    @Test fun codeSessionTraceDark() = withCode { a, _ ->
        val hub = CodeHub.getLoaded(ctx)
        val id = startDemo("Add a follow-system option to the theme setting")
        push(a, CodeSessionFragment.newInstance(id))
        idle(12)
        val approval = hub.sessions.value[id]!!.events.filterIsInstance<CodeEvent.Approval>().single()
        hub.answer(id, approval.requestId, approval.options.first { it.id == "allow" })
        idle(16)
        val list = root(a).findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.codeTranscript)
        val adapter = list.adapter as io.github.stardomains3.oxproxion.code.CodeTranscriptAdapter
        val rows = adapter.currentList.map { (it as? io.github.stardomains3.oxproxion.code.TranscriptRow.Event)?.event }
        fun railOf(match: (CodeEvent?) -> Boolean) = adapter.railAt(rows.indexOfFirst(match))
        assertEquals(io.github.stardomains3.oxproxion.code.Rail.START, railOf { it is CodeEvent.UserPrompt })
        assertEquals(io.github.stardomains3.oxproxion.code.Rail.NODE, railOf { it is CodeEvent.ToolCall })
        assertEquals("an answered approval folds to a bead",
            io.github.stardomains3.oxproxion.code.Rail.NODE, railOf { it is CodeEvent.Approval })
        assertEquals(io.github.stardomains3.oxproxion.code.Rail.END, railOf { it is CodeEvent.TurnEnd })
        assertEquals(io.github.stardomains3.oxproxion.code.Rail.NONE, adapter.railAt(rows.size))
        assertEquals("the rail stops at a diff's glass card",
            io.github.stardomains3.oxproxion.code.Rail.CARD, railOf { it is CodeEvent.FileDiff })
        assertEquals(io.github.stardomains3.oxproxion.code.Rail.CARD, railOf { it is CodeEvent.Plan })
        assertTranscriptOnGlassSheet(a)
        val run = bindRow(a) { it is CodeEvent.ToolCall && it.kind == io.github.stardomains3.oxproxion.code.ToolKind.EXECUTE }
        val prompt = run.findViewById<android.widget.TextView>(R.id.codeToolPrompt)
        assertEquals(View.VISIBLE, prompt.visibility)
        assertEquals("$ ./gradlew :app:testDebugUnitTest", prompt.text.toString())
        adapter.verbose = true
        val search = bindRow(a) { it is CodeEvent.ToolCall && it.kind == io.github.stardomains3.oxproxion.code.ToolKind.SEARCH }
        assertEquals(View.VISIBLE, search.findViewById<View>(R.id.codeToolOutputScroll).visibility)
        assertEquals("only commands get a prompt line", View.GONE, search.findViewById<View>(R.id.codeToolPrompt).visibility)
        adapter.verbose = false
        list.scrollToPosition(0)
        idle(2)
        assertOnTranscriptGrid(list)
        snap(root(a), "code_session_trace_dark")
    }

    /**
     * One grid for every row on screen: glyphs (beads, card icons, plan steps) centre on the rail,
     * and words start on the content column, inside a card or not.
     */
    private fun assertOnTranscriptGrid(list: androidx.recyclerview.widget.RecyclerView) {
        val res = ctx.resources
        val railX = res.getDimension(R.dimen.code_rail_x)
        val textX = res.getDimensionPixelSize(R.dimen.code_text_start)
        val origin = IntArray(2).also { list.getLocationInWindow(it) }[0]
        fun left(v: View) = IntArray(2).also { v.getLocationInWindow(it) }[0] - origin
        var glyphs = 0
        var words = 0
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            row.findViewById<View>(R.id.codeRailNode)?.takeIf { it.isShown }?.let {
                assertEquals("bead centres on the rail", railX, left(it) + it.width / 2f, 1.5f)
                glyphs++
            }
            row.findViewById<android.view.ViewGroup>(R.id.codePlanRows)?.takeIf { it.isShown }?.let { steps ->
                for (s in 0 until steps.childCount) {
                    val step = steps.getChildAt(s) as android.widget.TextView
                    val glyph = step.compoundDrawablesRelative[0]!!
                    assertEquals("plan step glyph centres on the rail", railX,
                        left(step) + step.paddingLeft + glyph.bounds.width() / 2f, 1.5f)
                    assertEquals("plan step words on the content column", textX, left(step) + step.compoundPaddingLeft)
                    glyphs++
                }
            }
            for (id in listOf(R.id.codeUserText, R.id.codeToolTitle, R.id.codeThoughtLabel, R.id.codeAgentText,
                R.id.codePlanTitle, R.id.codeDiffPath, R.id.codeApprovalTitle, R.id.codeApprovalWhat,
                R.id.codeApprovalDoneText, R.id.codeTurnText, R.id.codeWorkingText, R.id.codeNoticeText)) {
                row.findViewById<android.widget.TextView>(id)?.takeIf { it.isShown }?.let {
                    assertEquals("${res.getResourceEntryName(id)} starts on the content column",
                        textX, left(it) + it.compoundPaddingLeft)
                    words++
                }
            }
        }
        assertTrue("checked the rows on screen", glyphs >= 2 && words >= 3)
    }

    /**
     * An approval's answers share one line on the content column, pushed out to both of its edges;
     * labels too long for that line stack full width instead of clipping or scrolling sideways.
     */
    @Test fun codeApprovalChoicesFillOneLineOrStack() = withCode { a, _ ->
        val id = startDemo("Add a follow-system option to the theme setting")
        push(a, CodeSessionFragment.newInstance(id))
        idle(12)
        val list = root(a).findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.codeTranscript)
        val row = bindRow(a) { it is CodeEvent.Approval }
        fun lay() {
            row.measure(View.MeasureSpec.makeMeasureSpec(list.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        }
        lay()
        val box = row.findViewById<android.widget.LinearLayout>(R.id.codeApprovalButtons)
        val what = row.findViewById<View>(R.id.codeApprovalWhat)
        fun xIn(v: View): Int { var x = 0; var p: View = v; while (p !== row) { x += p.left; p = p.parent as View }; return x }
        assertEquals(android.widget.LinearLayout.HORIZONTAL, box.orientation)
        assertEquals("answers start on the content column", xIn(what), xIn(box.getChildAt(0)))
        val last = box.getChildAt(box.childCount - 1)
        assertEquals("answers run to the card's end pad", xIn(box) + box.width, xIn(last) + last.width)
        assertTrue("one line", (0 until box.childCount).all { box.getChildAt(it).top == 0 })
        (box.getChildAt(1) as android.widget.TextView).text = "Always allow edits to every file in this workspace"
        lay()
        assertEquals("a label too long for the line stacks the answers", android.widget.LinearLayout.VERTICAL, box.orientation)
        for (i in 0 until box.childCount) assertEquals("stacked answers fill the column", box.width, box.getChildAt(i).width)
    }

    /** Over an ambient background the transcript sheet is see-through glass: the field shows, blurred. */
    @Test fun codeSessionGlassOverBackgroundDark() = withCode { a, _ ->
        AmbientBackgroundView.renderFieldInline = true
        SharedPreferencesHelper(ctx).saveBackgroundStyle(AmbientBackgroundView.Style.DRIFT.key)
        try {
            val hub = CodeHub.getLoaded(ctx)
            val id = startDemo("Add a follow-system option to the theme setting")
            push(a, CodeSessionFragment.newInstance(id))
            idle(12)
            val approval = hub.sessions.value[id]!!.events.filterIsInstance<CodeEvent.Approval>().single()
            hub.answer(id, approval.requestId, approval.options.first { it.id == "allow" })
            idle(16)
            val ambient = root(a).findViewById<AmbientBackgroundView>(R.id.codeAmbient)
            assertEquals(AmbientBackgroundView.Style.DRIFT, ambient.resolvedStyle)
            assertTranscriptOnGlassSheet(a)
            snap(root(a), "code_session_glass_drift_dark")
        } finally {
            SharedPreferencesHelper(ctx).saveBackgroundStyle(AmbientBackgroundView.Style.OFF.key)
            AmbientBackgroundView.renderFieldInline = false
        }
    }

    /** Thinking verbosity: every thought and tool output opens; Normal folds them back. */
    @Test fun codeSessionThinkingDark() = withCode { a, _ ->
        val hub = CodeHub.getLoaded(ctx)
        hub.store.showThinking = true
        val id = startDemo("Add a follow-system option to the theme setting")
        push(a, CodeSessionFragment.newInstance(id))
        idle(12)
        snap(root(a), "code_session_thinking_dark")
        // Bind the thought row directly (the live list follows the bottom edge).
        val list = root(a).findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.codeTranscript)
        val adapter = list.adapter as io.github.stardomains3.oxproxion.code.CodeTranscriptAdapter
        fun thoughtOpen(): Boolean {
            val pos = adapter.currentList.indexOfFirst { (it as? io.github.stardomains3.oxproxion.code.TranscriptRow.Event)?.event is CodeEvent.Thought }
            assertTrue("demo streams a thought", pos >= 0)
            val holder = adapter.onCreateViewHolder(list, adapter.getItemViewType(pos))
            adapter.onBindViewHolder(holder, pos)
            return holder.itemView.findViewById<android.view.View>(R.id.codeThoughtText).visibility == android.view.View.VISIBLE
        }
        assertTrue("thoughts open in Thinking view", thoughtOpen())
        adapter.verbose = false
        assertEquals("Normal view folds them", false, thoughtOpen())
        hub.store.showThinking = false
    }

    @Test fun codeDiffDark() = withCode { a, _ ->
        val hub = CodeHub.getLoaded(ctx)
        val id = startDemo("Add a follow-system option to the theme setting")
        idle(12)
        val diff = hub.sessions.value[id]!!.events.filterIsInstance<CodeEvent.FileDiff>().single()
        push(a, CodeDiffFragment.newInstance(id, diff.key))
        snap(root(a), "code_diff_dark")
    }

    /**
     * The transcript scrolls inside one clipped glass sheet that blurs only the ambient background
     * (so scrolling never re-blurs it), between the header and just below the composer.
     */
    private fun assertTranscriptOnGlassSheet(a: MainActivity) {
        val list = root(a).findViewById<View>(R.id.codeTranscript)
        val sheet = root(a).findViewById<GlassFrameLayout>(R.id.codeSessionSheet)
        assertTrue("the transcript scrolls inside the sheet", list.parent === sheet)
        assertTrue("the sheet clips its rows", sheet.clipToOutline)
        assertTrue("the sheet samples only the ambient backdrop",
            sheet.glass.source === root(a).findViewById<View>(R.id.codeSessionAmbientBackdrop))
        val loc = IntArray(2)
        root(a).findViewById<View>(R.id.codeSessionTop).getLocationInWindow(loc)
        val headerBottom = loc[1] + root(a).findViewById<View>(R.id.codeSessionTop).height
        sheet.getLocationInWindow(loc)
        assertEquals("the sheet starts under the header", headerBottom, loc[1])
        val composer = root(a).findViewById<View>(R.id.codeSessionComposer)
        val composerLoc = IntArray(2).also { composer.getLocationInWindow(it) }
        assertTrue("the composer floats inside the sheet",
            composerLoc[1] + composer.height <= loc[1] + sheet.height && composerLoc[0] > loc[0])
    }

    /** Binds one transcript row straight through the adapter (the live list follows the bottom edge). */
    private fun bindRow(a: MainActivity, match: (CodeEvent) -> Boolean): android.view.View {
        val list = root(a).findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.codeTranscript)
        val adapter = list.adapter as io.github.stardomains3.oxproxion.code.CodeTranscriptAdapter
        val pos = adapter.currentList.indexOfFirst {
            val e = (it as? io.github.stardomains3.oxproxion.code.TranscriptRow.Event)?.event
            e != null && match(e)
        }
        assertTrue("row present in the transcript", pos >= 0)
        val holder = adapter.onCreateViewHolder(list, adapter.getItemViewType(pos))
        adapter.onBindViewHolder(holder, pos)
        return holder.itemView
    }

    /** Audit 9 + 12: approval buttons are 44dp, and one tap locks all of them until the answer lands. */
    @Test fun codeApprovalButtonsAre44dpAndLockOnTap() = withCode { a, _ ->
        val id = startDemo("Add a follow-system option to the theme setting")
        push(a, CodeSessionFragment.newInstance(id))
        idle(12)
        assertEquals("state view is gone once there is a transcript",
            View.GONE, root(a).findViewById<View>(R.id.codeSessionState).visibility)
        val row = bindRow(a) { it is CodeEvent.Approval }
        val box = row.findViewById<android.view.ViewGroup>(R.id.codeApprovalButtons)
        val min = (44 * ctx.resources.displayMetrics.density).toInt()
        assertTrue(box.childCount >= 2)
        for (i in 0 until box.childCount) {
            assertTrue("button $i is at least 44dp", box.getChildAt(i).layoutParams.height >= min)
            assertTrue("button $i starts enabled", box.getChildAt(i).isEnabled)
        }
        box.getChildAt(box.childCount - 1).performClick()
        for (i in 0 until box.childCount) {
            assertEquals("button $i locks after a tap", false, box.getChildAt(i).isEnabled)
        }
    }

    /** Audit 2: an approval the turn outlived reads "Expired" and has no buttons. */
    @Test fun codeUnansweredApprovalExpiresWhenStopped() = withCode { a, _ ->
        val hub = CodeHub.getLoaded(ctx)
        val id = startDemo("Add a follow-system option to the theme setting")
        push(a, CodeSessionFragment.newInstance(id))
        idle(12)
        hub.cancel(id)
        idle(4)
        val approval = hub.sessions.value[id]!!.events.filterIsInstance<CodeEvent.Approval>().single()
        assertTrue("the approval expired with the turn", approval.expired)
        val row = bindRow(a) { it is CodeEvent.Approval }
        assertEquals(View.GONE, row.findViewById<View>(R.id.codeApprovalCard).visibility)
        val done = row.findViewById<android.widget.TextView>(R.id.codeApprovalDoneText)
        assertTrue(done.text.toString().startsWith(ctx.getString(R.string.code_approval_expired)))
        // Audit 15: Stop shows once (the turn-end row), not as a notice as well.
        val stopped = hub.sessions.value[id]!!.events.count {
            it is CodeEvent.Notice && it.text == "Stopped"
        }
        assertEquals(0, stopped)
    }

    /** Audit 12 + 14: tool rows, thought headers and "full output" clear 44dp and speak their state. */
    @Test fun codeTranscriptRowsAreTappableAndDescribed() = withCode { a, _ ->
        val id = startDemo("Add a follow-system option to the theme setting")
        push(a, CodeSessionFragment.newInstance(id))
        idle(12)
        val min = 44 * ctx.resources.displayMetrics.density
        val tool = bindRow(a) { it is CodeEvent.ToolCall }
        assertTrue(tool.findViewById<View>(R.id.codeToolRow).minimumHeight >= min.toInt())
        assertTrue(tool.findViewById<View>(R.id.codeToolFull).layoutParams.height >= min.toInt())
        assertTrue("tool row announces its state",
            !androidx.core.view.ViewCompat.getStateDescription(tool.findViewById(R.id.codeToolRow)).isNullOrBlank())
        val thought = bindRow(a) { it is CodeEvent.Thought }
        assertTrue(thought.findViewById<View>(R.id.codeThoughtHeader).minimumHeight >= min.toInt())
        val diff = bindRow(a) { it is CodeEvent.FileDiff }
        val path = (CodeHub.getLoaded(ctx).sessions.value[id]!!.events.filterIsInstance<CodeEvent.FileDiff>().first()).path
        assertTrue("diff card is described for TalkBack",
            diff.findViewById<View>(R.id.codeDiffCard).contentDescription.toString().contains(path))
    }

    /** Audit 20: from another tab, a session waiting on approval puts a cue on the Code tab. */
    @Test fun codeTabFlagsPendingApprovalFromOtherTabs() = withCode { a, _ ->
        val hub = CodeHub.getLoaded(ctx)
        val id = startDemo("Add a follow-system option to the theme setting")
        idle(12)
        val tab = a.findViewById<View>(R.id.tabCode)
        assertEquals("no cue while Code is the tab on screen",
            ctx.getString(R.string.mode_tab_code_a11y), tab.contentDescription)
        a.findViewById<View>(R.id.tabChat).performClick()
        idle()
        assertEquals(ctx.getString(R.string.code_tab_needs_you_a11y), tab.contentDescription)
        val approval = hub.sessions.value[id]!!.events.filterIsInstance<CodeEvent.Approval>().single()
        hub.answer(id, approval.requestId, approval.options.first { it.id == "allow" })
        idle(16)
        assertEquals(ctx.getString(R.string.mode_tab_code_a11y), tab.contentDescription)
    }

    @Test fun codeSettingsDark() = withCode { a, _ ->
        push(a, CodeSettingsFragment())
        idle()
        val settings = a.supportFragmentManager.fragments.last { it is CodeSettingsFragment }.requireView()
        val hits = ArrayList<android.view.View>()
        settings.findViewsWithText(hits, "nothing leaves the phone", android.view.View.FIND_VIEWS_WITH_TEXT)
        val demo = hits.filterIsInstance<android.widget.TextView>().first()
        val layout = demo.layout
        org.junit.Assert.assertNotNull(layout)
        val cut = (0 until layout.lineCount).sumOf { layout.getEllipsisCount(it) }
        org.junit.Assert.assertEquals("demo machine row is clipped", 0, cut)
        org.junit.Assert.assertTrue(demo.text.toString().contains("nothing leaves the phone"))
        snap(root(a), "code_settings_dark")
    }

    @Test fun codeHostDialogDark() = withCode { a, chat ->
        push(a, CodeSettingsFragment())
        val f = a.supportFragmentManager.fragments.last { it is CodeSettingsFragment }
        io.github.stardomains3.oxproxion.code.CodeHostDialog.show(f, null)
        idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val agents = dialog.findViewById<android.view.ViewGroup>(io.github.stardomains3.oxproxion.R.id.codeHostAgents)
        val labels = (0 until agents.childCount).map { agents.getChildAt(it) }
            .filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        val example = dialog.findViewById<android.widget.TextView>(io.github.stardomains3.oxproxion.R.id.codeHostUrlExample)
        val exampleLayout = example.layout
        org.junit.Assert.assertNotNull(exampleLayout)
        val exampleCut = (0 until exampleLayout.lineCount).sumOf { exampleLayout.getEllipsisCount(it) }
        org.junit.Assert.assertEquals("bridge example is clipped", 0, exampleCut)
        org.junit.Assert.assertTrue(example.text.toString().contains("7878/v1"))
        org.junit.Assert.assertTrue("every default agent is on the card",
            listOf("Grok Build", "Cursor Agent", "Pi").all { it in labels })
        for (i in 0 until agents.childCount) {
            val pill = agents.getChildAt(i) as? android.widget.TextView ?: continue
            org.junit.Assert.assertTrue("${pill.text} is clipped",
                pill.width > 0 && pill.left >= 0 && pill.right <= agents.width)
        }
        snap(dialog.window!!.decorView, "code_host_dialog_dark")
    }

    // ── behaviour ─────────────────────────────────────────────────────────────────────────

    @Test fun codeDictatingDark() = withCode { a, _ ->
        VoiceInput.deviceAvailableOverride = true
        val home = a.supportFragmentManager.fragments.flatMap { listOf(it) + it.childFragmentManager.fragments }
            .filterIsInstance<io.github.stardomains3.oxproxion.code.CodeHomeFragment>().first()
        val composer = home.javaClass.getDeclaredField("composer").apply { isAccessible = true }.get(home)
        val d = composer.javaClass.getDeclaredField("dictation").apply { isAccessible = true }.get(composer) as VoiceDictation
        d.refresh()
        d.onStateChanged(VoiceInput.State.LISTENING)
        d.onCommit("add a dark mode toggle to settings")
        d.onPartial("and remember it")
        val speech = floatArrayOf(0.2f, 0.6f, 0.95f, 0.5f, 0.3f, 0.85f, 0.4f, 0.1f)
        repeat(60) { i ->
            d.onLevel(speech[i % speech.size])
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(66))
        }
        snap(root(a), "code_dictating_dark")
        VoiceInput.deviceAvailableOverride = null
    }

    @Test fun codeTabHiddenWhenDisabled() {
        CodeHub.getLoaded(ctx).store.enabled = false
        launch { a, _ ->
            assertEquals(View.GONE, a.findViewById<View>(R.id.tabCode).visibility)
        }
    }

    /** The agent picker is a grid of tiles, two to a line, with the chosen agent checked. */
    @Test fun codeHarnessPickerDark() = withCode { a, _ ->
        a.findViewById<View>(R.id.codeComposerAgent).performClick()
        idle(3)
        val rows = a.findViewById<android.view.ViewGroup>(R.id.popoverRows)
        val tiles = (0 until rows.childCount).flatMap { i ->
            val line = rows.getChildAt(i) as android.view.ViewGroup
            assertEquals("line $i holds two tiles", 2, line.childCount)
            (0 until line.childCount).map { line.getChildAt(it) }
        }.filter { it.findViewById<View>(R.id.popoverRowTitle) != null }
        val names = tiles.map { it.findViewById<android.widget.TextView>(R.id.popoverRowTitle).text.toString() }
        assertEquals(listOf("Claude Code", "Codex CLI", "OpenCode", "Grok Build", "Cursor Agent", "Pi"), names)
        val checked = tiles.filter { it.findViewById<View>(R.id.popoverRowCheck).isShown }
        assertEquals(listOf("Claude Code"), checked.map { it.findViewById<android.widget.TextView>(R.id.popoverRowTitle).text.toString() })
        val min = 44 * ctx.resources.displayMetrics.density
        tiles.forEach { assertTrue("tile is at least 44dp", it.height >= min && it.width >= min) }
        snap(root(a), "code_harness_picker_dark")
    }

    @Test fun codeApprovalsPickerDark() = withCode { a, _ ->
        a.findViewById<View>(R.id.codeComposerPermission).performClick()
        idle(3)
        val rows = a.findViewById<android.view.ViewGroup>(R.id.popoverRows)
        assertEquals(PermissionMode.entries.size, rows.childCount)
        for (i in 0 until rows.childCount) {
            val icon = rows.getChildAt(i).findViewById<android.widget.ImageView>(R.id.popoverRowIcon)
            assertTrue("approval row $i needs its icon", icon.isShown && icon.drawable != null)
        }
        snap(root(a), "code_approvals_picker_dark")
    }

    /**
     * Changes list: both actions turn on once status arrives. The filter narrows the rows only,
     * so a search that matches nothing still leaves revert available for the tracked files.
     */
    @Test fun codeChangesRevertAllEnabled() = withCode { a, _ ->
        val id = startDemo("Add a follow-system option to the theme setting")
        push(a, CodeChangesFragment.newInstance(id))
        idle(2)
        val frag = a.supportFragmentManager.fragments.filterIsInstance<CodeChangesFragment>().single()
        val root = frag.requireView()
        val toolbar = root.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        val list = root.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.codeChangesList)
        val filter = root.findViewById<android.widget.EditText>(R.id.codeChangesFilter)
        assertEquals(a.getString(R.string.code_changes_ask_revert_all), toolbar.menu.findItem(R.id.action_ask_revert_all).title)
        assertTrue(toolbar.menu.findItem(R.id.action_ask_revert_all).isEnabled)
        assertTrue(toolbar.menu.findItem(R.id.action_ask_commit).isEnabled)
        assertEquals(View.VISIBLE, filter.visibility)
        val subtitle = toolbar.subtitle?.toString().orEmpty()
        assertTrue(subtitle, subtitle.contains("2 tracked"))
        assertTrue(subtitle, subtitle.contains("1 untracked"))
        assertEquals(3, list.adapter!!.itemCount)
        filter.setText("notes")
        assertEquals(1, list.adapter!!.itemCount)
        filter.setText("nope")
        assertEquals(0, list.adapter!!.itemCount)
        assertTrue(toolbar.menu.findItem(R.id.action_ask_revert_all).isEnabled)
        filter.setText("")
        assertEquals(3, list.adapter!!.itemCount)
        snap(this.root(a), "code_changes_dark")
    }

    /** The composer sits above the popover's scrim: a second tap on the pill must fold the card. */
    @Test fun codePillSecondTapFolds() = withCode { a, _ ->
        val pill = a.findViewById<View>(R.id.codeComposerAgent)
        pill.performClick(); idle(3)
        assertTrue(a.findViewById<View>(R.id.popoverRows)?.isShown == true)
        pill.performClick(); idle(3)
        assertTrue("card should be gone", a.findViewById<View>(R.id.popoverRows) == null)
    }

    @Test fun codeTabSwitchesAndComesBack() = withCode { a, _ ->
        val code = a.findViewById<View>(R.id.codeModeContainer)
        assertEquals(View.VISIBLE, code.visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.composerDock).visibility)
        a.findViewById<View>(R.id.tabChat).performClick()
        idle()
        assertEquals(View.GONE, code.visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.composerDock).visibility)
        assertTrue(a.findViewById<View>(R.id.tabChat).isSelected)
        a.findViewById<View>(R.id.tabCode).performClick()
        idle()
        assertEquals(View.VISIBLE, code.visibility)
        assertTrue(a.findViewById<View>(R.id.tabCode).isSelected)
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private fun startDemo(prompt: String): String = runBlocking {
        CodeHub.getLoaded(ctx).startSession(
            NewSessionRequest("demo", HarnessKind.CLAUDE_CODE, "~/code/GradatiON", prompt, PermissionMode.ASK)
        ).getOrThrow()
    }

    /** Code enabled, last tab Code, optionally the demo machine (which lists two past sessions). */
    private fun withCode(demo: Boolean = true, seedSessions: Boolean = true, block: (MainActivity, ChatFragment) -> Unit) {
        val hub = CodeHub.getLoaded(ctx)
        hub.store.enabled = true
        hub.store.lastTabWasCode = true
        if (demo) {
            hub.addDemoHost()
            if (!seedSessions) CodeHub.getLoaded(ctx).sessions.value.keys.toList().forEach { hub.forget(it) }
        }
        launch { a, chat ->
            if (demo && !seedSessions) {
                CodeHub.getLoaded(ctx).sessions.value.keys.toList().forEach { hub.forget(it) }
                idle()
            }
            block(a, chat)
        }
    }

    private fun launch(block: (MainActivity, ChatFragment) -> Unit) {
        val night = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        SharedPreferencesHelper(ctx).saveThemeMode(
            if (night) SharedPreferencesHelper.THEME_DARK else SharedPreferencesHelper.THEME_LIGHT
        )
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            idle()
            sc.onActivity { a ->
                idle()
                block(a, a.supportFragmentManager.findFragmentByTag("ChatFragment") as ChatFragment)
            }
        }
    }

    private fun push(a: MainActivity, f: androidx.fragment.app.Fragment) {
        a.supportFragmentManager.beginTransaction().add(R.id.fragment_container, f).commitNow()
        idle()
    }

    private fun root(a: MainActivity) = a.window.decorView

    /** Advances the main looper in small steps so coroutine delays and frame callbacks both run. */
    private fun idle(seconds: Int = 2) {
        repeat(seconds * 10) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        }
    }

    private fun snap(view: View, name: String) {
        val bmp = runCatching { renderHardware(view) }.getOrNull() ?: Bitmap.createBitmap(
            view.width, view.height, Bitmap.Config.ARGB_8888
        ).also { view.draw(Canvas(it)) }
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun renderHardware(view: View): Bitmap {
        val w = view.width
        val h = view.height
        val node = RenderNode("snap").apply {
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
            setContentRoot(node)
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
}

private const val CODE_DARK = "w411dp-h891dp-night-xxhdpi"
private const val CODE_LIGHT = "w411dp-h891dp-notnight-xxhdpi"
