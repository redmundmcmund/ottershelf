package io.github.ottershelf.devicetest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * EAN-13 barcodes (with an optional EAN-5 add-on, the price on many paperbacks) drawn from the
 * standard's tables, as a book's back cover prints them, for the scanner tests.
 */
object Barcodes {

    private val L = arrayOf("0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011")
    private val G = arrayOf("0100111", "0110011", "0011011", "0100001", "0011101", "0111001", "0000101", "0010001", "0001001", "0010111")
    private val R = arrayOf("1110010", "1100110", "1101100", "1000010", "1011100", "1001110", "1010000", "1000100", "1001000", "1110100")

    /** The left half's L/G parities, by the first digit. */
    private val PARITY = arrayOf("LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL")

    /** The add-on's parities, by its checksum. */
    private val ADDON_PARITY = arrayOf("GGLLL", "GLGLL", "GLLGL", "GLLLG", "LGGLL", "LLGGL", "LLLGG", "LGLGL", "LGLLG", "LLGLG")

    /** 12 digits and their EAN-13 check digit. */
    fun withCheckDigit(twelve: String): String {
        require(twelve.length == 12 && twelve.all(Char::isDigit))
        val sum = twelve.withIndex().sumOf { (i, c) -> (c - '0') * if (i % 2 == 0) 1 else 3 }
        return twelve + ((10 - sum % 10) % 10)
    }

    /** The 95 modules of [code] (13 digits), and which of them are guard bars (drawn longer). */
    fun ean13Modules(code: String): Pair<String, BooleanArray> {
        require(code.length == 13 && code.all(Char::isDigit))
        val d = code.map { it - '0' }
        val parity = PARITY[d[0]]
        val sb = StringBuilder("101")
        for (i in 1..6) sb.append(if (parity[i - 1] == 'L') L[d[i]] else G[d[i]])
        sb.append("01010")
        for (i in 7..12) sb.append(R[d[i]])
        sb.append("101")
        val guard = BooleanArray(95) { it < 3 || it in 45..49 || it >= 92 }
        return sb.toString() to guard
    }

    /** The 47 modules of a 5-digit add-on. */
    fun addon5Modules(addon: String): String {
        require(addon.length == 5 && addon.all(Char::isDigit))
        val d = addon.map { it - '0' }
        val check = (3 * (d[0] + d[2] + d[4]) + 9 * (d[1] + d[3])) % 10
        val parity = ADDON_PARITY[check]
        val sb = StringBuilder("1011")
        for (i in 0 until 5) {
            if (i > 0) sb.append("01")
            sb.append(if (parity[i] == 'L') L[d[i]] else G[d[i]])
        }
        return sb.toString()
    }

    /**
     * [code] as a book prints it: bars [module] px wide and [height] tall, the digits below, quiet
     * zones of 11 modules, and an [addon] to the right (9 modules after the main code) if given.
     */
    fun draw(code: String, module: Int = 4, height: Int = 220, addon: String? = null, label: String? = null): Bitmap {
        val (bars, guard) = ean13Modules(code)
        val addonBars = addon?.let(::addon5Modules)
        val quiet = 11 * module
        val width = quiet + 95 * module + (addonBars?.let { 9 * module + it.length * module } ?: 0) + quiet
        val text = (module * 7.5f).coerceAtLeast(12f)
        val top = (label?.let { text * 1.6f } ?: 0f) + module * 4
        val bitmap = Bitmap.createBitmap(width, (top + height + text * 1.4f + module * 4).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val ink = Paint().apply { color = Color.BLACK; isAntiAlias = false }
        val font = Paint().apply { color = Color.BLACK; isAntiAlias = true; textSize = text; typeface = Typeface.MONOSPACE }
        label?.let { canvas.drawText(it, quiet.toFloat(), text * 1.2f, font) }
        val x0 = quiet.toFloat()
        for (i in bars.indices) {
            if (bars[i] != '1') continue
            val bottom = top + height + if (guard[i]) text * 0.6f else 0f
            canvas.drawRect(x0 + i * module, top, x0 + (i + 1) * module, bottom, ink)
        }
        // The human-readable digits: the first outside the bars, then two groups of six.
        canvas.drawText(code.substring(0, 1), x0 - module * 8, top + height + text * 1.2f, font)
        canvas.drawText(code.substring(1, 7), x0 + module * 5, top + height + text * 1.2f, font)
        canvas.drawText(code.substring(7), x0 + module * 51, top + height + text * 1.2f, font)
        if (addonBars != null) {
            val ax = x0 + 95 * module + 9 * module
            // The add-on's digits go above its (shorter) bars.
            canvas.drawText(addon, ax + module * 4, top + text, font)
            for (i in addonBars.indices) {
                if (addonBars[i] != '1') continue
                canvas.drawRect(ax + i * module, top + text * 1.3f, ax + (i + 1) * module, top + height + text * 0.6f, ink)
            }
        }
        return bitmap
    }

    /** [bitmap] drawn [degrees] turned, smaller by [scale], softly blurred, on a grey card with a little noise. */
    fun degrade(bitmap: Bitmap, degrees: Float, scale: Float, blurPasses: Int, canvasWidth: Int = 960, canvasHeight: Int = 720): Bitmap {
        val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        val out = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(196, 190, 178))
        canvas.save()
        canvas.rotate(degrees, canvasWidth / 2f, canvasHeight / 2f)
        canvas.drawBitmap(small, (canvasWidth - small.width) / 2f, (canvasHeight - small.height) / 2f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        canvas.restore()
        var result = out
        repeat(blurPasses) { result = boxBlur(result) }
        noise(result, 10)
        return result
    }

    private fun boxBlur(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val input = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val output = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val c = input[yy * w + xx]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    n++
                }
                output[y * w + x] = Color.rgb(r / n, g / n, b / n)
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(output, 0, w, 0, 0, w, h) }
    }

    private fun noise(bitmap: Bitmap, amount: Int) {
        val random = java.util.Random(7)
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        for (i in px.indices) {
            val c = px[i]
            val n = random.nextInt(amount * 2 + 1) - amount
            px[i] = Color.rgb(
                (((c shr 16) and 0xFF) + n).coerceIn(0, 255),
                (((c shr 8) and 0xFF) + n).coerceIn(0, 255),
                ((c and 0xFF) + n).coerceIn(0, 255),
            )
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** [a] and [b] side by side on one white card (a book's EAN beside a US price code). */
    fun sideBySide(a: Bitmap, b: Bitmap, gap: Int = 60): Bitmap {
        val out = Bitmap.createBitmap(a.width + gap + b.width, maxOf(a.height, b.height), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(a, 0f, 0f, null)
        canvas.drawBitmap(b, (a.width + gap).toFloat(), 0f, null)
        return out
    }
}
