package io.github.ottershelf.feature.bookedit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.FittedCoverImage
import io.github.ottershelf.ui.components.COVER_ASPECT
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.SkeletonBox
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.components.belowStatusBar

/** The online cover search (the web's CoverSearchDrawer) as a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoverSearchSheet(
    state: CoverSearchUi,
    onTitle: (String) -> Unit,
    onAuthor: (String) -> Unit,
    onProvider: (String) -> Unit,
    onSearch: () -> Unit,
    onPick: (CoverResult) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        modifier = Modifier.belowStatusBar(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OttershelfTheme.colors.card,
    ) {
        CoverSearchContent(state, onTitle, onAuthor, onProvider, onSearch, onPick)
    }
}

/**
 * What to search for (the title and first author, editable; the source, the user's default first), then
 * the covers found, three across, each with its size and where it's from. A tap chooses one (to
 * confirm).
 */
@Composable
internal fun CoverSearchContent(
    state: CoverSearchUi,
    onTitle: (String) -> Unit = {},
    onAuthor: (String) -> Unit = {},
    onProvider: (String) -> Unit = {},
    onSearch: () -> Unit = {},
    onPick: (CoverResult) -> Unit = {},
) {
    val colors = OttershelfTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Text(
            stringResource(R.string.bookedit_search_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.foreground,
        )
        val search = KeyboardActions(onSearch = { onSearch() })
        OutlinedTextField(
            value = state.title,
            onValueChange = onTitle,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            placeholder = { Text(stringResource(R.string.bookedit_search_book_title)) },
            leadingIcon = { LucideIcon("BookOpen", contentDescription = null, tint = colors.mutedForeground, size = 18.dp) },
            singleLine = true,
            shape = RoundedCornerShape(OttershelfTheme.radii.md),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Search),
            keyboardActions = search,
        )
        OutlinedTextField(
            value = state.author,
            onValueChange = onAuthor,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            placeholder = { Text(stringResource(R.string.bookedit_search_author)) },
            leadingIcon = { LucideIcon("User", contentDescription = null, tint = colors.mutedForeground, size = 18.dp) },
            singleLine = true,
            shape = RoundedCornerShape(OttershelfTheme.radii.md),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
            keyboardActions = search,
        )
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (provider in CoverProviders.choices) {
                    FilterChip(
                        selected = provider == state.provider,
                        onClick = { if (provider != state.provider) onProvider(provider) },
                        label = { Text(providerLabel(provider), maxLines = 1) },
                        shape = RoundedCornerShape(OttershelfTheme.radii.md),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            AccentButton(stringResource(R.string.bookedit_search), onClick = onSearch, icon = "Search", enabled = state.title.isNotBlank() && !state.loading)
        }
        val results = state.results
        val area = Modifier.fillMaxWidth().padding(top = 14.dp).heightIn(min = 240.dp, max = 520.dp)
        when {
            state.loading -> CoverGrid(area) {
                items(9) { SkeletonBox(Modifier.fillMaxWidth().aspectRatio(COVER_ASPECT), shape = RoundedCornerShape(OttershelfTheme.radii.md)) }
            }
            state.error != null -> Box(area, contentAlignment = Alignment.Center) {
                ErrorState(onSearch, message = stringResource(R.string.bookedit_search_failed, errorText(state.error)))
            }
            results == null -> Spacer(Modifier.padding(top = 14.dp))
            results.isEmpty() -> Box(area, contentAlignment = Alignment.Center) {
                EmptyState(stringResource(R.string.bookedit_search_none), icon = "ImageOff")
            }
            else -> CoverGrid(area) {
                items(results) { result -> FoundCover(result, onPick) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Top) {
            LucideIcon("Info", contentDescription = null, tint = colors.mutedForeground, size = 14.dp, modifier = Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.bookedit_search_note), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground)
        }
    }
}

@Composable
private fun CoverGrid(modifier: Modifier, content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 4.dp),
        content = content,
    )
}

/** A found cover: the preview, its size top right (on the success colour from 1000 px wide, as the web does), its source. */
@Composable
private fun FoundCover(result: CoverResult, onPick: (CoverResult) -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.md)
    val size = stringResource(R.string.bookedit_cover_size, result.width, result.height)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(COVER_ASPECT)
            .clip(shape)
            .background(colors.coverSurface)
            .border(1.dp, colors.border, shape)
            .clickable(role = Role.Button) { onPick(result) }
            .semantics { contentDescription = listOf(size, result.source).filter { it.isNotBlank() }.joinToString(", ") },
    ) {
        var failed by remember(result.preview) { mutableStateOf(false) }
        if (result.preview == null || failed) {
            LucideIcon("ImageOff", contentDescription = null, tint = colors.mutedForeground, size = 22.dp, modifier = Modifier.align(Alignment.Center))
        } else {
            FittedCoverImage(
                model = result.preview,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                onError = { failed = true },
            )
        }
        val sharp = result.width >= 1000
        Pill(size, if (sharp) colors.success.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.55f), if (sharp) colors.onSuccess else Color.White, Modifier.align(Alignment.TopEnd).padding(5.dp))
        if (result.source.isNotBlank()) {
            Pill(result.source, Color.Black.copy(alpha = 0.55f), Color.White, Modifier.align(Alignment.BottomStart).padding(5.dp))
        }
    }
}

@Composable
private fun Pill(text: String, background: Color, content: Color, modifier: Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(OttershelfTheme.radii.sm))
            .background(background)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun providerLabel(provider: String): String = when (provider) {
    CoverProviders.ITUNES -> stringResource(R.string.bookedit_provider_itunes)
    CoverProviders.ALL -> stringResource(R.string.bookedit_provider_all)
    else -> stringResource(R.string.bookedit_provider_duckduckgo)
}
