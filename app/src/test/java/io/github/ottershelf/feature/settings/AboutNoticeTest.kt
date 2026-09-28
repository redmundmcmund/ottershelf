package io.github.ottershelf.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The About screen shows BookOrbit's required notices word for word: each line is checked against
 * the repository's copy of BookOrbit's ADDITIONAL_TERMS.md (sections 1 and 3), with the Markdown
 * (quote marks, bold, link brackets) taken out.
 */
@RunWith(AndroidJUnit4::class)
class AboutNoticeTest {

    private val terms: String = File("../ADDITIONAL_TERMS.md").readText()
        .lines()
        .joinToString("\n") { it.removePrefix(">").trim() }
        .replace("**", "")
        .replace("<", "")
        .replace(">", "")

    @Test
    fun theNoticesAreBookOrbitsWordForWord() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (id in listOf(
            R.string.about_powered_by,
            R.string.about_bookorbit_copyright,
            R.string.about_bookorbit_contributors,
            R.string.about_bookorbit_project,
            R.string.about_bookorbit_licence,
            R.string.about_modified,
        )) {
            val text = context.getString(id)
            assertTrue("not word for word in ADDITIONAL_TERMS.md: \"$text\"", terms.lines().any { it == text })
        }
    }

    @Test
    fun poweredByLinksToTheOriginalProject() {
        assertTrue(terms.contains("\"Powered by BookOrbit\" must link directly to $BOOKORBIT_URL."))
    }
}
