package io.github.ottershelf.feature.seriesnext

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.core.model.formatSeriesIndex
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.theme.OttershelfTheme
import java.util.Locale

/**
 * "Next in the series" (the finish celebration, the timer's result): a dashboard card with the next
 * book's cover, title and "Book 4 of The Oz Books", and Read (when a reader here opens it) and
 * Details. Once the whole series is read, the quiet "You've read all 6" line instead; nothing when
 * [state] is hidden. It opens out once the series has been looked up (the celebration's column is
 * centred, so it doesn't jump). [center]: the line is centred, as on the celebration.
 */
@Composable
fun NextInSeriesCard(
    state: SeriesNextState,
    onRead: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String = stringResource(R.string.seriesnext_eyebrow),
    center: Boolean = true,
) {
    // The last thing shown stays while it folds away.
    val shown = rememberLastShown(state)
    AnimatedVisibility(
        visible = state !is SeriesNextState.Hidden,
        modifier = modifier,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        when (shown) {
            SeriesNextState.Hidden -> Unit
            is SeriesNextState.AllRead -> AllReadLine(shown, Modifier, center)
            is SeriesNextState.Next -> NextCard(shown.book, eyebrow, onRead, onDetails, Modifier)
        }
    }
}

/** The book page of a series book the user has finished: [NextInSeriesCard] under the Read and status buttons, near the series chip. */
@Composable
fun NextInSeriesRow(state: SeriesNextState, onRead: () -> Unit, onDetails: () -> Unit, modifier: Modifier = Modifier) {
    NextInSeriesCard(
        state = state,
        onRead = onRead,
        onDetails = onDetails,
        modifier = modifier,
        eyebrow = stringResource(R.string.seriesnext_row_eyebrow),
        center = false,
    )
}

