package io.github.ottershelf.ui.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.theme.Accent
import io.github.ottershelf.core.theme.ThemePrefs
import io.github.ottershelf.testing.BROKEN_COVER
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.captureLandscape
import io.github.ottershelf.testing.captureLightAndDark
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.components.BOOK_GRID_SPACING
import io.github.ottershelf.ui.components.BookCover
import io.github.ottershelf.ui.components.BookCoverPlaceholder
import io.github.ottershelf.ui.components.BookGridItem
import io.github.ottershelf.ui.components.CardRow
import io.github.ottershelf.ui.components.CardTitle
import io.github.ottershelf.ui.components.CountPill
import io.github.ottershelf.ui.components.CoverProgressBar
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.EmptyState
import io.github.ottershelf.ui.components.ErrorState
import io.github.ottershelf.ui.components.FormatBadge
import io.github.ottershelf.ui.components.FormatChip
import io.github.ottershelf.ui.components.LoadingState
import io.github.ottershelf.ui.components.PillProgressBar
import io.github.ottershelf.ui.components.SectionHeader
import io.github.ottershelf.ui.components.SeriesBadge
import io.github.ottershelf.ui.components.ShelfCover
import io.github.ottershelf.ui.components.SkeletonBox
import io.github.ottershelf.ui.components.StatusBadge
import io.github.ottershelf.ui.components.StatusIcon
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.theme.PatternBackground
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every shared component in ui.components, with made-up books, light and dark. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class ComponentsScreenshotTest {

    @Test
    fun grid() = captureLightAndDark("components/grid") { WithFakeCovers { Grid(columns = 3) } }

    @Test
    fun gridLandscape() = captureLandscape("components/grid") { WithFakeCovers { Grid(columns = 6) } }

    @Test
    fun dashboardCards() = captureLightAndDark("components/cards") { WithFakeCovers { Cards() } }

    @Test
    fun badges() = captureLightAndDark("components/badges") { WithFakeCovers { Badges() } }

    @Test
    fun states() = captureLightAndDark("components/states") { WithFakeCovers { States() } }

    @Test
    fun icons() = captureLightAndDark("components/icons") { WithFakeCovers { Icons() } }

    @Test
    fun placeholders() = captureLightAndDark("components/placeholders") { WithFakeCovers { Placeholders() } }

    @Test
    fun placeholdersPastel() = captureLightAndDark("components/placeholders_pastel", prefs = ThemePrefs(accent = Accent.PERIWINKLE)) {
        WithFakeCovers { Placeholders() }
    }
}

internal val sampleBooks = listOf(
    BookCard(1, "The Lost World", listOf("Arthur Conan Doyle"), seriesName = "Professor Challenger", seriesIndex = "4", readingProgress = 42.0, readStatus = ReadStatusInfo("reading"), hasCover = true, files = listOf(BookFile(1, "epub", "primary"))),
    BookCard(2, "A Study in Scarlet", listOf("Arthur Conan Doyle"), seriesIndex = "1", readingProgress = 100.0, readStatus = ReadStatusInfo("read"), hasCover = true, files = listOf(BookFile(2, "pdf", "primary"))),
    BookCard(3, "Frankenstein", listOf("Mary Shelley"), readStatus = ReadStatusInfo("want_to_read"), hasCover = false, files = listOf(BookFile(3, "epub"))),
    BookCard(4, "The Refugees: A Tale of Two Continents", listOf("Arthur Conan Doyle"), seriesIndex = "4.5", readStatus = ReadStatusInfo("on_hold"), readingProgress = 12.0, hasCover = true, files = listOf(BookFile(4, "mobi"))),
    BookCard(5, "The Castle of Otranto", listOf("Horace Walpole"), readStatus = ReadStatusInfo("abandoned"), hasCover = true, files = listOf(BookFile(5, "cbz"))),
    BookCard(6, "The First Men in the Moon", listOf("H. G. Wells"), readStatus = ReadStatusInfo("unread"), hasCover = true, files = listOf(BookFile(6, "m4b"))),
    BookCard(7, "Dracula", listOf("Bram Stoker"), seriesIndex = "1", readStatus = ReadStatusInfo("rereading"), readingProgress = 71.0, hasCover = true),
    BookCard(8, "Middlemarch", listOf("George Eliot"), readStatus = ReadStatusInfo("skimmed"), hasCover = false),
    BookCard(9, "King Solomon's Mines", listOf("H. Rider Haggard"), hasCover = true),
)

