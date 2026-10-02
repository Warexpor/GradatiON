package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.CodeComposerDrafts
import io.github.stardomains3.oxproxion.code.PromptAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeComposerDraftsTest {

    @Test fun blankLineIsNotADraft() {
        val drafts = HashMap<String, CodeComposerDrafts.Draft>()
        CodeComposerDrafts.park(drafts, "s1", "hello")
        assertEquals("hello", drafts["s1"]?.text)
        CodeComposerDrafts.park(drafts, "s1", "   ")
        assertNull(drafts["s1"])
    }

    @Test fun picturesStayWhenTheLineIsBlank() {
        val drafts = HashMap<String, CodeComposerDrafts.Draft>()
        val photo = PromptAttachment("image/png", "AAAA")
        CodeComposerDrafts.park(drafts, "s1", "  ", listOf(photo))
        assertEquals(1, drafts["s1"]?.attachments?.size)
        CodeComposerDrafts.park(drafts, "s1", "", emptyList())
        assertNull(drafts["s1"])
    }

    @Test fun blankSessionIdIsIgnored() {
        val drafts = HashMap<String, CodeComposerDrafts.Draft>()
        CodeComposerDrafts.park(drafts, "  ", "hello")
        assertEquals(0, drafts.size)
    }

    @Test fun homeKeyIsPerHost() {
        assertEquals("home\u0000laptop", CodeComposerDrafts.homeKey("laptop"))
        assertNull(CodeComposerDrafts.homeKey("  "))
        val drafts = HashMap<String, CodeComposerDrafts.Draft>()
        val key = CodeComposerDrafts.homeKey("demo")!!
        CodeComposerDrafts.park(drafts, key, "fix reconnect")
        assertEquals("fix reconnect", drafts[key]?.text)
        CodeComposerDrafts.park(drafts, key, "   ")
        assertNull(drafts[key])
    }

    @Test fun homeDraftDoesNotCollideWithASessionId() {
        val drafts = HashMap<String, CodeComposerDrafts.Draft>()
        CodeComposerDrafts.park(drafts, "demo", "session line")
        val home = CodeComposerDrafts.homeKey("demo")!!
        CodeComposerDrafts.park(drafts, home, "home line")
        assertEquals("session line", drafts["demo"]?.text)
        assertEquals("home line", drafts[home]?.text)
    }
}
