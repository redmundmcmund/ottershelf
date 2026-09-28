package io.github.ottershelf.feature.reader

import androidx.compose.ui.graphics.Color
import io.github.ottershelf.core.network.ApiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** The reading settings as the page's CSS and layout (ReaderStyles), and the settings' own rules. Plain JVM. */
class ReaderStylesTest {

    private val app = ReaderAppColors(Color(0xFFFAFAFB), Color(0xFF17181C), Color(0xFF2563EB), dark = false)
    private val appDark = ReaderAppColors(Color(0xFF16171B), Color(0xFFE6E7EB), Color(0xFF60A5FA), dark = true)

    // --- the settings the reader always had look exactly as before ------------------------------

    /**
     * reader.js' generateCSS before the CSS moved to the app, line for line (its THEMES and FONTS,
     * the app colours it was sent for the App theme, JavaScript's number printing).
     */
    private fun legacyCss(prefs: ReaderPrefs, app: ReaderAppColors?): String {
        data class T(val fg: String, val bg: String, val link: String, val dark: Boolean)
        val themes = mapOf(
            "original" to T("#000000", "#ffffff", "#1a4fd6", false),
            "light" to T("#1b1b1b", "#ffffff", "#1a4fd6", false),
            "sepia" to T("#5b4636", "#f1e8d0", "#8a5a2b", false),
            "dark" to T("#e0e0e0", "#222222", "#8ab4ff", true),
            "black" to T("#cfcfcf", "#000000", "#8ab4ff", true),
        )
        val t = if (prefs.theme == "app") {
            if (app == null) T("#1b1b1b", "#ffffff", "#1a4fd6", false)
            else T(PageColor.hex(app.foreground), PageColor.hex(app.background), PageColor.hex(app.link), app.dark)
        } else themes[prefs.theme] ?: themes.getValue("original")
        val font = mapOf("serif" to "Georgia, \"Noto Serif\", serif", "sans" to "\"Roboto\", \"Noto Sans\", sans-serif")[prefs.font]
        val recolor = prefs.theme != "original"
        val lh = prefs.lineHeight.let { if (it == it.roundToInt().toDouble()) it.roundToInt().toString() else it.toString() }
        return """
    html { color-scheme: ${if (t.dark) "dark" else "light"}; font-size: ${prefs.fontSize}px !important; }
    ${if (recolor) """html, body { color: ${t.fg} !important; background: ${t.bg} !important; }
      body * { color: inherit !important; background-color: transparent !important; border-color: ${t.fg}33 !important; }
      a:link, a:visited, a:link *, a:visited * { color: ${t.link} !important; }""" else ""}
    ${if (font != null) "body, body * { font-family: $font !important; }" else ""}
    p, li, blockquote, dd {
      line-height: $lh !important;
      text-align: ${if (prefs.justify) "justify" else "start"};
      hyphens: ${if (prefs.hyphenate) "auto" else "manual"};
      -webkit-hyphens: ${if (prefs.hyphenate) "auto" else "manual"};
    }
    img, svg { max-width: 100%; height: auto; }
    aside[role~="doc-footnote"] { display: none; }
  """
    }

    private fun squash(css: String) = css.replace(Regex("\\s+"), " ").trim()

    private fun cssOf(prefs: ReaderPrefs, app: ReaderAppColors? = this.app) = ReaderStyles.pageSettings(prefs, app).css

    @Test
    fun theOriginalSettingsGiveTheCssReaderJsMade() {
        val cases = listOf(
            ReaderPrefs(),
            ReaderPrefs(theme = "sepia", fontSize = 21, lineHeight = 1.8),
            ReaderPrefs(theme = "original", font = "serif", justify = false),
            ReaderPrefs(theme = "dark", font = "sans", hyphenate = true, lineHeight = 1.0),
            ReaderPrefs(theme = "black", fontSize = 12, lineHeight = 2.2, margin = 0.10),
            ReaderPrefs(theme = "app", fontSize = 32),
        )
        for (prefs in cases) {
            for (colors in listOf(app, appDark, null)) {
                assertEquals(prefs.toString(), squash(legacyCss(prefs, colors)), squash(cssOf(prefs, colors)))
            }
        }
    }

