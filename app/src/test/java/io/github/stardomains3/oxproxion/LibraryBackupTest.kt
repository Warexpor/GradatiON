package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Prompt and system-message files from a newer version carry fields this one does not know.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*LibraryBackupTest*'
 */
class LibraryBackupTest {

    @Test
    fun unknownFieldsAreKeptOutOfTheWay() {
        val prompts = LibraryBackup.prompts("""[{"title":"T","prompt":"P","extra":1}]""")
        assertEquals("T", prompts.single().title)
        assertEquals("P", prompts.single().prompt)

        val messages = LibraryBackup.systemMessages(
            """[{"title":"S","prompt":"Body","isDefault":false,"note":"later"}]"""
        )
        assertEquals("S", messages.single().title)
        assertEquals("Body", messages.single().prompt)
    }
}
