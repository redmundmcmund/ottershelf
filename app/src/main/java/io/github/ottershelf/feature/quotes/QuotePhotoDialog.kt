package io.github.ottershelf.feature.quotes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.github.ottershelf.R
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.io.File

/**
 * A kept quote photo (`QuotePhotos`), full screen on the page colour: tap anywhere, the close
 * button or Back closes it. Opened from a quote's card on the Highlights screen and in Notes.
 * [onRemove] (asked first) deletes the photo from the phone; the quote stays.
 */
@Composable
fun QuotePhotoDialog(file: File, onDismiss: () -> Unit, onRemove: (() -> Unit)? = null) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val colors = OttershelfTheme.colors
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.background)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .safeDrawingPadding(),
        ) {
            AsyncImage(
                model = file,
                contentDescription = stringResource(R.string.quotes_photo),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRemove != null) {
                    CircleButton("Trash", stringResource(R.string.quotes_photo_remove)) { confirming = true }
                }
                CircleButton("X", stringResource(R.string.quotes_close), onDismiss)
            }
        }
        if (confirming && onRemove != null) {
            AlertDialog(
                onDismissRequest = { confirming = false },
                title = { Text(stringResource(R.string.quotes_photo_remove_title)) },
                text = { Text(stringResource(R.string.quotes_photo_remove_text)) },
                confirmButton = {
                    TextButton(onClick = { confirming = false; onRemove() }) { Text(stringResource(R.string.quotes_photo_remove)) }
                },
                dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.quotes_cancel)) } },
                containerColor = colors.popover,
            )
        }
    }
}

@Composable
private fun CircleButton(icon: String, label: String, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.card.copy(alpha = 0.85f)),
    ) {
        LucideIcon(icon, contentDescription = label, tint = colors.foreground, size = 20.dp)
    }
}