/** Cover models for [sampleBooks]: fakes, a coverless one (placeholder) and one that fails to load. */
private fun coverFor(book: BookCard): Any? = when {
    !book.hasCover -> null
    book.id == 9L -> BROKEN_COVER
    else -> fakeCover(book.id.toInt())
}

@Composable
private fun Grid(columns: Int) {
    PatternBackground(Modifier.fillMaxSize()) {
        Column(Modifier.padding(BOOK_GRID_SPACING)) {
            sampleBooks.chunked(columns).forEach { row ->
                Row {
                    row.forEach { book ->
                        BookGridItem(book, cover = coverFor(book), onClick = {}, modifier = Modifier.weight(1f), showFormat = book.id == 1L)
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Cards() {
    val c = OttershelfTheme.colors
    PatternBackground(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DashCard(Modifier.fillMaxWidth().height(244.dp)) {
                CardTitle("Currently Reading", icon = "BookOpen")
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sampleBooks.filter { it.readingProgress != null && it.readingProgress!! < 100 }.take(3).forEach { book ->
                        CardRow(onClick = {}) {
                            BookCover(coverFor(book), book.title, Modifier.size(36.dp, 56.dp), shape = RoundedCornerShape(4.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(book.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(book.authors.joinToString(), style = MaterialTheme.typography.bodySmall, color = c.mutedForeground, maxLines = 1)
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PillProgressBar((book.readingProgress!! / 100).toFloat(), Modifier.weight(1f))
                                    Spacer(Modifier.width(6.dp))
                                    Text("${book.readingProgress!!.toInt()}%", style = MaterialTheme.typography.labelSmall, color = c.mutedForeground)
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.size(32.dp).background(c.primary, CircleShape), contentAlignment = Alignment.Center) {
                                LucideIcon("Play", contentDescription = null, tint = c.onPrimary, size = 14.dp)
                            }
                        }
                    }
                }
            }
            DashCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 14.dp, bottom = 16.dp)) {
                SectionHeader("Recently Added", icon = "Sparkles", count = sampleBooks.size, modifier = Modifier.padding(horizontal = 14.dp))
                Spacer(Modifier.height(14.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    sampleBooks.take(4).forEach { ShelfCover(it, cover = coverFor(it), onClick = {}) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DashCard(Modifier.weight(1f).height(160.dp)) {
                    CardTitle("Reading Streak", icon = "Flame")
                    EmptyState("No reading yet. Open a book to start a streak.", icon = "Flame", compact = true, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp))
                }
                DashCard(Modifier.weight(1f).height(160.dp)) {
                    CardTitle("Currently Reading", icon = "BookOpen")
                    ErrorState(onRetry = {}, message = "Couldn't load.", compact = true, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp))
                }
            }
        }
    }
}

@Composable
private fun Badges() {
    val c = OttershelfTheme.colors
    val formats = listOf("epub", "kepub", "mobi", "azw3", "azw", "fb2", "djvu", "txt", "pdf", "cbz", "cbr", "cb7", "cbx", "m4b", "m4a", "mp3", "opus", "ogg", "flac", "zip")
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Status badges (24dp, 40dp) and icons", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ReadStatus.entries.forEach { StatusBadge(it, size = 24.dp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ReadStatus.entries.drop(1).forEach { StatusBadge(it, size = 40.dp) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ReadStatus.entries.forEach { status ->
                Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusIcon(status)
                    Spacer(Modifier.width(20.dp))
                    Text(status.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Text("Format badges (covers) and chips (text)", style = MaterialTheme.typography.titleMedium)
        formats.chunked(10).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { row.forEach { FormatBadge(it) } }
        }
        formats.chunked(7).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { row.forEach { FormatChip(it) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(c.coverSurface, RoundedCornerShape(6.dp)).padding(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SeriesBadge("3")
                    SeriesBadge("4.50")
                    SeriesBadge("12", small = true)
                }
            }
            CountPill("20")
            CountPill("1,284")
        }
        Text("Progress", style = MaterialTheme.typography.titleMedium)
        PillProgressBar(0f)
        PillProgressBar(0.01f)
        PillProgressBar(0.42f)
        PillProgressBar(1f, height = 4.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(80.dp).height(24.dp).background(c.coverSurface), contentAlignment = Alignment.BottomCenter) { CoverProgressBar(0.6f) }
            Box(Modifier.width(80.dp).height(24.dp).background(c.coverSurface), contentAlignment = Alignment.BottomCenter) { CoverProgressBar(1f, read = true) }
        }
    }
}

@Composable
private fun States() {
    PatternBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            EmptyState("Nothing downloaded yet. Books you download appear here.", icon = "HardDriveDownload", modifier = Modifier.fillMaxWidth().weight(1f))
            ErrorState(onRetry = {}, detail = "Unable to resolve host \"books.example.net\"", modifier = Modifier.fillMaxWidth().weight(1f))
            LoadingState(Modifier.fillMaxWidth().weight(1f))
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) {
                    Column(Modifier.weight(1f)) {
                        SkeletonBox(Modifier.fillMaxWidth().height(150.dp), pulse = false)
                        Spacer(Modifier.height(7.dp))
                        SkeletonBox(Modifier.fillMaxWidth(0.8f).height(12.dp), pulse = false)
                        Spacer(Modifier.height(4.dp))
                        SkeletonBox(Modifier.fillMaxWidth(0.5f).height(10.dp), pulse = false)
                    }
                }
            }
        }
    }
}

