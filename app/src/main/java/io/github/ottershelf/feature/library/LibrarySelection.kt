package io.github.ottershelf.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme

/**
 * A book grid's selection (the toolbar's Select): taps pick books instead of opening them, and this
 * bar floats over the grid's bottom with how many are picked, Select all (the books loaded so far),
 * Add to collection, and Done (also Back).
 */
@Composable
internal fun SelectionBar(
    count: Int,
    onAddToCollection: () -> Unit,
    onSelectAll: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OttershelfTheme.colors
    Surface(
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth(),
        shape = RoundedCornerShape(OttershelfTheme.radii.lg),
        color = colors.card,
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) {
                LucideIcon("X", contentDescription = stringResource(R.string.library_select_done), tint = colors.foreground, size = 20.dp)
            }
            Text(
                pluralStringResource(R.plurals.library_selected, count, count),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.library_select_all), maxLines = 1) }
            Spacer(Modifier.width(4.dp))
            AccentButton(stringResource(R.string.collections_add_action), onClick = onAddToCollection, icon = "FolderPlus", enabled = count > 0)
        }
    }
}

/** A book's tick while selecting: filled in the accent when picked, an empty ring otherwise. */
@Composable
internal fun SelectMark(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val label = stringResource(if (selected) R.string.library_selected_book else R.string.library_not_selected_book)
    Box(
        modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary else Color.Black.copy(alpha = 0.35f))
            .border(2.dp, if (selected) colors.primary else Color.White, CircleShape)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) LucideIcon("Check", contentDescription = null, tint = colors.onPrimary, size = 16.dp)
    }
}
