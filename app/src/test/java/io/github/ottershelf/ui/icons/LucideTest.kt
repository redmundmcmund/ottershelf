package io.github.ottershelf.ui.icons

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.addPathNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** The Lucide catalogue: every icon in assets/lucide.txt parses and builds, as Lucide draws it. */
class LucideTest {

    companion object {
        /** Unit tests run in the module directory (app/). */
        private val FILE = File("src/main/assets/lucide.txt")

        @BeforeClass
        @JvmStatic
        fun load() = Lucide.load { FILE.inputStream() }
    }

    @Test
    fun everyIconParsesAndBuilds() {
        val lines = FILE.readLines().filter { it.isNotBlank() }
        assertEquals(1838, lines.size)
        val failed = lines.map { it.substringBefore('\t') }.filter { name ->
            runCatching { Lucide.get(name) }.getOrNull() == null
        }
        assertEquals("icons that didn't build: $failed", emptyList<String>(), failed)
        assertTrue(Lucide.ready.value)
        assertEquals(1838, Lucide.names().size)
    }

    @Test
    fun iconsAreStrokedLikeLucide() {
        val book = Lucide.get("BookCheck")!!
        assertEquals(24f, book.viewportWidth)
        val path = book.root.first() as VectorPath
        assertEquals(2f, path.strokeLineWidth)
        assertNull(path.fill)
        // A filled dot is filled and stroked.
        val scatter = Lucide.get("ChartScatter")!!
        val dots = scatter.root.toList().map { it as VectorPath }.last()
        assertNotNull(dots.fill)
        assertNotNull(dots.stroke)
    }

    @Test
    fun implicitLinesAfterAMoveAreKept() {
        // lucide.txt writes "M 5 3 2 6": a move, then a line (SVG's implicit lineto).
        val nodes = addPathNodes("M 5 3 2 6")
        assertEquals(listOf(PathNode.MoveTo(5f, 3f), PathNode.LineTo(2f, 6f)), nodes)
        val relative = addPathNodes("m 5 3 2 6")
        assertEquals(listOf(PathNode.RelativeMoveTo(5f, 3f), PathNode.RelativeLineTo(2f, 6f)), relative)
    }

    @Test
    fun builtInCopiesMatchTheCatalogue() {
        val catalogue = FILE.readLines().associate { it.substringBefore('\t') to it.substringAfter('\t') }
        LUCIDE_BUILT_IN.forEach { (name, data) -> assertEquals(name, catalogue[name], data) }
    }

    @Test
    fun renamedIconsAndUnknownNames() {
        assertNotNull(Lucide.get("BookMarked")) // the web's name for BookBookmark (want to read)
        assertNull(Lucide.get("NoSuchIcon"))
        assertNull(Lucide.get(""))
        assertNotNull(Lucide.get(Lucide.FALLBACK))
    }
}
