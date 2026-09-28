package io.github.ottershelf.core.tracking

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.settings.TimerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The running timer's notification on the lock screen: only that a timer runs, its clock and its
 * buttons, never the book the user is reading (the user's "hide sensitive content" setting applies to it).
 */
@RunWith(AndroidJUnit4::class)
class TimerNotificationsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifications = TimerNotifications(context)
    private val now = 1_800_000_000_000L

    @Before
    fun allowNotifications() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.ensureChannels()
    }

    private fun timer(paused: Boolean = false, targetMs: Long? = null) = ActiveTimer(
        sessionId = "s1",
        bookId = 7,
        fileId = 70,
        title = "The Private Diary",
        startedAtMs = now - 25 * 60_000,
        accumulatedMs = if (paused) 25 * 60_000L else 0,
        runningSinceMs = if (paused) null else now - 25 * 60_000,
        pausedAtMs = if (paused) now else null,
        mode = if (targetMs != null) TimerMode.COUNTDOWN else TimerMode.COUNT_UP,
        targetMs = targetMs,
    )

    private fun posted(): Notification = shadowOf(manager).allNotifications.single()

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun Notification.texts() =
        listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_BIG_TEXT)
            .mapNotNull { extras.getCharSequence(it)?.toString() }

    private fun Notification.buttons() = actions.orEmpty().map { it.title.toString() }

    @Test
    fun theLockScreenShowsTheRunningTimerButNotTheBook() {
        notifications.showActive(timer(), now)
        val shown = posted()
        assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility)
        assertEquals("The Private Diary", shown.title())
        val locked = shown.publicVersion
        assertNotNull(locked)
        assertEquals("Reading timer", locked.title())
        assertFalse(locked.texts().any { "Private Diary" in it })
        // The clock and the buttons are still there.
        assertTrue(locked.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(now - 25 * 60_000, locked.`when`)
        assertEquals(listOf("Pause", "Stop"), locked.buttons())
        assertEquals(shown.buttons(), locked.buttons())
    }

    @Test
    fun aPausedOrCountdownTimerKeepsTheBookOffTheLockScreenToo() {
        notifications.showActive(timer(paused = true), now)
        val paused = posted().publicVersion
        assertEquals("Reading timer", paused.title())
        assertFalse(paused.texts().any { "Private Diary" in it })
        assertEquals(listOf("Resume", "Stop"), paused.buttons())

        notifications.showActive(timer(targetMs = 45 * 60_000L), now)
        val countdown = posted().publicVersion
        assertEquals("Reading timer", countdown.title())
        assertTrue(countdown.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(now + 20 * 60_000, countdown.`when`)
    }
}
