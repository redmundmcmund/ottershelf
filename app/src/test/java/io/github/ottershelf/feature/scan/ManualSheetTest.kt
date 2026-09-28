package io.github.ottershelf.feature.scan

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.R
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Typing an ISBN in the scanner's sheet: the X key types at the cursor and leaves the cursor after
 * the X, so the next key (a Backspace to take it back, or more digits) edits where the user is.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ManualSheetTest {

    @get:Rule
    val rule = createComposeRule()

    /** The ViewModel's side: the text as setManual keeps it. */
    private val text = mutableStateOf("")

    private fun show() = rule.setContent {
        OttershelfTheme {
            ScanContent(
                state = ScanUiState(manual = ManualEntry(text.value)),
                camera = CameraUi(CameraAccess.Denied),
                actions = ScanActions(onManualText = { text.value = it }),
            )
        }
    }

    private fun field() = rule.onNode(hasSetTextAction())

    private fun xKey() = rule.onNodeWithContentDescription(
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.scan_manual_x_description),
    )

    private fun cursorAt(index: Int) =
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(index)))

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theXKeyLeavesTheCursorAfterTheX() {
        show()
        field().performTextInput("030640615")
        xKey().performClick()
        rule.runOnIdle { assertEquals("030640615X", text.value) }
        cursorAt(10)
        // A Backspace now takes the X back, not the digit before it.
        field().performKeyInput { pressKey(Key.Backspace) }
        rule.runOnIdle { assertEquals("030640615", text.value) }
        cursorAt(9)
    }

    @Test
    fun anXTappedByMistakeStaysWhereItWasTyped() {
        show()
        field().performTextInput("978030640")
        xKey().performClick()
        // The user goes on typing the user's ISBN-13: the digits follow the X (and the X shows as misplaced), not go in before it.
        field().performTextInput("6157")
        rule.runOnIdle { assertEquals("978030640X6157", text.value) }
        cursorAt(14)
    }

    @Test
    fun theXKeyTypesAtTheCursor() {
        show()
        field().performTextInput("080442957")
        field().performTextInputSelection(TextRange(3))
        xKey().performClick()
        rule.runOnIdle { assertEquals("080X442957", text.value) }
        cursorAt(4)
    }

    @Test
    fun theXReplacesASelection() {
        assertEquals(TextFieldValue("0804429X", TextRange(8)), withTypedX(TextFieldValue("080442957", TextRange(7, 9))))
        assertEquals(TextFieldValue("X", TextRange(1)), withTypedX(TextFieldValue("", TextRange(0))))
        // A cursor past the text (it shouldn't be) types at its end.
        assertEquals(TextFieldValue("12X", TextRange(3)), withTypedX(TextFieldValue("12", TextRange(5))))
    }
}
