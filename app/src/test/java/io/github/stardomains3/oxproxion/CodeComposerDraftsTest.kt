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
}
