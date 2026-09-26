package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.GitBridgeJson
import io.github.stardomains3.oxproxion.code.GitFileStatus
import io.github.stardomains3.oxproxion.code.GitStatusResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
        assertEquals(GitStatusResult("", 0, 0, emptyList()), GitBridgeJson.parseStatus(null))
    }

    @Test fun parseDiffReadsUnified() {
        val el = json.parseToJsonElement("""{"unified":"@@ -1 +1 @@\n-a\n+b\n"}""")
        assertEquals("@@ -1 +1 @@\n-a\n+b\n", GitBridgeJson.parseDiff(el).unified)
        assertEquals("", GitBridgeJson.parseDiff(null).unified)
        assertEquals("", GitBridgeJson.parseDiff(json.parseToJsonElement("{}")).unified)
    }

    @Test fun statusLetterPrefersWorkTreeThenIndex() {
        assertEquals("M", GitBridgeJson.statusLetter(" M"))
        assertEquals("M", GitBridgeJson.statusLetter("M "))
        assertEquals("?", GitBridgeJson.statusLetter("??"))
        assertEquals("A", GitBridgeJson.statusLetter("A "))
        assertEquals("D", GitBridgeJson.statusLetter(" D"))
        assertTrue(GitBridgeJson.statusLetter("  ").isNotEmpty())
    }
}
