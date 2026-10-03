package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ComposerFilesTest {

    @Test fun files_are_admitted_against_the_running_total() {
        val one = 800L * 1024
        var total = 0L
        repeat(3) {
            assertEquals(ComposerFiles.Decision.ACCEPT, ComposerFiles.decide(total, one))
            total += one
        }
        assertEquals(ComposerFiles.Decision.OVER_TOTAL, ComposerFiles.decide(total, one))
        // Each file used to measure a snapshot of 0, so all four were kept.
        assertEquals(ComposerFiles.Decision.ACCEPT, ComposerFiles.decide(0, one))
        assertEquals(
            ComposerFiles.Decision.ACCEPT,
            ComposerFiles.decide(ComposerFiles.MAX_TOTAL_BYTES - one, one),
        )
    }

    @Test fun a_single_file_past_one_megabyte_is_refused() {
        assertEquals(
            ComposerFiles.Decision.TOO_BIG,
            ComposerFiles.decide(0, ComposerFiles.MAX_SINGLE_BYTES + 1L),
        )
        assertEquals(
            ComposerFiles.Decision.ACCEPT,
            ComposerFiles.decide(0, ComposerFiles.MAX_SINGLE_BYTES.toLong()),
        )
        assertEquals(ComposerFiles.Decision.ACCEPT, ComposerFiles.decide(0, 0))
    }

    @Test fun read_stops_once_the_file_is_past_the_cap() {
        val ok = ComposerFiles.readCapped(ByteArrayInputStream("hello".toByteArray()), maxBytes = 5)
        assertFalse(ok.overflow)
        assertEquals("hello", ok.text)
        assertEquals(5, ok.bytes)
        val big = ComposerFiles.readCapped(ByteArrayInputStream("hello!".toByteArray()), maxBytes = 5)
        assertTrue(big.overflow)
        assertEquals("", big.text)
        val empty = ComposerFiles.readCapped(ByteArrayInputStream(ByteArray(0)), maxBytes = 5)
        assertFalse(empty.overflow)
        assertEquals("", empty.text)
    }

    @Test fun a_long_file_is_not_read_past_the_cap() {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int {
                reads++
                if (reads > 8) error("read past the cap")
                return 'a'.code
            }
        }
        val read = ComposerFiles.readCapped(input, maxBytes = 4)
        assertTrue(read.overflow)
        assertEquals("", read.text)
        assertTrue(reads <= 8)
    }

    @Test fun a_file_that_contains_a_fence_does_not_close_the_wrapper() {
        assertEquals(
            "File 1 (notes.txt):\n\n```text\nhello\n```",
            ComposerFiles.section(1, "notes.txt", "hello"),
        )
        assertEquals(
            "File 1 (empty.txt): (empty file)",
            ComposerFiles.section(1, "empty.txt", "  \n"),
        )
        val body = "before\n```\ncode\n```\nafter"
        val section = ComposerFiles.section(2, "a\nb.md", "\n$body\n")
        assertEquals("File 2 (a b.md):", section.lineSequence().first())
        assertTrue(section.contains("````text\n$body\n````"))
        assertFalse(section.contains("\n```text\n"))
        // U+2028 is a line break that is not CR or LF. It used to split the header.
        val unicode = ComposerFiles.section(3, "a\u2028b\u2029c.md", "hello")
        assertEquals("File 3 (a b c.md):", unicode.lineSequence().first())
        assertFalse(unicode.substringBefore("\n").contains('\u2028'))
        assertFalse(unicode.substringBefore("\n").contains('\u2029'))
    }

    @Test fun a_file_keeps_the_indent_on_its_first_line() {
        val snippet = "\n    def foo():\n        return 1\n"
        val section = ComposerFiles.section(1, "a.py", snippet)
        assertTrue(section.contains("```text\n    def foo():\n        return 1\n```"))
        val tabbed = ComposerFiles.section(2, "b.py", "\tkeep\n\t\tthis")
        assertTrue(tabbed.contains("```text\n\tkeep\n\t\tthis\n```"))
        assertEquals(
            "File 3 (blank.txt): (empty file)",
            ComposerFiles.section(3, "blank.txt", "\n\n"),
        )
    }

    @Test fun a_text_type_and_a_sibling_extension_are_accepted() {
        assertTrue(ComposerFiles.accepts("text/plain", "README"))
        assertTrue(ComposerFiles.accepts("text/plain; charset=utf-8", "README"))
        assertTrue(ComposerFiles.accepts("text/x-kotlin", "Main"))
        assertTrue(ComposerFiles.accepts("application/octet-stream", "Button.tsx"))
        assertTrue(ComposerFiles.accepts("application/octet-stream", "App.jsx"))
        assertTrue(ComposerFiles.accepts("application/octet-stream", "build.kts"))
        assertTrue(ComposerFiles.accepts("application/octet-stream", "config.toml"))
        assertTrue(ComposerFiles.accepts(null, "notes.py"))
        assertTrue(ComposerFiles.accepts("image/svg+xml", "icon.svg"))
        assertTrue(ComposerFiles.accepts("application/json", "a.json"))
        assertFalse(ComposerFiles.accepts("application/octet-stream", "photo.png"))
        assertFalse(ComposerFiles.accepts("application/pdf", "book.pdf"))
        assertFalse(ComposerFiles.accepts(null, "README"))
    }

    @Test fun a_bom_is_not_part_of_the_file_and_the_name_stays_one_line() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray()
        val read = ComposerFiles.readCapped(ByteArrayInputStream(bom), maxBytes = 10)
        assertEquals("hi", read.text)
        assertFalse(read.overflow)
        val section = ComposerFiles.section(1, "a\u2028b.txt", "\uFEFFhello")
        assertEquals("File 1 (a b.txt):", section.lineSequence().first())
        assertTrue(section.contains("```text\nhello\n```"))
        assertFalse(section.contains("\uFEFF"))
        val indented = ComposerFiles.section(2, "a.py", "\uFEFF\n    def foo():\n")
        assertTrue(indented.contains("```text\n    def foo():\n```"))
        assertEquals("a b.txt", ComposerFiles.singleLineName("a\nb.txt"))
        assertEquals(
            "File 1 (a b.txt): (empty file)",
            ComposerFiles.section(1, "a\u2028b.txt", "\uFEFF"),
        )
    }
}
