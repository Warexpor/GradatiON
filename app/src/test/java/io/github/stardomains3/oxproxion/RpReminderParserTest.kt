package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpReminderParserTest {

    @Test
    fun extractsReminderAndCleansUserText() {
        val parsed = RpReminderParser.parse("Hello _(Reminder: stay tense)_ there")
        assertTrue(parsed.userText.contains("Hello"))
        assertTrue(parsed.userText.contains("there"))
        assertEquals("stay tense", parsed.reminder)
    }

    @Test
    fun emptyReminderBodyStillMatches() {
        val parsed = RpReminderParser.parse("Hi _(Reminder: )_ bye")
        assertEquals("", parsed.reminder)
        assertTrue(parsed.userText.contains("Hi"))
        assertTrue(parsed.userText.contains("bye"))
    }

    @Test
    fun multilineReminder() {
        val parsed = RpReminderParser.parse("Go _(Reminder: keep\ncalm)_ now")
        assertEquals("keep\ncalm", parsed.reminder)
        assertTrue(parsed.userText.contains("Go"))
        assertTrue(parsed.userText.contains("now"))
    }

    @Test
    fun noReminderLeavesTextIntact() {
        val parsed = RpReminderParser.parse("Just a normal message")
        assertNull(parsed.reminder)
        assertEquals("Just a normal message", parsed.userText)
    }

    @Test
    fun reminderOnlyLeavesUserTextBlank() {
        val parsed = RpReminderParser.parse("_(Reminder: stay tense)_")
        assertEquals("stay tense", parsed.reminder)
        assertEquals("", parsed.userText)
    }

    @Test
    fun everyReminderIsKept() {
        val parsed = RpReminderParser.parse("_(Reminder: one)_ and _(Reminder: two)_")
        assertEquals("one\ntwo", parsed.reminder)
        assertEquals("and", parsed.userText)
    }

    @Test
    fun reminderLabelMatchesInAnyCase() {
        val parsed = RpReminderParser.parse("Hello _(reminder: stay tense)_ there")
        assertEquals("stay tense", parsed.reminder)
        assertEquals("Hello  there", parsed.userText)
        assertEquals("one\ntwo", RpReminderParser.parse("_(REMINDER: one)_ _(Reminder: two)_").reminder)
    }

    @Test
    fun aSpaceOrFullwidthColonStillMarksAReminder() {
        assertEquals("stay tense", RpReminderParser.parse("_(Reminder : stay tense)_").reminder)
        assertEquals("stay tense", RpReminderParser.parse("_( Reminder：stay tense )_").reminder)
        assertEquals("Hello  there", RpReminderParser.parse("Hello _(Reminder : stay tense)_ there").userText)
    }

    @Test
    fun aNoteMayContainParentheses() {
        val parsed = RpReminderParser.parse("Hello _(Reminder: stay (tense))_ there")
        assertEquals("stay (tense)", parsed.reminder)
        assertEquals("Hello  there", parsed.userText)
    }
}