    @Test
    fun theOriginalLayoutIsUnchanged() {
        val s = ReaderStyles.pageSettings(ReaderPrefs(), app)
        assertEquals("paginated", s.flow)
        assertEquals("2", s.columns)
        assertEquals("6%", s.gap)
        assertEquals("28px", s.margin)
        assertEquals("720px", s.maxInlineSize)
        assertEquals("1440px", s.maxBlockSize)
        assertEquals("auto", s.fixedLayoutSpread)
        assertTrue(s.animated)
        assertEquals("#fafafb", s.bg)
        assertEquals("#17181c", s.fg)
        assertFalse(s.dark)
        assertEquals("3%", ReaderStyles.pageSettings(ReaderPrefs(margin = 0.03), app).gap)
        assertEquals("10%", ReaderStyles.pageSettings(ReaderPrefs(margin = 0.10), app).gap)
        assertEquals("scrolled", ReaderStyles.pageSettings(ReaderPrefs(flow = "scrolled"), app).flow)
    }

    @Test
    fun settingsStoredBeforeTheNewKeysReadAsTheyDid() {
        // What the DataStore holds from before: none of the added keys.
        val stored = """{"theme":"sepia","fontSize":20,"lineHeight":1.6,"justify":false,"hyphenate":true,"font":"serif","margin":0.1,"columns":2,"flow":"scrolled","animated":false}"""
        val prefs = ApiJson.decodeFromString(ReaderPrefs.serializer(), stored)
        assertEquals(0.0, prefs.paragraphSpacing, 0.0)
        assertNull(prefs.letterSpacing)
        assertNull(prefs.wordSpacing)
        assertNull(prefs.textIndent)
        assertEquals(720, prefs.maxInlineSize)
        assertEquals("auto", prefs.fixedLayoutSpread)
        assertEquals(0, prefs.footerDisplayMode)
        assertEquals(squash(legacyCss(prefs, app)), squash(cssOf(prefs)))
    }

    // --- the web reader's spacing rules, only when set ---------------------------------------

    @Test
    fun spacingIsTheBooksUntilSet() {
        val css = cssOf(ReaderPrefs())
        assertFalse(css.contains("margin-block"))
        assertFalse(css.contains("letter-spacing"))
        assertFalse(css.contains("word-spacing"))
        assertFalse(css.contains("text-indent"))
    }

    @Test
    fun paragraphSpacingIsTheWebsRule() {
        val css = squash(cssOf(ReaderPrefs(paragraphSpacing = 0.5)))
        assertTrue(css.contains("p { margin-block: 0 0.5em !important; }"))
        assertTrue(css.contains(":is(hgroup, header) p { margin-block: unset !important; }"))
    }

    @Test
    fun letterAndWordSpacingShareTheWebsRule() {
        assertTrue(squash(cssOf(ReaderPrefs(letterSpacing = 0.05, wordSpacing = 0.1))).contains(
            "p, li, blockquote, dd { letter-spacing: 0.05em !important; word-spacing: 0.1em !important; }",
        ))
        val letterOnly = squash(cssOf(ReaderPrefs(letterSpacing = 0.0)))
        assertTrue(letterOnly.contains("p, li, blockquote, dd { letter-spacing: 0em !important; }"))
        assertFalse(letterOnly.contains("word-spacing"))
        assertTrue(squash(cssOf(ReaderPrefs(wordSpacing = 0.25))).contains("p, li, blockquote, dd { word-spacing: 0.25em !important; }"))
    }

    @Test
    fun aZeroIndentIsSetButNullKeepsTheBooks() {
        val none = squash(cssOf(ReaderPrefs(textIndent = 0.0)))
        assertTrue(none.contains("p { text-indent: 0em !important; }"))
        assertTrue(none.contains(":is(hgroup, header, figure, figcaption, blockquote, li) > p { text-indent: unset !important; }"))
        assertTrue(squash(cssOf(ReaderPrefs(textIndent = 1.5))).contains("p { text-indent: 1.5em !important; }"))
    }

