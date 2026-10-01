package io.github.ottershelf.devicetest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Covers that aren't 2:3 ([io.github.ottershelf.ui.components.FittedCoverImage], the web's
 * "blurred fit"): a tall, narrow cover (a mass-market paperback, 3:5), a square one (an audiobook)
 * and a 2:3 one, each with a red band along its top and a blue one along its bottom, shown with
 * [BookCover] in the library's 2:3 boxes. Nothing may be cropped: both bands of every cover show
 * in its box. The screen is saved to `device-tests/covers/fit.png`.
 */
@RunWith(AndroidJUnit4::class)
class CoverFitDeviceTest {

    private fun cover(name: String, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.rgb(200, 190, 160))
        val band = height / 20
        canvas.drawRect(Rect(0, 0, width, band), Paint().apply { color = AndroidColor.RED })
        canvas.drawRect(Rect(0, height - band, width, height), Paint().apply { color = AndroidColor.BLUE })
        return File(Device.fixtureDir("covers-$name"), "$name.png").apply {
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun narrowSquareAndTwoByThreeCoversShowWhole() {
        val covers = linkedMapOf(
            "tall" to cover("tall", 600, 1000),
            "square" to cover("square", 800, 800),
            "classic" to cover("classic", 400, 600),
        )
        val bounds = ConcurrentHashMap<String, androidx.compose.ui.geometry.Rect>()
        LockScreenHost.launch().use { host ->
            host.setContent {
                OttershelfTheme {
                    Box(Modifier.fillMaxSize().background(OttershelfTheme.colors.background), contentAlignment = Alignment.Center) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for ((name, file) in covers) {
                                BookCover(
                                    model = file,
                                    title = name,
                                    modifier = Modifier.width(110.dp).onGloballyPositioned { bounds[name] = it.boundsInWindow() },
                                )
                            }
                        }
                    }
                }
            }
            Device.waitUntil("the covers laid out", 10_000) { bounds.size == covers.size }
            SystemClock.sleep(2_000)
            val shot = Device.screenshot("covers/fit")
            for ((name, b) in bounds) {
                val x = b.center.x.roundToInt()
                // Search the cover's column for each band (the fitted image may sit anywhere in the box).
                val column = (b.top.roundToInt() until b.bottom.roundToInt()).map { y -> shot.getPixel(x, y) }
                val red = column.indexOfFirst { AndroidColor.red(it) > 200 && AndroidColor.green(it) < 60 && AndroidColor.blue(it) < 60 }
                val blue = column.indexOfLast { AndroidColor.blue(it) > 200 && AndroidColor.red(it) < 60 && AndroidColor.green(it) < 60 }
                Device.report("cover $name: box ${b.width.roundToInt()}x${b.height.roundToInt()} px, red band from $red, blue band to $blue of ${column.size}")
                assertTrue("$name: its top (red) band was cropped", red >= 0)
                assertTrue("$name: its bottom (blue) band was cropped", blue >= 0)
            }
        }
    }
}
