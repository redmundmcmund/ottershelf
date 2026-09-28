package io.github.ottershelf.feature.timer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.ui.theme.OttershelfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reader's timer notice says how long the timer has run before the user stops it (its time is saved
 * at once, with no save form), so a timer forgotten overnight stands out.
 */
@RunWith(AndroidJUnit4::class)
class ReaderTimerNoticeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theNoticeSaysHowLongTheTimerHasRun() {
        var stopped = 0
        rule.setContent {
            OttershelfTheme {
                ReaderTimerDialog(ranMs = (9 * 60 + 12) * 60_000L + 20_000, onStop = { stopped++ }, onKeep = {})
            }
        }
        rule.onNodeWithText("has run for 9 h 12 min", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Stop timer").performClick()
        rule.runOnIdle { assertEquals(1, stopped) }
    }

    @Test
    fun aShortRunReadsInMinutes() {
        rule.setContent {
            OttershelfTheme { ReaderTimerDialog(ranMs = 25 * 60_000L, onStop = {}, onKeep = {}) }
        }
        rule.onNodeWithText("has run for 25 min.", substring = true).assertIsDisplayed()
    }
}
