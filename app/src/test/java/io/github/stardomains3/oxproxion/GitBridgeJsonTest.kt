package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.GitBridgeJson
import io.github.stardomains3.oxproxion.code.GitFileStatus
import io.github.stardomains3.oxproxion.code.GitStatusResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitBridgeJsonTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parseStatusReadsBranchAheadBehindAndFiles() {
        val el = json.parseToJsonElement(
            """{"branch":"feat/x","ahead":2,"behind":1,"files":[
                {"path":"a.kt","status":" M"},
                {"path":"b.md","status":"??"}
            ]}"""
        )
        val s = GitBridgeJson.parseStatus(el)
        assertEquals(
            GitStatusResult(
                branch = "feat/x",
                ahead = 2,
                behind = 1,
                files = listOf(
                    GitFileStatus("a.kt", " M"),
                    GitFileStatus("b.md", "??")
                )
            ),
            s
        )
    }

    @Test fun parseStatusToleratesMissingFields() {
        val s = GitBridgeJson.parseStatus(json.parseToJsonElement("{}"))
        assertEquals(GitStatusResult("", 0, 0, emptyList()), s)
    }

    @Test fun parseStatusRejectsNullOrNonObject() {
        assertThrows(IllegalStateException::class.java) {
            GitBridgeJson.parseStatus(null)
        }
        assertThrows(IllegalStateException::class.java) {
            GitBridgeJson.parseStatus(json.parseToJsonElement("\"ok\""))
        }
        assertThrows(IllegalStateException::class.java) {
            GitBridgeJson.parseStatus(json.parseToJsonElement("[]"))
        }
    }

    @Test fun parseDiffReadsUnified() {
        val el = json.parseToJsonElement("""{"unified":"@@ -1 +1 @@\n-a\n+b\n"}""")
        assertEquals("@@ -1 +1 @@\n-a\n+b\n", GitBridgeJson.parseDiff(el).unified)
        assertEquals("", GitBridgeJson.parseDiff(json.parseToJsonElement("{}")).unified)
    }

    @Test fun parseDiffRejectsNullOrNonObject() {
        assertThrows(IllegalStateException::class.java) {
            GitBridgeJson.parseDiff(null)
        }
        assertThrows(IllegalStateException::class.java) {
            GitBridgeJson.parseDiff(json.parseToJsonElement("\"diff\""))
        }
        assertThrows(IllegalStateException::class.java) {
            GitBridgeJson.parseDiff(json.parseToJsonElement("[1]"))
        }
    }

    @Test fun statusLetterPrefersWorkTreeThenIndex() {
        assertEquals("M", GitBridgeJson.statusLetter(" M"))
        assertEquals("M", GitBridgeJson.statusLetter("M "))
        assertEquals("?", GitBridgeJson.statusLetter("??"))
        assertEquals("A", GitBridgeJson.statusLetter("A "))
        assertEquals("D", GitBridgeJson.statusLetter(" D"))
        assertTrue(GitBridgeJson.statusLetter("  ").isNotEmpty())
        assertEquals("R", GitBridgeJson.statusLetter("R100"))
        assertEquals("C", GitBridgeJson.statusLetter("C075"))
        assertEquals("!", GitBridgeJson.statusLetter("!!"))
    }

    @Test fun aheadBehindWrittenAsDoublesStillParse() {
        val el = json.parseToJsonElement(
            """{"branch":"main","ahead":2.0,"behind":1.0,"files":[]}"""
        )
        val s = GitBridgeJson.parseStatus(el)
        assertEquals(2, s.ahead)
        assertEquals(1, s.behind)
        val elStr = json.parseToJsonElement(
            """{"branch":"main","ahead":"3.0","behind":"0.0","files":[]}"""
        )
        val s2 = GitBridgeJson.parseStatus(elStr)
        assertEquals(3, s2.ahead)
        assertEquals(0, s2.behind)
    }

    @Test fun pathAndBranchWrittenAsDoublesStillMatch() {
        val el = json.parseToJsonElement(
            """{"branch":5.0,"ahead":0,"behind":0,"files":[
                {"path":5.0,"status":" M"},
                {"path":"9.0","status":"??"},
                {"path":"a.kt","status":"A "}
            ]}"""
        )
        val s = GitBridgeJson.parseStatus(el)
        assertEquals("5", s.branch)
        assertEquals(
            listOf(
                GitFileStatus("5", " M"),
                GitFileStatus("9", "??"),
                GitFileStatus("a.kt", "A "),
            ),
            s.files,
        )
        val elStr = json.parseToJsonElement(
            """{"branch":"7.0","files":[{"path":"3.0","status":"M "}]}"""
        )
        val s2 = GitBridgeJson.parseStatus(elStr)
        assertEquals("7", s2.branch)
        assertEquals(listOf(GitFileStatus("3", "M ")), s2.files)
    }
}
