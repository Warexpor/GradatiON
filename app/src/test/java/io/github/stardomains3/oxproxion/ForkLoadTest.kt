package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A fork this version cannot decode is not a reason to delete the stored copy.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*ForkLoadTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class ForkLoadTest {

    @Test
    fun aTornForkDecodesAsNothing() {
        assertNull(ForkLoad.messages(null))
        assertNull(ForkLoad.messages(""))
        assertNull(ForkLoad.messages("{torn"))
        assertNull(ForkLoad.messages("[]"))
    }

    @Test
    fun aReadableForkComesBack() {
        val raw = """[{"role":"user","content":"hi"}]"""
        assertEquals("user", ForkLoad.messages(raw)!!.single().role)
    }

    private fun m(text: String, role: String = "assistant") =
        FlexibleMessage(role = role, content = kotlinx.serialization.json.JsonPrimitive(text))

    private fun texts(tail: List<FlexibleMessage>) = tail.map { (it.content as kotlinx.serialization.json.JsonPrimitive).content }

    @Test
    fun everyRedoAddsABranch() {
        val q = m("q", "user")
        var state = ForkBranches.cut(listOf(q, m("a1")), 1, null)
        state = ForkBranches.cut(listOf(q, m("a2")), 1, state)
        state = ForkBranches.cut(listOf(q, m("a3")), 1, state)
        assertEquals(4, state.branches.size)
        assertEquals(3, state.active)
        assertEquals(listOf("a1"), texts(state.branches[0]))
        assertEquals(listOf("a2"), texts(state.branches[1]))
        assertEquals(listOf("a3"), texts(state.branches[2]))
    }

    @Test
    fun aDeeperCutKeepsTheWholeTailAndAShallowerOneCarriesEveryBranch() {
        val q = m("q", "user")
        val first = ForkBranches.cut(listOf(q, m("a1")), 1, null)
        // Redo a later reply: the branch is everything from the first cut.
        val deeper = ForkBranches.cut(listOf(q, m("a2"), m("q2", "user"), m("b1")), 3, first)
        assertEquals(1, deeper.index)
        assertEquals(listOf("a2", "q2", "b1"), texts(deeper.branches[1]))
        // Edit the first question: the cut moves up and the old branches keep their question.
        val shallower = ForkBranches.cut(listOf(q, m("a2"), m("q2", "user"), m("b2")), 0, deeper)
        assertEquals(0, shallower.index)
        assertEquals(listOf("q", "a1"), texts(shallower.branches[0]))
        assertEquals(listOf("q", "a2", "q2", "b1"), texts(shallower.branches[1]))
        assertEquals(listOf("q", "a2", "q2", "b2"), texts(shallower.branches[2]))
        assertEquals(3, shallower.active)
    }

    @Test
    fun branchesRoundTripAndTheOldShapeStillLoads() {
        val tails = listOf(listOf(m("a1")), listOf(m("a2")), emptyList())
        val back = ForkLoad.branches(ForkLoad.encode(tails, 2))!!
        assertEquals(2, back.active)
        assertEquals(listOf("a2"), texts(back.tails[1]))
        val legacy = ForkLoad.branches("""[{"role":"assistant","content":"old"}]""")!!
        assertEquals(1, legacy.active)
        assertEquals(listOf("old"), texts(legacy.tails[0]))
        // Two branches, newest on screen: still the old shape.
        assertEquals("assistant", ForkLoad.messages(ForkLoad.encode(listOf(listOf(m("x")), emptyList()), 1))!!.single().role)
    }
}
