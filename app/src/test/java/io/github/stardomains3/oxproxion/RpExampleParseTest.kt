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
    fun leadingBlankLineKeepsTheUserSide() {
        val examples = RpPromptEngine.parseExamplesFromEdit("\n\nUser: hi\nChar: hello")
        assertEquals(1, examples.size)
        assertEquals("hi", examples[0].user)
        assertEquals("hello", examples[0].char)
    }

    @Test
    fun windowsBreaksAndSpacedDashesStillSplit() {
        val windows = RpPromptEngine.parseExamplesFromEdit(
            "User: hi\r\nChar: hello\r\n---\r\nUser: bye\r\nChar: later"
        )
        assertEquals(2, windows.size)
        assertEquals("hi", windows[0].user)
        assertEquals("hello", windows[0].char)
        assertEquals("bye", windows[1].user)
        assertEquals("later", windows[1].char)
        val spaced = RpPromptEngine.parseExamplesFromEdit("User: hi\nChar: hello\n --- \nUser: bye\nChar: later")
        assertEquals(2, spaced.size)
        assertEquals("bye", spaced[1].user)
        assertEquals("later", spaced[1].char)
    }

    @Test
    fun fullwidthColonStillLabelsTheSides() {
        val examples = RpPromptEngine.parseExamplesFromEdit("User：hi\nChar：hello")
        assertEquals(1, examples.size)
        assertEquals("hi", examples[0].user)
        assertEquals("hello", examples[0].char)
    }

    @Test
    fun aSpaceBeforeTheColonStillLabelsTheSides() {
        val examples = RpPromptEngine.parseExamplesFromEdit("User : hi\nChar : hello")
        assertEquals(1, examples.size)
        assertEquals("hi", examples[0].user)
        assertEquals("hello", examples[0].char)
        val wide = RpPromptEngine.parseExamplesFromEdit("User ： hi\n  Char ： hello")
        assertEquals("hi", wide[0].user)
        assertEquals("hello", wide[0].char)
    }

    @Test
    fun characterFirstStillKeepsTheUserSide() {
        val examples = RpPromptEngine.parseExamplesFromEdit("Char: hello\nUser: hi")
        assertEquals(1, examples.size)
        assertEquals("hi", examples[0].user)
        assertEquals("hello", examples[0].char)
        val quoted = RpPromptEngine.parseExamplesFromEdit(
            "User: hi\nChar: the note said\nUser: come back"
        )
        assertEquals("hi", quoted[0].user)
        assertEquals("the note said\nUser: come back", quoted[0].char)
    }

    @Test
    fun cardStartMarkerKeepsLaterExchanges() {
        val pasted = """
            <START>
            {{user}}: hi
            {{char}}: hello
            <START>
            {{bot}}: I was here first
            <USER>: bye
        """.trimIndent()
        val examples = RpPromptEngine.parseExamplesFromEdit(pasted)
        assertEquals(2, examples.size)
        assertEquals("hi", examples[0].user)
        assertEquals("hello", examples[0].char)
        assertEquals("bye", examples[1].user)
        assertEquals("I was here first", examples[1].char)
        val plain = RpPromptEngine.parseExamplesFromEdit(
            "User: hi\nChar: hello\n<START>\nUser: bye\nChar: later"
        )
        assertEquals(2, plain.size)
        assertEquals("hi", plain[0].user)
        assertEquals("hello", plain[0].char)
        assertEquals("bye", plain[1].user)
        assertEquals("later", plain[1].char)
    }

    @Test
    fun aTrailingStartOrEndOfDialogIsNotTheReply() {
        val trailing = RpPromptEngine.parseExamplesFromEdit("User: hi\nChar: hello\n<START>")
        assertEquals(1, trailing.size)
        assertEquals("hi", trailing[0].user)
        assertEquals("hello", trailing[0].char)
        val withBreak = RpPromptEngine.parseExamplesFromEdit("User: hi\nChar: hello\n<START>\n")
        assertEquals("hello", withBreak[0].char)
        val ended = RpPromptEngine.parseExamplesFromEdit(
            "{{user}}: hi\n{{char}}: hello\nEND_OF_DIALOG\n{{user}}: bye\n{{char}}: later"
        )
        assertEquals(2, ended.size)
        assertEquals("hi", ended[0].user)
        assertEquals("hello", ended[0].char)
        assertEquals("bye", ended[1].user)
        assertEquals("later", ended[1].char)
        val markerOnly = RpPromptEngine.parseExamplesFromEdit("User: hi\nChar: hello\r\nEND_OF_DIALOG")
        assertEquals("hello", markerOnly[0].char)
    }

    @Test
    fun botLabelIsTheCharacterSide() {
        val examples = RpPromptEngine.parseExamplesFromEdit("User: hi\nBot: hello")
        assertEquals(1, examples.size)
        assertEquals("hi", examples[0].user)
        assertEquals("hello", examples[0].char)
        val first = RpPromptEngine.parseExamplesFromEdit("Bot : I was here first\nUser： hi")
        assertEquals("hi", first[0].user)
        assertEquals("I was here first", first[0].char)
        assertTrue(RpPromptEngine.parseExamplesFromEdit("Botany: hi").isEmpty())
    }

    @Test
    fun aStartWordInsideAReplyStaysThere() {
        val examples = RpPromptEngine.parseExamplesFromEdit(
            "User: hi\nChar: the note said <START> come back\n{{user}}: quoted"
        )
        assertEquals(1, examples.size)
        assertEquals("hi", examples[0].user)
        assertEquals("the note said <START> come back\n{{user}}: quoted", examples[0].char)
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
