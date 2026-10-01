package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.GitChanges
import io.github.stardomains3.oxproxion.code.GitFileStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitChangesTest {

    @Test fun trackedCountSkipsUntrackedAndIgnored() {
        val files = listOf(
            GitFileStatus("a.kt", " M"),
            GitFileStatus("b.md", "M "),
            GitFileStatus("c.kt", "??"),
            GitFileStatus("d.log", "!!"),
            GitFileStatus("e.kt", "R "),
            GitFileStatus("f.kt", "?"),
        )
        assertEquals(3, GitChanges.trackedCount(files))
        assertEquals(2, GitChanges.untrackedCount(files))
        assertFalse(GitChanges.isTrackedChange("??"))
        assertFalse(GitChanges.isTrackedChange("!"))
        assertFalse(GitChanges.isTrackedChange("  "))
        assertTrue(GitChanges.isTrackedChange("AM"))
        assertTrue(GitChanges.isUntracked("??"))
    }

    @Test fun filterMatchesThePathAndLeavesABlankQueryAlone() {
        val files = listOf(
            GitFileStatus("src/Ws.ts", " M"),
            GitFileStatus("README.md", "??"),
        )
        assertEquals(files, GitChanges.filter(files, "  "))
        assertEquals(listOf("src/Ws.ts"), GitChanges.filter(files, "ws").map { it.path })
        assertTrue(GitChanges.filter(files, "nope").isEmpty())
    }
}
