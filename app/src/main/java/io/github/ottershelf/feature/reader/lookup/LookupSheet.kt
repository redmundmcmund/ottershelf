package io.github.ottershelf.feature.reader.lookup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.components.SkeletonBox
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme

/** What the Look up sheet can do. */
class LookupActions(
    val onTab: (LookupTab) -> Unit = {},
    val onRetry: () -> Unit = {},
    /** A Wiktionary or Wikipedia page, in the browser. */
    val onOpenLink: (String) -> Unit = {},
    val onApp: (TextApp) -> Unit = {},
)

/** The sheet over the reader while [state] is open; swiped down, Back or the scrim close it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookupSheet(state: LookupUiState, viewModel: LookupViewModel) {
    val context = LocalContext.current
    val actions = LookupActions(
        onTab = viewModel::selectTab,
        onRetry = viewModel::retry,
        onOpenLink = { TextApps.openLink(context, it) },
        onApp = { app -> TextApps.launch(context, app, state.text) },
    )
    ModalBottomSheet(
        onDismissRequest = viewModel::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OttershelfTheme.colors.card,
    ) {
        LookupContent(state, actions, Modifier.fillMaxHeight(SHEET_HEIGHT))
    }
}

/** A steady height (the reader stays in view above it), whatever loads into it. */
private const val SHEET_HEIGHT = 0.72f

/**
 * The sheet's content (stateless; `LookupScreenshotTest`): the selection, the Dictionary and
 * Wikipedia tabs (Wikipedia alone for a phrase of more than five words), and the apps that take
 * text along the bottom, which work whatever the tabs show (offline too).
 */
@Composable
fun LookupContent(state: LookupUiState, actions: LookupActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        LookupHeader(state)
        if (state.showDictionary) {
            LookupTabs(state.tab, actions.onTab)
        } else if (state.wikipedia !is LookupPart.Skipped) {
            // A phrase: Wikipedia only, so a label rather than tabs.
            Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                LucideIcon("Globe", contentDescription = null, tint = OttershelfTheme.colors.primary, size = 15.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.reader_lookup_tab_wikipedia),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    color = OttershelfTheme.colors.mutedForeground,
                )
            }
        }
        val scroll = key(state.text, state.tab) { rememberScrollState() }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 16.dp),
        ) {
            when (state.tab) {
                LookupTab.Dictionary -> DictionaryPane(state, actions)
                LookupTab.Wikipedia -> WikipediaPane(state, actions)
            }
        }
        if (state.apps.isNotEmpty()) AppsRow(state.apps, actions.onApp)
    }
}

@Composable
private fun LookupHeader(state: LookupUiState) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.md)
    val phrase = !state.showDictionary
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).background(colors.accentTint, shape).border(1.dp, colors.primary.copy(alpha = 0.25f), shape),
            contentAlignment = Alignment.Center,
        ) {
            LucideIcon("BookA", contentDescription = null, tint = colors.primary, size = 18.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.reader_lookup_title),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = colors.mutedForeground,
            )
            if (phrase) {
                Text(
                    "“${state.text}”",
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = MaterialTheme.typography.headlineSmall.fontFamily, fontStyle = FontStyle.Italic, fontSize = 15.sp, lineHeight = 21.sp),
                    color = colors.foreground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    state.term,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LookupTabs(tab: LookupTab, onTab: (LookupTab) -> Unit) {
    val colors = OttershelfTheme.colors
    val radii = OttershelfTheme.radii
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(radii.md))
            .background(colors.muted)
            .padding(3.dp),
    ) {
        LookupTab.entries.forEach { t ->
            val selected = t == tab
            val (label, icon) = when (t) {
                LookupTab.Dictionary -> stringResource(R.string.reader_lookup_tab_dictionary) to "BookA"
                LookupTab.Wikipedia -> stringResource(R.string.reader_lookup_tab_wikipedia) to "Globe"
            }
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(radii.sm))
                    .background(if (selected) colors.card else Color.Transparent)
                    .clickable(role = Role.Tab, onClick = { onTab(t) })
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LucideIcon(icon, contentDescription = null, tint = if (selected) colors.primary else colors.mutedForeground, size = 15.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    color = if (selected) colors.foreground else colors.mutedForeground,
                    maxLines = 1,
                )
            }
        }
    }
}

// --- Dictionary ----------------------------------------------------------------------------------

