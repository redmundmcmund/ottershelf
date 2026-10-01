package io.github.ottershelf.feature.achievements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.R
import io.github.ottershelf.feature.calendar.GoalRing
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.CountPill
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.PillProgressBar
import io.github.ottershelf.ui.components.SectionHeader
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.TopBarActions
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.components.belowStatusBar

/** Achievements: a root list (the drawer's Tracking > Achievements) under the shell's toolbar. */
@Composable
fun AchievementsScreen(navigator: AppNavigator, contentPadding: PaddingValues) {
    val viewModel = appViewModel { AchievementsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    TopBarActions {
        IconButton(onClick = { navigator.navigate(Route.Rewind()) }) {
            LucideIcon("Rewind", contentDescription = stringResource(R.string.achievements_rewind), tint = LocalContentColor.current, size = 22.dp)
        }
    }
    AchievementsContent(
        state = state,
        onFilter = viewModel::setFilter,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onOpenBook = { navigator.navigate(Route.BookDetail(it)) },
        contentPadding = contentPadding,
    )
}

/**
 * The summary card (earned of total, the bar and the filter), then each category as a Nexus
 * section (framed icon, name, a ring and "earned / total") over a two-column grid of badge cards.
 * Tapping a card opens its details (every tier, the book it came with). Pull to refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsContent(
    state: AchievementsUiState,
    onFilter: (AchievementFilter) -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenBook: (Long) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    initialDetail: Badge? = null,
) {
    val colors = OttershelfTheme.colors
    val direction = LocalLayoutDirection.current
    val pullState = rememberPullToRefreshState()
    var detailKey by rememberSaveable { mutableStateOf(initialDetail?.key) }
    val detail = detailKey?.let { key -> initialDetail?.takeIf { it.key == key } ?: state.sections.flatMap { it.badges }.firstOrNull { it.key == key } }
    val outer = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())
    when {
        state.loading && state.sections.isEmpty() -> LoadingState(outer)
        state.error != null && state.sections.isEmpty() -> ErrorState(onRetry, outer, message = stringResource(R.string.achievements_load_failed), detail = state.error)
        else -> PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            state = pullState,
            modifier = outer,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = colors.card,
                    color = colors.primary,
                )
            },
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = contentPadding.calculateStartPadding(direction) + 12.dp,
                    end = contentPadding.calculateEndPadding(direction) + 12.dp,
                    top = 12.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "summary") { SummaryCard(state, onFilter) }
                if (state.sections.isEmpty()) {
                    item(key = "empty") { EmptyState(stringResource(R.string.achievements_empty), icon = "Trophy") }
                }
                state.sections.forEach { section ->
                    val shown = section.badges.filter { AchievementLogic.matches(it, state.filter) }
                    item(key = "h-${section.key}") {
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            SectionHeader(title = section.label, icon = AchievementLogic.categoryIcon(section.key), modifier = Modifier.weight(1f))
                            GoalRing(
                                progress = if (section.total > 0) section.earned.toFloat() / section.total else 0f,
                                size = 20.dp,
                                stroke = 3.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                            CountPill("${section.earned} / ${section.total}")
                        }
                    }
                    if (shown.isEmpty()) {
                        item(key = "e-${section.key}") {
                            Text(
                                stringResource(R.string.achievements_none_match),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.mutedForeground,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    shown.chunked(2).forEach { pair ->
                        item(key = "r-${section.key}-${pair.first().key}") {
                            BadgeRow(pair[0], pair.getOrNull(1), onBadge = { detailKey = it.key }, onOpenBook = onOpenBook)
                        }
                    }
                }
            }
        }
    }
    if (detail != null) {
        ModalBottomSheet(
            modifier = Modifier.belowStatusBar(),
            onDismissRequest = { detailKey = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.card,
        ) {
            BadgeDetail(detail, onOpenBook = { detailKey = null; onOpenBook(it) })
        }
    }
}

@Composable
private fun SummaryCard(state: AchievementsUiState, onFilter: (AchievementFilter) -> Unit) {
    val colors = OttershelfTheme.colors
    DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile("Trophy", colors.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.achievements_earned_of, state.totalEarned, state.totalAvailable),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                )
                // The chips count badges, the line above tiers: say both, so they don't seem to disagree.
                Text(
                    stringResource(R.string.achievements_badges_of, state.earnedBadges, state.totalBadges),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedForeground,
                    maxLines = 2,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        PillProgressBar(if (state.totalAvailable > 0) state.totalEarned.toFloat() / state.totalAvailable else 0f)
        Spacer(Modifier.height(12.dp))
        Segmented(
            options = listOf(
                AchievementFilter.ALL to stringResource(R.string.achievements_filter_all),
                AchievementFilter.EARNED to stringResource(R.string.achievements_filter_earned, state.earnedBadges),
                AchievementFilter.IN_PROGRESS to stringResource(R.string.achievements_filter_in_progress, state.inProgressBadges),
            ),
            selected = state.filter,
            onSelect = onFilter,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The bottom sheet: the medallion large, what it is, progress, when and with which book, every tier. */
@Composable
private fun BadgeDetail(badge: Badge, onOpenBook: (Long) -> Unit) {
    val colors = OttershelfTheme.colors
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BadgeMedallion(badge.icon, badge.rarity, badge.earned, badge.fraction, size = 96.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            if (badge.secret) stringResource(R.string.achievements_secret) else badge.name,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.foreground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!badge.secret) RarityPill(badge.rarity)
            if (badge.tiered) {
                Text(stringResource(R.string.achievements_tier, badge.earnedCount, badge.tiers.size), style = MaterialTheme.typography.labelMedium, color = colors.mutedForeground)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (badge.secret) stringResource(R.string.achievements_secret_hint) else badge.description.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.mutedForeground,
            textAlign = TextAlign.Center,
        )
        val fraction = badge.fraction
        if (fraction != null && badge.threshold != null) {
            Spacer(Modifier.height(16.dp))
            PillProgressBar(fraction, Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.achievements_progress, number(badge.progress ?: 0.0), number(badge.threshold)),
                    style = MaterialTheme.typography.labelMedium, color = colors.foreground,
                )
                Spacer(Modifier.weight(1f))
                badge.nextName?.let {
                    Text(stringResource(R.string.achievements_next, it), style = MaterialTheme.typography.labelMedium, color = colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        awardedText(badge.awardedAt)?.let {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.achievements_earned_on, it), style = MaterialTheme.typography.labelLarge, color = colors.foreground)
        }
        badge.contextBookTitle?.let { title ->
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.achievements_while_reading, title), style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, textAlign = TextAlign.Center)
            badge.contextBookId?.let { id ->
                Spacer(Modifier.height(12.dp))
                AccentButton(stringResource(R.string.achievements_open_book), onClick = { onOpenBook(id) }, icon = "BookOpen")
            }
        }
        if (badge.tiered) {
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = colors.border)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.achievements_tiers), style = MaterialTheme.typography.titleSmall, color = colors.foreground, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            badge.tiers.forEach { tier ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    LucideIcon(
                        if (tier.earned) "CircleCheckBig" else "Circle",
                        contentDescription = stringResource(if (tier.earned) R.string.achievements_earned else R.string.achievements_locked),
                        tint = if (tier.earned) rarityColor(tier.rarity) else colors.mutedForeground,
                        size = 18.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tier.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (tier.earned) FontWeight.SemiBold else FontWeight.Normal), color = colors.foreground)
                        Text(
                            listOfNotNull(rarityLabel(tier.rarity), tier.threshold?.let { number(it) }).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground,
                        )
                    }
                    awardedText(tier.awardedAt)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.mutedForeground) }
                }
            }
        }
        Spacer(Modifier.size(8.dp))
    }
}
