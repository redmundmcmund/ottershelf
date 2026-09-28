package io.github.ottershelf.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ottershelf.R
import io.github.ottershelf.ui.icons.AppIcons

/**
 * Stand-in body for screens not built yet: [title], an optional [detail] line, and [extra]
 * content (buttons that exercise the plumbing). Delete its use when the real screen lands.
 */
@Composable
fun PlaceholderContent(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    contentPadding: PaddingValues = PaddingValues(),
    extra: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.Orbit, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
        Text(
            stringResource(R.string.common_placeholder),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        extra()
    }
}