    @Test
    fun spacingComesAfterTheOriginalRules() {
        val css = cssOf(ReaderPrefs(paragraphSpacing = 1.0, letterSpacing = 0.02, textIndent = 2.0))
        val footnotes = css.indexOf("aside[role")
        assertTrue(css.indexOf("margin-block") > footnotes)
        assertTrue(css.indexOf("letter-spacing") > footnotes)
        assertTrue(css.indexOf("text-indent") > footnotes)
    }

    @Test
    fun pageWidthIsTheRenderersMaxInlineSize() {
        assertEquals("560px", ReaderStyles.pageSettings(ReaderPrefs(maxInlineSize = 560), app).maxInlineSize)
        assertEquals("1600px", ReaderStyles.pageSettings(ReaderPrefs(maxInlineSize = 1600), app).maxInlineSize)
        // Out of the web's range: its limits.
        assertEquals("400px", ReaderStyles.pageSettings(ReaderPrefs(maxInlineSize = 120), app).maxInlineSize)
        assertEquals("1600px", ReaderStyles.pageSettings(ReaderPrefs(maxInlineSize = 5000), app).maxInlineSize)
    }

    @Test
    fun pageWidthsFallInTheWebsBands() {
        assertEquals(listOf(0, 1, 2, 3), ReaderPrefs.PAGE_WIDTHS.map(ReaderPrefs::pageWidthIndex))
        assertEquals(1, ReaderPrefs.pageWidthIndex(720)) // the default reads as Medium, as on the web
        assertEquals(0, ReaderPrefs.pageWidthIndex(640))
        assertEquals(1, ReaderPrefs.pageWidthIndex(641))
        assertEquals(2, ReaderPrefs.pageWidthIndex(1320))
        assertEquals(3, ReaderPrefs.pageWidthIndex(1321))
        // On the web's 40 px grid from 400.
        assertTrue(ReaderPrefs.PAGE_WIDTHS.all { (it - 400) % 40 == 0 })
    }

    @Test
    fun spreadsPassThrough() {
        assertEquals("none", ReaderStyles.pageSettings(ReaderPrefs(fixedLayoutSpread = "none"), app).fixedLayoutSpread)
        assertEquals("auto", ReaderStyles.pageSettings(ReaderPrefs(fixedLayoutSpread = "both"), app).fixedLayoutSpread)
    }

    @Test
    fun normalizedKeepsTheWebsRangesAndSteps() {
        val p = ReaderPrefs(
            paragraphSpacing = 2.7, letterSpacing = 0.5, wordSpacing = 0.07, textIndent = 1.1,
            maxInlineSize = 90, customBg = "nope", customFg = "#ABC", fixedLayoutSpread = "left", footerDisplayMode = 9,
        ).normalized()
        assertEquals(2.0, p.paragraphSpacing, 1e-9)
        assertEquals(0.2, p.letterSpacing!!, 1e-9)
        assertEquals(0.05, p.wordSpacing!!, 1e-9)
        assertEquals(1.0, p.textIndent!!, 1e-9)
        assertEquals(400, p.maxInlineSize)
        assertEquals(ReaderPrefs.DEFAULT_CUSTOM_BG, p.customBg)
        assertEquals("#aabbcc", p.customFg)
        assertEquals("auto", p.fixedLayoutSpread)
        assertEquals(0, p.footerDisplayMode)
        assertEquals(0.0, ReaderPrefs(paragraphSpacing = -1.0).normalized().paragraphSpacing, 0.0)
        // The originals are left as they are.
        val originals = ReaderPrefs(fontSize = 31, lineHeight = 1.7, margin = 0.1, columns = 1)
        assertEquals(originals, originals.normalized())
    }

    // --- page colours --------------------------------------------------------------------

