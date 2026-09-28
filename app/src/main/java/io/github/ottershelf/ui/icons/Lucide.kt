package io.github.ottershelf.ui.icons

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * BookOrbit's icons: the whole Lucide set (lucide-static 1.46.0, ISC licence, 1838 icons), so the
 * icon a library, smart scope or collection was given in the web app (stored as its PascalCase
 * name, e.g. `BookHeart`) shows here too.
 *
 * `assets/lucide.txt` holds one icon per line, `Name<TAB>stroked path data`, plus
 * `<TAB>filled path data` for the few with solid dots (made by the Nexus app's
 * tools/gen-lucide.ps1). The file is read once, off the main thread, by [preload] at app start;
 * each icon becomes a stroked [ImageVector] (24-unit box, 2-unit round-capped strokes, as Lucide
 * draws) the first time it's asked for, and is kept.
 *
 * The icons the app's own screens use are built in ([LUCIDE_BUILT_IN]), so they never wait.
 */
object Lucide {
    /** What [LucideIcon] shows for a name that isn't a Lucide icon (the web shows nothing). */
    const val FALLBACK = "CircleDashed"

    /**
     * Names the web's lucide package also accepts (renamed icons) but lucide.txt knows only by their
     * current name.
     */
    private val ALIASES = mapOf(
        "BookMarked" to "BookBookmark",
    )

    @Volatile private var raw: Map<String, String>? = null
    private val vectors = ConcurrentHashMap<String, ImageVector>()
    private val _ready = MutableStateFlow(false)

    /** True once the full set is read; icons outside the built-in ones resolve only after that. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * Reads the icon list (off the main thread); cheap to call again. AppContainer calls it when the
     * first activity is created.
     */
    suspend fun preload(context: Context) {
        if (raw != null) return
        withContext(Dispatchers.IO) {
            load { context.applicationContext.assets.open("lucide.txt") }
        }
    }

    /**
     * Builds [names]' vectors ahead of drawing them, off the main thread (e.g. a ViewModel with the
     * libraries' icons). Waits for nothing: names not yet readable are skipped.
     */
    suspend fun prefetch(names: Collection<String?>) = withContext(Dispatchers.Default) {
        names.forEach { if (it != null) get(it) }
    }

    /** Reads lucide.txt from [open] (tests pass the file directly). */
    internal fun load(open: () -> InputStream) {
        if (raw != null) return
        synchronized(this) {
            if (raw != null) return
            val map = HashMap<String, String>(2048)
            open().bufferedReader().useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab > 0) map[line.substring(0, tab)] = line.substring(tab + 1)
                }
            }
            raw = map
            _ready.value = true
        }
    }

    /**
     * The icon named [name] (PascalCase, as the server stores it), or null if there is no such icon
     * (or the set isn't read yet and it isn't built in). Never blocks on the file.
     */
    fun get(name: String?): ImageVector? {
        if (name.isNullOrBlank()) return null
        vectors[name]?.let { return it }
        val key = ALIASES[name] ?: name
        val data = LUCIDE_BUILT_IN[key] ?: raw?.get(key) ?: return null
        val vector = runCatching { build(key, data) }.getOrNull() ?: return null
        vectors[name] = vector
        return vector
    }

    /** Whether [name] is an icon (only definite once [ready]). */
    fun exists(name: String?): Boolean = get(name) != null

    /** All icon names, for an icon picker (empty until [ready]). */
    fun names(): List<String> = raw?.keys?.sorted().orEmpty()

    /**
     * An ImageVector drawn the way Lucide draws: stroked paths, plus filled paths that are stroked
     * too (Lucide's solid dots take the icon's stroke width as well).
     */
    internal fun build(name: String, data: String, autoMirror: Boolean = false): ImageVector {
        val parts = data.split('\t')
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        )
        parts[0].takeIf { it.isNotBlank() }?.let { stroke ->
            builder.addPath(
                pathData = addPathNodes(stroke),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { filled ->
            builder.addPath(
                pathData = addPathNodes(filled),
                fill = SolidColor(Color.Black),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}

/**
 * A Lucide icon by name, tinted like any icon. An unknown name shows [fallback] (another Lucide
 * name, or nothing when null); while the full set is still being read, a name that isn't built in
 * leaves an empty space of the same size rather than flashing the fallback. Pass
 * `size = Dp.Unspecified` to size it with [modifier] instead.
 */
@Composable
fun LucideIcon(
    name: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 20.dp,
    fallback: String? = Lucide.FALLBACK,
) {
    val ready by Lucide.ready.collectAsState()
    val vector = remember(name, fallback, ready) {
        Lucide.get(name) ?: if (ready || name.isNullOrBlank()) Lucide.get(fallback) else null
    }
    val sized = if (size.isSpecified) modifier.size(size) else modifier
    if (vector != null) {
        Icon(vector, contentDescription, sized, tint)
    } else {
        Spacer(sized)
    }
}
