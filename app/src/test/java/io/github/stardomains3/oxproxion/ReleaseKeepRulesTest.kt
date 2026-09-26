package io.github.stardomains3.oxproxion

import android.graphics.drawable.Drawable
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for the release-only crash when opening the attach popup, the
 * history drawer and every glass dialog: those backgrounds are
 * `<drawable class="...GlassDrawable">`, which the framework resolves by name
 * at runtime. R8 renamed the class, so inflation threw ClassNotFoundException.
 */
class ReleaseKeepRulesTest {

    private val resDir = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val rules = listOf(File("proguard-rules.pro"), File("app/proguard-rules.pro")).first { it.isFile }.readText()

    private fun xmlDrawableClasses(): Set<String> {
        val re = Regex("""<drawable[^>]*\bclass="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
        return resDir.walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("drawable") }
            .flatMap { f -> re.findAll(f.readText()).map { it.groupValues[1] } }
            .toSet()
    }

    @Test
    fun xmlInflatedDrawablesAreKeptByR8() {
        val classes = xmlDrawableClasses()
        assertTrue("expected glass drawables in XML", classes.isNotEmpty())
        val keep = Regex("""-keep public class io\.github\.stardomains3\.oxproxion\.\*\* extends android\.graphics\.drawable\.Drawable""")
        assertTrue("proguard-rules.pro lost the Drawable keep rule", keep.containsMatchIn(rules))
        for (name in classes) {
            assertTrue("$name is outside the kept package", name.startsWith("io.github.stardomains3.oxproxion."))
            val cls = Class.forName(name)
            assertTrue("$name must extend Drawable", Drawable::class.java.isAssignableFrom(cls))
            assertTrue("$name must be public", java.lang.reflect.Modifier.isPublic(cls.modifiers))
            cls.getConstructor() // public no-arg ctor, as Drawable.createFromXml needs
        }
    }
}
