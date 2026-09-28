package io.github.ottershelf.feature.bookedit

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.R
import io.github.ottershelf.core.model.AuthorRef
import io.github.ottershelf.core.model.BookDetail
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The authors' chips on the phone: however long a name is (messy metadata, all in one author), its
 * chip keeps an X the user can tap to remove it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measuring: the legacy mode makes every name tiny
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AuthorChipTest {

    @get:Rule
    val rule = createComposeRule()

    private val long = "Arthur Conan Doyle (Author), Charles Doyle (Illustrator), Joseph Bell (Introduction)"

    private val book = BookDetail(id = 1, title = "The Sherlock Holmes Stories", authors = listOf(AuthorRef(5, long), AuthorRef(6, "Charles Doyle")))

    private fun show(fontScale: Float, removed: MutableList<Int>) = rule.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            OttershelfTheme {
                val form = EditForm.of(book)
                BookEditContent(
                    BookEditUiState(bookId = 1, loading = false, book = book, original = form, form = form),
                    actions = BookEditActions(onRemoveAuthor = { removed += it }),
                )
            }
        }
    }

    private fun removeButton() = rule.onNodeWithContentDescription(
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.bookedit_remove_author, long),
    )

    private fun theLongNameCanBeRemoved(fontScale: Float) {
        val removed = mutableListOf<Int>()
        show(fontScale, removed)
        removeButton().assertIsDisplayed().assertWidthIsEqualTo(26.dp).assertHeightIsEqualTo(26.dp)
        removeButton().performClick()
        rule.runOnIdle { assertEquals(listOf(0), removed) }
    }

    @Test
    fun aVeryLongNameKeepsItsRemoveButton() = theLongNameCanBeRemoved(fontScale = 1f)

    @Test
    fun aVeryLongNameKeepsItsRemoveButtonWithLargeText() = theLongNameCanBeRemoved(fontScale = 1.6f)
}
