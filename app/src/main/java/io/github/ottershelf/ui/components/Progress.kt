package io.github.ottershelf.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ottershelf.ui.theme.OttershelfTheme

/**
 * A rounded progress bar: a track and a fill whose end stays round (the web's `h-1.5 rounded-full
 * bg-muted` with a `bg-primary` fill; the Nexus `progress_bar_pill`). [progress] is 0..1.
 */
@Composable
fun PillProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    color: Color = OttershelfTheme.colors.primary,
    trackColor: Color = OttershelfTheme.colors.muted,
) {
    val value = progress.coerceIn(0f, 1f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f) },
    ) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(trackColor, cornerRadius = radius)
        if (value > 0f) {
            // At least a dot, so a started book shows something.
            val width = (size.width * value).coerceAtLeast(size.height)
            drawRoundRect(color, size = Size(width, size.height), cornerRadius = radius)
        }
    }
}

/**
 * Reading progress along a cover's bottom edge: 3dp, no track, primary at 70% (green once [read]),
 * as the web's cards and the Nexus grid draw it. Put it in a cover's overlay, aligned to the bottom.
 * Draws nothing at 0.
 */
@Composable
fun CoverProgressBar(progress: Float, modifier: Modifier = Modifier, read: Boolean = false) {
    val value = progress.coerceIn(0f, 1f)
    if (value <= 0f) return
    val color = if (read) OttershelfTheme.colors.coverProgressRead else OttershelfTheme.colors.coverProgress
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        drawRect(color, size = Size(size.width * value, size.height))
    }
}
