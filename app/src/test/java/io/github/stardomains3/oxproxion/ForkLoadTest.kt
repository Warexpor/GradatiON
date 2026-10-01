package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A fork this version cannot decode is not a reason to delete the stored copy.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*ForkLoadTest*'
 */
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
}
