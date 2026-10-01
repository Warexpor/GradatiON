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

    @Test fun changePathUnquotesAndTakesTheNewNameOfARename() {
        val plain = GitChanges.changePath(GitFileStatus("src/A.kt", " M"))
        assertEquals("src/A.kt", plain.diffPath)
        assertEquals(null, plain.renamedFrom)

        val spaced = GitChanges.changePath(GitFileStatus("\"src/My File.kt\"", " M"))
        assertEquals("src/My File.kt", spaced.diffPath)

        val renamed = GitChanges.changePath(GitFileStatus("\"old name.kt\" -> \"new name.kt\"", "R "))
        assertEquals("new name.kt", renamed.diffPath)
        assertEquals("old name.kt", renamed.renamedFrom)

        // The arrow is inside the quotes, so this is one path, not a rename.
        val literal = GitChanges.changePath(GitFileStatus("\"a -> b.txt\"", "??"))
        assertEquals("a -> b.txt", literal.diffPath)
        assertEquals(null, literal.renamedFrom)

        val utf8 = GitChanges.changePath(GitFileStatus("\"\\344\\270\\255.kt\"", " M"))
        assertEquals("中.kt", utf8.diffPath)
    }

    @Test fun filterMatchesTheOldNameOfARename() {
        val files = listOf(GitFileStatus("\"old name.kt\" -> \"new name.kt\"", "R "))
        assertEquals(1, GitChanges.filter(files, "old name").size)
        assertEquals(1, GitChanges.filter(files, "new name").size)
        assertTrue(GitChanges.filter(files, "missing").isEmpty())
    }
}
