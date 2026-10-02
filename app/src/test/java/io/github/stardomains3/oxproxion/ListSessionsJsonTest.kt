package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.HarnessKind
import io.github.stardomains3.oxproxion.code.ListSessionsJson
import io.github.stardomains3.oxproxion.code.PermissionMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListSessionsJsonTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parseReadsSessionsAndHostId() {
        val el = json.parseToJsonElement(
            """{"sessions":[{
                "sessionId":"s1","harness":"cursor","cwd":"/w","title":"T",
                "createdAt":100,"updatedAt":200,"permissionMode":"ask",
                "model":"m","preview":"p","branch":"main","lastSeq":7
            }]}"""
        )
        val list = ListSessionsJson.parse(el, "host-a")
        assertEquals(1, list.size)
        val s = list.single()
        assertEquals("s1", s.id)
        assertEquals("host-a", s.hostId)
        assertEquals(HarnessKind.fromId("cursor"), s.harness)
        assertEquals("/w", s.workspace)
        assertEquals("T", s.title)
        assertEquals(100L, s.createdAt)
        assertEquals(200L, s.updatedAt)
        assertEquals(PermissionMode.ASK, s.permissionMode)
        assertEquals("m", s.model)
        assertEquals("p", s.preview)
        assertEquals("main", s.branch)
        assertEquals(7L, s.lastSeq)
    }

    @Test fun lastSeqAndTimestampsWrittenAsDoublesStillParse() {
        val el = json.parseToJsonElement(
            """{"sessions":[{
                "sessionId":"s1","cwd":"/w","title":"T",
                "createdAt":100.0,"updatedAt":200.0,"lastSeq":42.0
            }]}"""
        )
        val s = ListSessionsJson.parse(el, "h").single()
        assertEquals(100L, s.createdAt)
        assertEquals(200L, s.updatedAt)
        assertEquals(42L, s.lastSeq)
    }

    @Test fun lastSeqStringWholeNumberDoublesStillParse() {
        val el = json.parseToJsonElement(
            """{"sessions":[{"sessionId":"s1","lastSeq":"9.0","createdAt":"3.0","updatedAt":"4.0"}]}"""
        )
        val s = ListSessionsJson.parse(el, "h").single()
        assertEquals(3L, s.createdAt)
        assertEquals(4L, s.updatedAt)
        assertEquals(9L, s.lastSeq)
    }

    @Test fun sessionIdWrittenAsDoubleStillMatchesAsDigitString() {
        val el = json.parseToJsonElement(
            """{"sessions":[{"sessionId":5.0,"cwd":"/w","title":"T"},{"sessionId":"6.0","cwd":"/w","title":"U"}]}"""
        )
        val list = ListSessionsJson.parse(el, "h")
        assertEquals(listOf("5", "6"), list.map { it.id })
    }

    @Test fun missingLastSeqStaysNullAndEmptyResultIsEmpty() {
        assertNull(ListSessionsJson.parse(json.parseToJsonElement("""{"sessions":[{"sessionId":"s1"}]}"""), "h").single().lastSeq)
        assertTrue(ListSessionsJson.parse(null, "h").isEmpty())
        assertTrue(ListSessionsJson.parse(json.parseToJsonElement("[]"), "h").isEmpty())
        assertTrue(ListSessionsJson.parse(json.parseToJsonElement("{}"), "h").isEmpty())
    }
}
