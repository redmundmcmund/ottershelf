package io.github.ottershelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The hand-written baseline profile (src/main/baseline-prof.txt; ARCHITECTURE.md, "Release build"):
 * rules for the cold-start path, compiled ahead of time in the release and dev builds. AGP drops a
 * rule that matches nothing without a word, so a renamed class or moved package would quietly stop
 * being compiled: every rule must still name something, and the profile must stay on the start path
 * (a wildcard over the whole app would compile all of its roughly 1.5 MB of bytecode, not the start
 * path's 450 KB, and grow the install).
 */
class BaselineProfileTest {

    private val rules = FILE.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /**
     * `L<class>;` or `HSPL<class>;->**(**)**` (flags HSP, then the class descriptor), where the class
     * may end in `**` (a class and its nested classes, or a whole package).
     */
    private val rule = Regex("""^(HSP)?L(io/github/ottershelf/[A-Za-z0-9_/$]*(?:\*\*)?);(->\*\*\(\*\*\)\*\*)?$""")

    private fun target(line: String): String = rule.matchEntire(line)?.groupValues?.get(2) ?: error("not a rule this profile uses: $line")

    @Test
    fun everyLineIsAClassOrAWholeClassMethodRule() {
        assertTrue("no rules in $FILE", rules.isNotEmpty())
        for (line in rules) {
            val match = rule.matchEntire(line)
            assertTrue("not a rule this profile uses: $line", match != null)
            assertEquals("the HSP flags go on a method rule (->**(**)**) and only there: $line", match!!.groupValues[1].isEmpty(), match.groupValues[3].isEmpty())
        }
    }

    @Test
    fun everyRuleNamesAClassOrPackageThatExists() {
        val loader = javaClass.classLoader
        for (target in rules.map(::target).distinct()) {
            if (target.endsWith("/**")) {
                val dir = File(SOURCES, target.removeSuffix("/**"))
                assertTrue("no package $dir for $target", dir.walkTopDown().any { it.extension == "kt" })
            } else {
                val name = target.removeSuffix("**").replace('/', '.')
                val found = runCatching { Class.forName(name, false, loader) }.isSuccess
                assertTrue("no class $name for $target", found)
            }
        }
    }

    @Test
    fun everyClassWithMethodRulesIsAlsoLoadedAtStart() {
        val (methods, classes) = rules.partition { it.startsWith("HSPL") }
        assertEquals(methods.map(::target).toSet(), classes.map(::target).toSet())
    }

    @Test
    fun theProfileStaysOnTheStartPath() {
        for (target in rules.map(::target)) {
            assertFalse("a wildcard over the whole app: $target", target.removePrefix("io/github/ottershelf").trim('/', '*').isEmpty())
            assertFalse("a wildcard over every feature: $target", target.trimEnd('/', '*') == "io/github/ottershelf/feature")
        }
    }

    private companion object {
        val FILE = File("src/main/baseline-prof.txt")
        val SOURCES = File("src/main/java")
    }
}