    @Test
    fun presetsKeepTheirPaperColours() {
        val sepia = ReaderStyles.pageSettings(ReaderPrefs(theme = "sepia"), app)
        assertEquals("#f1e8d0", sepia.bg)
        assertEquals("#5b4636", sepia.fg)
        assertFalse(sepia.dark)
        assertTrue(ReaderStyles.pageSettings(ReaderPrefs(theme = "black"), app).dark)
        assertTrue(ReaderStyles.pageSettings(ReaderPrefs(theme = "app"), appDark).dark)
    }

    @Test
    fun aCustomPageUsesTheUsersColours() {
        val s = ReaderStyles.pageSettings(ReaderPrefs(theme = "custom", customBg = "#DFE6D3", customFg = "#1f2d3d"), app)
        assertEquals("#dfe6d3", s.bg)
        assertEquals("#1f2d3d", s.fg)
        assertFalse(s.dark)
        val css = squash(s.css)
        assertTrue(css.contains("html, body { color: #1f2d3d !important; background: #dfe6d3 !important; }"))
        assertTrue(css.contains("border-color: #1f2d3d33 !important;"))
        // The accent reads well on it: links take it.
        assertTrue(css.contains("a:link, a:visited, a:link *, a:visited * { color: #2563eb !important; }"))
        val night = ReaderStyles.pageSettings(ReaderPrefs(theme = "custom", customBg = "#1f2a33", customFg = "#d6d0c4"), app)
        assertTrue(night.dark)
        assertTrue(squash(night.css).contains("color-scheme: dark"))
        // The blue accent is too dark on a dark page: links take the text colour.
        assertTrue(squash(night.css).contains("a:link, a:visited, a:link *, a:visited * { color: #d6d0c4 !important; }"))
    }

    @Test
    fun contrastIsWcags() {
        assertEquals(21.0, PageColor.contrast(Color.Black, Color.White), 1e-6)
        assertEquals(1.0, PageColor.contrast(Color(0xFF777777), Color(0xFF777777)), 1e-9)
        // #767676 on white is the classic 4.54:1, just above AA.
        assertEquals(4.54, PageColor.contrast(PageColor.parse("#767676")!!, Color.White), 0.01)
        assertTrue(PageColor.contrast(PageColor.parse("#777777")!!, Color.White) < PageColor.MIN_CONTRAST)
        assertEquals("4.5", PageColor.ratioText(4.54))
        assertTrue(PageColor.isDark(Color(0xFF222222)))
        assertFalse(PageColor.isDark(Color(0xFFF1E8D0)))
    }

    @Test
    fun aRatioJustUnderTheThresholdNeverReadsAsIt() {
        // #777777 on white is 4.48:1: warned about, so it mustn't say "4.5".
        val grey = PageColor.contrast(PageColor.parse("#777777")!!, Color.White)
        assertTrue(grey < PageColor.MIN_CONTRAST)
        assertEquals("4.4", PageColor.ratioText(grey))
        assertEquals("4.4", PageColor.ratioText(4.4999))
        assertEquals("4.5", PageColor.ratioText(4.5))
        assertEquals("4.5", PageColor.ratioText(PageColor.contrast(PageColor.parse("#767676")!!, Color.White)))
        assertEquals("21.0", PageColor.ratioText(PageColor.contrast(Color.Black, Color.White)))
        assertEquals("1.0", PageColor.ratioText(1.0))
        assertEquals("7.0", PageColor.ratioText(7.0))
    }

    @Test
    fun hexParsesAndPrints() {
        assertEquals("#f1e8d0", PageColor.normalize("F1E8D0"))
        assertEquals("#aabbcc", PageColor.normalize("#abc"))
        assertNull(PageColor.normalize("#12345"))
        assertNull(PageColor.normalize("#gg0000"))
        assertNull(PageColor.normalize(null))
        for (hex in BACKGROUND_PRESETS + TEXT_PRESETS) assertEquals(hex, PageColor.hex(PageColor.parse(hex)!!))
    }