@Composable
private fun NextCard(next: NextBook, eyebrow: String, onRead: () -> Unit, onDetails: () -> Unit, modifier: Modifier) {
    val colors = OttershelfTheme.colors
    val title = next.title ?: stringResource(R.string.seriesnext_untitled)
    DashCard(modifier.widthIn(max = CARD_MAX_WIDTH).fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
        Row {
            BookCover(
                model = next.cover,
                title = next.title,
                authors = next.card.authors.joinToString(", ").ifEmpty { null },
                seed = next.title ?: next.bookId.toString(),
                modifier = Modifier.width(64.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow(eyebrow)
                Text(
                    text = title,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                numberLine(next)?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.mutedForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (next.file != null) {
                        AccentButton(stringResource(R.string.seriesnext_read), onClick = onRead, icon = "BookOpen")
                    }
                    SecondaryButton(stringResource(R.string.seriesnext_details), onClick = onDetails)
                }
            }
        }
    }
}

@Composable
private fun Eyebrow(text: String, color: Color = OttershelfTheme.colors.primary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LucideIcon("Library", contentDescription = null, tint = color, size = 13.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = text.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Book 4 of The Oz Books", "Book 4" without the series' name, or the name alone without a number. */
@Composable
private fun numberLine(next: NextBook): String? {
    val index = next.seriesIndex?.let(::formatSeriesIndex)
    val name = next.seriesName
    return when {
        index != null && name != null -> stringResource(R.string.seriesnext_book_of, index, name)
        index != null -> stringResource(R.string.seriesnext_book_n, index)
        else -> name
    }
}

@Composable
private fun allReadText(state: SeriesNextState.AllRead): String =
    state.seriesName?.let { stringResource(R.string.seriesnext_all_read, state.count, it) }
        ?: stringResource(R.string.seriesnext_all_read_plain, state.count)

@Composable
private fun AllReadLine(state: SeriesNextState.AllRead, modifier: Modifier, center: Boolean) {
    val colors = OttershelfTheme.colors
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start,
    ) {
        LucideIcon("BookCheck", contentDescription = null, tint = colors.success, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = allReadText(state),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.mutedForeground,
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
        )
    }
}

// --- the reader's last page --------------------------------------------------------------------

/**
 * The foliate reader's end of book: once the user reaches it ([bookEnd], the reader's word: the last
 * page, the bottom of the last section, or a fixed-layout book's last page on screen, while the book
 * shows: [ready]), a small card over the bottom of the page with the next book in the series, Read
 * (in its own reader) and Details (its page). Both replace this reader: the user is done with the book
 * (its place is saved as the user leaves), Back returns to where the user came from, and no reader is left
 * under another. The series is looked up when the user nears the end ([fraction]). Closing it hides it
 * until the user comes back to the end. [raised]: the reader's bottom bar is showing.
 */
@Composable
fun BoxScope.EndOfBookCard(bookId: Long, fraction: Float, bookEnd: Boolean, ready: Boolean, raised: Boolean, navigator: AppNavigator) {
    val next = rememberSeriesNextNearEnd(bookId, near = ready && SeriesNext.nearEndOfBook(fraction, bookEnd))
    val atEnd = ready && bookEnd
    var closed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(atEnd) { if (!atEnd) closed = false }
    val shown = rememberLastShown(next.state)
    AnimatedVisibility(
        visible = atEnd && !closed && next.state !is SeriesNextState.Hidden,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = if (raised) 120.dp else 24.dp),
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut(),
    ) {
        EndOfBookCardContent(
            state = shown,
            onRead = { next.readRoute()?.let(navigator::replace) },
            onDetails = { next.detailsRoute()?.let(navigator::replace) },
            onClose = { closed = true },
        )
    }
}

/** Stateless: the end-of-book card for [state] (nothing when hidden). */
@Composable
fun EndOfBookCardContent(state: SeriesNextState, onRead: () -> Unit, onDetails: () -> Unit, onClose: () -> Unit) {
    val colors = OttershelfTheme.colors
    when (state) {
        SeriesNextState.Hidden -> Unit
        is SeriesNextState.AllRead -> EndSurface {
            Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                LucideIcon("BookCheck", contentDescription = null, tint = colors.success, size = 16.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = allReadText(state),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.foreground,
                )
                CloseButton(onClose)
            }
        }
        is SeriesNextState.Next -> EndSurface {
            val next = state.book
            val title = next.title ?: stringResource(R.string.seriesnext_untitled)
            Column(Modifier.padding(12.dp)) {
                Row {
                    BookCover(model = next.cover, title = next.title, seed = next.title ?: next.bookId.toString(), modifier = Modifier.width(44.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp, top = 2.dp)) {
                        Eyebrow(stringResource(R.string.seriesnext_eyebrow), color = colors.mutedForeground)
                        Text(
                            text = title,
                            modifier = Modifier.padding(top = 3.dp),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                            color = colors.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        numberLine(next)?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = colors.mutedForeground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    CloseButton(onClose, Modifier.padding(start = 4.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    CompactButton(
                        text = stringResource(R.string.seriesnext_details),
                        accent = false,
                        onClick = onDetails,
                        onClickLabel = stringResource(R.string.seriesnext_details_desc, title),
                    )
                    if (next.file != null) {
                        CompactButton(
                            text = stringResource(R.string.seriesnext_read),
                            accent = true,
                            icon = "ChevronRight",
                            onClick = onRead,
                            onClickLabel = stringResource(R.string.seriesnext_read_desc, title),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The comics reader's NextIssueCard surface: the page colour, a border, the xl radius, a shadow. Opaque
 * (the comics card is 96%): the shadow would show through a translucent fill.
 */
@Composable
private fun EndSurface(content: @Composable () -> Unit) {
    val colors = OttershelfTheme.colors
    Surface(
        modifier = Modifier.widthIn(max = CARD_MAX_WIDTH).fillMaxWidth(),
        color = colors.background,
        contentColor = colors.foreground,
        shape = RoundedCornerShape(OttershelfTheme.radii.xl),
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = 12.dp,
        content = content,
    )
}

@Composable
private fun CloseButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    Box(
        modifier
            .size(32.dp)
            .clip(RoundedCornerShape(OttershelfTheme.radii.md))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.seriesnext_close), onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon("X", contentDescription = stringResource(R.string.seriesnext_close), tint = colors.mutedForeground, size = 18.dp)
    }
}

/** The reader cards' 36dp buttons: the accent fill (the comics' Read next), or card fill with a border. */
@Composable
private fun CompactButton(text: String, accent: Boolean, onClick: () -> Unit, onClickLabel: String, icon: String? = null) {
    val colors = OttershelfTheme.colors
    val shape = RoundedCornerShape(OttershelfTheme.radii.md)
    val content = if (accent) colors.onPrimary else colors.foreground
    Row(
        Modifier
            .height(36.dp)
            .clip(shape)
            .background(if (accent) colors.primary else colors.card)
            .then(if (accent) Modifier else Modifier.border(1.dp, colors.border, shape))
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(start = 12.dp, end = if (icon != null) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = content,
        )
        if (icon != null) LucideIcon(icon, contentDescription = null, tint = content, size = 16.dp)
    }
}

/** [state], or while it is hidden the last one shown, so a card sliding away keeps its content. */
@Composable
private fun rememberLastShown(state: SeriesNextState): SeriesNextState {
    val last = remember { arrayOf(state) }
    if (state !is SeriesNextState.Hidden) last[0] = state
    return last[0]
}

private val CARD_MAX_WIDTH = 448.dp
