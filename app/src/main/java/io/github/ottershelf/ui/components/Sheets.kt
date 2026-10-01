package io.github.ottershelf.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Every ModalBottomSheet's `modifier`: keeps the sheet below the status bar. Without it, a sheet
 * tall enough to reach the status bar pads its content by however much of the bar it covers, so
 * its height follows its own offset. A hard fling that ends in the sheet (a long list scrolled to
 * its end) springs the sheet past its anchor, which changes that padding, the height and so the
 * anchor again: the sheet shook up and down until the next touch. Padded outside the sheet, the
 * top inset is consumed before the sheet's content sees it, and the height no longer moves.
 */
@Composable
fun Modifier.belowStatusBar(): Modifier = windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
