package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Test

class RpChatSummariesTest {

    @Test
    fun previewKeepsActionMarksButDropsOtherMarkdown() {
        val out = RpChatSummaries.previewOf("**Bold** and `code`\n\n*sighs and grabs a wrench* Fine. Show me.")
        assertEquals("Bold and code *sighs and grabs a wrench* Fine. Show me.", out)
    }

    @Test
    fun styledPreviewMarksTheActionRange() {
        val styled = RpChatSummaries.styledPreview("*sighs and grabs a wrench* Fine. Show me.")
        assertEquals("sighs and grabs a wrench Fine. Show me.", styled.text)
        assertEquals(1, styled.actions.size)
        assertEquals("sighs and grabs a wrench", styled.text.substring(styled.actions[0].first, styled.actions[0].last + 1))
    }

    @Test
    fun styledPreviewHandlesSeveralActionsAndStrayMarks() {
        val styled = RpChatSummaries.styledPreview("Hi *waves* there *nods* and a lone * star")
        assertEquals("Hi waves there nods and a lone  star", styled.text)
        assertEquals(listOf("waves", "nods"), styled.actions.map { styled.text.substring(it.first, it.last + 1) })
    }

    @Test
    fun plainTextHasNoActions() {
        val styled = RpChatSummaries.styledPreview("No messages yet")
        assertEquals("No messages yet", styled.text)
        assertEquals(0, styled.actions.size)
    }
}