@Composable
private fun ColumnScope.DictionaryPane(state: LookupUiState, actions: LookupActions) {
    when (val part = state.dictionary) {
        LookupPart.Loading, LookupPart.Skipped -> LoadingCard()
        is LookupPart.Failed -> FailedState(part.offline, stringResource(R.string.reader_lookup_failed_dictionary), state.apps.isNotEmpty(), actions.onRetry)
        LookupPart.NotFound -> EmptyState(
            message = stringResource(R.string.reader_lookup_no_definition, state.term),
            icon = "SearchX",
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            action = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val wiki = state.wikipedia
                    if (wiki is LookupPart.Ready || wiki is LookupPart.Loading) {
                        AccentButton(stringResource(R.string.reader_lookup_try_wikipedia), onClick = { actions.onTab(LookupTab.Wikipedia) }, icon = "Globe")
                    }
                    SecondaryButton(
                        stringResource(R.string.reader_lookup_search_wiktionary),
                        onClick = { actions.onOpenLink(Urls.wiktionarySearch(state.term).toString()) },
                        icon = "ExternalLink",
                    )
                }
            },
        )
        is LookupPart.Ready -> Dictionary(part.value, state.term, actions)
    }
}

@Composable
private fun Dictionary(result: DictionaryResult, term: String, actions: LookupActions) {
    val colors = OttershelfTheme.colors
    if (!result.word.equals(term, ignoreCase = true)) {
        Text(
            stringResource(R.string.reader_lookup_showing, result.word),
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = colors.mutedForeground,
        )
    }
    result.sections.forEachIndexed { index, section ->
        if (index > 0) Spacer(Modifier.height(10.dp))
        LanguageCard(section, first = result.word)
    }
    LinkRow(stringResource(R.string.reader_lookup_open_wiktionary), "en.wiktionary.org") { actions.onOpenLink(result.url) }
}

@Composable
private fun LanguageCard(section: LanguageSection, first: String) {
    val colors = OttershelfTheme.colors
    DashCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = colors.cardRow,
        borderColor = colors.border,
        shape = RoundedCornerShape(OttershelfTheme.radii.lg),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            section.language,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = colors.foreground,
        )
        var headword = first
        section.blocks.forEach { block ->
            if (block.headword != headword) {
                headword = block.headword
                HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp), thickness = 1.dp, color = colors.border)
                Text(
                    block.headword,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 17.sp),
                    color = colors.foreground,
                )
            }
            SenseBlockView(block)
        }
    }
}

@Composable
private fun SenseBlockView(block: SenseBlock) {
    val colors = OttershelfTheme.colors
    Text(
        block.partOfSpeech.uppercase(),
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
        color = colors.primary,
    )
    block.senses.forEachIndexed { i, sense ->
        Row(Modifier.padding(top = 4.dp)) {
            Text(
                "${i + 1}.",
                modifier = Modifier.width(22.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
                color = colors.mutedForeground,
            )
            Column(Modifier.weight(1f)) {
                Text(sense.text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp), color = colors.foreground)
                sense.example?.let { example ->
                    Row(Modifier.padding(top = 4.dp)) {
                        Box(Modifier.padding(top = 2.dp).width(2.dp).height(16.dp).background(colors.primary.copy(alpha = 0.45f), RoundedCornerShape(1.dp)))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                example,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontStyle = FontStyle.Italic),
                                color = colors.mutedForeground,
                            )
                            sense.translation?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp), color = colors.mutedForeground)
                            }
                        }
                    }
                }
            }
        }
    }
    if (block.more > 0) {
        Text(
            pluralStringResource(R.plurals.reader_lookup_more, block.more, block.more),
            modifier = Modifier.padding(start = 22.dp, top = 4.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = colors.mutedForeground,
        )
    }
}

// --- Wikipedia -----------------------------------------------------------------------------------

