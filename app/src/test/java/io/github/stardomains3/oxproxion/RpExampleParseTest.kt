package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpExampleParseTest {

    @Test
    fun multilineUserAndCharBlocks() {
        val text = """
            User: line one
            line two
            Char: reply one
            reply two
        """.trimIndent()
        val examples = RpPromptEngine.parseExamplesFromEdit(text)
        assertEquals(1, examples.size)
        assertEquals("line one\nline two", examples[0].user)
        assertEquals("reply one\nreply two", examples[0].char)
    }

    @Test
    fun multipleBlocksSeparatedByDashes() {
        val text = """
            User: hi
            Char: hello
            ---
            User: bye
            Char: later
        """.trimIndent()
        val examples = RpPromptEngine.parseExamplesFromEdit(text)
        assertEquals(2, examples.size)
        assertEquals("hi", examples[0].user)
        assertEquals("hello", examples[0].char)
        assertEquals("bye", examples[1].user)
        assertEquals("later", examples[1].char)
    }

    @Test
    fun oneSidedUserOnly() {
        val examples = RpPromptEngine.parseExamplesFromEdit("User: only user")
        assertEquals(1, examples.size)
        assertEquals("only user", examples[0].user)
        assertTrue(examples[0].char.isBlank())
    }

    @Test
    fun oneSidedCharOnly() {
        val examples = RpPromptEngine.parseExamplesFromEdit("Char: only char")
        assertEquals(1, examples.size)
        assertTrue(examples[0].user.isBlank())
        assertEquals("only char", examples[0].char)
    }

    @Test
    fun blankInputYieldsEmpty() {
        assertTrue(RpPromptEngine.parseExamplesFromEdit("").isEmpty())
        assertTrue(RpPromptEngine.parseExamplesFromEdit("   ").isEmpty())
    }

    @Test
    fun freeformWithoutLabelsYieldsEmpty() {
        assertTrue(RpPromptEngine.parseExamplesFromEdit("just some dialogue").isEmpty())
        assertTrue(RpPromptEngine.parseExamplesFromEdit("Alice: hi\nBob: hey").isEmpty())
    }

    @Test
    fun roundTripFormatAndParse() {
        val json = """[{"user":"a","char":"b"},{"user":"c","char":"d"}]"""
        val formatted = RpPromptEngine.formatExamplesForEdit(json)
        val parsed = RpPromptEngine.parseExamplesFromEdit(formatted)
        assertEquals(2, parsed.size)
        assertEquals("a", parsed[0].user)
        assertEquals("b", parsed[0].char)
        assertEquals("c", parsed[1].user)
        assertEquals("d", parsed[1].char)
    }
}
