package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * System messages Export wrote Default without isDefault and Import skipped any matching title,
 * so a customized Default never round-tripped; Export also refused when there were no customs.
 */
class SystemMessageImportTest {

    private val stock = SystemMessage("Default", "You are a helpful assistant.", isDefault = true)

    @Test
    fun exportKeepsDefaultFlagAndStripsItFromCustoms() {
        val custom = SystemMessage("Terse", "Be brief.", isDefault = true) // bad flag on disk
        val list = systemMessagesExportList(stock, listOf(custom))
        assertEquals(2, list.size)
        assertTrue(list[0].isDefault)
        assertEquals("Default", list[0].title)
        assertFalse(list[1].isDefault)
        assertEquals("Terse", list[1].title)
    }

    @Test
    fun importUpdatesDefaultAndAddsNewCustoms() {
        val imported = listOf(
            SystemMessage("Default", "Be a pirate.", isDefault = true),
            SystemMessage("Terse", "Be brief."),
            SystemMessage("Terse", "duplicate title"),
        )
        val plan = planSystemMessageImport(imported, stock, emptyList())
        assertEquals("Be a pirate.", plan.default!!.prompt)
        assertTrue(plan.default!!.isDefault)
        assertEquals(listOf("Terse"), plan.customs.map { it.title })
        assertFalse(plan.customs.single().isDefault)
    }

    @Test
    fun legacyExportWithoutFlagStillUpdatesDefaultByTitle() {
        val imported = listOf(SystemMessage("Default", "Legacy body."))
        val plan = planSystemMessageImport(imported, stock, listOf(SystemMessage("Keep", "me")))
        assertEquals("Legacy body.", plan.default!!.prompt)
        assertEquals(listOf("Keep"), plan.customs.map { it.title })
    }

    @Test
    fun customsOnlyFileLeavesDefaultAlone() {
        val plan = planSystemMessageImport(
            listOf(SystemMessage("Terse", "Be brief.")),
            stock,
            emptyList(),
        )
        assertNull(plan.default)
        assertEquals("Terse", plan.customs.single().title)
    }

    @Test
    fun explicitDefaultNotOverriddenByCustomMatchingCurrentTitle() {
        // Device A renamed Default to Helper and added a custom titled Default. Device B still
        // has the stock Default title — the custom must not steal the file's real Default.
        val imported = listOf(
            SystemMessage("Helper", "From file Default.", isDefault = true),
            SystemMessage("Default", "A custom that reused the stock title."),
        )
        val plan = planSystemMessageImport(imported, stock, emptyList())
        assertEquals("Helper", plan.default!!.title)
        assertEquals("From file Default.", plan.default!!.prompt)
        assertTrue(plan.default!!.isDefault)
        assertEquals(listOf("Default"), plan.customs.map { it.title })
        assertEquals("A custom that reused the stock title.", plan.customs.single().prompt)
        assertFalse(plan.customs.single().isDefault)
    }
}
