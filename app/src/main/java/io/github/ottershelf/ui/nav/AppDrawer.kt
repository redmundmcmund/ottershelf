package io.github.ottershelf.ui.nav

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.core.model.BookSource
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfFonts
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.util.Locale

/** A row of the drawer's list (the Nexus NavAdapter's NavEntry). */
@Immutable
sealed interface DrawerEntry {
    /** A section title: Libraries, Smart scopes, Collections. */
    data class Header(@param:StringRes val label: Int) : DrawerEntry

    data object Separator : DrawerEntry

    /**
     * A list to show: [key] is its `BookSource.key`; [name] from the server, or [nameRes] for the
     * app's own lists. [icon]: a Lucide name (PascalCase) as the web app stores it, [fallbackIcon]
     * if it's missing, unknown or a `custom:` upload.
     */
    data class Item(
        val key: String,
        val name: String? = null,
        @param:StringRes val nameRes: Int = 0,
        val count: Int? = null,
        val icon: String?,
        val fallbackIcon: String,
    ) : DrawerEntry

    /** A row that does something rather than show a list: never selected. */
    data class Action(val action: DrawerAction, val count: Int? = null) : DrawerEntry
}

enum class DrawerAction(@param:StringRes val label: Int, val icon: String) {
    /** Opens Book requests, pushed on top. */
    Requests(R.string.nav_book_requests, "BookPlus"),

    /** Loads the drawer's lists again after they failed. */
    Retry(R.string.nav_retry, "RotateCw"),
}

/** What a drawer item opens: the Dashboard, Downloaded, Authors, Series, or a book list. */
fun DrawerEntry.Item.route(label: String): Route = when (key) {
    BookSource.DASHBOARD -> Route.Home
    BookSource.DOWNLOADED -> Route.Downloads()
    BookSource.AUTHORS -> Route.Authors()
    BookSource.SERIES -> Route.Series()
    CALENDAR_KEY -> Route.Calendar
    HISTORY_KEY -> Route.History
    STATISTICS_KEY -> Route.Statistics
    ACHIEVEMENTS_KEY -> Route.Achievements
    NOTES_KEY -> Route.Notes()
    else -> Route.BookList(key, label)
}

/** 1234 -> "1.2K", as the web's badges do. */
fun compactCount(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
    n >= 10_000 -> "${n / 1000}K"
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1000.0).replace(".0K", "K")
    else -> n.toString()
}

/**
 * The scrim over the page while the drawer is open: the Nexus DrawerLayout's (its default
 * 0x99000000, black at 60%). Material's 32% barely shows over the dark pages.
 */
val DrawerScrimColor = Color(0x99000000)

/** The Nexus drawer's width (300dp), leaving at least 56dp of scrim to tap on narrow phones. */
@Composable
private fun drawerWidth(): Dp = (LocalConfiguration.current.screenWidthDp.dp - 56.dp).coerceIn(240.dp, 300.dp)

/**
 * The navigation drawer (activity_main.xml's navPanel): the logo tile and wordmark, who's signed
 * in where (and why the lists are missing, if they failed), the lists, then Settings and Sign out.
 * Edge to edge: the surface runs under the status and navigation bars, the content is padded.
 *
 * [drawerState] gives the sheet predictive back; null in screenshot tests.
 */
