package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.CodeMachineDetail
import io.github.stardomains3.oxproxion.code.CodeMachineDetail.StatusKind
import io.github.stardomains3.oxproxion.code.ConnectionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeMachineDetailTest {

    @Test
    fun redactUrl_stripsUserinfo() {
        assertEquals(
            "wss://•••@studio.tailnet.ts.net:7878/v1",
            CodeMachineDetail.redactUrl("wss://user:secret@studio.tailnet.ts.net:7878/v1"),
        )
    }

    @Test
    fun redactUrl_redactsTokenQueryParams() {
        assertEquals(
            "wss://studio.ts.net:7878/v1?token=•••&x=1",
            CodeMachineDetail.redactUrl("wss://studio.ts.net:7878/v1?token=super-secret&x=1"),
        )
        assertEquals(
            "wss://h/v1?t=•••",
            CodeMachineDetail.redactUrl("wss://h/v1?t=abc"),
        )
        assertEquals(
            "wss://h/v1?auth=•••",
            CodeMachineDetail.redactUrl("wss://h/v1?auth=abc"),
        )
    }

    @Test
    fun redactUrl_leavesCleanUrls() {
        assertEquals(
            "wss://studio.tailnet.ts.net:7878/v1",
            CodeMachineDetail.redactUrl("wss://studio.tailnet.ts.net:7878/v1"),
        )
        assertEquals("", CodeMachineDetail.redactUrl("  "))
    }

    @Test
    fun fingerprintShort_fromHex() {
        val hex = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val short = CodeMachineDetail.fingerprintShort(hex)
        assertEquals("a1b2c3d4…", short)
    }

    @Test
    fun fingerprintShort_blankIsNull() {
        assertNull(CodeMachineDetail.fingerprintShort(""))
        assertNull(CodeMachineDetail.fingerprintShort("   "))
    }

    @Test
    fun fingerprintShort_truncatesUnparseable() {
        val short = CodeMachineDetail.fingerprintShort("not-a-real-fingerprint-value-here")
        assertTrue(short!!.endsWith("…"))
        assertTrue(short.length <= 12)
    }

    @Test
    fun statusKind_mapping() {
        assertEquals(StatusKind.DEMO, CodeMachineDetail.statusKind(true, ConnectionState.DISCONNECTED))
        assertEquals(StatusKind.CONNECTED, CodeMachineDetail.statusKind(false, ConnectionState.CONNECTED))
        assertEquals(StatusKind.CONNECTING, CodeMachineDetail.statusKind(false, ConnectionState.CONNECTING))
        assertEquals(StatusKind.ERROR, CodeMachineDetail.statusKind(false, ConnectionState.FAILED))
        assertEquals(StatusKind.OFFLINE, CodeMachineDetail.statusKind(false, ConnectionState.DISCONNECTED))
    }

    @Test
    fun parseBridgeVersion_fromMetaBridge() {
        val json = Json.parseToJsonElement(
            """{"protocolVersion":1,"_meta":{"bridge":{"version":"0.4.2","hostName":"studio"}}}"""
        ).jsonObject
        assertEquals("0.4.2", CodeMachineDetail.parseBridgeVersion(json))
    }

    @Test
    fun parseBridgeVersion_fromServerInfo() {
        val json = Json.parseToJsonElement(
            """{"protocolVersion":1,"serverInfo":{"name":"gradation-bridge","version":"1.2.0"}}"""
        ).jsonObject
        assertEquals("1.2.0", CodeMachineDetail.parseBridgeVersion(json))
    }

    @Test
    fun parseBridgeVersion_prefersMetaOverServerInfo() {
        val json = Json.parseToJsonElement(
            """{"serverInfo":{"version":"9.9.9"},"_meta":{"bridge":{"version":"0.1.0"}}}"""
        ).jsonObject
        assertEquals("0.1.0", CodeMachineDetail.parseBridgeVersion(json))
    }

    @Test
    fun parseBridgeVersion_nullWhenMissing() {
        assertNull(CodeMachineDetail.parseBridgeVersion(null))
        val json = Json.parseToJsonElement("""{"protocolVersion":1}""").jsonObject
        assertNull(CodeMachineDetail.parseBridgeVersion(json))
    }

    @Test
    fun harnessModelCountLabel() {
        assertNull(CodeMachineDetail.harnessModelCountLabel(0))
        assertEquals("1 model", CodeMachineDetail.harnessModelCountLabel(1))
        assertEquals("3 models", CodeMachineDetail.harnessModelCountLabel(3))
    }
}
