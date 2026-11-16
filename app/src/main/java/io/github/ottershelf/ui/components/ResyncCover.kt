package io.github.ottershelf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ottershelf.R
import io.github.ottershelf.ui.theme.OttershelfTheme

/**
 * Over a reader's pages after it comes back on screen, until the server has said where the book is
 * now and the reader has landed there (`ReaderViewModel.onResume`, `PageReaderProgress.onResume`):
 * it says so, and swallows every touch, so the page left before the pause can't be turned,
 * scrolled or zoomed. (Volume-key turns are refused by the view models.)
 */
@Composable
fun ResyncCover(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    val colors = OttershelfTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .background(colors.card, RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LoadingState()
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.reader_resync_checking), style = MaterialTheme.typography.bodyMedium, color = colors.foreground)
        }
    }
}