@Composable
fun AppDrawerSheet(
    state: DrawerUiState,
    selectedKey: String?,
    onPick: (DrawerEntry.Item, String) -> Unit,
    onAction: (DrawerAction) -> Unit,
    onSettings: () -> Unit,
    onSignOut: () -> Unit,
    drawerState: DrawerState?,
    modifier: Modifier = Modifier,
) {
    val colors = OttershelfTheme.colors
    val surface = colors.shellSurface.compositeOver(colors.background)
    val sheetModifier = modifier.width(drawerWidth()).fillMaxHeight()
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))) {
            DrawerHeader(state)
            HorizontalDivider(thickness = 1.dp, color = colors.border, modifier = Modifier.padding(bottom = 6.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                itemsIndexed(state.entries, key = { i, e -> e.lazyKey(i) }) { _, entry ->
                    when (entry) {
                        is DrawerEntry.Header -> SectionHeader(stringResource(entry.label))
                        DrawerEntry.Separator -> HorizontalDivider(
                            thickness = 1.dp,
                            color = colors.border,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        is DrawerEntry.Item -> {
                            val label = entry.name ?: stringResource(entry.nameRes)
                            NavRow(
                                label = label,
                                icon = entry.icon?.takeUnless { it.startsWith("custom:") },
                                fallbackIcon = entry.fallbackIcon,
                                count = entry.count,
                                selected = entry.key == selectedKey,
                                onClick = { onPick(entry, label) },
                            )
                        }
                        is DrawerEntry.Action -> NavRow(
                            label = stringResource(entry.action.label),
                            icon = entry.action.icon,
                            fallbackIcon = if (entry.action == DrawerAction.Retry) "RefreshCw" else entry.action.icon,
                            count = entry.count,
                            selected = false,
                            onClick = { onAction(entry.action) },
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = colors.border)
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    .padding(vertical = 6.dp),
            ) {
                FooterRow(stringResource(R.string.nav_settings), "Settings", onSettings)
                FooterRow(stringResource(R.string.nav_sign_out), "LogOut", onSignOut)
            }
        }
    }
    if (drawerState != null) {
        ModalDrawerSheet(
            drawerState = drawerState,
            modifier = sheetModifier,
            drawerShape = RectangleShape,
            drawerContainerColor = surface,
            drawerContentColor = colors.foreground,
            drawerTonalElevation = 0.dp,
            windowInsets = WindowInsets(0),
        ) { content() }
    } else {
        ModalDrawerSheet(
            modifier = sheetModifier,
            drawerShape = RectangleShape,
            drawerContainerColor = surface,
            drawerContentColor = colors.foreground,
            drawerTonalElevation = 0.dp,
            windowInsets = WindowInsets(0),
        ) { content() }
    }
}

private fun DrawerEntry.lazyKey(index: Int): String = when (this) {
    is DrawerEntry.Header -> "header:$label"
    DrawerEntry.Separator -> "separator:$index"
    is DrawerEntry.Item -> "item:$key"
    is DrawerEntry.Action -> "action:${action.name}"
}

/** The badge, the "Ottershelf" wordmark with "shelf" in the accent, then username and server. */
@Composable
private fun DrawerHeader(state: DrawerUiState) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            .padding(start = 16.dp, top = 18.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.ottershelf_badge), contentDescription = null, modifier = Modifier.size(40.dp))
        val otter = stringResource(R.string.nav_wordmark_otter)
        val shelf = stringResource(R.string.nav_wordmark_shelf)
        Text(
            buildAnnotatedString {
                append(otter)
                withStyle(SpanStyle(color = colors.primary)) { append(shelf) }
            },
            modifier = Modifier.padding(start = 12.dp),
            style = TextStyle(fontFamily = OttershelfFonts.Serif, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = colors.foreground),
        )
    }
    val account = listOf(state.username, state.server).joinToString("\n")
    val failure = state.failureMessage?.let { stringResource(R.string.nav_lists_failed, it) }
    Text(
        text = if (failure != null) "$account\n\n$failure" else account,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 12.dp),
        style = TextStyle(fontFamily = OttershelfFonts.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = colors.mutedForeground),
    )
}

/** item_nav_header.xml: 11sp medium capitals, spaced out, dim. */
@Composable
private fun SectionHeader(label: String) {
    Text(
        label.uppercase(),
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, top = 16.dp, end = 20.dp, bottom = 6.dp),
        style = TextStyle(
            fontFamily = OttershelfFonts.Sans,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            letterSpacing = 0.14.em,
            color = OttershelfTheme.colors.mutedForeground,
        ),
        maxLines = 1,
    )
}

/**
 * item_nav.xml: a 44dp row inset 8dp, icon, label and count pill. Selected: the accent tint with a
 * short accent bar at the start, accent icon, label and pill; pressed: an accent wash.
 */
@Composable
private fun NavRow(
    label: String,
    icon: String?,
    fallbackIcon: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = OttershelfTheme.colors
    val shape = OttershelfTheme.radii.mdShape
    Box(
        Modifier
            .padding(start = 8.dp, end = 8.dp, bottom = 2.dp)
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(colors.accentTint) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = colors.primary),
                onClick = onClick,
            ),
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .size(width = 3.dp, height = 18.dp)
                    .background(colors.primary, RoundedCornerShape(2.dp)),
            )
        }
        Row(
            Modifier.fillMaxHeight().padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LucideIcon(
                name = icon,
                contentDescription = null,
                tint = if (selected) colors.primary else colors.mutedForeground,
                size = 20.dp,
                fallback = fallbackIcon,
            )
            Text(
                label,
                modifier = Modifier.weight(1f).padding(start = 14.dp),
                style = TextStyle(
                    fontFamily = OttershelfFonts.Sans,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    fontSize = 15.sp,
                    color = if (selected) colors.primary else colors.foreground,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Like the web: no pill for nothing.
            if (count != null && count > 0) {
                Text(
                    compactCount(count),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .background(
                            if (selected) colors.primary.copy(alpha = 0.22f) else colors.accentTint,
                            OttershelfTheme.radii.smShape,
                        )
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                    style = TextStyle(
                        fontFamily = OttershelfFonts.Sans,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = if (selected) colors.primary else colors.countForeground,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** The footer's rows (the Nexus signOut row): dim icon and label, never selected. */
@Composable
private fun FooterRow(label: String, icon: String, onClick: () -> Unit) {
    val colors = OttershelfTheme.colors
    Row(
        Modifier
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .fillMaxWidth()
            .height(44.dp)
            .clip(OttershelfTheme.radii.mdShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = colors.primary),
                onClick = onClick,
            )
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon(name = icon, contentDescription = null, tint = colors.mutedForeground, size = 20.dp)
        Spacer(Modifier.width(14.dp))
        Text(label, style = TextStyle(fontFamily = OttershelfFonts.Sans, fontSize = 15.sp, color = colors.mutedForeground), maxLines = 1)
    }
}