@Composable
private fun ColumnScope.WikipediaPane(state: LookupUiState, actions: LookupActions) {
    when (val part = state.wikipedia) {
        LookupPart.Loading -> LoadingCard(image = true)
        LookupPart.Skipped -> EmptyState(
            message = stringResource(if (state.apps.isEmpty()) R.string.reader_lookup_too_long else R.string.reader_lookup_too_long_apps),
            icon = "TextQuote",
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        is LookupPart.Failed -> FailedState(part.offline, stringResource(R.string.reader_lookup_failed_wikipedia), state.apps.isNotEmpty(), actions.onRetry)
        LookupPart.NotFound -> EmptyState(
            message = stringResource(R.string.reader_lookup_no_article, state.term),
            icon = "SearchX",
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            action = {
                SecondaryButton(
                    stringResource(R.string.reader_lookup_search_wikipedia),
                    onClick = { actions.onOpenLink(Urls.wikipediaSearch(state.wikipediaLang, state.term).toString()) },
                    icon = "ExternalLink",
                )
            },
        )
        is LookupPart.Ready -> Wikipedia(part.value, actions)
    }
}

@Composable
private fun Wikipedia(summary: WikiSummary, actions: LookupActions) {
    val colors = OttershelfTheme.colors
    DashCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = colors.cardRow,
        borderColor = colors.border,
        shape = RoundedCornerShape(OttershelfTheme.radii.lg),
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(summary.title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 19.sp), color = colors.foreground)
                summary.description?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        color = colors.mutedForeground,
                    )
                }
            }
            summary.thumbnail?.let { url ->
                Spacer(Modifier.width(12.dp))
                val context = LocalContext.current
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    imageLoader = LookupImages.loader(context),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 76.dp, height = thumbHeight(summary))
                        .clip(RoundedCornerShape(OttershelfTheme.radii.md))
                        .background(colors.coverSurface),
                )
            }
        }
        if (summary.extract.isNotBlank()) {
            Text(
                summary.extract,
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                color = colors.foreground,
            )
        }
        if (summary.disambiguation) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                LucideIcon("Info", contentDescription = null, tint = colors.mutedForeground, size = 14.dp, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.reader_lookup_disambiguation),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = colors.mutedForeground,
                )
            }
        }
    }
    LinkRow(stringResource(R.string.reader_lookup_open_wikipedia), "${summary.lang}.wikipedia.org") { actions.onOpenLink(summary.url) }
}

/** The thumbnail's height at 76dp wide, between square-ish and a tall portrait. */
private fun thumbHeight(summary: WikiSummary) =
    if (summary.thumbnailWidth > 0 && summary.thumbnailHeight > 0) {
        (76f * summary.thumbnailHeight / summary.thumbnailWidth).coerceIn(56f, 112f).dp
    } else 76.dp

// --- shared pieces -------------------------------------------------------------------------------

@Composable
private fun LinkRow(label: String, host: String, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(OttershelfTheme.radii.md))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon("ExternalLink", contentDescription = null, tint = colors.primary, size = 15.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = colors.primary)
        Spacer(Modifier.width(8.dp))
        Text(host, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = colors.mutedForeground, maxLines = 1)
    }
}

@Composable
private fun FailedState(offline: Boolean, failedMessage: String, hasApps: Boolean, onRetry: () -> Unit) {
    val message = if (offline) stringResource(R.string.reader_lookup_offline) else failedMessage
    EmptyState(
        message = if (hasApps) message + "\n" + stringResource(R.string.reader_lookup_apps_still_work) else message,
        icon = if (offline) "WifiOff" else "CloudOff",
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        action = { AccentButton(stringResource(R.string.common_retry), onClick = onRetry, icon = "RefreshCw") },
    )
}

@Composable
private fun LoadingCard(image: Boolean = false) {
    val colors = OttershelfTheme.colors
    DashCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = colors.cardRow,
        borderColor = colors.border,
        shape = RoundedCornerShape(OttershelfTheme.radii.lg),
        contentPadding = PaddingValues(14.dp),
    ) {
        Row {
            Column(Modifier.weight(1f)) {
                SkeletonBox(Modifier.fillMaxWidth(0.45f).height(16.dp))
                Spacer(Modifier.height(10.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.25f).height(10.dp))
            }
            if (image) SkeletonBox(Modifier.size(76.dp), RoundedCornerShape(OttershelfTheme.radii.md))
        }
        Spacer(Modifier.height(14.dp))
        listOf(1f, 0.92f, 0.97f, 0.6f).forEach { w ->
            SkeletonBox(Modifier.fillMaxWidth(w).height(12.dp))
            Spacer(Modifier.height(8.dp))
        }
    }
}

// --- apps ----------------------------------------------------------------------------------------

@Composable
private fun AppsRow(apps: List<TextApp>, onApp: (TextApp) -> Unit) {
    val colors = OttershelfTheme.colors
    HorizontalDivider(thickness = 1.dp, color = colors.border)
    Text(
        stringResource(R.string.reader_lookup_apps),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
        color = colors.mutedForeground,
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 12.dp),
    ) {
        items(apps, key = { it.key }) { app -> AppChip(app) { onApp(app) } }
    }
}

@Composable
private fun AppChip(app: TextApp, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.md)
    Row(
        Modifier
            .clip(shape)
            .background(colors.cardRow)
            .border(1.dp, colors.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = app.icon
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        } else {
            LucideIcon(if (app.kind == TextApp.Kind.Translate) "Languages" else "AppWindow", contentDescription = null, tint = colors.primary, size = 18.dp)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            app.label,
            modifier = Modifier.widthIn(max = 150.dp),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