    @Test
    fun hslRoundTrips() {
        for (hex in BACKGROUND_PRESETS + TEXT_PRESETS + listOf("#ff0000", "#00ff00", "#0000ff", "#8a5a2b", "#2563eb")) {
            val c = PageColor.parse(hex)!!
            val (h, s, l) = PageColor.toHsl(c).toList()
            val back = PageColor.hex(Color.hsl(h.coerceIn(0f, 359.9f), s, l))
            // Within one step per channel.
            val a = PageColor.parse(back)!!
            assertTrue("$hex -> $back", abs(a.red - c.red) <= 1.5f / 255 && abs(a.green - c.green) <= 1.5f / 255 && abs(a.blue - c.blue) <= 1.5f / 255)
        }
        val red = PageColor.toHsl(Color.Red)
        assertEquals(0f, red[0], 0.01f)
        assertEquals(1f, red[1], 0.01f)
        assertEquals(0.5f, red[2], 0.01f)
        assertEquals(0f, PageColor.toHsl(Color(0xFF808080))[1], 0.001f)
    }

    @Test
    fun numbersPrintAsJavaScriptDoes() {
        assertEquals("1.5", ReaderStyles.number(1.5))
        assertEquals("2", ReaderStyles.number(2.0))
        assertEquals("0", ReaderStyles.number(0.0))
        assertEquals("0.05", ReaderStyles.number(0.05))
        assertEquals("0.3", ReaderStyles.number(0.1 + 0.2))
    }

    // --- the spacing steppers ------------------------------------------------------------

    @Test
    fun letterSpacingStepsFromTheBooksToTheWebsMax() {
        val spec = SpacingSteps.Spec(ReaderPrefs.LETTER_SPACING_STEP, ReaderPrefs.LETTER_SPACING_MAX)
        assertTrue(SpacingSteps.isBook(null, spec))
        assertFalse(SpacingSteps.canDecrease(null, spec))
        assertEquals(0.0, SpacingSteps.up(null, spec)!!, 0.0)
        assertEquals(0.01, SpacingSteps.up(0.0, spec)!!, 1e-9)
        assertNull(SpacingSteps.down(0.0, spec)) // below 0 em: the book's own again
        assertEquals(0.0, SpacingSteps.down(0.01, spec)!!, 1e-9)
        var v: Double? = null
        repeat(40) { v = SpacingSteps.up(v, spec) }
        assertEquals(0.2, v!!, 1e-9)
        assertFalse(SpacingSteps.canIncrease(v, spec))
    }

    @Test
    fun paragraphSpacingsBookIsZero() {
        val spec = SpacingSteps.Spec(ReaderPrefs.PARAGRAPH_SPACING_STEP, ReaderPrefs.PARAGRAPH_SPACING_MAX, zeroIsBook = true)
        assertTrue(SpacingSteps.isBook(0.0, spec))
        assertEquals(0.1, SpacingSteps.up(0.0, spec)!!, 1e-9)
        assertEquals(0.0, SpacingSteps.down(0.1, spec)!!, 1e-9)
        assertEquals(0.0, SpacingSteps.down(0.0, spec)!!, 0.0)
        assertEquals(2.0, SpacingSteps.up(1.95, spec)!!, 1e-9)
    }

    @Test
    fun indentAndWordSpacingUseTheWebsSteps() {
        val indent = SpacingSteps.Spec(ReaderPrefs.TEXT_INDENT_STEP, ReaderPrefs.TEXT_INDENT_MAX)
        assertEquals(1.25, SpacingSteps.up(1.0, indent)!!, 1e-9)
        assertEquals(4.0, SpacingSteps.up(4.0, indent)!!, 1e-9)
        val word = SpacingSteps.Spec(ReaderPrefs.WORD_SPACING_STEP, ReaderPrefs.WORD_SPACING_MAX)
        assertEquals(0.15, SpacingSteps.up(0.1, word)!!, 1e-9)
        assertEquals(0.05, SpacingSteps.down(0.1, word)!!, 1e-9)
    }
}
