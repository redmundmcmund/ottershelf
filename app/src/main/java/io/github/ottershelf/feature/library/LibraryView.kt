package io.github.ottershelf.feature.library

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How a book grid shows its books: covers in a grid or rows in a list, and how big the covers are.
 * Remembered on the device per list, as the sort is ([LibraryViewPrefs], the lists of
 * [ListKind.prefsKey]; Downloaded has its own). Pure, so the rules are unit-tested (`LibraryViewTest`).
 *
 * @property cellDp the width of a grid cell the user chose (by pinching, or the view menu's stepper), in
 *   dp; null until the user changes it, which is the old fixed grid ([GridColumns.DEFAULT_CELL_DP]). A
 *   width rather than a column count, so the covers keep their size when the phone turns: three
 *   columns in portrait are six or seven in landscape.
 */
data class ListView(val mode: ViewMode = ViewMode.GRID, val cellDp: Float? = null) {
    val isList: Boolean get() = mode == ViewMode.LIST

    fun stored() = StoredListView(mode.id, cellDp)

    companion object {
        fun restore(stored: StoredListView?): ListView {
            if (stored == null) return ListView()
            val cell = stored.cellDp?.takeIf { it.isFinite() && it > 0f }
            return ListView(ViewMode.of(stored.mode) ?: ViewMode.GRID, cell)
        }
    }
}

enum class ViewMode(val id: String) {
    GRID("grid"),
    LIST("list"),
    ;

    companion object {
        fun of(id: String?): ViewMode? = entries.firstOrNull { it.id == id }
    }
}

@Serializable
data class StoredListView(val mode: String = ViewMode.GRID.id, val cellDp: Float? = null)

/**
 * The grid's column maths. Widths are the grid's inner width in dp (inside its padding), which the
 * cells share equally.
 */
object GridColumns {
    /** The old fixed grid: at least 120dp a cell (three columns on the phone, about seven in landscape). */
    const val DEFAULT_CELL_DP = 120f

    /** The smallest and largest cells: two to six columns across the phone in portrait. */
    const val MIN_CELL_DP = 64f
    const val MAX_CELL_DP = 200f

    /** A row of the list view is at least this wide: one column on the phone, two in landscape. */
    const val LIST_MIN_ROW_DP = 340f

    private const val EPSILON = 1e-3f

    /** The column counts on offer at [widthDp]: cells between [MIN_CELL_DP] and [MAX_CELL_DP] wide. */
    fun range(widthDp: Float): IntRange {
        if (widthDp <= 0f) return 1..1
        val least = max(1, ceil(widthDp / MAX_CELL_DP - EPSILON).toInt())
        val most = max(least, floor(widthDp / MIN_CELL_DP + EPSILON).toInt())
        return least..most
    }

    /** The columns at [widthDp] for a chosen [cellDp] (null: the old grid, as many 120dp cells as fit). */
    fun count(widthDp: Float, cellDp: Float?): Int {
        if (widthDp <= 0f) return 1
        val raw = if (cellDp == null || cellDp <= 0f) floor(widthDp / DEFAULT_CELL_DP + EPSILON).toInt() else (widthDp / cellDp).roundToInt()
        return raw.coerceIn(range(widthDp))
    }

    /** What to remember for [columns] at [widthDp]. */
    fun cellFor(widthDp: Float, columns: Int): Float = widthDp / columns.coerceAtLeast(1)

    /** One more ([delta] 1) or one fewer (-1) column at [widthDp], within [range]. */
    fun step(widthDp: Float, current: Int, delta: Int): Int = (current + delta).coerceIn(range(widthDp))

    /** The list view's columns of rows at [widthDp]. */
    fun listColumns(widthDp: Float): Int = max(1, floor(widthDp / LIST_MIN_ROW_DP + EPSILON).toInt())
}

/**
 * A pinch on the grid, turned into column steps. Spreading the fingers makes the covers bigger (one
 * column fewer), pinching them together smaller (one more). [zoom] is how far the fingers have
 * gone since the grid last changed, and is also how much bigger the grid is drawn meanwhile, so the
 * covers follow the fingers smoothly between steps.
 *
 * A step is taken a little before the covers have grown (or shrunk) to the next column count's
 * size ([stepAt]); the zoom left over is then measured against the new layout, so the covers keep
 * the size they were drawn at and, being in between two steps, don't step straight back. At the
 * ends of the range the zoom only gives a little ([EDGE_GIVE]).
 */
class PinchSteps {
    var zoom: Float = 1f
        private set

    /** The zoom at the start, or at the last step: a lift steps only toward where the fingers went since. */
    private var base: Float = 1f

    /**
     * A pinch begins with the grid drawn [drawn] times its size: 1, or where the last pinch's spring
     * back has got to, so a quick second pinch carries on from the size on screen.
     */
    fun start(drawn: Float = 1f) {
        zoom = if (drawn.isFinite() && drawn > 0f) drawn else 1f
        base = zoom
    }

    /**
     * The fingers moved apart by [factor] (above 1) or together (below 1) with the grid at
     * [columns] of [range]: the step to take now (-1: one column fewer, 1: one more, 0: none).
     */
    fun onZoom(factor: Float, columns: Int, range: IntRange): Int {
        if (!factor.isFinite() || factor <= 0f) return 0
        val before = zoom
        zoom *= factor
        val fewer = columns - 1 >= range.first
        val more = columns + 1 <= range.last
        return when {
            fewer && zoom >= stepAt(columns, columns - 1) -> {
                zoom /= ratio(columns, columns - 1)
                base = zoom
                -1
            }
            more && zoom <= stepAt(columns, columns + 1) -> {
                zoom /= ratio(columns, columns + 1)
                base = zoom
                1
            }
            else -> {
                // At an end of the range the zoom gives only a little; a pinch that began further out
                // (the spring back from a step) isn't pulled in at once, just not let further out.
                val low = if (more) 0f else 1f / EDGE_GIVE
                val high = if (fewer) Float.MAX_VALUE else EDGE_GIVE
                zoom = when {
                    zoom > high -> max(high, min(zoom, before))
                    zoom < low -> min(low, max(zoom, before))
                    else -> zoom
                }
                0
            }
        }
    }

    /** The fingers lifted: past half way (in scale) to a step, and moved toward it, it is taken. */
    fun onEnd(columns: Int, range: IntRange): Int {
        val z = zoom
        zoom = 1f
        if (columns - 1 >= range.first && z > base && ln(z) >= ln(stepAt(columns, columns - 1)) / 2f) return -1
        if (columns + 1 <= range.last && z < base && ln(z) <= ln(stepAt(columns, columns + 1)) / 2f) return 1
        return 0
    }

    companion object {
        /** How far the grid stretches past the first or last column count. */
        const val EDGE_GIVE = 1.06f

        /** A step is taken at this power of the change in cover size (three quarters of the way, in scale). */
        private const val STEP_POWER = 0.75f

        /** How much bigger each cover is at [to] columns than at [from]. */
        fun ratio(from: Int, to: Int): Float = from.toFloat() / to

        /** The zoom at which [from] columns become [to]. */
        fun stepAt(from: Int, to: Int): Float = ratio(from, to).pow(STEP_POWER)
    }
}

/**
 * Keeping the book under the user's fingers where it is when the grid changes column count: its new top
 * (in the grid's own pixels) so the same point of it ([fraction] of its height down) stays at
 * [focusY], given its new height.
 */
fun anchoredTop(focusY: Float, fraction: Float, newHeight: Float): Float = focusY - fraction.coerceIn(0f, 1f) * newHeight

/**
 * A grid cell's height at a new width: the cover (2:3, [padding] each side of it) grows with the
 * cell, the lines under it don't. [oldHeight] and [oldCell] are the cell as it is laid out now.
 */
fun cellHeightAt(newCell: Float, oldCell: Float, oldHeight: Float, padding: Float): Float {
    val text = oldHeight - (oldCell - 2 * padding) * 1.5f
    return (newCell - 2 * padding) * 1.5f + text
}
