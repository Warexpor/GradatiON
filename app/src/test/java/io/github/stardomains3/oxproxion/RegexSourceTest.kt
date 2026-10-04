package io.github.stardomains3.oxproxion

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's ICU regex engine rejects a bare `}` that the desktop JVM accepts, so a pattern like
 * `\{\{user}}` passes every unit test and crashes on the phone. Every `}` in a regex literal
 * must be escaped, close a `{n,m}` quantifier, or close a `\p{...}` class.
 */
class RegexSourceTest {
    private val literal = Regex("""Regex\(\s*"(?:""(.*?)""|((?:[^"\\]|\\.)*))"""", RegexOption.DOT_MATCHES_ALL)
    private val quantifier = Regex("""\{\d+(,\d*)?\}""")
    private val property = Regex("""\\[pP]\{\w+\}""")

    @Test fun noBareClosingBraces() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val bad = root.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            literal.findAll(f.readText()).mapNotNull { m ->
                // A plain string spells the regex `\p{L}` as `\\p{L}`; read it as the regex text.
                val pattern = m.groupValues[1].ifEmpty { m.groupValues[2].replace("\\\\", "\\") }
                val stripped = quantifier.replace(property.replace(pattern.replace("\\\\", ""), ""), "")
                if (Regex("""(?<!\\)\}""").containsMatchIn(stripped)) "${f.name}: $pattern" else null
            }
        }.toList()
        assertTrue("unescaped } in:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
