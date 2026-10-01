package io.github.ottershelf.devicetest

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import io.github.ottershelf.feature.bookedit.CoverProviders
import io.github.ottershelf.feature.bookedit.CoverResult
import io.github.ottershelf.feature.bookedit.CoverSearchContent
import io.github.ottershelf.feature.bookedit.CoverSearchUi
import io.github.ottershelf.ui.components.belowStatusBar
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * The cover search's sheet after a hard fling to the end of a long list of covers: CoverSearchSheet's
 * ModalBottomSheet with the real content (90 covers), with and without `belowStatusBar`. The
 * sheet's offset is recorded every frame once the flings are over; with the fix it must stay put.
 * Without it is recorded for comparison only. Offsets go to `report.txt`.
 */
@RunWith(AndroidJUnit4::class)
class SheetDeviceTest {

    private class Run(val moved: Float, val reversals: Int)

    private val covers = CoverSearchUi(
        title = "A long series",
        author = "Some Author",
        provider = CoverProviders.ALL,
        audiobook = false,
        results = List(90) { CoverResult(url = "https://example.org/$it.jpg", preview = null, width = 600 + it, height = 900, source = "Test") },
    )

    @OptIn(ExperimentalMaterial3Api::class)
    private fun flingAndWatch(fixed: Boolean): Run {
        val offsets = Collections.synchronizedList(mutableListOf<Float>())
        LockScreenHost.launch().use { host ->
            host.setContent {
                OttershelfTheme {
                    Box(Modifier.fillMaxSize().background(OttershelfTheme.colors.background))
                    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    // CoverSearchSheet's call, with or without the fix.
                    ModalBottomSheet(
                        onDismissRequest = {},
                        modifier = if (fixed) Modifier.belowStatusBar() else Modifier,
                        sheetState = state,
                        containerColor = OttershelfTheme.colors.card,
                    ) {
                        LaunchedEffect(state) {
                            while (true) withFrameNanos { runCatching { offsets += state.requireOffset() } }
                        }
                        CoverSearchContent(covers)
                    }
                }
            }
            Device.waitUntil("the covers", 10_000) { Device.ui.findObject(By.text("A long series")) != null }
            SystemClock.sleep(1_500)
            val w = Device.ui.displayWidth
            val h = Device.ui.displayHeight
            repeat(3) {
                host.requireOnTop(allowDialog = true)
                // A hard flick up the grid (its lower part): about 45% of the screen in a few milliseconds.
                Device.ui.swipe(w / 2, (h * 0.85).toInt(), w / 2, (h * 0.4).toInt(), 4)
                SystemClock.sleep(250)
            }
            SystemClock.sleep(800)
            offsets.clear()
            SystemClock.sleep(1_500)
            Device.screenshot("sheet/${if (fixed) "with" else "without"}_fix")
        }
        val seen = synchronized(offsets) { offsets.toList() }
        val moved = (seen.maxOrNull() ?: 0f) - (seen.minOrNull() ?: 0f)
        var reversals = 0
        var last = 0
        for (i in 1 until seen.size) {
            val d = seen[i] - seen[i - 1]
            val sign = if (d > 0.5f) 1 else if (d < -0.5f) -1 else 0
            if (sign != 0 && last != 0 && sign != last) reversals++
            if (sign != 0) last = sign
        }
        Device.report("sheet ${if (fixed) "with" else "without"} belowStatusBar: ${seen.size} frames after the flings, moved ${"%.1f".format(moved)} px, $reversals reversals, offsets ${seen.take(16).joinToString { "%.0f".format(it) }}")
        return Run(moved, reversals)
    }

    @Test
    fun theCoverSheetStaysPutAfterAHardFlingToTheEnd() {
        val without = flingAndWatch(fixed = false)
        val with = flingAndWatch(fixed = true)
        Device.report("sheet: without the fix moved ${without.moved} px (${without.reversals} reversals); with it ${with.moved} px")
        assertTrue("the sheet kept moving after the fling (${with.moved} px, ${with.reversals} reversals)", with.moved < 2f && with.reversals == 0)
    }
}
