package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings wave 31: tool switches can turn off without a grant, local HTTP accepts
 * CGNAT and local IPv6, and the server row does not show a pasted password.
 * Run: ./gradlew :app:testDebugUnitTest --tests io.github.stardomains3.oxproxion.SettingsWave31Test
 */
class SettingsWave31Test {

    @Test
    fun toolSwitchStaysUsableWhileTheToolIsOnWithoutPermission() {
        assertTrue(ToolItem.toolSwitchEnabled(needsPermission = true, permissionGranted = false, toolOn = true))
        assertFalse(ToolItem.toolSwitchEnabled(needsPermission = true, permissionGranted = false, toolOn = false))
        assertTrue(ToolItem.toolSwitchEnabled(needsPermission = true, permissionGranted = true, toolOn = false))
        assertTrue(ToolItem.toolSwitchEnabled(needsPermission = false, permissionGranted = false, toolOn = false))
    }

    @Test
    fun turningAGatedToolOnWithoutPermissionSnapsBack() {
        assertNull(
            ToolItem.toolEnabledAfterUserToggle(needsPermission = true, permissionGranted = false, enable = true),
        )
        assertEquals(
            false,
            ToolItem.toolEnabledAfterUserToggle(needsPermission = true, permissionGranted = false, enable = false),
        )
        assertEquals(
            true,
            ToolItem.toolEnabledAfterUserToggle(needsPermission = true, permissionGranted = true, enable = true),
        )
        // Create file and the other ungated tools are not snapped back.
        assertEquals(
            true,
            ToolItem.toolEnabledAfterUserToggle(needsPermission = false, permissionGranted = false, enable = true),
        )
    }

    @Test
    fun folderGrantListIsUnchanged() {
        assertTrue(ToolItem.needsFolderGrant("list_gradation_files"))
        assertTrue(ToolItem.needsFolderGrant("read_gradation_file"))
        assertTrue(ToolItem.needsFolderGrant("delete_files"))
        assertTrue(ToolItem.needsFolderGrant("open_file"))
        assertTrue(ToolItem.needsFolderGrant("edit_file"))
        assertTrue(ToolItem.needsFolderGrant("copy_file"))
        assertFalse(ToolItem.needsFolderGrant("make_file"))
        assertFalse(ToolItem.needsFolderGrant("create_folder"))
        assertFalse(ToolItem.needsFolderGrant("get_location"))
    }

    @Test
    fun httpAllowsCgnatLinkLocalAndUniqueLocal() {
        assertNull(LanEndpointValidator.validate("http://100.64.0.1:11434"))
        assertNull(LanEndpointValidator.validate("http://100.127.255.254:11434"))
        assertNull(LanEndpointValidator.validate("http://[fe80::1]:11434"))
        assertNull(LanEndpointValidator.validate("http://[fd7a:115c:a1e0::1]:11434"))
        assertNull(LanEndpointValidator.validate("http://[::ffff:192.168.1.5]:11434"))
        assertNull(LanEndpointValidator.validate("http://[::1]:11434"))
        assertNull(LanEndpointValidator.validate("http://192.168.1.10:11434"))
        assertNull(LanEndpointValidator.validate("http://nas.local:11434"))
    }

    @Test
    fun httpStillRejectsPublicLiterals() {
        assertEquals(R.string.lan_error_url_http_public, LanEndpointValidator.validate("http://100.63.255.255"))
        assertEquals(R.string.lan_error_url_http_public, LanEndpointValidator.validate("http://100.128.0.1"))
        assertEquals(R.string.lan_error_url_http_public, LanEndpointValidator.validate("http://8.8.8.8"))
        assertEquals(R.string.lan_error_url_http_public, LanEndpointValidator.validate("http://[2001:db8::1]"))
        assertEquals(R.string.lan_error_url_http_public, LanEndpointValidator.validate("http://[::ffff:8.8.8.8]"))
        assertEquals(R.string.lan_error_url_http_public, LanEndpointValidator.validate("http://[fec0::1]"))
        assertNull(LanEndpointValidator.validate("https://[2001:db8::1]"))
    }

    @Test
    fun privateHostEdges() {
        assertTrue(LanEndpointValidator.isPrivateOrLocalHost("fe80::1%wlan0"))
        assertTrue(LanEndpointValidator.isPrivateOrLocalHost("[FD00::1]"))
        assertTrue(LanEndpointValidator.isPrivateOrLocalHost("febf::"))
        assertTrue(LanEndpointValidator.isPrivateOrLocalHost("fc00::1"))
        assertFalse(LanEndpointValidator.isPrivateOrLocalHost("fec0::1"))
        assertFalse(LanEndpointValidator.isPrivateOrLocalHost(":::1"))
        assertTrue(LanEndpointValidator.isPrivateOrLocalHost("192.168.1.10"))
        assertTrue(LanEndpointValidator.isPrivateOrLocalHost("nas.local"))
        assertFalse(LanEndpointValidator.isPrivateOrLocalHost("8.8.8.8"))
        assertFalse(LanEndpointValidator.isPrivateOrLocalHost("172.15.0.1"))
        assertTrue(LanEndpointValidator.isPrivateOrLocalHost("172.16.0.1"))
    }

    @Test
    fun lanRowOmitsUserinfo() {
        assertEquals("10.0.0.23:11434", settingsLanRowValue("http://user:secret@10.0.0.23:11434"))
        assertEquals("10.0.0.23:11434", settingsLanRowValue("http://10.0.0.23:11434"))
        assertEquals("10.0.0.1:8080", settingsLanRowValue("http://user:sec%40ret@10.0.0.1:8080"))
        assertEquals("nas.local", settingsLanRowValue("https://nas.local"))
        assertEquals("[fe80::1]:11434", settingsLanRowValue("http://[fe80::1]:11434"))
        assertEquals("[fd00::1]:11434", settingsLanRowValue("http://user:secret@[fd00::1]:11434/v1"))
        assertNull(settingsLanRowValue(null))
        assertNull(settingsLanRowValue("   "))
    }
}
