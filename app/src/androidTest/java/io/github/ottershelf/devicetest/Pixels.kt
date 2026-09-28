package io.github.ottershelf.devicetest

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs

/** What a screenshot shows in a rectangle: the page colour and how much of it is ink (text, rules). */
object Pixels {

    data class Page(val background: Int, val ink: Double, val samples: Int) {
        fun hex(): String = String.format("#%06x", background and 0xFFFFFF)
    }

    /**
     * The most common colour in [rect] of [shot] (sampled every [step] px, quantised a little) is the
     * page; ink is the share of samples far from it. A blank page has next to none.
     */
    fun page(shot: Bitmap, rect: Rect, step: Int = 3): Page {
        val r = Rect(rect).apply { intersect(0, 0, shot.width, shot.height) }
        val counts = HashMap<Int, Int>()
        val samples = ArrayList<Int>()
        // A software copy: a hardware screenshot can't be read pixel by pixel.
        val bitmap = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false) else shot
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                val c = bitmap.getPixel(x, y)
                samples += c
                val key = (c shr 3) and 0x1F1F1F
                counts[key] = (counts[key] ?: 0) + 1
                x += step
            }
            y += step
        }
        val modeKey = counts.maxByOrNull { it.value }?.key ?: 0
        val background = samples.first { ((it shr 3) and 0x1F1F1F) == modeKey }
        val ink = samples.count { distance(it, background) > 60 }
        return Page(background, if (samples.isEmpty()) 0.0 else ink.toDouble() / samples.size, samples.size)
    }

    /** Sum of the channel differences (0..765). */
    fun distance(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) + abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))

    fun rgb(hex: String): Int = 0xFF000000.toInt() or hex.removePrefix("#").toInt(16)
}
