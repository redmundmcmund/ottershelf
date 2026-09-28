package io.github.ottershelf.ui.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.model.BookCard
import io.github.ottershelf.core.model.BookFile
import io.github.ottershelf.core.model.ReadStatus
import io.github.ottershelf.core.model.ReadStatusInfo
import io.github.ottershelf.core.theme.Accent
import io.github.ottershelf.core.theme.Radius
import io.github.ottershelf.core.theme.ThemeBackground
import io.github.ottershelf.core.theme.ThemePrefs
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.captureLightAndDark
import io.github.ottershelf.testing.fakeCover
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.BookGridItem
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.components.PillProgressBar
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.components.SectionHeader
import io.github.ottershelf.ui.components.StatusIcon
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.theme.PatternBackground
import io.github.ottershelf.ui.theme.oklch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The theme itself: tokens, type, Material components and a few BookOrbit components, per accent
 * (blue is the default; white and grey are the neutral ones; periwinkle is pastel; amber has dark
 * text on its primary), per radius, and the dark-mode brightness range.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class ThemeGalleryScreenshotTest {

    @Test
    fun blue() = captureLightAndDark("theme/gallery_blue", heightDp = GALLERY_HEIGHT) { ThemeSampler("Blue (default)") }

    @Test
    fun white() = captureLightAndDark("theme/gallery_white", heightDp = GALLERY_HEIGHT, prefs = ThemePrefs(accent = Accent.WHITE)) {
        ThemeSampler("White")
    }

    @Test
    fun periwinkle() = captureLightAndDark("theme/gallery_periwinkle", heightDp = GALLERY_HEIGHT, prefs = ThemePrefs(accent = Accent.PERIWINKLE)) {
        ThemeSampler("Periwinkle (pastel)")
    }

    @Test
    fun amber() = captureLightAndDark("theme/gallery_amber", heightDp = GALLERY_HEIGHT, prefs = ThemePrefs(accent = Accent.AMBER, background = ThemeBackground.DOTS)) {
        ThemeSampler("Amber, dots pattern")
    }

    @Test
    fun accents() = captureLightAndDark("theme/accents") { AccentBoard() }

    @Test
    fun radii() = captureLightAndDark("theme/radius") { RadiusBoard() }

    /** Dark mode at brightness 0, 35 (default) and 100: only the surfaces lift. */
    @Test
    fun brightness() = captureLightAndDark("theme/brightness", heightDp = 520) {
        Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in listOf(0, 35, 100)) {
                Box(Modifier.weight(1f).fillMaxSize()) {
                    OttershelfTheme(prefs = ThemePrefs(brightness = b)) {
                        PatternBackground(Modifier.fillMaxSize().border(1.dp, OttershelfTheme.colors.border)) {
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Brightness $b", style = MaterialTheme.typography.titleSmall)
                                DashCard(Modifier.fillMaxWidth()) {
                                    Text("Card", style = MaterialTheme.typography.bodySmall)
                                }
                                Swatch("card", OttershelfTheme.colors.card)
                                Swatch("surface4", OttershelfTheme.colors.surface4)
                                Swatch("sidebar", OttershelfTheme.colors.sidebar)
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val GALLERY_HEIGHT = 1320
    }
}

private val sampleBook = BookCard(
    id = 7,
    title = "The Lost World",
    authors = listOf("Arthur Conan Doyle"),
    seriesIndex = "4",
    readingProgress = 42.0,
    readStatus = ReadStatusInfo("reading"),
    hasCover = true,
    files = listOf(BookFile(1, "epub", "primary")),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSampler(name: String) {
    val c = OttershelfTheme.colors
    WithFakeCovers {
        PatternBackground(Modifier.fillMaxSize()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, style = MaterialTheme.typography.headlineLarge)
                Text("Toolbar title 19sp", style = MaterialTheme.typography.titleLarge)
                Text("Card title 15sp semibold", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Book title 13sp", style = MaterialTheme.typography.titleSmall)
                    Text("Author 12sp dim", style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                    Text("LIBRARIES", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.14.em), color = c.mutedForeground)
                }

                Group("Neutrals") {
                    Swatches(
                        "background" to c.background, "card" to c.card, "sidebar" to c.sidebar, "muted" to c.muted,
                        "surface2" to c.surface2, "surface3" to c.surface3, "surface4" to c.surface4, "border" to c.border,
                        "dim text" to c.mutedForeground, "count" to c.countForeground, "dots" to c.dotColor, "cover" to c.coverSurface,
                    )
                }
                Group("Accent") {
                    Swatches(
                        "primary" to c.primary, "on primary" to c.onPrimary, "tint 12%" to c.accentTint, "line 30%" to c.accentLine,
                        "shell" to c.shellSurface, "dash card" to c.dashCard, "card edge" to c.dashCardBorder, "card row" to c.cardRow,
                    )
                }
                Group("Semantic") {
                    Swatches(
                        "success" to c.success, "warning" to c.warning, "info" to c.info, "destructive" to c.destructive,
                        "star" to c.starHighlight, "vol read" to c.volumeRead, "vol reading" to c.volumeReading, "vol unread" to c.volumeUnread,
                        "ebook" to c.formatEbook, "kindle" to c.formatKindle, "comic" to c.formatComic, "audio" to c.formatAudio,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ReadStatus.entries.forEach { StatusIcon(it, size = 22.dp) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AccentButton("Read", onClick = {}, icon = "BookOpen")
                    SecondaryButton("Download", onClick = {}, icon = "Download")
                    TextButton(onClick = {}) { Text("Text action") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = true, onClick = {}, label = { Text("Selected") })
                    FilterChip(selected = false, onClick = {}, label = { Text("Chip") })
                    Switch(checked = true, onCheckedChange = {})
                    Switch(checked = false, onCheckedChange = {})
                    Checkbox(checked = true, onCheckedChange = {})
                }
                OutlinedTextField(value = "books.example.net", onValueChange = {}, label = { Text("Server") }, modifier = Modifier.fillMaxWidth())
                Slider(value = 0.35f, onValueChange = {})
                HorizontalDivider()

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DashCard(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                        SectionHeader("Recently added", icon = "Sparkles", count = 20)
                        Spacer(Modifier.height(12.dp))
                        Text("Currently reading", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        PillProgressBar(0.42f)
                        Spacer(Modifier.height(10.dp))
                        Text("A dashboard card: card at 30% over the page, accent edge, 2xl radius.", style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                    }
                    BookGridItem(sampleBook, cover = fakeCover(3), onClick = {}, modifier = Modifier.width(130.dp))
                }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = OttershelfTheme.colors.mutedForeground)
        content()
    }
}

@Composable
private fun Swatches(vararg items: Pair<String, Color>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.toList().chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (label, color) -> Swatch(label, color, Modifier.weight(1f)) }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Swatch(label: String, color: Color, modifier: Modifier = Modifier) {
    val c = OttershelfTheme.colors
    Row(modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(22.dp)
                .background(color, RoundedCornerShape(4.dp))
                .border(1.dp, c.border, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = c.foreground, maxLines = 1)
    }
}

/** The 64 accents in the web picker's four rows: each swatch is the primary in this mode. */
@Composable
private fun AccentBoard() {
    val dark = OttershelfTheme.colors.isDark
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Accents (picker rows)", style = MaterialTheme.typography.headlineSmall)
        Accent.rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { accent ->
                    val primary = if (dark) oklch(accent.darkL, accent.darkC, accent.hue) else oklch(accent.lightL, accent.lightC, accent.hue)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(28.dp)
                            .background(primary, CircleShape)
                            .border(1.dp, OttershelfTheme.colors.border, CircleShape),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        listOf(Accent.BLUE, Accent.IRIS, Accent.EMERALD, Accent.VERMILION, Accent.GREY, Accent.BUTTER).forEach { accent ->
            OttershelfTheme(prefs = ThemePrefs(accent = accent)) {
                val c = OttershelfTheme.colors
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.background, RoundedCornerShape(10.dp))
                        .border(1.dp, c.border, RoundedCornerShape(10.dp))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(accent.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(72.dp))
                    AccentButton("Primary", onClick = {})
                    Box(Modifier.size(28.dp).background(c.card, RoundedCornerShape(6.dp)).border(1.dp, c.border, RoundedCornerShape(6.dp)))
                    Box(Modifier.size(28.dp).background(c.muted, RoundedCornerShape(6.dp)))
                    Box(Modifier.size(28.dp).background(c.accentTint, RoundedCornerShape(6.dp)))
                }
            }
        }
    }
}

/** The four radius options side by side: a card, a button, a cover and a field at each. */
@Composable
private fun RadiusBoard() {
    WithFakeCovers {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Radius", style = MaterialTheme.typography.headlineSmall)
            Radius.entries.forEach { radius ->
                OttershelfTheme(prefs = ThemePrefs(radius = radius)) {
                    val r = OttershelfTheme.radii
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(96.dp)) {
                            Text(radius.label, style = MaterialTheme.typography.titleSmall)
                            Text("sm ${r.sm.value.toInt()} md ${r.md.value.toInt()} lg ${r.lg.value.toInt()} 2xl ${r.xl2.value.toInt()}", style = MaterialTheme.typography.bodySmall, color = OttershelfTheme.colors.mutedForeground)
                        }
                        DashCard(Modifier.size(90.dp, 80.dp)) { Text("Card", style = MaterialTheme.typography.bodySmall) }
                        AccentButton("Button", onClick = {})
                        BookGridItem(sampleBook, cover = fakeCover(radius.ordinal), onClick = {}, modifier = Modifier.width(70.dp))
                    }
                }
            }
        }
    }
}
