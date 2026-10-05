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
 * Roleplay screens, rendered to PNGs, and the flows that only show on them.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*RpScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ScreenshotApp::class, sdk = [35], qualifiers = DARK)
class RpScreenshotTest : ScreenshotHarness() {

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
                // Pages are fragment transactions on the main looper: an idle lands them. settle()
                // waits a second of real time per call for database work, and this loop did 18.
                .performClick(); idle()
            snap(root(a), "rp_page_${name}_dark")
            if (name == "persona") {
                // A photo comes from the gallery app or the photo picker, in a card under the portrait.
                a.findViewById<View>(R.id.rpPersonaAvatarFrame).performClick(); idle()
                val found = ArrayList<View>()
                root(a).findViewsWithText(found, a.getString(R.string.avatar_source_photos), View.FIND_VIEWS_WITH_TEXT)
                org.junit.Assert.assertTrue("the source card opened", found.isNotEmpty())
                snap(root(a), "rp_avatar_source_dark")
                a.onBackPressedDispatcher.onBackPressed(); idle()
            }
            a.supportFragmentManager.popBackStack(); idle()
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
        // A second version of that reply, then a new turn: the reply keeps both, a swipe away.
        val replyAt = msgs.lastIndex
        vm.swipeRpNext()
        waitFor(30_000) { vm.isAwaitingResponse.value == false && vm.rpSwipeNav.value?.total == 2 }
        val second = vm.getMessageText(vm.chatMessages.value.orEmpty()[replyAt].content)
        input.setText("I slide the map across the table.")
        send.performClick()
        // Already earlier while the answer is still thinking: the redraw that shows its control runs now.
        waitFor(30_000) { vm.chatMessages.value.orEmpty().size >= replyAt + 3 }
        org.junit.Assert.assertEquals(2, vm.getRpVersionNav(replyAt)?.totalVariants)
        waitFor(30_000) { vm.isAwaitingResponse.value == false && vm.chatMessages.value.orEmpty().size == replyAt + 3 }
        settle()
        org.junit.Assert.assertEquals(2, vm.getRpVersionNav(replyAt)?.totalVariants)
        val transcript = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.chatRecyclerView)
        transcript.scrollToPosition(replyAt); idle()
        val versions = transcript.findViewHolderForAdapterPosition(replyAt)!!.itemView.findViewById<View>(R.id.forkNavigator)
        org.junit.Assert.assertEquals("the earlier reply shows its versions", View.VISIBLE, versions.visibility)
        vm.swipeEarlierRpReply(replyAt, -1); idle()
        org.junit.Assert.assertEquals("swapped in place", afterText, vm.getMessageText(vm.chatMessages.value.orEmpty()[replyAt].content))
        org.junit.Assert.assertEquals("what came after stays", replyAt + 3, vm.chatMessages.value.orEmpty().size)
        vm.swipeEarlierRpReply(replyAt, 1); idle()
        org.junit.Assert.assertEquals(second, vm.getMessageText(vm.chatMessages.value.orEmpty()[replyAt].content))
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
        // Bubbles is flat on both sides: no gradient on the character's or the user's fill.
        val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.chatRecyclerView)
        val fills = (0 until rv.childCount).mapNotNull {
            rv.getChildAt(it).findViewById<View>(R.id.messageContainer)?.background as? android.graphics.drawable.GradientDrawable
        }
        org.junit.Assert.assertTrue("bubbles on screen", fills.size >= 2)
        fills.forEach { org.junit.Assert.assertNull("flat fill", it.colors) }
        // Mira's persona (Sam) sits over your lines; the Layout switch takes it away.
        fun youHeaders() = (0 until rv.childCount).mapNotNull { rv.getChildAt(it).findViewById<View>(R.id.rpUserHeader) }
        org.junit.Assert.assertTrue("your lines on screen", youHeaders().isNotEmpty())
        youHeaders().forEach {
            org.junit.Assert.assertEquals(View.VISIBLE, it.visibility)
            org.junit.Assert.assertEquals("Sam", it.findViewById<android.widget.TextView>(R.id.rpUserName).text.toString())
        }
        SharedPreferencesHelper(a).saveRpShowPersona(mira.id, false)
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
        a.findViewById<View>(R.id.tabRoleplay).performClick(); settle()
        youHeaders().forEach { org.junit.Assert.assertEquals(View.GONE, it.visibility) }
        SharedPreferencesHelper(a).saveRpShowPersona(mira.id, true)
        SharedPreferencesHelper(a).saveRpLayout(mira.id, SharedPreferencesHelper.RP_LAYOUT_CLASSIC)
        BackgroundPhoto.delete(ctx, BackgroundPhoto.slotForCharacter(mira.id))
        a.findViewById<View>(R.id.tabChat).performClick(); settle()
    }
}