/** App icons, server-chosen names (libraries, scopes, collections) and an unknown name's fallback. */
@Composable
private fun Icons() {
    val names = listOf(
        "LayoutDashboard", "LibraryBig", "HardDriveDownload", "BookPlus", "Users", "Library", "Aperture", "FolderOpen",
        "Search", "Menu", "LogOut", "Settings", "RefreshCw", "Download", "CircleCheckBig", "Play",
        "BookHeart", "Rocket", "Ghost", "Swords", "Drama", "Baby", "GraduationCap", "Telescope",
        "Headphones", "ChartScatter", "Galaxy", "Images", "BookMarked", "NoSuchIcon", "Sparkles", "Flame",
    )
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Lucide (by name)", style = MaterialTheme.typography.titleMedium)
        names.chunked(8).forEach { row ->
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                row.forEach { name ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(46.dp)) {
                        LucideIcon(name, contentDescription = name, size = 24.dp)
                        Text(name.take(8), style = MaterialTheme.typography.labelSmall, color = OttershelfTheme.colors.mutedForeground, maxLines = 1)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LucideIcon("BookOpen", contentDescription = null, size = 16.dp, tint = OttershelfTheme.colors.primary)
            LucideIcon("BookOpen", contentDescription = null, size = 20.dp)
            LucideIcon("BookOpen", contentDescription = null, size = 32.dp)
            LucideIcon("BookOpen", contentDescription = null, size = 48.dp, tint = OttershelfTheme.colors.success)
        }
    }
}

@Composable
private fun Placeholders() {
    val titles = listOf("Dracula", "Frankenstein", "The Lost World", "A Very Long Title That Needs Several Lines To Fit On The Cover", null)
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            titles.take(3).forEach { title ->
                BookCoverPlaceholder(title, authors = "Arthur Conan Doyle", modifier = Modifier.weight(1f).height(180.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            titles.drop(3).forEach { title ->
                BookCoverPlaceholder(title, authors = "Someone Else", modifier = Modifier.width(120.dp).height(180.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    titles.take(3).forEach { BookCover(null, it, Modifier.size(36.dp, 56.dp), shape = RoundedCornerShape(4.dp)) }
                }
                BookCover(BROKEN_COVER, "Failed to load", Modifier.width(80.dp))
            }
        }
    }
}
