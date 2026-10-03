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


    @Test fun modelAndCwdWrittenAsDoublesStillMatchAsDigitStrings() {
        val el = json.parseToJsonElement(
            """{"sessions":[
                {"sessionId":"s1","cwd":5.0,"model":5.0,"title":"T"},
                {"sessionId":"s2","cwd":"9.0","model":"7.0","title":"U"},
                {"sessionId":"s3","cwd":"/home/warexpor","model":"gpt-5","title":"V"}
            ]}"""
        )
        val list = ListSessionsJson.parse(el, "h")
        assertEquals(listOf("5", "9", "/home/warexpor"), list.map { it.workspace })
        assertEquals(listOf("5", "7", "gpt-5"), list.map { it.model })
    }


    @Test fun harnessAndBranchWrittenAsDoublesStillMatchAsDigitStrings() {
        val el = json.parseToJsonElement(
            """{"sessions":[
                {"sessionId":"s1","harness":5.0,"branch":5.0,"title":"T"},
                {"sessionId":"s2","harness":"9.0","branch":"7.0","title":"U"},
                {"sessionId":"s3","harness":"opencode","branch":"feat/x","title":"V"}
            ]}"""
        )
        val list = ListSessionsJson.parse(el, "h")
        // Unknown numeric harness ids become CUSTOM. Branch "7.0" is "7", not the harness digit.
        assertEquals(listOf("5", "7", "feat/x"), list.map { it.branch })
        assertEquals(
            listOf(
                io.github.stardomains3.oxproxion.code.HarnessKind.CUSTOM,
                io.github.stardomains3.oxproxion.code.HarnessKind.CUSTOM,
                io.github.stardomains3.oxproxion.code.HarnessKind.OPENCODE,
            ),
            list.map { it.harness },
        )
    }

    @Test fun permissionModeUsesAcpAliasesNotOnlyExactIds() {
        val el = json.parseToJsonElement(
            """{"sessions":[
                {"sessionId":"a","permissionMode":"acceptEdits"},
                {"sessionId":"b","mode":"bypassPermissions"},
                {"sessionId":"c","permissionMode":"agent"},
                {"sessionId":"d","permissionMode":"full_auto"},
                {"sessionId":"e","permissionMode":"default"},
                {"sessionId":"f","mode":"Plan"},
                {"sessionId":"g","permissionMode":"not-a-mode"},
                {"sessionId":"h"}
            ]}"""
        )
        val modes = ListSessionsJson.parse(el, "h").map { it.permissionMode }
        assertEquals(
            listOf(
                PermissionMode.AUTO_EDIT,
                PermissionMode.FULL_AUTO,
                PermissionMode.FULL_AUTO,
                PermissionMode.FULL_AUTO,
                PermissionMode.ASK,
                PermissionMode.PLAN,
                PermissionMode.ASK,
                PermissionMode.ASK,
            ),
            modes,
        )
    }

    @Test fun codexAndOpenCodeModesAreNotAsk() {
        val el = json.parseToJsonElement(
            """{"sessions":[
                {"sessionId":"ro","permissionMode":"read-only"},
                {"sessionId":"au","mode":"auto"},
                {"sessionId":"fa","permissionMode":"full-access"},
                {"sessionId":"fu","permissionMode":"full_access"},
                {"sessionId":"bd","mode":"build"},
                {"sessionId":"ae","permissionMode":"auto-edit"}
            ]}"""
        )
        val modes = ListSessionsJson.parse(el, "h").associate { it.id to it.permissionMode }
        assertEquals(PermissionMode.ASK, modes["ro"])
        assertEquals(PermissionMode.AUTO_EDIT, modes["au"])
        assertEquals(PermissionMode.FULL_AUTO, modes["fa"])
        assertEquals(PermissionMode.FULL_AUTO, modes["fu"])
        assertEquals(PermissionMode.AUTO_EDIT, modes["bd"])
        assertEquals(PermissionMode.AUTO_EDIT, modes["ae"])
    }

    @Test fun missingLastSeqStaysNullAndEmptyResultIsEmpty() {
        assertNull(ListSessionsJson.parse(json.parseToJsonElement("""{"sessions":[{"sessionId":"s1"}]}"""), "h").single().lastSeq)
        assertTrue(ListSessionsJson.parse(null, "h").isEmpty())
        assertTrue(ListSessionsJson.parse(json.parseToJsonElement("[]"), "h").isEmpty())
        assertTrue(ListSessionsJson.parse(json.parseToJsonElement("{}"), "h").isEmpty())
    }
}
