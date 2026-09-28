package io.github.ottershelf.feature.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ottershelf.R
import io.github.ottershelf.ui.icons.AppIcons
import io.github.ottershelf.ui.theme.OttershelfTheme

/**
 * "Scan a book" beside the Currently Reading card's title, where the user picks the book to time (each
 * row's timer button): opens the scanner for the reading timer (Route.Scan(forTimer = true)), for
 * a paper book not on the list yet.
 */
@Composable
fun ScanForTimerAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(OttershelfTheme.radii.md))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.scan_for_timer_description), onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.ScanBarcode, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(stringResource(R.string.scan_for_timer), style = MaterialTheme.typography.labelLarge, color = colors.primary)
    }
}
