package io.github.ottershelf.feature.bookedit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.COVER_ASPECT
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.io.File

@Composable
internal fun DiscardDialog(onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        containerColor = OttershelfTheme.colors.popover,
        title = { Text(stringResource(R.string.bookedit_discard_title)) },
        text = { Text(stringResource(R.string.bookedit_discard_message)) },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.bookedit_discard)) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.bookedit_keep_editing)) } },
    )
}

@Composable
internal fun UnlockDialog(group: LockGroup, onUnlock: () -> Unit, onCancel: () -> Unit) {
    val title = when (group) {
        LockGroup.TITLE -> R.string.bookedit_unlock_title_title
        LockGroup.AUTHORS -> R.string.bookedit_unlock_authors_title
        LockGroup.SERIES -> R.string.bookedit_unlock_series_title
        LockGroup.COVER -> R.string.bookedit_unlock_cover_title
    }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = OttershelfTheme.colors.popover,
        icon = { LucideIcon("LockOpen", contentDescription = null, tint = OttershelfTheme.colors.primary, size = 24.dp) },
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(R.string.bookedit_unlock_message)) },
        confirmButton = { TextButton(onClick = onUnlock) { Text(stringResource(R.string.bookedit_unlock)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.bookedit_cancel)) } },
    )
}

/** "Use this cover?" for a photo or a found cover; "Use the file's cover?" before removing the user's. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PendingCoverDialog(
    pending: PendingCover,
    busy: Boolean,
    failed: EditError?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        properties = DialogProperties(dismissOnClickOutside = !busy, dismissOnBackPress = !busy),
    ) {
        PendingCoverContent(pending, busy, failed, onConfirm, onCancel)
    }
}

@Composable
internal fun PendingCoverContent(
    pending: PendingCover,
    busy: Boolean,
    failed: EditError?,
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
    /** What to show for [pending] (a test's fake cover); by default its photo or preview. */
    preview: Any? = when (pending) {
        is PendingCover.Photo -> File(pending.path)
        is PendingCover.Online -> pending.result.preview
        PendingCover.FromFileCover -> null
    },
) {
    val colors = OttershelfTheme.colors
    Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.popover, contentColor = colors.foreground) {
        Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
            val fromFile = pending == PendingCover.FromFileCover
            Text(
                stringResource(if (fromFile) R.string.bookedit_restore_title else R.string.bookedit_use_cover_title),
                modifier = Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (fromFile) {
                Text(
                    stringResource(R.string.bookedit_restore_message),
                    modifier = Modifier.padding(horizontal = 24.dp).padding(top = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.mutedForeground,
                )
            } else {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CoverPreview(preview, Modifier.width(168.dp))
                    val size = when (pending) {
                        is PendingCover.Photo -> stringResource(R.string.bookedit_cover_size, pending.width, pending.height)
                        is PendingCover.Online -> {
                            val px = stringResource(R.string.bookedit_cover_size, pending.result.width, pending.result.height)
                            if (pending.result.source.isBlank()) px else stringResource(R.string.bookedit_cover_source, px, pending.result.source)
                        }
                        PendingCover.FromFileCover -> null
                    }
                    size?.let {
                        Text(it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
                    }
                }
            }
            failed?.let {
                Row(Modifier.padding(horizontal = 24.dp).padding(top = 14.dp), verticalAlignment = Alignment.Top) {
                    LucideIcon("TriangleAlert", contentDescription = null, tint = colors.destructive, size = 16.dp, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bookedit_cover_failed, errorText(it)), style = MaterialTheme.typography.bodyMedium, color = colors.destructive)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    Spinner(stringResource(R.string.bookedit_changing_cover), size = 20.dp)
                    Spacer(Modifier.width(12.dp))
                }
                TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.bookedit_cancel)) }
                TextButton(onClick = onConfirm, enabled = !busy) {
                    Text(
                        stringResource(
                            when {
                                failed != null -> R.string.bookedit_try_again
                                fromFile -> R.string.bookedit_file_cover
                                else -> R.string.bookedit_use_cover
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** A chosen cover in the 2:3 box, whole (not cropped), on the cover surface. */
@Composable
internal fun CoverPreview(model: Any?, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.md)
    Box(modifier.aspectRatio(COVER_ASPECT).clip(shape).background(colors.coverSurface), contentAlignment = Alignment.Center) {
        var failed by remember(model) { mutableStateOf(false) }
        if (model == null || failed) {
            LucideIcon("ImageOff", contentDescription = null, tint = colors.mutedForeground, size = 28.dp)
        } else {
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit,
                onError = { failed = true },
            )
        }
    }
}

@Composable
internal fun CameraRationaleDialog(onContinue: () -> Unit, onChoosePhoto: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OttershelfTheme.colors.popover,
        icon = { LucideIcon("Camera", contentDescription = null, tint = OttershelfTheme.colors.primary, size = 24.dp) },
        title = { Text(stringResource(R.string.bookedit_camera_title)) },
        text = { Text(stringResource(R.string.bookedit_camera_rationale)) },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.bookedit_camera_continue)) } },
        dismissButton = { TextButton(onClick = onChoosePhoto) { Text(stringResource(R.string.bookedit_choose_photo)) } },
    )
}

@Composable
internal fun CameraDeniedDialog(onChoosePhoto: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OttershelfTheme.colors.popover,
        title = { Text(stringResource(R.string.bookedit_camera_denied_title)) },
        text = { Text(stringResource(R.string.bookedit_camera_denied)) },
        confirmButton = { TextButton(onClick = onChoosePhoto) { Text(stringResource(R.string.bookedit_choose_photo)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookedit_cancel)) }
                TextButton(onClick = onSettings) { Text(stringResource(R.string.bookedit_open_settings)) }
            }
        },
    )
}
